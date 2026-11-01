package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * The use cases behind {@code find_processes} and {@code query_logs} (US2, FR-009 – FR-013,
 * research R-8, R-9).
 *
 * <ul>
 *   <li>Everything that can be checked without INUBIT is checked first (FR-028): the target
 *       ({@code TARGET_UNKNOWN}), {@code since}/{@code until} (ISO-8601 with offset, or a
 *       duration such as {@code PT24H} meaning "now minus the duration"), {@code offset + limit
 *       <= 10000}, the {@code processId} pattern, and the log filters of every resolved server
 *       ({@code LogPort.validate}); a failure fails the whole call. Feature 010 (R-8): a server
 *       whose credentials are not held yet (first fetch from AWS Parameter Store pending) is
 *       checked at the start of its own branch instead, so that no wait for a fetch happens
 *       outside its deadline; a failure there is that server's {@code error}.
 *   <li>Servers are queried in parallel ({@link FanOut}); results come in config order, one per
 *       server, a failed server only gets its {@code error}. The ports are taken without waiting
 *       for the adapter selection's version detection, and only REST is used (no CLI home
 *       needed), except on 6.1, whose ports read through StartCLI. The per-server deadline is
 *       three times the server timeout plus {@link #FAN_OUT_GRACE}: while the credentials are
 *       unconfirmed the first request runs alone (credential guard), the others after it; then
 *       the version gate's detection (feature 009) and the query. A node whose secrets come
 *       from AWS Parameter Store adds the {@link SecretTiming} allowance for their first fetch
 *       (feature 010). Feature 011: a node whose process diagnosis runs over StartCLI (6.1)
 *       gets a deadline from its {@code cliTimeout} instead
 *       ({@link #deadline(NodeId, Capability)}).
 *   <li>Feature 009 (research D-5, D-6, FR-006, FR-009): before its query, each server's call
 *       asks the {@link VersionGate} for its capability ({@code READ}, {@code {REST}}), which
 *       detects the version of an {@code AUTO} node on first use; its warnings go into that
 *       server's {@link NodePage}, a refusal ({@code UNSUPPORTED_VERSION}) becomes that server's
 *       {@code error} and leaves the other servers' results untouched. Feature 011: so does an
 *       {@code UNSUPPORTED_VERSION} of the process port's own input check (e.g. {@code tag} on
 *       6.1), before the gate is asked; the admission's version notes follow its warnings.
 *   <li>{@code timeInState} and {@code hanging} are computed here for every instance with the
 *       MCP server's clock: hanging = a {@link ProcessState#NON_FINAL} state longer than the
 *       threshold (request, else the server's {@code hangingThreshold}, else
 *       {@link #DEFAULT_HANGING_THRESHOLD}); {@code ERROR} is never hanging. A query that cannot
 *       match ({@code states=[ERROR]} with {@code hangingOnly}) gives an empty page without a
 *       request.
 *   <li>Items are sorted newest first and each server's page is bounded with
 *       {@link ResultLimiter#share(int) share(n)}; a page whose items were cut by the adapter
 *       stays {@code truncated}.
 * </ul>
 */
public final class DiagnosisService {

    /** The hanging threshold when neither the request nor the server configures one. */
    public static final Duration DEFAULT_HANGING_THRESHOLD = Duration.ofMinutes(60);
    /** Upper bound of {@code offset + limit} (research R-9). */
    public static final int MAX_WINDOW = 10_000;
    static final Duration FAN_OUT_GRACE = Duration.ofSeconds(1);

    private static final Pattern PROCESS_ID = Pattern.compile("^[0-9A-Za-z_-]{1,64}$");
    private static final String TIME_FORMATS = "an ISO-8601 timestamp with offset, e.g."
        + " 2026-10-01T08:00:00Z or 2026-10-01T10:00:00+02:00, or a duration back from now,"
        + " e.g. PT24H or P7D";

    /**
     * One server's page with the version gate's warnings for it (feature 009); empty warnings
     * are omitted from the tool result.
     */
    public record NodePage<T>(Page<T> page, List<String> warnings) {

        public NodePage {
            Objects.requireNonNull(page, "page");
            warnings = List.copyOf(warnings);
        }
    }

    /** The arguments of {@code find_processes} (contracts/mcp-tools.md §3). */
    public record ProcessRequest(String target, Set<ProcessState> states, boolean hangingOnly,
        Optional<Integer> hangingThresholdMinutes, Optional<String> workflow,
        Optional<String> tag, Optional<String> since, Optional<String> until, int offset,
        int limit) {

        public ProcessRequest {
            Objects.requireNonNull(target, "target");
            states = Set.copyOf(states);
            hangingThresholdMinutes = hangingThresholdMinutes == null ? Optional.empty()
                : hangingThresholdMinutes;
            workflow = workflow == null ? Optional.empty() : workflow;
            tag = tag == null ? Optional.empty() : tag;
            since = since == null ? Optional.empty() : since;
            until = until == null ? Optional.empty() : until;
        }
    }

    /** The arguments of {@code query_logs} (contracts/mcp-tools.md §4). */
    public record LogRequest(String target, LogType logType, Optional<String> since,
        Optional<String> until, Optional<String> workflow, Optional<String> processId,
        Set<Severity> severities, Optional<String> text, int offset, int limit) {

        public LogRequest {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(logType, "logType");
            since = since == null ? Optional.empty() : since;
            until = until == null ? Optional.empty() : until;
            workflow = workflow == null ? Optional.empty() : workflow;
            processId = processId == null ? Optional.empty() : processId;
            severities = Set.copyOf(severities);
            text = text == null ? Optional.empty() : text;
        }
    }

    private final GatewayFactory gateways;
    private final TargetResolver targets;
    private final FanOut fanOut;
    private final ResultLimiter limiter;
    private final Function<NodeId, Duration> timeouts;
    private final Function<NodeId, Duration> cliTimeouts;
    private final Function<NodeId, Duration> hangingThresholds;
    private final Clock clock;
    private final ToIntFunction<Object> sizeOf;
    private final VersionGate gate;
    private final SecretTiming secretTiming;

    /**
     * @param timeouts          the REST timeout of each server
     * @param cliTimeouts       feature 011: the StartCLI timeout of each server
     * @param hangingThresholds the configured hanging threshold of each server ({@code null}:
     *                          {@link #DEFAULT_HANGING_THRESHOLD})
     * @param clock             "now" for durations, {@code timeInState} and {@code hanging}
     * @param sizeOf            an item's serialized size in chars (the tool result's JSON)
     * @param gate              feature 009: the version gate
     * @param secretTiming      feature 010: the fan-out allowance for fetching the secrets
     */
    public DiagnosisService(GatewayFactory gateways, TargetResolver targets, FanOut fanOut,
        ResultLimiter limiter, Function<NodeId, Duration> timeouts,
        Function<NodeId, Duration> cliTimeouts, Function<NodeId, Duration> hangingThresholds,
        Clock clock, ToIntFunction<Object> sizeOf, VersionGate gate, SecretTiming secretTiming) {
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.fanOut = Objects.requireNonNull(fanOut, "fanOut");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.cliTimeouts = Objects.requireNonNull(cliTimeouts, "cliTimeouts");
        this.hangingThresholds = Objects.requireNonNull(hangingThresholds, "hangingThresholds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sizeOf = Objects.requireNonNull(sizeOf, "sizeOf");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.secretTiming = Objects.requireNonNull(secretTiming, "secretTiming");
    }

    /**
     * One page of process instances per resolved server, in config order.
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN} or {@code INVALID_INPUT} before any
     *     server is contacted
     */
    public List<NodeResult<NodePage<ProcessInstance>>> findProcesses(ProcessRequest request) {
        List<NodeId> servers = targets.resolve(request.target());
        Instant now = clock.instant();
        Optional<Instant> since = time("since", request.since(), now);
        Optional<Instant> until = time("until", request.until(), now);
        checkRange(since, until);
        checkPaging(request.offset(), request.limit());
        Function<NodeId, ProcessQuery> queryOf = server -> new ProcessQuery(request.states(),
            request.hangingOnly(), threshold(request, server), request.workflow(),
            request.tag(), since, until, request.offset(), request.limit(), now);
        Validation validation = validateHeld(servers,
            server -> gateways.processes(server).validate(queryOf.apply(server)), true);
        ResultLimiter shared = limiter.share(servers.size());
        Function<NodeId, Duration> deadline =
            server -> deadline(server, Capability.PROCESS_DIAGNOSIS);
        return fanOut.run(servers, deadline, server -> {
            validation.atBranchStart(server,
                () -> gateways.processes(server).validate(queryOf.apply(server)));
            List<String> warnings = admit("find_processes", Capability.PROCESS_DIAGNOSIS, server);
            ProcessQuery query = queryOf.apply(server);
            Duration threshold = query.hangingThreshold();
            if (query.matchesNothing()) {
                return new NodePage<>(empty(request.offset(), request.limit()), warnings);
            }
            Page<ProcessInstance> page = gateways.processes(server).find(query);
            List<ProcessInstance> items = new ArrayList<>();
            page.items().forEach(item -> items.add(item.assessedAt(now, threshold)));
            items.sort(Comparator.comparing(ProcessInstance::since).reversed());
            return new NodePage<>(bound(shared, page, items, request.offset(), request.limit()),
                warnings);
        });
    }

    /**
     * One page of log entries per resolved server, in config order.
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN} or {@code INVALID_INPUT} (also for
     *     a filter the log type does not support) before any server is contacted
     */
    public List<NodeResult<NodePage<LogEntry>>> queryLogs(LogRequest request) {
        List<NodeId> servers = targets.resolve(request.target());
        Instant now = clock.instant();
        Optional<Instant> since = time("since", request.since(), now);
        Optional<Instant> until = time("until", request.until(), now);
        checkRange(since, until);
        checkPaging(request.offset(), request.limit());
        request.processId().filter(id -> !PROCESS_ID.matcher(id).matches()).ifPresent(id -> {
            throw invalid("Invalid processId " + Names.quote(id),
                "A processId is a workflowId (digits) or a globalPId (UUID)",
                "Use the processId or globalProcessId from find_processes");
        });
        LogQuery query = new LogQuery(request.logType(), since, until, request.workflow(),
            request.processId(), request.severities(), request.text(), request.offset(),
            request.limit());
        Validation validation = validateHeld(servers,
            server -> gateways.logs(server).validate(query), false);
        ResultLimiter shared = limiter.share(servers.size());
        Function<NodeId, Duration> deadline = server -> deadline(server, Capability.LOG_DIAGNOSIS);
        return fanOut.run(servers, deadline, server -> {
            validation.atBranchStart(server, () -> gateways.logs(server).validate(query));
            List<String> warnings = admit("query_logs", Capability.LOG_DIAGNOSIS, server);
            Page<LogEntry> page = gateways.logs(server).query(query);
            List<LogEntry> items = new ArrayList<>(page.items());
            items.sort(Comparator.comparing(
                (LogEntry entry) -> entry.timestamp().orElse(Instant.MIN)).reversed());
            if (items.stream().anyMatch(entry -> entry.timestamp().isEmpty())) {
                items = new ArrayList<>(page.items()); // no time field: INUBIT's order
            }
            return new NodePage<>(bound(shared, page, items, request.offset(), request.limit()),
                warnings);
        });
    }

    /**
     * The version gate's warnings for a diagnosis read on {@code server}, followed by the version
     * notes of its admission (feature 011, FR-010: on 6.1 where the logs can be read):
     * {@code PROCESS_DIAGNOSIS} for {@code find_processes}, {@code LOG_DIAGNOSIS} for
     * {@code query_logs} (feature 011, FR-004). The call names REST; on a CLI-only version the
     * gate judges the channels of its matrix entry instead.
     */
    private List<String> admit(String tool, Capability capability, NodeId server) {
        VersionGate.Admission admission = gate.admit(tool, server, capability,
            Capability.Kind.READ, Set.of(Channel.REST));
        List<String> warnings = new ArrayList<>(admission.warnings());
        warnings.addAll(admission.notes());
        return warnings;
    }

    /**
     * The outcome of {@link #validateHeld}: the servers to check in their own branch, and the
     * refusals ({@code UNSUPPORTED_VERSION}) of the servers checked up front.
     */
    private record Validation(Set<NodeId> unchecked, Map<NodeId, ToolErrorException> refused) {

        /**
         * At the start of {@code server}'s fan-out branch: throws its kept refusal, so that it
         * becomes that server's entry before the version gate is asked (feature 011, FR-007:
         * nothing is launched), or runs {@code check} if the server was not checked up front.
         */
        void atBranchStart(NodeId server, Runnable check) {
            ToolErrorException refusal = refused.get(server);
            if (refusal != null) {
                throw refusal;
            }
            if (unchecked.contains(server)) {
                check.run();
            }
        }
    }

    /**
     * Runs {@code check} now for every server whose credentials are held; the others stay
     * unchecked: taking their port would wait for their first fetch, so they are checked at the
     * start of their own fan-out branch, within their deadline (feature 010, R-8).
     *
     * @param keepRefusals whether an {@code UNSUPPORTED_VERSION} of the check is kept as the
     *     server's entry: for {@code find_processes}, whose 6.1 port refuses an input such as
     *     {@code tag} (feature 011, research D-10); not for {@code query_logs}, whose 6.1 port
     *     refuses everything and is preceded by the gate's refusal of log diagnosis with its
     *     next step
     */
    private Validation validateHeld(List<NodeId> servers, Consumer<NodeId> check,
        boolean keepRefusals) {
        Set<NodeId> unchecked = new HashSet<>();
        Map<NodeId, ToolErrorException> refused = new HashMap<>();
        for (NodeId server : servers) {
            if (gateways.credentialsHeld(server)) {
                validate(() -> check.accept(server)).filter(refusal -> keepRefusals)
                    .ifPresent(refusal -> refused.put(server, refusal));
            } else {
                unchecked.add(server);
            }
        }
        return new Validation(unchecked, refused);
    }

    /**
     * A port's check without network: {@code INVALID_INPUT} fails the whole call; an
     * {@code UNSUPPORTED_VERSION} (an input the node's version cannot serve, feature 011
     * research D-10) is returned to become that server's entry; other failures (e.g. an
     * unusable trust store) are reported per server by the fan-out.
     */
    private static Optional<ToolErrorException> validate(Runnable check) {
        try {
            check.run();
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.INVALID_INPUT) {
                throw e;
            }
            if (e.error().code() == ErrorCode.UNSUPPORTED_VERSION) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /**
     * The fan-out deadline of {@code server} for {@code capability}, by the channel the version
     * gate gives the capability on that node (feature 011, research D-10), plus
     * {@link #FAN_OUT_GRACE}:
     * <ul>
     *   <li>REST: {@code 3 × timeout} (guard wait, the version gate's detection, the query);
     *   <li>StartCLI ({@code find_processes} on 6.1): {@code cliTimeout} for the client-option
     *       probe of a first call, {@code cliTimeout} for the wait for the concurrency limiter
     *       (which also bounds the credential guard wait), {@code timeout} for the TLS handshake
     *       and {@code cliTimeout} for the session.
     * </ul>
     * The detection and the option probe are always counted: whether they run is only known
     * inside the call (first use, a stale version, the end of the back-off). Feature 010: plus
     * the {@link SecretTiming#allowance fetch allowance} of an AWS node, always, for the same
     * reason.
     */
    Duration deadline(NodeId server, Capability capability) {
        Duration timeout = timeouts.apply(server);
        Duration work = gate.channels(server, capability).contains(Channel.CLI)
            ? cliTimeouts.apply(server).multipliedBy(3).plus(timeout)
            : timeout.multipliedBy(3);
        return work.plus(FAN_OUT_GRACE).plus(secretTiming.allowance(server));
    }

    private Duration threshold(ProcessRequest request, NodeId server) {
        return request.hangingThresholdMinutes()
            .map(minutes -> Duration.ofMinutes(minutes))
            .orElseGet(() -> configuredThreshold(server));
    }

    private Duration configuredThreshold(NodeId server) {
        Duration configured = hangingThresholds.apply(server);
        return configured == null ? DEFAULT_HANGING_THRESHOLD : configured;
    }

    private <T> Page<T> bound(ResultLimiter shared, Page<T> fetched, List<T> items, int offset,
        int limit) {
        Page<T> page = shared.bound(items, offset, limit, fetched.total(),
            item -> sizeOf.applyAsInt(item));
        return fetched.truncated() ? page.asTruncated() : page;
    }

    private static <T> Page<T> empty(int offset, int limit) {
        return new Page<>(List.of(), offset, limit, OptionalLong.of(0), false, false,
            OptionalInt.empty());
    }

    /**
     * Parses an ISO-8601 timestamp with offset or a positive duration back from {@code now}. The
     * result must be representable in epoch milliseconds (with room for the open-range ±1 ms),
     * which is what INUBIT compares (review D3).
     */
    static Optional<Instant> time(String name, Optional<String> value, Instant now) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String text = value.get().strip();
        Instant instant;
        try {
            if (text.startsWith("P") || text.startsWith("p")) {
                Duration duration = Duration.parse(text);
                if (duration.isNegative() || duration.isZero()) {
                    throw invalidTime(name, text);
                }
                instant = now.minus(duration);
            } else {
                instant = OffsetDateTime.parse(text).toInstant();
            }
            long millis = instant.toEpochMilli();
            if (millis == Long.MIN_VALUE || millis == Long.MAX_VALUE) {
                throw invalidTime(name, text);
            }
        } catch (DateTimeException | ArithmeticException e) {
            throw invalidTime(name, text);
        }
        return Optional.of(instant);
    }

    private static void checkRange(Optional<Instant> since, Optional<Instant> until) {
        if (since.isPresent() && until.isPresent() && since.get().isAfter(until.get())) {
            throw invalid("since (" + since.get() + ") is after until (" + until.get() + ")",
                "The time range is inverted",
                "Use a since that is earlier than until (a duration since PT2H is earlier than"
                    + " PT1H)");
        }
    }

    private static void checkPaging(int offset, int limit) {
        if (offset < 0 || limit < 1) {
            throw invalid("offset must be >= 0 and limit >= 1, got " + offset + " and " + limit,
                "The paging parameters are out of range",
                "Use offset >= 0 and limit between 1 and 100");
        }
        if ((long) offset + limit > MAX_WINDOW) {
            throw invalid("offset + limit must be at most " + MAX_WINDOW + ", got " + offset
                    + " + " + limit,
                "Paging deeper than " + MAX_WINDOW + " entries is not supported",
                "Narrow the query with since/until or other filters instead of paging further");
        }
    }

    private static ToolErrorException invalidTime(String name, String text) {
        return invalid("Invalid " + name + " " + Names.quote(text),
            "The value is neither an ISO-8601 timestamp with offset nor a positive duration, or"
                + " it is outside the range of epoch milliseconds",
            "Use " + TIME_FORMATS);
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            likelyCause, nextStep));
    }
}
