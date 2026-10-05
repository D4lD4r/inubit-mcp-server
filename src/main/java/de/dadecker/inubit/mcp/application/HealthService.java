package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HealthReport;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Healthcheck;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Metrics;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Readiness;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code get_health} (US1, FR-005 – FR-008, research R-10, R-15).
 *
 * <ul>
 *   <li>The target is resolved first (unknown → {@code TARGET_UNKNOWN}); no target means all
 *       servers. Servers are checked in parallel ({@link FanOut}), reports come in config order,
 *       one per server, also for servers that failed.
 *   <li>Per server, the monitoring port is taken without waiting for a version detection
 *       (Phase 3 review H1): the unauthenticated healthcheck and readiness check start at once
 *       and the healthcheck alone decides reachability. {@code /system/info} runs in parallel;
 *       for {@code AUTO} servers it completes the version detection. {@code /metrics} runs in
 *       parallel only once the credentials are confirmed, otherwise after {@code /system/info},
 *       so that wrong credentials cost at most one failed login (review M2). Every failed part
 *       becomes an {@link UnavailablePart} with reason, likely cause and next step while the
 *       rest is still reported (FR-008).
 *   <li>Deadlines (SC-002): parts that have not answered {@link #PART_GRACE} after the server's
 *       timeout are reported as {@code TIMEOUT}; the fan-out itself gives up at timeout +
 *       {@link #FAN_OUT_GRACE}, so the whole call returns within the largest timeout + 1 s.
 *   <li>Warnings: the gateway's, if its version line is known by now (e.g. a failed version
 *       detection or an unsupported version line), maintenance mode, and a reported version
 *       other than 8.1.
 * </ul>
 */
public final class HealthService {

    /** Grace after the server timeout for the parts, so the client's own timeout wins. */
    static final Duration PART_GRACE = Duration.ofMillis(250);
    /** Grace after the server timeout for the whole server (backstop of the fan-out). */
    static final Duration FAN_OUT_GRACE = Duration.ofMillis(900);

    private static final Logger LOG = LoggerFactory.getLogger(HealthService.class);
    private static final String SUPPORTED_LINE = "8.1";

    private final GatewayFactory gateways;
    private final TargetResolver targets;
    private final FanOut fanOut;
    private final Function<NodeId, Duration> timeouts;
    private final Clock clock;

    /**
     * @param timeouts the REST timeout of each server
     * @param clock    for {@code checkedAt} when INUBIT gives no timestamp
     */
    public HealthService(GatewayFactory gateways, TargetResolver targets, FanOut fanOut,
        Function<NodeId, Duration> timeouts, Clock clock) {
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.fanOut = Objects.requireNonNull(fanOut, "fanOut");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * One report per resolved server, in config order.
     *
     * @param target a stage or server id; empty for all servers
     * @throws ToolErrorException {@code TARGET_UNKNOWN} or {@code INVALID_INPUT} for the
     *     target, before any server is contacted
     */
    public List<HealthReport> check(Optional<String> target, boolean includeSystemInfo) {
        List<NodeId> servers = target.map(targets::resolve).orElseGet(targets::all);
        List<NodeResult<HealthReport>> results = fanOut.run(servers,
            server -> timeouts.apply(server).plus(FAN_OUT_GRACE),
            server -> checkServer(server, includeSystemInfo));
        return results.stream()
            .map(result -> result.payload()
                .orElseGet(() -> failed(result.node(), result.error().orElseThrow())))
            .toList();
    }

    private HealthReport checkServer(NodeId server, boolean includeSystemInfo)
        throws InterruptedException {
        Duration timeout = timeouts.apply(server);
        long deadline = System.nanoTime() + timeout.plus(PART_GRACE).toNanos();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // never waits for a version detection (Phase 3 review H1)
            Outcome<MonitoringPort> port = await(executor.submit(() ->
                gateways.monitoring(server)), deadline, server, timeout,
                "while preparing the connection");
            if (port.failed()) {
                return failed(server, port.error());
            }
            MonitoringPort monitoring = port.value();
            // the public calls start at once and decide reachability
            Future<Healthcheck> healthcheck = executor.submit(monitoring::healthcheck);
            Future<Readiness> ready = executor.submit(monitoring::ready);
            Future<SystemInfo> systemInfo = executor.submit(monitoring::systemInfo);
            // unconfirmed credentials: one login attempt at a time (Phase 3 review M2)
            boolean serialized = !monitoring.credentialsConfirmed();
            long budgetEnd = deadline - PART_GRACE.toNanos();
            AtomicLong metricsStart = new AtomicLong();
            Future<Metrics> metrics = serialized
                ? executor.submit(() -> {
                    awaitCompletion(systemInfo);
                    long now = System.nanoTime();
                    if (now - budgetEnd >= 0) {
                        // the budget is used up: no authenticated call after the deadline
                        throw new ToolErrorException(serializedMetricsTimeout(server, timeout,
                            budgetEnd, 0));
                    }
                    metricsStart.set(now);
                    return monitoring.metrics();
                })
                : executor.submit(monitoring::metrics);
            Outcome<Healthcheck> health = await(healthcheck, deadline, server, timeout,
                "to the healthcheck");
            Outcome<Readiness> readiness = await(ready, deadline, server, timeout,
                "to the readiness check");
            Outcome<SystemInfo> info = await(systemInfo, deadline, server, timeout,
                "to the system information call");
            Outcome<Metrics> load = await(metrics, deadline, server, timeout,
                "to the metrics call");
            if (serialized && load.failed() && load.error().code() == ErrorCode.TIMEOUT
                && load.error().message().startsWith("No answer from")) {
                load = Outcome.failure(serializedMetricsTimeout(server, timeout, budgetEnd,
                    metricsStart.get()));
            }
            return assemble(server, gatewayWarnings(server), health, readiness, info, load,
                includeSystemInfo);
        } finally {
            // never close(): it would wait for calls that ignore interrupts
            executor.shutdownNow();
        }
    }

    /**
     * The TIMEOUT of a {@code /metrics} call that had to wait for {@code /system/info}
     * (Phase 3 re-review N2): it names the budget that was left for it, or that it never
     * started.
     *
     * @param budgetEnd    nano time at which the server's timeout ends
     * @param metricsStart nano time at which the call started, 0 if it never did
     */
    private static ToolError serializedMetricsTimeout(NodeId server, Duration timeout,
        long budgetEnd, long metricsStart) {
        long remainingMillis = metricsStart == 0 ? 0
            : Duration.ofNanos(budgetEnd - metricsStart).toMillis();
        String message = remainingMillis <= 0
            ? "The metrics call to " + server + " did not start within "
                + Durations.human(timeout) + ": it waits for the system information call"
                + " while the credentials are not confirmed yet"
            : "No answer from " + server + " to the metrics call within the remaining "
                + Durations.human(Duration.ofMillis(remainingMillis)) + " of the "
                + Durations.human(timeout) + " budget (it ran after the system information"
                + " call because the credentials are not confirmed yet)";
        return ToolError.of(ErrorCode.TIMEOUT, message,
            "The INUBIT server is slow; the first authenticated calls run one after the other until"
                + " INUBIT has accepted the credentials once (account-lockout protection)",
            "Retry: once the credentials are confirmed, the metrics run in parallel; or raise"
                + " the timeout of " + server + " in the configuration")
            .withNode(server);
    }

    /** The warnings of the gateway, if its version line is known by now (no blocking). */
    private List<String> gatewayWarnings(NodeId server) {
        try {
            return gateways.knownGateway(server).map(Gateway::warnings).orElse(List.of());
        } catch (ToolErrorException e) {
            return List.of();
        }
    }

    private static void awaitCompletion(Future<?> future) throws InterruptedException {
        try {
            future.get();
        } catch (ExecutionException | CancellationException e) {
            // its outcome is reported on its own
        }
    }

    private HealthReport assemble(NodeId server, List<String> gatewayWarnings,
        Outcome<Healthcheck> healthcheck, Outcome<Readiness> ready,
        Outcome<SystemInfo> systemInfo, Outcome<Metrics> metrics, boolean includeSystemInfo) {
        List<UnavailablePart> unavailable = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>(gatewayWarnings);

        boolean reachable = !healthcheck.failed();
        HealthStatus status = HealthStatus.UNKNOWN;
        Optional<Boolean> maintenanceMode = Optional.empty();
        Instant checkedAt = clock.instant();
        if (reachable) {
            Healthcheck health = healthcheck.value();
            status = health.status();
            maintenanceMode = health.maintenanceMode();
            checkedAt = health.timestamp().orElse(checkedAt);
            health.problem().ifPresent(problem -> unavailable.add(new UnavailablePart(
                UnavailablePart.STATUS, ErrorCode.UNEXPECTED_RESPONSE + ": " + problem,
                Optional.of("The baseUrl does not point to the INUBIT server, or a proxy answers"
                    + " instead"),
                Optional.of("Check the baseUrl of " + server + " in the configuration"))));
        }

        Optional<Boolean> isReady = Optional.empty();
        Optional<String> readyMessage = Optional.empty();
        if (ready.failed()) {
            unavailable.add(UnavailablePart.of(UnavailablePart.READY, ready.error()));
        } else {
            isReady = Optional.of(ready.value().ready());
            readyMessage = ready.value().message();
        }

        Optional<String> version = Optional.empty();
        Optional<SystemInfo> info = Optional.empty();
        if (systemInfo.failed()) {
            unavailable.add(UnavailablePart.of(UnavailablePart.VERSION, systemInfo.error()));
            if (includeSystemInfo) {
                unavailable.add(UnavailablePart.of(UnavailablePart.SYSTEM_INFO,
                    systemInfo.error()));
            }
        } else {
            version = systemInfo.value().version();
            if (version.isEmpty()) {
                unavailable.add(new UnavailablePart(UnavailablePart.VERSION,
                    ErrorCode.UNEXPECTED_RESPONSE + ": the system information of " + server
                        + " contains no version",
                    Optional.of("The endpoint answered with an unexpected document (another"
                        + " INUBIT version or a proxy page)"),
                    Optional.of("Check the INUBIT server; set versionLine: V8_1 if it is 8.1")));
            }
            if (includeSystemInfo) {
                info = Optional.of(systemInfo.value());
            }
        }

        Optional<LoadFigures> load = Optional.empty();
        if (metrics.failed()) {
            unavailable.add(UnavailablePart.of(UnavailablePart.LOAD, metrics.error()));
        } else if (metrics.value() instanceof Metrics.NotLicensed notLicensed) {
            unavailable.add(new UnavailablePart(UnavailablePart.LOAD,
                UnavailablePart.METRICS_NOT_LICENSED + ": " + notLicensed.reason(),
                Optional.of("The INUBIT license has no entry for the load metrics, or the"
                    + " account lacks the monitoring permission"),
                Optional.of("Ask the INUBIT administrators; the rest of the report is not"
                    + " affected")));
        } else if (metrics.value() instanceof Metrics.Available available) {
            load = Optional.of(available.figures());
        }

        if (maintenanceMode.orElse(false)) {
            warnings.add(server + " is in maintenance mode and therefore not processing"
                + " normally");
        }
        version.filter(v -> !isSupportedLine(v))
            .filter(v -> warnings.stream().noneMatch(warning -> warning.contains(v)))
            .ifPresent(v -> warnings.add("INUBIT " + v + " is not the supported version line "
                + SUPPORTED_LINE + "; the " + SUPPORTED_LINE + " adapters are used"));

        return new HealthReport(server, server.group(), checkedAt, reachable, status, isReady,
            readyMessage, maintenanceMode, version, info, load, unavailable,
            reachable ? Optional.empty() : Optional.of(healthcheck.error()),
            List.copyOf(warnings));
    }

    private HealthReport failed(NodeId server, ToolError error) {
        return new HealthReport(server, server.group(), clock.instant(), false,
            HealthStatus.UNKNOWN, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
            Optional.of(error.node().isPresent() ? error : error.withNode(server)),
            List.of());
    }

    private static boolean isSupportedLine(String version) {
        return version.equals(SUPPORTED_LINE) || version.startsWith(SUPPORTED_LINE + ".");
    }

    /** Waits for {@code future} until {@code deadline} (nano time); interrupts are passed on. */
    private static <T> Outcome<T> await(Future<T> future, long deadline, NodeId server,
        Duration timeout, String what) throws InterruptedException {
        try {
            long remaining = Math.max(deadline - System.nanoTime(), 0);
            return Outcome.of(future.get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return Outcome.failure(ToolError.of(ErrorCode.TIMEOUT,
                "No answer from " + server + " " + what + " within "
                    + Durations.human(timeout),
                "The INUBIT server or the network is slow, overloaded or hanging.",
                "Retry later, or raise its timeout in the configuration.")
                .withNode(server));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolErrorException toolError) {
                ToolError error = toolError.error();
                return Outcome.failure(error.node().isPresent() ? error
                    : error.withNode(server));
            }
            LOG.warn("Health call to {} failed unexpectedly", server, cause);
            return Outcome.failure(ToolError.of(ErrorCode.INTERNAL,
                "A health call to " + server + " failed unexpectedly ("
                    + cause.getClass().getSimpleName() + ")",
                "An unexpected error inside the MCP server.",
                "Retry; if it persists, check the MCP server's log on stderr.")
                .withNode(server));
        }
    }


    /** The value of one call or its error. */
    private record Outcome<T>(T value, ToolError error) {

        static <T> Outcome<T> of(T value) {
            return new Outcome<>(Objects.requireNonNull(value, "value"), null);
        }

        static <T> Outcome<T> failure(ToolError error) {
            return new Outcome<>(null, error);
        }

        boolean failed() {
            return error != null;
        }
    }
}
