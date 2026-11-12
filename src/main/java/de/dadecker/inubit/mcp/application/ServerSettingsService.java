package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.ServerSettings;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort.Metrics;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The reads of {@code get_server_settings} (feature 022, US1): the settings of every server of a
 * target, one entry per server (AS5).
 *
 * <ul>
 *   <li>The parts ({@link #PARTS}) are admitted per server by {@link VersionGate#admitParts}
 *       (research D-2 "Gate per call"): a part whose coverage entries are not reachable, or a
 *       StartCLI part on a server without a usable StartCLI client, is reported under
 *       {@code unavailable}; the server fails only when no part is admitted (FR-013).
 *   <li>Version, memory share, maximum blocking queue size and maintenance mode come from the
 *       {@link MonitoringPort} calls {@code get_health} makes ({@code /system/info},
 *       {@code /metrics}, the healthcheck), never from StartCLI (AS7, FR-002, research D-6).
 *       These three reads run on every call, whatever parts are asked for, so every result
 *       carries version, memory share, blocking queue size and maintenance mode (T021 review). A
 *       failed call is reported under get_health's part name ({@code version}, {@code load},
 *       {@code status}).
 *   <li>The licensed threads come from {@code /metrics}, and from {@code GET /license} only when
 *       {@code /metrics} is not licensed (data-model ServerSettings).
 *   <li>A part that fails, also all StartCLI parts when the whole session fails, is reported under
 *       {@code unavailable} with the error; the other parts are returned (FR-013). Every returned
 *       fact names its channel in {@code sources} (FR-009).
 * </ul>
 */
public final class ServerSettingsService {

    public static final String TOOL = "get_server_settings";

    /** Grace after the reads of one server (backstop of the fan-out). */
    static final Duration FAN_OUT_GRACE = Duration.ofSeconds(1);

    private static final Part LICENSE = new Part(UnavailablePart.LICENSE, Channel.REST,
        Set.of(rest("GET /license")));
    private static final Part CONFIGURATION = new Part(UnavailablePart.CONFIGURATION,
        Channel.REST, Set.of(rest("GET /configurations")));
    private static final Part THREADS = new Part(UnavailablePart.THREADS, Channel.CLI,
        Set.of(cli("threads showNumberOfWorkflowThreads")));
    private static final Part PRIVILEGED_WORKFLOWS = new Part(UnavailablePart.PRIVILEGED_WORKFLOWS,
        Channel.CLI, Set.of(cli("privilegedWorkflows showThreadsPercentage"),
            cli("privilegedWorkflows showWorkflowList")));
    private static final Part DB_CONNECTION_TOLERANCE = new Part(
        UnavailablePart.DB_CONNECTION_TOLERANCE, Channel.CLI,
        Set.of(cli("dbconnectiontolerance show")));
    private static final Part SERVER_TIME = new Part(UnavailablePart.SERVER_TIME, Channel.CLI,
        Set.of(cli("date")));

    /** The parts by name: one per fact group with its coverage entries and channel. */
    public static final Map<String, Part> PARTS = Map.of(LICENSE.name(), LICENSE,
        CONFIGURATION.name(), CONFIGURATION, THREADS.name(), THREADS,
        PRIVILEGED_WORKFLOWS.name(), PRIVILEGED_WORKFLOWS,
        DB_CONNECTION_TOLERANCE.name(), DB_CONNECTION_TOLERANCE, SERVER_TIME.name(), SERVER_TIME);

    /** The parts of a call that names none: all but the configuration. */
    public static final Set<String> DEFAULT_PARTS = Set.of(LICENSE.name(), THREADS.name(),
        PRIVILEGED_WORKFLOWS.name(), DB_CONNECTION_TOLERANCE.name(), SERVER_TIME.name());

    /** The only action of the tool (research D-2 action key {@code default}). */
    public static final ActionSpec SPEC = new ActionSpec(TOOL, "default",
        Capability.SERVER_SETTINGS, ActionSpec.Area.READ, ActionSpec.Kind.READ, Set.of(),
        input -> Set.of(), Set.copyOf(PARTS.values()), false, Optional.empty(),
        ActionSpec.RestartNotice.NONE);

    /**
     * The settings of one server and the version gate's warnings and notes for it.
     */
    public record NodeSettings(ServerSettings settings, List<String> warnings) {
        public NodeSettings {
            Objects.requireNonNull(settings, "settings");
            warnings = List.copyOf(warnings);
        }
    }

    private final GatewayFactory gateways;
    private final TargetResolver targets;
    private final FanOut fanOut;
    private final ResultLimiter limiter;
    private final Function<NodeId, Duration> timeouts;
    private final Function<NodeId, Duration> cliTimeouts;
    private final VersionGate gate;
    private final SecretTiming secretTiming;

    /**
     * @param limiter      bounds the configuration values, shared among the servers of a call
     * @param timeouts     the REST timeout of each server
     * @param cliTimeouts  the StartCLI timeout of each server
     * @param secretTiming the fan-out allowance for fetching the secrets (feature 010)
     */
    public ServerSettingsService(GatewayFactory gateways, TargetResolver targets, FanOut fanOut,
        ResultLimiter limiter, Function<NodeId, Duration> timeouts,
        Function<NodeId, Duration> cliTimeouts, VersionGate gate, SecretTiming secretTiming) {
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.fanOut = Objects.requireNonNull(fanOut, "fanOut");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.cliTimeouts = Objects.requireNonNull(cliTimeouts, "cliTimeouts");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.secretTiming = Objects.requireNonNull(secretTiming, "secretTiming");
    }

    /**
     * The settings of every server of {@code target}, in config order.
     *
     * @param parts   names of {@link #PARTS}; empty for {@link #DEFAULT_PARTS}
     * @param section the configuration section to show; implies the part {@code configuration}
     * @throws ToolErrorException {@code TARGET_UNKNOWN} or {@code INVALID_INPUT} (the target, an
     *     unknown part) before any server is contacted
     */
    public List<NodeResult<NodeSettings>> read(String target, Set<String> parts,
        Optional<String> section) {
        List<NodeId> servers = targets.resolve(target);
        Set<Part> asked = asked(parts, section);
        ResultLimiter shared = limiter.share(servers.size());
        boolean cli = asked.stream().anyMatch(part -> part.channel() == Channel.CLI);
        Set<NodeId> pending = secretTiming.pending(servers);
        return secretTiming.explainTimeouts(fanOut.run(servers,
            server -> deadline(server, cli),
            server -> readServer(server, asked, section, shared)), pending);
    }

    private static Set<Part> asked(Set<String> parts, Optional<String> section) {
        Set<String> names = new TreeSet<>(parts.isEmpty() ? DEFAULT_PARTS : parts);
        section.ifPresent(name -> names.add(CONFIGURATION.name()));
        Set<Part> asked = new LinkedHashSet<>();
        for (String name : names) {
            Part part = PARTS.get(name);
            if (part == null) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "Unknown part " + Names.quote(name) + " of " + TOOL,
                    "parts names one of " + new TreeSet<>(PARTS.keySet()),
                    "Name only these parts, or none for all but the configuration"));
            }
            asked.add(part);
        }
        return asked;
    }

    private NodeSettings readServer(NodeId server, Set<Part> asked, Optional<String> section,
        ResultLimiter shared) {
        VersionGate.PartAdmission admission = gate.admitParts(TOOL, server, SPEC, asked);
        Set<String> admitted = admission.admitted().stream().map(Part::name)
            .collect(Collectors.toUnmodifiableSet());
        List<UnavailablePart> unavailable = new ArrayList<>(admission.unavailable());
        Map<String, Channel> sources = new HashMap<>();

        // the facts get_health reads, from its calls (AS7, FR-002)
        MonitoringPort monitoring = gateways.monitoring(server);
        Optional<Boolean> maintenanceMode = read(monitoring::healthcheck, UnavailablePart.STATUS,
            unavailable).flatMap(MonitoringPort.Healthcheck::maintenanceMode);
        Optional<String> version = read(monitoring::systemInfo, UnavailablePart.VERSION,
            unavailable).flatMap(SystemInfo::version);
        Metrics metrics = read(monitoring::metrics, UnavailablePart.LOAD, unavailable)
            .orElse(null);
        Optional<LoadFigures> load = Optional.empty();
        boolean metricsLicensed = true;
        if (metrics instanceof Metrics.Available available) {
            load = Optional.of(available.figures());
        } else if (metrics instanceof Metrics.NotLicensed notLicensed) {
            metricsLicensed = false;
            unavailable.add(new UnavailablePart(UnavailablePart.LOAD,
                UnavailablePart.METRICS_NOT_LICENSED + ": " + notLicensed.reason()));
        }
        Optional<Long> blockingQueueSize = load.flatMap(LoadFigures::maxBlockingQueueSize);
        Optional<Double> memoryUsedPercent = load.flatMap(LoadFigures::memoryUsedPercent);
        source(sources, "maintenanceMode", maintenanceMode, Channel.REST);
        source(sources, "version", version, Channel.REST);
        source(sources, "blockingQueueSize", blockingQueueSize, Channel.REST);
        source(sources, "memoryUsedPercent", memoryUsedPercent, Channel.REST);

        ServerSettingsPort port = gateways.serverSettings(server);
        Optional<LicenseFacts> license = Optional.empty();
        if (asked.contains(LICENSE)) {
            // GET /license only while its part is admitted; /metrics is read anyway
            Optional<LicenseFacts> fromLicense = admitted.contains(LICENSE.name())
                ? read(port::license, LICENSE.name(), unavailable) : Optional.empty();
            source(sources, "license", fromLicense, Channel.REST);
            // the threads of GET /license only when /metrics is not licensed (data-model)
            Optional<Long> licensedThreads = metricsLicensed
                ? load.flatMap(LoadFigures::licensedThreads)
                : fromLicense.flatMap(LicenseFacts::licensedThreads);
            source(sources, "licensedThreads", licensedThreads, Channel.REST);
            LicenseFacts facts = new LicenseFacts(fromLicense.flatMap(LicenseFacts::expiryDate),
                licensedThreads, fromLicense.flatMap(LicenseFacts::licensee));
            if (facts.expiryDate().isPresent() || facts.licensedThreads().isPresent()
                || facts.licensee().isPresent()) {
                license = Optional.of(facts);
            }
        }

        Optional<ConfigurationView> configuration = admitted.contains(CONFIGURATION.name())
            ? read(() -> port.configuration(section, shared.maxItems(), shared.maxChars()),
                CONFIGURATION.name(), unavailable)
            : Optional.empty();
        source(sources, "configuration", configuration, Channel.REST);

        Set<String> cliParts = admitted.stream()
            .filter(name -> PARTS.get(name).channel() == Channel.CLI)
            .collect(Collectors.toCollection(TreeSet::new));
        CliSettingsSnapshot cli = new CliSettingsSnapshot(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), List.of());
        if (!cliParts.isEmpty()) {
            try {
                cli = port.cliSettings(cliParts);
                unavailable.addAll(cli.unavailable());
            } catch (ToolErrorException e) {
                // a failure of the whole session: its parts only (FR-013)
                cliParts.forEach(part -> unavailable.add(UnavailablePart.of(part, e.error())));
            }
        }
        source(sources, "workflowThreads", cli.workflowThreads(), Channel.CLI);
        source(sources, "privilegedWorkflows", cli.privilegedWorkflows(), Channel.CLI);
        source(sources, "dbConnectionTolerance", cli.dbConnectionTolerance(), Channel.CLI);
        source(sources, "serverTime", cli.serverTime(), Channel.CLI);

        List<String> warnings = new ArrayList<>(admission.admission().warnings());
        warnings.addAll(admission.admission().notes());
        return new NodeSettings(new ServerSettings(server, license, cli.workflowThreads(),
            cli.privilegedWorkflows(), cli.dbConnectionTolerance(), blockingQueueSize, version,
            memoryUsedPercent, maintenanceMode, cli.serverTime(), configuration, sources,
            unavailable), warnings);
    }

    /** The result of {@code call}, or empty with {@code part} in {@code unavailable}. */
    private static <T> Optional<T> read(Supplier<T> call, String part,
        List<UnavailablePart> unavailable) {
        try {
            return Optional.of(call.get());
        } catch (ToolErrorException e) {
            unavailable.add(UnavailablePart.of(part, e.error()));
            return Optional.empty();
        }
    }

    private static void source(Map<String, Channel> sources, String field, Optional<?> value,
        Channel channel) {
        value.ifPresent(present -> sources.put(field, channel));
    }

    /**
     * The fan-out deadline of {@code server}: the reads run one after the other, so
     * {@code timeout} for the version gate's detection, each of the three monitoring calls and
     * the two REST settings reads; with StartCLI parts {@code cliTimeout} for the client probe,
     * the wait for the concurrency limiter and the session, and {@code timeout} for the TLS
     * handshake (as {@link DiagnosisService}); plus {@link #FAN_OUT_GRACE} and the secret read
     * allowance (feature 010).
     */
    private Duration deadline(NodeId server, boolean cli) {
        Duration timeout = timeouts.apply(server);
        Duration work = timeout.multipliedBy(6);
        if (cli) {
            work = work.plus(cliTimeouts.apply(server).multipliedBy(3)).plus(timeout);
        }
        return work.plus(FAN_OUT_GRACE).plus(secretTiming.allowance(server));
    }

    private static CoverageKey rest(String entry) {
        return new CoverageKey(Channel.REST, entry);
    }

    private static CoverageKey cli(String entry) {
        return new CoverageKey(Channel.CLI, entry);
    }
}
