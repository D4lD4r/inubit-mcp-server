package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ChangeOutcome;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ChangeResult;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ServerOperationPort;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The actions of {@code operate_server} on one server (feature 022, US4, research D-12, D-13,
 * D-14, D-17, D-22; contracts/mcp-tools-delta.md → operate_server): the switches of maintenance
 * mode, readiness, trace and trace level, the midnight task, and the deletion of the processes in
 * state ERROR. StartCLI's {@code debug on|off} is not offered: it had no observable server effect
 * on 8.1.17 (T054, research D-22). A call runs in this order:
 *
 * <ol>
 *   <li>the inputs are checked, before anything is read ({@code INVALID_INPUT}, FR-020);
 *   <li>{@link ChangeGuard#admit} (D-12; it audits its own refusals); switching maintenance mode
 *       off is admitted on the node's last known version when the version detection failed
 *       with {@code MAINTENANCE_MODE} (D-22);
 *   <li>the key {@code node|OPERATION|<action>} is taken for the rest of the call
 *       ({@link ChangeLocks}, D-17; held: {@code PRECONDITION_FAILED});
 *   <li>the state before is read; for {@code deleteErrorProcesses} an empty set ends the call
 *       with {@code NOTHING_TO_DO}, audited, nothing sent (spec edge case; a code given is
 *       redeemed first);
 *   <li>under server-side confirmation the first call returns the {@link ChangePreview} with a code
 *       (audited {@code CHALLENGE_ISSUED}); the call with the code compares the state read now
 *       with the state of the preview ({@code CONFLICT} with a fresh preview, D-14). Under
 *       {@code write.confirmation: CLIENT} (never on production) the first call executes (D-13);
 *   <li>{@code PENDING} is audited, the change is sent with the call's {@link WritePermit} (the
 *       port hands it off under the node's write lock), the state is read again (FR-024) and
 *       {@code EXECUTED} or {@code FAILED} is audited.
 * </ol>
 *
 * <p>The states come from {@link ServerOperationPort} and {@link MonitoringPort} (research D-22):
 * maintenance mode from {@code maintenance -s}, where a {@code MAINTENANCE_MODE} error (INUBIT's
 * REST API answering 503) counts as "on"; before switching it on, the running and queued processes
 * from the metrics; readiness from {@code GET /ready}; trace on/off from the system information;
 * the trace level from the port, which knows no read form for it yet, so it is shown as unknown
 * with a warning, and the result says the re-read failed; the midnight task's
 * {@code MidnightTaskTriggerTime} from {@code GET /configurations}, if INUBIT reports it; the
 * processes in state ERROR from {@link ServerOperationPort#errorProcesses}, compared by a hash of
 * their sorted ids, or by their count when INUBIT lists no ids (D-14, FR-017).
 * Every refusal after the admission is audited {@code REFUSED}.
 */
public final class ServerOperationService {

    public static final String TOOL = "operate_server";

    static final String SET_MAINTENANCE = "setMaintenance";
    static final String SET_READINESS = "setReadiness";
    static final String SET_TRACE = "setTrace";
    static final String SET_TRACE_LEVEL = "setTraceLevel";
    static final String TRIGGER_MIDNIGHT_TASK = "triggerMidnightTask";
    static final String DELETE_ERROR_PROCESSES = "deleteErrorProcesses";

    static final String ON = "on";
    static final String EXECUTE_QUEUED = "executeQueued";
    static final String READY = "ready";
    static final String LEVEL = "level";

    private static final Logger LOG = LoggerFactory.getLogger(ServerOperationService.class);
    private static final Pattern REASON = Pattern.compile("^[^\\r\\n]{1,500}$");
    /** The configuration section of the midnight task's time in {@code GET /configurations}. */
    private static final String MIDNIGHT_TASK_TRIGGER_TIME = "MidnightTaskTriggerTime";

    private static final String MAINTENANCE_EFFECT = "In maintenance mode the process engine"
        + " executes no processes, and INUBIT's REST API answers every request with \"service"
        + " unavailable\".";
    private static final String EQ_TRUE = "-eq true is sent: the processes already queued are"
        + " still executed while maintenance mode is on.";
    private static final String EQ_FALSE = "-eq false is sent: the processes already queued are"
        + " not executed while maintenance mode is on.";
    private static final String EQ_NONE = "-eq is not sent: the processes already queued are not"
        + " executed while maintenance mode is on; only -eq true executes them.";
    private static final String READINESS_EFFECT =
        "The readiness stays NOT READY while maintenance mode is on.";
    private static final List<String> MIDNIGHT_TASK_EFFECTS = List.of(
        "The daily midnight task runs at once instead of at its configured start time.",
        "The vendor does not document what the midnight task does.");
    private static final String NO_TRACE_LEVEL_READ =
        "INUBIT offers no known command that reads the trace level";
    private static final List<String> DELETE_ERROR_PROCESSES_EFFECTS = List.of(
        "The processes in state ERROR are deleted and cannot be restored.",
        "processdelete error deletes every process in state ERROR when it runs, also one that"
            + " entered the state after the preview; the result names the count INUBIT reports.");
    private static final String NO_ERROR_PROCESS = "no process in state ERROR";
    private static final String REST_IN_MAINTENANCE = "maintenance mode is on and INUBIT's REST"
        + " API answers \"service unavailable\"";

    public static final ActionSpec SET_MAINTENANCE_SPEC = spec(SET_MAINTENANCE,
        Set.of(cli("maintenance on|off"), cli("maintenance -eq true|false")),
        input -> input.containsKey(EXECUTE_QUEUED)
            ? Set.of(cli("maintenance on|off"), cli("maintenance -eq true|false"))
            : Set.of(cli("maintenance on|off")));
    public static final ActionSpec SET_READINESS_SPEC = spec(SET_READINESS,
        Set.of(new CoverageKey(Channel.REST, "POST /ready/{status}")));
    public static final ActionSpec SET_TRACE_SPEC = spec(SET_TRACE, Set.of(cli("trace on|off")));
    public static final ActionSpec SET_TRACE_LEVEL_SPEC = spec(SET_TRACE_LEVEL,
        Set.of(cli("trace -l")));
    public static final ActionSpec TRIGGER_MIDNIGHT_TASK_SPEC = spec(TRIGGER_MIDNIGHT_TASK,
        Set.of(cli("triggerMidnightTask")));
    public static final ActionSpec DELETE_ERROR_PROCESSES_SPEC = new ActionSpec(TOOL,
        DELETE_ERROR_PROCESSES, Capability.SERVER_OPERATION, ActionSpec.Area.OPERATION,
        ActionSpec.Kind.CHANGE, Set.of(cli("processdelete error")),
        input -> Set.of(cli("processdelete error")), Set.of(), true, Optional.empty(),
        ActionSpec.RestartNotice.NONE);

    /** The specs of the actions, for the coverage gate (research D-2). */
    public static final List<ActionSpec> SPECS = List.of(SET_MAINTENANCE_SPEC, SET_READINESS_SPEC,
        SET_TRACE_SPEC, SET_TRACE_LEVEL_SPEC, TRIGGER_MIDNIGHT_TASK_SPEC,
        DELETE_ERROR_PROCESSES_SPEC);

    private final ChangeGuard guard;
    private final ChangeLocks locks;
    private final ChangeChallenges challenges;
    private final ChangeAudit audit;
    private final GatewayFactory gateways;
    private final Clock clock;

    /**
     * @param clock the time a midnight task was sent
     */
    public ServerOperationService(ChangeGuard guard, ChangeLocks locks,
        ChangeChallenges challenges, ChangeAudit audit, GatewayFactory gateways, Clock clock) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * One call of a switch action.
     *
     * @param node             the server id as requested
     * @param action           e.g. {@code setMaintenance}
     * @param fields           the action's inputs, e.g. {@code on}, {@code executeQueued}
     * @param reason           why the caller makes the change (audited)
     * @param confirmationCode the code of the preview, for the second call
     * @param mcpClient        the MCP client's {@code name/version}, if sent
     */
    public record Request(String node, String action, Map<String, Object> fields, String reason,
        Optional<String> confirmationCode, Optional<String> mcpClient) {

        public Request {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            Objects.requireNonNull(confirmationCode, "confirmationCode");
            Objects.requireNonNull(mcpClient, "mcpClient");
        }
    }

    /** The preview of a first call, or the result of an executed call. */
    public sealed interface Response {

        record Preview(ChangePreview preview) implements Response {
            public Preview {
                Objects.requireNonNull(preview, "preview");
            }
        }

        /**
         * @param warnings the version gate's warnings, an unknown state before, and an audit
         *     record that could not be written
         */
        record Completed(ChangeResult result, List<String> warnings) implements Response {
            public Completed {
                Objects.requireNonNull(result, "result");
                warnings = List.copyOf(warnings);
            }
        }
    }

    /**
     * A state as read: {@code compared} is the preview state of research D-14, {@code shown} the
     * text of {@code before} / {@code after}; {@code unknown} says why INUBIT's state is not known;
     * {@code affected} holds the processes in state ERROR of {@code deleteErrorProcesses}.
     */
    private record State(String compared, String shown, Optional<String> unknown,
        Optional<ChangePreview.Affected> affected) {

        State(String compared, String shown, Optional<String> unknown) {
            this(compared, shown, unknown, Optional.empty());
        }

        static State known(String value) {
            return new State(value, value, Optional.empty());
        }

        static State unknown(String reason) {
            return new State("unknown", "unknown: " + reason, Optional.of(reason));
        }
    }

    /**
     * Runs one call: a preview, or the change with its result.
     *
     * @throws ToolErrorException every refusal, after its audit record ({@code INVALID_INPUT},
     *     the codes of {@link ChangeGuard#admit}, {@code PRECONDITION_FAILED},
     *     {@code CONFIRMATION_INVALID}, {@link ChangeChallenges.Conflict}, the error of a failed
     *     read before the change); {@code INTERNAL} if a first audit record cannot be written
     */
    public Response execute(Request request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> input = new LinkedHashMap<>(request.fields());
        input.put("reason", request.reason());
        request.confirmationCode().ifPresent(code -> input.put(AuditRecord.CONFIRMATION_CODE,
            code));
        ActionSpec spec;
        try {
            spec = validate(request);
        } catch (ToolErrorException e) {
            throw refuse(audit.call(TOOL, String.valueOf(request.node()),
                String.valueOf(request.action()), input, request.mcpClient()),
                request.confirmationCode().isPresent() ? AuditRecord.Step.EXECUTE
                    : AuditRecord.Step.PREVIEW, e);
        }
        boolean leavingMaintenance = spec == SET_MAINTENANCE_SPEC
            && !flag(request.fields(), ON);
        WritePolicy policy = guard.admit(TOOL, request.node(), spec, input, leavingMaintenance);
        NodeId node = policy.node();
        boolean needsCode = ChangeChallenges.needsCode(policy, spec.area());
        ChangeAudit.Call call = audit.call(TOOL, node.value(), spec.action(), input,
            request.mcpClient());
        call.account(policy.account());
        AuditRecord.Step step = !needsCode || request.confirmationCode().isPresent()
            ? AuditRecord.Step.EXECUTE : AuditRecord.Step.PREVIEW;
        ChangeLocks.Lock lock;
        try {
            lock = locks.acquire(node, spec.area(), spec.action());
        } catch (ToolErrorException e) {
            throw refuse(call, step, e);
        }
        try (lock) {
            State before = readBefore(call, step, request, node);
            Map<String, Object> canonical = new LinkedHashMap<>(request.fields());
            canonical.put("action", spec.action());
            String fingerprint = ChangeChallenges.fingerprint(canonical);
            ChangePreview preview = preview(spec, request, node, before, policy);
            if (before.affected().filter(affected -> affected.count() == 0).isPresent()) {
                if (needsCode && request.confirmationCode().isPresent()) {
                    try {
                        challenges.redeem(request.confirmationCode().get(), TOOL, node,
                            fingerprint);
                    } catch (ToolErrorException e) {
                        throw refuse(call, step, e);
                    }
                }
                return nothingToDo(call, spec, node, before, policy);
            }
            if (needsCode && request.confirmationCode().isEmpty()) {
                return new Response.Preview(issue(call, step, preview, before, fingerprint,
                    policy));
            }
            if (needsCode) {
                try {
                    String previewed = challenges.redeem(request.confirmationCode().get(), TOOL,
                        node, fingerprint);
                    challenges.confirmOrConflict(previewed, previewState(spec.action(), before),
                        fingerprint, preview, policy.confirmationTtl());
                } catch (ToolErrorException e) {
                    throw refuse(call, step, e);
                }
            }
            return run(call, spec, request, node, before, policy);
        }
    }

    private ChangePreview issue(ChangeAudit.Call call, AuditRecord.Step step,
        ChangePreview preview, State before, String fingerprint, WritePolicy policy) {
        ChangePreview.Confirmation confirmation;
        try {
            confirmation = challenges.issue(TOOL, preview.node(), fingerprint,
                previewState(preview.action(), before), policy.confirmationTtl());
        } catch (ToolErrorException e) {
            throw refuse(call, step, e);
        }
        try {
            call.challengeIssued(confirmation.code(), "Preview of " + preview.action() + " on "
                + preview.node() + ": " + before.shown() + " -> " + preview.requested()
                + ", code valid until " + confirmation.expiresAt());
        } catch (ToolErrorException e) {
            challenges.discard(confirmation.code());
            throw e;
        }
        LOG.info("{} {} on {}: CHALLENGE_ISSUED (audit id {})", TOOL, preview.action(),
            preview.node(), call.auditId());
        return new ChangePreview(preview.node(), preview.tool(), preview.action(), preview.area(),
            preview.target(), preview.before(), preview.requested(), preview.affected(),
            preview.effects(), preview.backup(), Optional.of(confirmation), preview.warnings());
    }

    /** The result of a call whose set was empty; nothing is sent (spec edge case). */
    private static Response.Completed nothingToDo(ChangeAudit.Call call, ActionSpec spec,
        NodeId node, State before, WritePolicy policy) {
        String action = spec.action();
        call.nothingToDo("No process is in state ERROR on " + node + "; nothing was sent");
        LOG.info("{} {} on {}: NOTHING_TO_DO (audit id {})", TOOL, action, node, call.auditId());
        return new Response.Completed(new ChangeResult(node, TOOL, action, target(action),
            ChangeOutcome.NOTHING_TO_DO, before.shown(), "nothing was sent",
            Optional.of(before.shown()), Optional.empty(), OptionalInt.empty(), Optional.empty(),
            ChangeResult.RestartNeeded.FALSE, call.auditId()), warnings(policy, action, before));
    }

    private Response.Completed run(ChangeAudit.Call call, ActionSpec spec, Request request,
        NodeId node, State before, WritePolicy policy) {
        String action = spec.action();
        call.pending("About to " + action + " on " + node + ": " + before.shown() + " -> "
            + requested(action, request.fields()));
        String sentAt = clock.instant().toString();
        ChangeOutcome outcome;
        String answer;
        OptionalInt affectedCount = OptionalInt.empty();
        try {
            if (action.equals(DELETE_ERROR_PROCESSES)) {
                long deleted = gateways.serverOperation(node).deleteErrorProcesses(
                    policy.permit());
                affectedCount = OptionalInt.of((int) Math.min(deleted, Integer.MAX_VALUE));
                answer = "INUBIT reports " + processes(deleted) + " deleted";
            } else {
                answer = send(action, request.fields(), node, policy.permit());
            }
            outcome = ChangeOutcome.EXECUTED;
        } catch (ToolErrorException e) {
            answer = e.error().code() + ": " + e.error().message();
            outcome = ChangeOutcome.FAILED;
        } catch (RuntimeException e) {
            LOG.error("{} {} on {} failed unexpectedly", TOOL, action, node, e);
            answer = ErrorCode.INTERNAL + ": " + action + " failed unexpectedly ("
                + e.getClass().getSimpleName() + "); check the state with get_health and the MCP"
                + " server log on stderr";
            outcome = ChangeOutcome.FAILED;
        }
        Optional<String> after;
        Optional<String> reReadFailed;
        if (action.equals(TRIGGER_MIDNIGHT_TASK)) {
            after = Optional.of("sent at " + sentAt);
            reReadFailed = Optional.empty();
        } else {
            State reread;
            try {
                reread = read(action, request.fields(), node, false);
            } catch (ToolErrorException e) {
                reread = State.unknown(e.error().code() + ": " + e.error().message());
            } catch (RuntimeException e) {
                LOG.error("{} {} on {}: reading the state again failed unexpectedly", TOOL,
                    action, node, e);
                reread = State.unknown(ErrorCode.INTERNAL + ": reading the state again failed"
                    + " unexpectedly (" + e.getClass().getSimpleName() + ")");
            }
            after = reread.unknown().isPresent() ? Optional.empty() : Optional.of(reread.shown());
            reReadFailed = reread.unknown();
        }
        String text = action + " on " + node + ": " + outcome + " (" + answer + "); after: "
            + after.orElse("re-read failed");
        Optional<String> auditWarning = outcome == ChangeOutcome.EXECUTED ? call.executed(text)
            : call.failed(text);
        LOG.info("{} {} on {}: {} (audit id {})", TOOL, action, node, outcome, call.auditId());
        List<String> warnings = new ArrayList<>(warnings(policy, action, before));
        auditWarning.ifPresent(warnings::add);
        return new Response.Completed(new ChangeResult(node, TOOL, action, target(action),
            outcome, before.shown(), answer, after, reReadFailed, affectedCount,
            Optional.empty(), ChangeResult.RestartNeeded.FALSE, call.auditId()), warnings);
    }

    private State readBefore(ChangeAudit.Call call, AuditRecord.Step step, Request request,
        NodeId node) {
        try {
            return read(request.action(), request.fields(), node, true);
        } catch (ToolErrorException e) {
            throw refuse(call, step, e);
        } catch (RuntimeException e) {
            LOG.error("{} {} on {}: reading the state failed unexpectedly", TOOL,
                request.action(), node, e);
            throw refuse(call, step, new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "Reading the state of " + node + " failed unexpectedly ("
                    + e.getClass().getSimpleName() + "); nothing was changed",
                "A defect of the MCP server", "Check the MCP server log on stderr")
                .withNode(node)));
        }
    }

    /**
     * The state of {@code action} on {@code node}.
     *
     * @param before whether this is the read before the change: only then are the running and
     *     queued processes read before switching maintenance mode on
     */
    private State read(String action, Map<String, Object> fields, NodeId node, boolean before) {
        ServerOperationPort port = gateways.serverOperation(node);
        return switch (action) {
            case SET_MAINTENANCE -> {
                String mode = onOff(maintenanceOn(port));
                yield before && flag(fields, ON)
                    ? new State(mode, mode + " (" + load(node) + ")", Optional.empty())
                    : State.known(mode);
            }
            case SET_READINESS -> State.known(readiness(gateways.monitoring(node).ready()
                .ready()));
            case SET_TRACE -> trace(node);
            case SET_TRACE_LEVEL -> port.traceLevel().map(level -> State.known(name(level)))
                .orElseGet(() -> State.unknown(NO_TRACE_LEVEL_READ));
            case TRIGGER_MIDNIGHT_TASK -> new State(TRIGGER_MIDNIGHT_TASK, startTime(node),
                Optional.empty());
            case DELETE_ERROR_PROCESSES -> errorProcesses(port.errorProcesses(clock.instant()));
            default -> throw new IllegalArgumentException("Unknown action " + action);
        };
    }

    /**
     * The processes in state ERROR, compared by a SHA-256 of their sorted ids, or by their count
     * when INUBIT lists no ids: more than {@link ServerOperationPort#MAX_ERROR_PROCESS_IDS} rows
     * (research D-14) or a count without rows. The set is empty, and {@code affected} counts 0,
     * exactly when INUBIT reports no row. The preview names at most
     * {@link ChangePreview.Affected#MAX_IDS} ids and workflows.
     */
    private static State errorProcesses(ServerOperationPort.ErrorProcesses set) {
        if (set.count() == 0) {
            return new State("none", NO_ERROR_PROCESS, Optional.empty(),
                Optional.of(new ChangePreview.Affected(0, List.of(), false)));
        }
        List<String> ids = set.ids();
        if (set.countOnly() || ids.isEmpty()) {
            return new State("count " + set.count(), set.count() + " Queue Manager rows in state"
                + " ERROR; " + (set.countOnly() ? "with more than "
                    + ServerOperationPort.MAX_ERROR_PROCESS_IDS + " rows the process ids and"
                    + " workflows are not listed"
                    : "INUBIT listed none of their process ids and workflows"), Optional.empty(),
                Optional.of(new ChangePreview.Affected(Math.toIntExact(set.count()), List.of(),
                    true)));
        }
        List<String> workflows = set.workflows();
        int listed = Math.min(workflows.size(), ChangePreview.Affected.MAX_IDS);
        String shown = processes(ids.size()) + " in state ERROR"
            + (set.count() != ids.size() ? " (" + set.count() + " Queue Manager rows)" : "")
            + " in the workflow" + (workflows.size() == 1 ? " " : "s ")
            + String.join(", ", workflows.subList(0, listed))
            + (workflows.size() > listed ? " and " + (workflows.size() - listed) + " more" : "");
        return new State(ChangeChallenges.fingerprint(Map.of("ids", ids)), shown,
            Optional.empty(), Optional.of(ChangePreview.Affected.of(ids)));
    }

    private static String processes(long count) {
        return count + (count == 1 ? " process" : " processes");
    }

    private static boolean maintenanceOn(ServerOperationPort port) {
        try {
            return port.maintenanceState();
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.MAINTENANCE_MODE) {
                return true;
            }
            throw e;
        }
    }

    /** The running and queued processes as {@code get_health} reads them (AS2). */
    private String load(NodeId node) {
        String unknown = "running and queued processes unknown: ";
        try {
            return switch (gateways.monitoring(node).metrics()) {
                case MonitoringPort.Metrics.Available available -> {
                    LoadFigures figures = available.figures();
                    yield "running: " + count(figures.threadsInUse()) + " workflow threads in use;"
                        + " queued: " + count(figures.blockingQueueEntries())
                        + " entries in the blocking queue";
                }
                case MonitoringPort.Metrics.NotLicensed notLicensed ->
                    unknown + notLicensed.reason();
            };
        } catch (ToolErrorException e) {
            return unknown + e.error().code();
        }
    }

    private static String count(Optional<Long> value) {
        return value.map(String::valueOf).orElse("unknown");
    }

    private State trace(NodeId node) {
        try {
            return gateways.monitoring(node).systemInfo().tracingEnabled()
                .map(on -> State.known(onOff(on)))
                .orElseGet(() -> State.unknown("INUBIT's system information does not state"
                    + " whether the trace is on"));
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.MAINTENANCE_MODE) {
                return State.unknown(REST_IN_MAINTENANCE);
            }
            throw e;
        }
    }

    /** {@code MidnightTaskTriggerTime} of {@code GET /configurations}, if INUBIT reports it. */
    private String startTime(NodeId node) {
        try {
            return gateways.serverSettings(node).configuration(
                    Optional.of(MIDNIGHT_TASK_TRIGGER_TIME), 1, ItemBounds.MAX_MESSAGE_CHARS)
                .values().stream().findFirst().flatMap(ConfigurationView.Leaf::value)
                .map(time -> "start time (" + MIDNIGHT_TASK_TRIGGER_TIME + "): " + time)
                .orElse("start time not reported by INUBIT");
        } catch (ToolErrorException e) {
            return e.error().code() == ErrorCode.NOT_FOUND ? "start time not reported by INUBIT"
                : "start time could not be read (" + e.error().code() + ")";
        }
    }

    private String send(String action, Map<String, Object> fields, NodeId node,
        WritePermit permit) {
        ServerOperationPort port = gateways.serverOperation(node);
        return switch (action) {
            case SET_MAINTENANCE -> "maintenance mode " + onOff(port.switchMaintenance(
                flag(fields, ON), Optional.ofNullable((Boolean) fields.get(EXECUTE_QUEUED)),
                permit));
            case SET_READINESS -> {
                boolean ready = flag(fields, READY);
                port.switchReadiness(ready, permit);
                yield "POST /ready/" + onOff(ready) + " was sent; INUBIT's answer is not read";
            }
            case SET_TRACE -> "trace " + onOff(port.switchTrace(flag(fields, ON), permit));
            case SET_TRACE_LEVEL -> "trace level " + name(port.setTraceLevel(level(fields),
                permit));
            case TRIGGER_MIDNIGHT_TASK -> port.triggerMidnightTask(permit);
            default -> throw new IllegalArgumentException("Unknown action " + action);
        };
    }

    /** The preview without its code; {@link #issue} adds it. */
    private static ChangePreview preview(ActionSpec spec, Request request, NodeId node,
        State before, WritePolicy policy) {
        String action = spec.action();
        return new ChangePreview(node, TOOL, action,
            spec.area().name().toLowerCase(Locale.ROOT), target(action), before.shown(),
            requested(action, request.fields()), before.affected(),
            effects(action, request.fields()), action.equals(DELETE_ERROR_PROCESSES)
                ? "none: the processes are not exported before they are deleted"
                : "none: nothing is removed or replaced",
            Optional.empty(), warnings(policy, action, before));
    }

    private static List<String> warnings(WritePolicy policy, String action, State before) {
        List<String> warnings = new ArrayList<>(policy.warnings());
        before.unknown().ifPresent(reason -> warnings.add("The current " + target(action)
            + " cannot be shown: " + reason));
        return warnings;
    }

    /** The canonical state the preview showed (research D-14). */
    private static String previewState(String action, State state) {
        return action + "=" + state.compared();
    }

    private static String target(String action) {
        return switch (action) {
            case SET_MAINTENANCE -> "maintenance mode";
            case SET_READINESS -> "readiness";
            case SET_TRACE -> "trace";
            case SET_TRACE_LEVEL -> "trace level";
            case DELETE_ERROR_PROCESSES -> "processes in state ERROR";
            default -> "midnight task";
        };
    }

    private static String requested(String action, Map<String, Object> fields) {
        return switch (action) {
            case SET_MAINTENANCE -> flag(fields, ON)
                ? "on" + (fields.containsKey(EXECUTE_QUEUED)
                    ? " with -eq " + fields.get(EXECUTE_QUEUED) : "")
                : "off";
            case SET_READINESS -> readiness(flag(fields, READY));
            case SET_TRACE -> onOff(flag(fields, ON));
            case SET_TRACE_LEVEL -> name(level(fields));
            case DELETE_ERROR_PROCESSES -> "delete every process in state ERROR";
            default -> "run now";
        };
    }

    /** The fixed effect sentences of the contract. */
    private static List<String> effects(String action, Map<String, Object> fields) {
        return switch (action) {
            case SET_MAINTENANCE -> {
                if (!flag(fields, ON)) {
                    yield List.of(MAINTENANCE_EFFECT);
                }
                Object executeQueued = fields.get(EXECUTE_QUEUED);
                yield List.of(MAINTENANCE_EFFECT, executeQueued == null ? EQ_NONE
                    : (Boolean) executeQueued ? EQ_TRUE : EQ_FALSE);
            }
            case SET_READINESS -> List.of(READINESS_EFFECT);
            case SET_TRACE -> List.of(flag(fields, ON)
                ? "The trace stays on until it is switched off."
                : "The trace stays off until it is switched on.");
            case SET_TRACE_LEVEL -> List.of("The trace level stays " + name(level(fields))
                + " until it is set back.");
            case DELETE_ERROR_PROCESSES -> DELETE_ERROR_PROCESSES_EFFECTS;
            default -> MIDNIGHT_TASK_EFFECTS;
        };
    }

    // --- input validation (FR-020) -------------------------------------------------------------

    private static ActionSpec validate(Request request) {
        ActionSpec spec = SPECS.stream().filter(candidate -> candidate.action()
            .equals(request.action())).findFirst().orElseThrow(() -> invalid(
                "The action is not one of " + SPECS.stream().map(ActionSpec::action)
                    .collect(Collectors.joining(", ")) + "; nothing was changed"));
        String action = spec.action();
        Map<String, Object> fields = request.fields();
        Set<String> allowed = switch (action) {
            case SET_MAINTENANCE -> Set.of(ON, EXECUTE_QUEUED);
            case SET_READINESS -> Set.of(READY);
            case SET_TRACE -> Set.of(ON);
            case SET_TRACE_LEVEL -> Set.of(LEVEL);
            default -> Set.of();
        };
        if (!allowed.containsAll(fields.keySet())) {
            throw invalid(action + " takes " + (allowed.isEmpty() ? "no inputs besides reason"
                : "only " + String.join(", ", new TreeSet<>(allowed))) + "; nothing was changed");
        }
        switch (action) {
            case SET_MAINTENANCE -> {
                boolean on = flag(fields, ON);
                if (fields.containsKey(EXECUTE_QUEUED)) {
                    flag(fields, EXECUTE_QUEUED);
                    if (!on) {
                        throw invalid("executeQueued (-eq) is sent only when maintenance mode is"
                            + " switched on (on: true); nothing was changed");
                    }
                }
            }
            case SET_READINESS -> flag(fields, READY);
            case SET_TRACE -> flag(fields, ON);
            case SET_TRACE_LEVEL -> level(fields);
            default -> {
            }
        }
        if (request.reason() == null || !REASON.matcher(request.reason()).matches()) {
            throw invalid("reason must be 1 to 500 characters on one line; nothing was changed");
        }
        return spec;
    }

    private static boolean flag(Map<String, Object> fields, String name) {
        if (fields.get(name) instanceof Boolean value) {
            return value;
        }
        throw invalid(name + " must be true or false; nothing was changed");
    }

    private static TraceLevel level(Map<String, Object> fields) {
        Object value = fields.get(LEVEL);
        return Arrays.stream(TraceLevel.values()).filter(level -> name(level).equals(value))
            .findFirst().orElseThrow(() -> invalid("level must be one of " + Arrays.stream(
                TraceLevel.values()).map(ServerOperationService::name)
                .collect(Collectors.joining(", ")) + "; nothing was changed"));
    }

    private static ToolErrorException invalid(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            "The input does not match the operate_server action",
            "Correct the input and call operate_server again"));
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Audits {@code e} as {@code REFUSED} and returns it unchanged (a
     * {@link ChangeChallenges.Conflict} keeps its preview), or {@code INTERNAL} if the record
     * could not be written.
     */
    private static ToolErrorException refuse(ChangeAudit.Call call, AuditRecord.Step step,
        ToolErrorException e) {
        ToolErrorException refusal = call.refused(step, e.error());
        return refusal.error() == e.error() ? e : refusal;
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private static String readiness(boolean ready) {
        return ready ? "READY" : "NOT READY";
    }

    private static String name(TraceLevel level) {
        return level.name().toLowerCase(Locale.ROOT);
    }

    private static CoverageKey cli(String entry) {
        return new CoverageKey(Channel.CLI, entry);
    }

    /** A change of the area {@code OPERATION} whose every call sends {@code entries}. */
    private static ActionSpec spec(String action, Set<CoverageKey> entries) {
        return spec(action, entries, input -> entries);
    }

    /** A change of the area {@code OPERATION} whose call sends the entries of {@code variant}. */
    private static ActionSpec spec(String action, Set<CoverageKey> entries,
        Function<Map<String, Object>, Set<CoverageKey>> variant) {
        return new ActionSpec(TOOL, action, Capability.SERVER_OPERATION,
            ActionSpec.Area.OPERATION, ActionSpec.Kind.CHANGE, entries, variant, Set.of(), false,
            Optional.empty(), ActionSpec.RestartNotice.NONE);
    }
}
