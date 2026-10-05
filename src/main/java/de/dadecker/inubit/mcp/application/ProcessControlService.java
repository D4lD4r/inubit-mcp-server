package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ConfirmationChallenge;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;
import de.dadecker.inubit.mcp.domain.model.ProcessControlResult;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code restart_process} and {@code kill_process} (US4, FR-018 – FR-024,
 * contracts/mcp-tools.md §7–8). Every call that ends the flow writes an audit record first.
 *
 * <ol>
 *   <li>Inputs that can be checked without INUBIT: the process id ({@code ^[1-9][0-9]{0,18}$}), the
 *       confirmation code ({@code ^[A-Za-z0-9_-]{22}$}) and the reason (≤ 500 chars) →
 *       {@code INVALID_INPUT}.
 *   <li>{@link WriteGuard}: single configured server, production lock, {@code write.enabled},
 *       CLI available.
 *   <li>One action per instance at a time: a second call for the same server and process id
 *       while one runs is {@code PRECONDITION_FAILED}.
 *   <li>The current state is read from the Queue Manager
 *       ({@link de.dadecker.inubit.mcp.domain.port.ProcessQueryPort#findByProcessId}, REST
 *       {@code queueLog} {@code workflowId EQUAL <pid>}). No row → {@code NOT_FOUND} (the
 *       instance finished or was restarted/killed meanwhile). A restart needs a row in
 *       {@code ERROR} (FR-020); otherwise {@code PRECONDITION_FAILED} with the actual states,
 *       also when the row cap was hit without an {@code ERROR} row. Because the state is read on
 *       every call, a change between preview and execution is detected and nothing is executed.
 *   <li>Confirmation {@code SERVER} without code: a {@link ConfirmationChallenge} with the
 *       preview, audited {@code CHALLENGE_ISSUED} (if that record cannot be written, the code is
 *       withdrawn and the call fails with {@code INTERNAL}). With code: the code is redeemed
 *       (single use) or {@code CONFIRMATION_INVALID}. A code presented with a call that is
 *       refused for another reason is discarded as well. The code is bound to the previewed
 *       state of the instance (state, raw state, {@code since}, workflow, module of the previewed
 *       row): if the freshly read row differs in any of them, the call is
 *       {@code PRECONDITION_FAILED} naming previewed and actual state, and nothing is executed
 *       (review W1). Confirmation {@code CLIENT}: the action runs on the first call (FR-022).
 *   <li>The {@code EXECUTE} record with outcome {@code PENDING} is written and forced to disk
 *       <em>before</em> the StartCLI call; if that fails, the action is not executed
 *       ({@code INTERNAL}, fail closed, research R-14).
 *   <li>StartCLI runs; its failure is a {@code FAILED} result (not a tool error), so that the
 *       audit id and the re-read state reach the caller. The state is re-read
 *       ({@code stateAfter}), and the final record ({@code EXECUTED}/{@code FAILED}) is written
 *       with the same audit id. If only that record fails, the result says so.
 * </ol>
 *
 * <p>Every refusal is audited as {@code REFUSED} with step {@code EXECUTE} when the call would
 * have executed (a code was presented, or confirmation {@code CLIENT}), else {@code PREVIEW}. If
 * a refusal cannot be audited, the call fails with {@code INTERNAL} naming the refusal. An
 * unexpected exception before the action (guard, state read, code handling) is an audited
 * {@code INTERNAL} refusal (review W3). Each
 * outcome is also logged (INFO; a failed execution WARN) for operators (Constitution VII).
 */
public final class ProcessControlService {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessControlService.class);
    private static final Pattern PROCESS_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    /** Upper bound of {@code reason} (contracts/mcp-tools.md §7–8). */
    public static final int MAX_REASON = 500;
    private static final int MAX_AUDITED_INPUT = 500;

    /**
     * The arguments of {@code restart_process} / {@code kill_process}.
     *
     * @param mcpClient the MCP client's {@code name/version} from {@code initialize}, if known
     */
    public record ControlRequest(ProcessAction action, String node, String processId,
        Optional<String> confirmationCode, Optional<String> reason, Optional<String> mcpClient) {

        public ControlRequest {
            Objects.requireNonNull(action, "action");
            node = node == null ? "" : node;
            processId = processId == null ? "" : processId;
            confirmationCode = confirmationCode == null ? Optional.empty() : confirmationCode;
            reason = reason == null ? Optional.empty() : reason;
            mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
        }
    }

    /** Either the preview of the first step or the result of an execution. */
    public sealed interface Response {

        record Challenge(ConfirmationChallenge challenge) implements Response {
            public Challenge {
                Objects.requireNonNull(challenge, "challenge");
            }
        }

        record Completed(ProcessControlResult result) implements Response {
            public Completed {
                Objects.requireNonNull(result, "result");
            }
        }
    }

    private final WriteGuard guard;
    private final Function<NodeId, WritePolicy> policies;
    private final GatewayFactory gateways;
    private final ConfirmationRegistry confirmations;
    private final AuditPort audit;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final String profile;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /**
     * @param policies the write settings of each configured server (for account and stage of
     *                 refusals); the same as the guard's
     * @param ids      the audit ids
     * @param profile  the profile name every audit record carries (002 research D-7)
     */
    public ProcessControlService(WriteGuard guard, Function<NodeId, WritePolicy> policies,
        GatewayFactory gateways, ConfirmationRegistry confirmations, AuditPort audit, Clock clock,
        Supplier<UUID> ids, String profile) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * Runs the decision flow for one call.
     *
     * @throws ToolErrorException every refusal (after its audit record), or {@code INTERNAL} if
     *     an audit record cannot be written
     */
    public Response execute(ControlRequest request) {
        Call call = new Call(request);
        WritePolicy policy;
        try {
            validate(request);
            policy = guard.admit(request.node());
        } catch (ToolErrorException e) {
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            throw call.refuse(unexpected("checking the write access", e));
        }
        call.policy = policy;
        String key = policy.node() + " " + request.processId();
        if (!running.add(key)) {
            throw call.refuse(error(ErrorCode.PRECONDITION_FAILED, policy.node(),
                "Another restart or kill of process " + request.processId() + " on "
                    + policy.node() + " is running; nothing was changed",
                "The same instance was requested twice at the same time",
                "Wait for the running action and check the instance with find_processes"));
        }
        try {
            return decide(call, policy);
        } finally {
            running.remove(key);
        }
    }

    private Response decide(Call call, WritePolicy policy) {
        ControlRequest request = call.request;
        NodeId node = policy.node();
        ProcessInstance before;
        try {
            before = currentRow(request.action(), node, request.processId());
        } catch (ToolErrorException e) {
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            throw call.refuse(unexpected("reading the state of the instance", e));
        }
        if (policy.confirmation() == WritePolicy.Confirmation.SERVER) {
            if (request.confirmationCode().isEmpty()) {
                return challenge(call, node, before);
            }
            ConfirmationRegistry.Previewed previewed;
            try {
                previewed = confirmations.redeem(request.confirmationCode().get(), node,
                    request.action(), request.processId());
            } catch (ToolErrorException e) {
                throw call.refuse(e.error());
            } catch (RuntimeException e) {
                throw call.refuse(unexpected("checking the confirmation code", e));
            }
            ConfirmationRegistry.Previewed actual = ConfirmationRegistry.Previewed.of(before);
            if (!previewed.equals(actual)) {
                throw call.refuse(error(ErrorCode.PRECONDITION_FAILED, node,
                    "Process " + request.processId() + " on " + node + " changed since the"
                        + " preview (previewed: " + describe(previewed) + "; actual: "
                        + describe(actual) + "); nothing was changed and the code is used up",
                    "The instance was restarted, continued, failed again or another row of it"
                        + " is now relevant",
                    "Call " + request.action().capability() + " again without"
                        + " confirmationCode to get a new preview of the current state"));
            }
        }
        return run(call, node, before);
    }

    private Response challenge(Call call, NodeId node, ProcessInstance before) {
        ControlRequest request = call.request;
        WritePolicy policy = call.policy;
        ConfirmationRegistry.Issued issued;
        try {
            issued = confirmations.issue(node, request.action(), request.processId(),
                ConfirmationRegistry.Previewed.of(before), policy.confirmationTtl());
        } catch (ToolErrorException e) {
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            throw call.refuse(unexpected("issuing the confirmation code", e));
        }
        Map<String, String> inputs = call.inputs();
        inputs.put(AuditRecord.ISSUED_CONFIRMATION_CODE, issued.code());
        try {
            audit.append(call.record(ids.get(), AuditRecord.Step.PREVIEW, inputs,
                AuditOutcome.CHALLENGE_ISSUED, Optional.of("Preview of "
                    + request.action().capability() + " " + request.processId() + " in state "
                    + before.state() + ", code valid until " + issued.expiresAt())));
        } catch (RuntimeException e) {
            confirmations.discard(issued.code());
            throw auditFailure(node, e, "the preview was not issued");
        }
        LOG.info("{} {} on {}: CHALLENGE_ISSUED (valid until {})",
            request.action().capability(), request.processId(), node, issued.expiresAt());
        ConfirmationChallenge.Preview preview = new ConfirmationChallenge.Preview(node,
            request.action(), request.processId(), before.workflow(), before.module(),
            before.state(), before.since());
        return new Response.Challenge(new ConfirmationChallenge(issued.code(),
            issued.expiresAt(), preview, "Nothing was changed. To " + verb(request.action())
                + " process " + request.processId() + " on " + node + ", show this preview"
                + " to the user and, after their explicit approval, call "
                + request.action().capability() + " again with the same node and processId"
                + " and confirmationCode before " + issued.expiresAt() + "."));
    }

    private Response.Completed run(Call call, NodeId node, ProcessInstance before) {
        ControlRequest request = call.request;
        UUID auditId = ids.get();
        try {
            audit.append(call.record(auditId, AuditRecord.Step.EXECUTE, call.inputs(),
                AuditOutcome.PENDING, Optional.of("About to run " + request.action().capability()
                    + " " + request.processId() + " (state " + before.state() + ")")));
        } catch (RuntimeException e) {
            throw auditFailure(node, e, "the action was NOT executed");
        }
        ProcessControlResult.Outcome outcome;
        String message;
        try {
            message = request.action() == ProcessAction.RESTART
                ? gateways.processControl(node).restart(request.processId())
                : gateways.processControl(node).kill(request.processId());
            outcome = ProcessControlResult.Outcome.EXECUTED;
        } catch (ToolErrorException e) {
            outcome = ProcessControlResult.Outcome.FAILED;
            message = describe(e.error());
        } catch (RuntimeException e) {
            LOG.error("{} {} on {} failed unexpectedly", request.action().capability(),
                request.processId(), node, e);
            outcome = ProcessControlResult.Outcome.FAILED;
            message = ErrorCode.INTERNAL + ": " + request.action().capability()
                + " failed unexpectedly (" + e.getClass().getSimpleName() + "). Next step:"
                + " check the instance with find_processes and the MCP server log on stderr";
        }
        Optional<String> stateAfter = stateAfter(node, request.processId());
        try {
            audit.append(call.record(auditId, AuditRecord.Step.EXECUTE, call.inputs(),
                outcome == ProcessControlResult.Outcome.EXECUTED ? AuditOutcome.EXECUTED
                    : AuditOutcome.FAILED,
                Optional.of(message + stateAfter.map(state -> " (state after: " + state + ")")
                    .orElse(""))));
        } catch (RuntimeException e) {
            LOG.error("The final audit record {} of {} {} on {} could not be written ({})",
                auditId, request.action().capability(), request.processId(), node,
                e.getClass().getSimpleName());
            message = message + " WARNING: the final audit record could not be written (only"
                + " the pending record " + auditId + " exists); check the audit directory";
        }
        if (outcome == ProcessControlResult.Outcome.EXECUTED) {
            LOG.info("{} {} on {}: EXECUTED (audit id {}, state after: {})",
                request.action().capability(), request.processId(), node, auditId,
                stateAfter.orElse("unknown"));
        } else {
            LOG.warn("{} {} on {}: FAILED (audit id {}): {}", request.action().capability(),
                request.processId(), node, auditId, message);
        }
        return new Response.Completed(new ProcessControlResult(node, request.action(),
            request.processId(), outcome, before.state(), stateAfter, message, auditId));
    }

    /**
     * The row the action applies to: for a restart the newest row in {@code ERROR}; for a kill
     * the top-level row ({@code globalProcessId == processId}), else the newest.
     */
    private ProcessInstance currentRow(ProcessAction action, NodeId node, String processId) {
        ProcessRows rows = gateways.processes(node).findByProcessId(processId,
            clock.instant(), DiagnosisService.DEFAULT_HANGING_THRESHOLD);
        if (rows.rows().isEmpty()) {
            throw new ToolErrorException(error(ErrorCode.NOT_FOUND, node,
                "Process " + processId + " is no longer (or not) in the Queue Manager of "
                    + node + "; nothing was changed",
                "The instance finished, was restarted or killed meanwhile, or the id is wrong",
                "Look the instance up again with find_processes"));
        }
        if (action == ProcessAction.KILL) {
            return rows.rows().stream()
                .filter(row -> row.globalProcessId().filter(processId::equals).isPresent())
                .findFirst()
                .orElse(rows.rows().get(0));
        }
        return rows.rows().stream()
            .filter(row -> row.state() == ProcessState.ERROR)
            .findFirst()
            .orElseThrow(() -> new ToolErrorException(error(ErrorCode.PRECONDITION_FAILED,
                node, "Process " + processId + " on " + node + " is not in ERROR (actual: "
                    + states(rows) + "); only instances in ERROR can be restarted, nothing was"
                    + " changed",
                "The instance is still running, waiting or queued, or it was restarted"
                    + " meanwhile",
                "Check the instance with find_processes; kill_process removes an instance in"
                    + " any state")));
    }

    private static String states(ProcessRows rows) {
        Map<String, Integer> counts = new TreeMap<>();
        rows.rows().forEach(row -> counts.merge(row.state() + " (" + row.rawState() + ")", 1,
            Integer::sum));
        StringBuilder text = new StringBuilder();
        counts.forEach((state, count) -> text.append(text.isEmpty() ? "" : ", ").append(state)
            .append(count > 1 ? " ×" + count : ""));
        if (rows.truncated()) {
            text.append("; only ").append(rows.rows().size()).append(" of ").append(rows.total())
                .append(" rows were read and none is in ERROR");
        }
        return text.toString();
    }

    /** The state after the action; {@code NOT_IN_QUEUE} if gone, empty if unreadable. */
    private Optional<String> stateAfter(NodeId node, String processId) {
        try {
            ProcessRows rows = gateways.processes(node).findByProcessId(processId,
                clock.instant(), DiagnosisService.DEFAULT_HANGING_THRESHOLD);
            if (rows.rows().isEmpty()) {
                return Optional.of(ProcessControlResult.NOT_IN_QUEUE);
            }
            return Optional.of(rows.rows().stream()
                .filter(row -> row.state() == ProcessState.ERROR)
                .findFirst()
                .orElse(rows.rows().get(0)).state().name());
        } catch (RuntimeException e) {
            LOG.warn("The state of {} on {} could not be re-read ({})", processId, node,
                e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static void validate(ControlRequest request) {
        if (!PROCESS_ID.matcher(request.processId()).matches()) {
            throw invalid("Invalid processId " + Names.quote(cut(request.processId())),
                "A processId is the decimal Queue Manager id (workflowId, 1–19 digits without"
                    + " leading zero); a UUID"
                    + " globalProcessId is not accepted",
                "Use the processId from find_processes");
        }
        if (request.confirmationCode().filter(code -> !CODE.matcher(code).matches())
            .isPresent()) {
            throw invalid("Invalid confirmationCode", "A confirmation code has 22 URL-safe"
                + " Base64 chars", "Use the code of the preview exactly as returned");
        }
        if (request.reason().filter(reason -> reason.length() > MAX_REASON).isPresent()) {
            throw invalid("reason is longer than " + MAX_REASON + " chars",
                "The reason is stored in the audit log and bounded",
                "Shorten the reason to at most " + MAX_REASON + " chars");
        }
    }

    private static String describe(ConfirmationRegistry.Previewed row) {
        return row.state() + " (" + row.rawState() + ") since " + row.since()
            + row.workflow().map(workflow -> " in " + workflow).orElse("")
            + row.module().map(module -> " / " + module).orElse("");
    }

    /** An unexpected failure before the action: logged, refused as {@code INTERNAL}. */
    private static ToolError unexpected(String step, RuntimeException e) {
        LOG.error("Unexpected failure while {}", step, e);
        return ToolError.of(ErrorCode.INTERNAL, "Unexpected failure while " + step + " ("
                + e.getClass().getSimpleName() + "); nothing was changed",
            "An internal error of the INUBIT MCP server",
            "Retry; if it persists, check the MCP server log on stderr and report the problem");
    }

    private static String verb(ProcessAction action) {
        return action == ProcessAction.RESTART ? "restart" : "kill";
    }

    private static String describe(ToolError error) {
        return error.code() + ": " + error.message() + " Likely cause: " + error.likelyCause()
            + ". Next step: " + error.nextStep()
            + error.excerpt().map(excerpt -> " Excerpt: " + excerpt).orElse("");
    }

    private static String cut(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    private static ToolError error(ErrorCode code, NodeId node, String message,
        String likelyCause, String nextStep) {
        return ToolError.of(code, message, likelyCause, nextStep).withNode(node);
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            likelyCause, nextStep));
    }

    private static ToolErrorException auditFailure(NodeId node, RuntimeException e,
        String consequence) {
        LOG.error("Audit record could not be written for {} ({})", node,
            e.getClass().getSimpleName());
        return new ToolErrorException(error(ErrorCode.INTERNAL, node,
            "The audit record could not be written; " + consequence,
            "The audit directory is not writable, full, or not owned by this user",
            "Fix the audit directory (auditDirectory in the configuration), then retry"));
    }

    /** One call: its request, the audit fields known so far and the refusal path. */
    private final class Call {

        final ControlRequest request;
        WritePolicy policy;

        Call(ControlRequest request) {
            this.request = request;
        }

        /** The call's inputs for the audit, in a fixed order; long values are cut. */
        Map<String, String> inputs() {
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("processId", bounded(request.processId()));
            request.confirmationCode().ifPresent(code ->
                inputs.put(AuditRecord.CONFIRMATION_CODE, code));
            request.reason().ifPresent(reason -> inputs.put("reason", bounded(reason)));
            return inputs;
        }

        AuditRecord record(UUID auditId, AuditRecord.Step step, Map<String, String> inputs,
            AuditOutcome outcome, Optional<String> reason) {
            Optional<WritePolicy> known = known();
            return new AuditRecord(auditId, clock.instant(), profile,
                known.map(p -> p.node().value()).orElse(bounded(request.node())), group(),
                request.action().capability(), step, inputs,
                known.flatMap(WritePolicy::account), outcome, reason, request.mcpClient());
        }

        /** Audits {@code error} as {@code REFUSED}; discards a presented code. */
        ToolErrorException refuse(ToolError error) {
            request.confirmationCode().ifPresent(confirmations::discard);
            boolean execute = request.confirmationCode().isPresent() || known()
                .map(p -> p.confirmation() == WritePolicy.Confirmation.CLIENT).orElse(false);
            try {
                audit.append(record(ids.get(), execute ? AuditRecord.Step.EXECUTE
                    : AuditRecord.Step.PREVIEW, inputs(), AuditOutcome.REFUSED,
                    Optional.of(error.code() + ": " + error.message())));
            } catch (RuntimeException e) {
                LOG.error("Audit record of a refusal ({}) could not be written ({})",
                    error.code(), e.getClass().getSimpleName());
                return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "The audit record could not be written; the call was refused anyway ("
                        + error.code() + ": " + error.message() + ") and nothing was changed",
                    "The audit directory is not writable, full, or not owned by this user",
                    "Fix the audit directory (auditDirectory in the configuration), then"
                        + " retry"));
            }
            LOG.info("{} {} on {}: REFUSED ({})", request.action().capability(),
                bounded(request.processId()), bounded(request.node()), error.code());
            return new ToolErrorException(error);
        }

        /** The policy of the requested server, if it is a configured server. */
        private Optional<WritePolicy> known() {
            if (policy != null) {
                return Optional.of(policy);
            }
            try {
                return Optional.ofNullable(policies.apply(NodeId.parse(request.node())));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private Optional<String> group() {
            try {
                return Optional.of(switch (Target.parse(request.node())) {
                    case Target.Group group -> group.id().value();
                    case Target.Node node -> node.id().group().value();
                });
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private static String bounded(String value) {
            return value.length() <= MAX_AUDITED_INPUT ? value
                : value.substring(0, MAX_AUDITED_INPUT);
        }
    }
}
