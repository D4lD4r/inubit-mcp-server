package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit records of the log-level tools and of the automatic resets (feature 024, research
 * D-22, Constitution VII), built like the records of {@code ProcessControlService} and
 * {@code ServerLogAudit}.
 *
 * <p>{@link ToolCall}: one call of {@value #SET_TOOL} or {@value #RESET_TOOL}, one
 * {@code auditId}; the capability is the tool name. A preview is one {@code PREVIEW} record
 * ({@code CHALLENGE_ISSUED} or {@code REFUSED}); an execution is a {@code REFUSED} record or a
 * {@code PENDING} record, forced to disk before anything is sent, followed by the final record.
 * Inputs in the fixed order {@code node}, {@code leaseId}, {@code loggers} (names and levels
 * only; without loggers the requested {@code level} alone), {@code durationMinutes}, {@code forget}, {@code confirmationCode} (the audit log stores
 * every confirmation code only as a SHA-256 prefix); details {@code leaseId}, {@code inubitNode},
 * {@code expiresAt}, {@code leaseOpen}, each once known. The details describe the lease as it is
 * after the call: {@code leaseOpen} is its state then (false after a reset ended it), unlike the
 * {@code leaseOpen} of a reset result, which says whether a lease was open when the call ran.
 *
 * <p>{@link AutomaticReset}: one reset of a lease without a tool call, capability
 * {@value #RESET_TOOL}, inputs {@code trigger} and {@code leaseId}. Fail closed: a record that
 * cannot be written is logged as ERROR and thrown as {@link NotWritableException}, never
 * swallowed (D-16 step 4: nothing is sent then).
 */
public final class LogLevelAudit {

    public static final String SET_TOOL = "set_temporary_log_level";
    public static final String RESET_TOOL = "reset_temporary_log_level";
    /** The {@code node} of a record whose server is not known (a forget of an unknown lease). */
    public static final String NO_NODE = "-";
    /** The bound of the {@code loggers} input: five names of the maximum length with levels. */
    public static final int MAX_AUDITED_LOGGERS = 1_100;

    private static final Logger LOG = LoggerFactory.getLogger(LogLevelAudit.class);
    private static final int MAX_AUDITED_INPUT = 500;
    private static final Set<AuditOutcome> FINAL = EnumSet.of(AuditOutcome.EXECUTED,
        AuditOutcome.FAILED, AuditOutcome.REFUSED);
    private static final String AUDIT_CAUSE =
        "The audit directory is not writable, full, or not owned by this user";
    private static final String AUDIT_NEXT_STEP =
        "Fix the audit directory (auditDirectory in the configuration), then retry";

    private final AuditPort port;
    private final Clock clock;
    private final String profile;
    private final Supplier<UUID> ids;

    /**
     * @param port    the append-only audit log
     * @param clock   the time of each record
     * @param profile the name of the profile this MCP server serves
     * @param ids     the source of the {@code auditId}s (one per call or automatic reset)
     */
    public LogLevelAudit(AuditPort port, Clock clock, String profile, Supplier<UUID> ids) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * Starts the audit of one tool call.
     *
     * @param tool      {@value #SET_TOOL} or {@value #RESET_TOOL}
     * @param inputs    the call's inputs as requested
     * @param mcpClient the MCP client's {@code name/version}, if sent
     */
    public ToolCall toolCall(String tool, Inputs inputs, Optional<String> mcpClient) {
        if (!SET_TOOL.equals(tool) && !RESET_TOOL.equals(tool)) {
            throw new IllegalArgumentException("Not a log-level tool: " + tool);
        }
        return new ToolCall(tool, inputs, mcpClient);
    }

    /**
     * Starts the audit of one automatic reset of {@code lease}.
     *
     * @param account the INUBIT username of the lease's server, if the server is configured
     */
    public AutomaticReset automaticReset(Trigger trigger, Lease lease, Optional<String> account) {
        return new AutomaticReset(trigger, lease, account);
    }

    /** Why an automatic record is written (D-22), with its name in the audit log. */
    public enum Trigger {
        EXPIRY("expiry", true),
        RETRY("retry", true),
        /** Another instance took the lease over; nothing is sent. */
        TAKEOVER("takeover", false),
        STARTUP("startup", true),
        SHUTDOWN("shutdown", true),
        /** The lease of an unconfigured server ends as {@code FORGOTTEN}; nothing is sent. */
        FORGET_AFTER_7_DAYS("forget-after-7-days", false);

        private final String wireName;
        private final boolean sendsReset;

        Trigger(String wireName, boolean sendsReset) {
            this.wireName = wireName;
            this.sendsReset = sendsReset;
        }

        public String wireName() {
            return wireName;
        }
    }

    /**
     * The inputs of one call as requested (also when invalid); absent inputs are not recorded.
     *
     * @param level the requested level of every logger of a set
     */
    public record Inputs(Optional<String> node, Optional<String> leaseId, List<String> loggers,
        Optional<String> level, OptionalInt durationMinutes, Optional<Boolean> forget,
        Optional<String> confirmationCode) {

        public Inputs {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(leaseId, "leaseId");
            loggers = List.copyOf(loggers);
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(durationMinutes, "durationMinutes");
            Objects.requireNonNull(forget, "forget");
            Objects.requireNonNull(confirmationCode, "confirmationCode");
        }

        /** The inputs of {@code set_temporary_log_level}. */
        public static Inputs set(String node, List<String> loggers, String level,
            OptionalInt durationMinutes, Optional<String> confirmationCode) {
            return new Inputs(Optional.of(node), Optional.empty(), loggers, Optional.of(level),
                durationMinutes, Optional.empty(), confirmationCode);
        }

        /** The inputs of {@code reset_temporary_log_level}. */
        public static Inputs reset(Optional<String> node, Optional<String> leaseId,
            Optional<Boolean> forget, Optional<String> confirmationCode) {
            return new Inputs(node, leaseId, List.of(), Optional.empty(), OptionalInt.empty(),
                forget, confirmationCode);
        }

        /** In the fixed order of D-22; {@code issuedCode} is the code a preview issues. */
        private Map<String, String> audited(Optional<String> issuedCode) {
            Map<String, String> inputs = new LinkedHashMap<>();
            node.ifPresent(value -> inputs.put("node", bounded(value)));
            leaseId.ifPresent(value -> inputs.put("leaseId", bounded(value)));
            if (!loggers.isEmpty()) {
                String names = loggers.stream()
                    .map(name -> name + level.map(value -> "=" + value).orElse(""))
                    .collect(Collectors.joining(","));
                inputs.put("loggers", names.length() <= MAX_AUDITED_LOGGERS ? names
                    : names.substring(0, MAX_AUDITED_LOGGERS));
            } else {
                level.ifPresent(value -> inputs.put("level", bounded(value)));
            }
            durationMinutes.ifPresent(value -> inputs.put("durationMinutes",
                Integer.toString(value)));
            forget.ifPresent(value -> inputs.put("forget", value.toString()));
            confirmationCode.ifPresent(code -> inputs.put(AuditRecord.CONFIRMATION_CODE, code));
            issuedCode.ifPresent(code -> inputs.put(AuditRecord.ISSUED_CONFIRMATION_CODE, code));
            return inputs;
        }
    }

    /** The records of one tool call. */
    public final class ToolCall {

        private final String tool;
        private final Inputs inputs;
        private final Optional<String> mcpClient;
        private final UUID auditId;
        private NodeId node;
        private Optional<String> account = Optional.empty();
        private Optional<String> inubitNode = Optional.empty();
        private Optional<Lease> lease;
        private boolean pending;
        private boolean finished;

        private ToolCall(String tool, Inputs inputs, Optional<String> mcpClient) {
            this.tool = tool;
            this.inputs = Objects.requireNonNull(inputs, "inputs");
            this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
            this.auditId = Objects.requireNonNull(ids.get(), "auditId");
        }

        /** The id of every record of this call. */
        public UUID auditId() {
            return auditId;
        }

        /** The resolved server (for a forget: the lease's) and its INUBIT account. */
        public void node(NodeId resolved, Optional<String> serverAccount) {
            this.node = Objects.requireNonNull(resolved, "resolved");
            this.account = Objects.requireNonNull(serverAccount, "serverAccount");
        }

        /** The INUBIT node that answered the own-node lookup. */
        public void inubitNode(String name) {
            this.inubitNode = Optional.of(name);
        }

        /** The lease of the call as it is now; empty: the node has no lease. */
        public void lease(Optional<Lease> current) {
            this.lease = Objects.requireNonNull(current, "current");
            current.ifPresent(known -> inubitNode = Optional.of(known.inubitNode()));
        }

        /**
         * Appends the {@code CHALLENGE_ISSUED} record of a preview.
         *
         * @throws ToolErrorException {@code INTERNAL} if the record could not be written; the
         *     code must not be issued then
         */
        public void challengeIssued(String issuedCode, String text) {
            requireFirst();
            finished = true;
            try {
                port.append(record(AuditRecord.Step.PREVIEW, AuditOutcome.CHALLENGE_ISSUED,
                    Optional.of(text), Optional.of(issuedCode)));
            } catch (RuntimeException e) {
                throw internal(AuditOutcome.CHALLENGE_ISSUED, e, "the preview was not issued");
            }
        }

        /**
         * Audits a refusal before {@link #pending()} as one {@code REFUSED} record.
         *
         * @return {@code error}, or {@code INTERNAL} naming it if the record could not be written
         */
        public ToolErrorException refused(AuditRecord.Step step, ToolError error) {
            requireFirst();
            finished = true;
            try {
                port.append(record(step, AuditOutcome.REFUSED,
                    Optional.of(error.code() + ": " + error.message()), Optional.empty()));
            } catch (RuntimeException e) {
                logFailure(AuditOutcome.REFUSED, e);
                return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "The audit record could not be written; the call was refused anyway ("
                        + error.code() + ": " + error.message() + ") and nothing was changed",
                    AUDIT_CAUSE, AUDIT_NEXT_STEP));
            }
            return new ToolErrorException(error);
        }

        /**
         * Appends the {@code PENDING} record, forced to disk before anything is sent.
         *
         * @throws ToolErrorException {@code INTERNAL} if the record could not be written; nothing
         *     may be sent then
         */
        public void pending() {
            requireFirst();
            try {
                port.append(record(AuditRecord.Step.EXECUTE, AuditOutcome.PENDING,
                    Optional.empty(), Optional.empty()));
            } catch (RuntimeException e) {
                throw internal(AuditOutcome.PENDING, e, "nothing was sent to INUBIT");
            }
            pending = true;
        }

        /**
         * Appends the final record after {@link #pending()}, with the same {@code auditId}.
         *
         * @param outcome {@code EXECUTED}, {@code FAILED} or {@code REFUSED}
         * @return a result warning naming the {@code auditId} if the record could not be written
         */
        public Optional<String> outcome(AuditOutcome outcome, String text) {
            if (!FINAL.contains(outcome)) {
                throw new IllegalArgumentException("Not a final outcome: " + outcome);
            }
            Objects.requireNonNull(text, "text");
            if (!pending || finished) {
                throw new IllegalStateException("No pending audit record to close");
            }
            finished = true;
            try {
                port.append(record(AuditRecord.Step.EXECUTE, outcome, Optional.of(text),
                    Optional.empty()));
                return Optional.empty();
            } catch (RuntimeException e) {
                logFailure(outcome, e);
                return Optional.of("The final audit record could not be written (only the pending"
                    + " record " + auditId + " exists); check the audit directory (auditDirectory"
                    + " in the configuration)");
            }
        }

        private void requireFirst() {
            if (pending || finished) {
                throw new IllegalStateException("This call already wrote its first audit record");
            }
        }

        private ToolErrorException internal(AuditOutcome outcome, RuntimeException e,
            String consequence) {
            logFailure(outcome, e);
            ToolError error = ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; " + consequence, AUDIT_CAUSE,
                AUDIT_NEXT_STEP);
            return new ToolErrorException(node == null ? error : error.withNode(node));
        }

        private void logFailure(AuditOutcome outcome, RuntimeException e) {
            LOG.error("Audit record {} of {} ({}) could not be written ({})", auditId, tool,
                outcome, e.getClass().getSimpleName());
        }

        private AuditRecord record(AuditRecord.Step step, AuditOutcome outcome,
            Optional<String> text, Optional<String> issuedCode) {
            return new AuditRecord(auditId, clock.instant(), profile, node(), group(), tool, step,
                inputs.audited(issuedCode), account, outcome, text, mcpClient, Optional.empty(),
                details());
        }

        private String node() {
            if (node != null) {
                return node.value();
            }
            return inputs.node().map(LogLevelAudit::bounded).orElse(NO_NODE);
        }

        private Optional<String> group() {
            if (node != null) {
                return Optional.of(node.group().value());
            }
            try {
                return inputs.node().map(requested -> NodeId.parse(requested).group().value());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        /** The details in their fixed order (D-22), each once known. */
        private Map<String, Object> details() {
            Map<String, Object> details = new LinkedHashMap<>();
            Optional<Lease> known = lease == null ? Optional.empty() : lease;
            known.ifPresent(current -> details.put("leaseId", current.leaseId().toString()));
            inubitNode.ifPresent(name -> details.put("inubitNode", bounded(name)));
            known.ifPresent(current -> details.put("expiresAt", current.expiresAt().toString()));
            if (lease != null) {
                details.put("leaseOpen", known.map(current -> current.state().open())
                    .orElse(false));
            }
            return details;
        }
    }

    /** The records of one automatic reset (or takeover, or forget after 7 days) of a lease. */
    public final class AutomaticReset {

        private final Trigger trigger;
        private final Lease lease;
        private final Optional<String> account;
        private final UUID auditId;
        private boolean pending;
        private boolean finished;

        private AutomaticReset(Trigger trigger, Lease lease, Optional<String> account) {
            this.trigger = Objects.requireNonNull(trigger, "trigger");
            this.lease = Objects.requireNonNull(lease, "lease");
            this.account = Objects.requireNonNull(account, "account");
            this.auditId = Objects.requireNonNull(ids.get(), "auditId");
        }

        /** The id of every record of this reset. */
        public UUID auditId() {
            return auditId;
        }

        /**
         * Appends the {@code PENDING} record before anything is sent.
         *
         * @throws NotWritableException if the record could not be written; nothing may be sent
         */
        public void pending() {
            requireReset();
            append(AuditOutcome.PENDING, Optional.empty(), "; nothing was sent");
            pending = true;
        }

        /**
         * Appends the one {@code REFUSED} record of a reset refused before {@link #pending()}
         * (cleanup precondition not met; nothing is sent).
         *
         * @throws NotWritableException if the record could not be written; the caller records
         *     reason {@code AUDIT_NOT_WRITABLE} instead (D-16 step 3) and catches it
         *     (a scheduler task must catch it: an exception escaping a
         *     {@code scheduleWithFixedDelay} task stops every later tick)
         */
        public void refused(String reason) {
            requireReset();
            finished = true;
            append(AuditOutcome.REFUSED, Optional.of(reason), "");
        }

        /**
         * Appends the final record after {@link #pending()}, with the same {@code auditId}.
         *
         * @param outcome {@code EXECUTED}, {@code FAILED} or {@code REFUSED} (another node answers)
         * @throws NotWritableException if the record could not be written; the reset was
         *     already sent and its outcome is decided, so the caller catches it, still persists
         *     the lease outcome (D-16 step 6) and does not retry the reset
         *     (a scheduler task must catch it: an exception escaping a
         *     {@code scheduleWithFixedDelay} task stops every later tick)
         */
        public void outcome(AuditOutcome outcome, String reason) {
            if (!FINAL.contains(outcome)) {
                throw new IllegalArgumentException("Not a final outcome: " + outcome);
            }
            if (!pending || finished) {
                throw new IllegalStateException("No pending audit record to close");
            }
            finished = true;
            append(outcome, Optional.of(reason), "");
        }

        /**
         * Appends the one {@code EXECUTED} record of a {@link Trigger#TAKEOVER} or
         * {@link Trigger#FORGET_AFTER_7_DAYS}, which send nothing.
         *
         * @throws NotWritableException if the record could not be written; the caller catches
         *     it
         *     (a scheduler task must catch it: an exception escaping a
         *     {@code scheduleWithFixedDelay} task stops every later tick)
         */
        public void changed(String text) {
            if (trigger.sendsReset || finished) {
                throw new IllegalStateException("Not a single record of " + trigger.wireName);
            }
            finished = true;
            append(AuditOutcome.EXECUTED, Optional.of(text), "");
        }

        private void requireReset() {
            if (!trigger.sendsReset || pending || finished) {
                throw new IllegalStateException("No reset record expected now for "
                    + trigger.wireName);
            }
        }

        private void append(AuditOutcome outcome, Optional<String> reason, String consequence) {
            Objects.requireNonNull(reason, "reason");
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("trigger", trigger.wireName);
            inputs.put("leaseId", lease.leaseId().toString());
            try {
                port.append(new AuditRecord(auditId, clock.instant(), profile,
                    lease.server().value(), Optional.of(lease.server().group().value()),
                    RESET_TOOL, AuditRecord.Step.EXECUTE, inputs, account, outcome, reason,
                    Optional.empty(), Optional.empty(), Map.of()));
            } catch (RuntimeException e) {
                LOG.error("Audit record {} ({}, trigger {}) of lease {} could not be written"
                    + " ({}){}", auditId, outcome, trigger.wireName, lease.leaseId(),
                    e.getClass().getSimpleName(), consequence);
                throw new NotWritableException(auditId);
            }
        }
    }

    /** An automatic record could not be written; only the failure's type is logged. */
    public static final class NotWritableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NotWritableException(UUID auditId) {
            super("The audit record " + auditId + " could not be written");
        }
    }

    private static String bounded(String value) {
        return value.length() <= MAX_AUDITED_INPUT ? value : value.substring(0, MAX_AUDITED_INPUT);
    }
}
