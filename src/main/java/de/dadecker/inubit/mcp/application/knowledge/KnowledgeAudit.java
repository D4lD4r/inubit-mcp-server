package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit records of one {@code record_knowledge} call (feature 019, research D-13), modelled
 * on the audit of {@code export_archives}: one instance per call, one {@code auditId},
 * {@code step} {@code EXECUTE}, node {@value #NODE} (no INUBIT node is involved).
 *
 * <p>A refusal before the write starts is one {@code REFUSED} record ({@link #refused});
 * otherwise a {@code PENDING} record ({@link #pending}) precedes the lock and the write, and
 * {@link #outcome} appends the final record with the same {@code auditId}. No audit record, no
 * write: a refusal or a {@code PENDING} record that cannot be written becomes {@code INTERNAL}.
 * The inputs are the name (masked by the caller, one line, cut; {@code ***} until it has passed
 * the secret check), the mode and the sizes - never a title, a topic, the content, a version
 * token or a {@code ref}.
 */
public final class KnowledgeAudit {

    /** The tool name, the {@code capability} of every record. */
    public static final String CAPABILITY = "record_knowledge";
    /** The node of every record. */
    public static final String NODE = "-";
    /** The name before it has passed the secret check. */
    public static final String HIDDEN = "***";

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeAudit.class);
    private static final Set<AuditOutcome> FINAL = EnumSet.of(AuditOutcome.EXECUTED,
        AuditOutcome.REFUSED, AuditOutcome.FAILED);
    private static final String AUDIT_CAUSE =
        "The audit directory is not writable, full, or not owned by this user";
    private static final String AUDIT_NEXT_STEP =
        "Fix the audit directory (auditDirectory in the configuration), then retry";

    /**
     * What every call's audit shares.
     *
     * @param port    the append-only audit log
     * @param clock   the time of each record
     * @param profile the name of the profile
     * @param ids     the source of the call's {@code auditId} (drawn once per call)
     */
    public record Dependencies(AuditPort port, Clock clock, String profile, Supplier<UUID> ids) {

        public Dependencies {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(ids, "ids");
        }
    }

    private final Dependencies d;
    private final UUID auditId;
    private final String mode;
    private final Optional<String> mcpClient;
    private String name = HIDDEN;
    private OptionalLong sizeBefore = OptionalLong.empty();
    private OptionalLong sizeAfter = OptionalLong.empty();
    private boolean pending;
    private boolean finished;

    /**
     * Starts the audit of one call.
     *
     * @param mode      the requested mode
     * @param mcpClient the MCP client's {@code name/version}, if sent
     */
    public KnowledgeAudit(Dependencies dependencies, String mode, Optional<String> mcpClient) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
        this.auditId = Objects.requireNonNull(d.ids().get(), "auditId");
        this.mode = Shown.cut(Shown.line(Objects.requireNonNull(mode, "mode")));
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
    }

    /** The id of every record of this call. */
    public UUID auditId() {
        return auditId;
    }

    /** The note's name once it has passed the secret check, already masked by the caller. */
    public void name(String masked) {
        this.name = Shown.cut(Shown.line(Objects.requireNonNull(masked, "masked")));
    }

    /** The size of the note before the write, once read. */
    public void sizeBefore(long bytes) {
        this.sizeBefore = OptionalLong.of(bytes);
    }

    /** The sizes of the note before and after the write. */
    public void sizes(long before, long after) {
        this.sizeBefore = OptionalLong.of(before);
        this.sizeAfter = OptionalLong.of(after);
    }

    /**
     * Audits a refusal before the write started as one {@code REFUSED} record.
     *
     * @return {@code error}, or {@code INTERNAL} carrying its code and message if the record could
     *     not be written
     */
    public ToolErrorException refused(ToolError error) {
        if (pending) {
            throw new IllegalStateException("A refusal after the pending audit record");
        }
        try {
            d.port().append(record(AuditOutcome.REFUSED,
                Optional.of(error.code() + ": " + error.message())));
        } catch (RuntimeException e) {
            LOG.error("Audit record {} of a refusal ({}) could not be written ({})", auditId,
                error.code(), e.getClass().getSimpleName());
            return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; the call was refused anyway ("
                    + error.code() + ": " + error.message() + "); nothing was written",
                AUDIT_CAUSE, AUDIT_NEXT_STEP));
        }
        return new ToolErrorException(error);
    }

    /**
     * Appends the {@code PENDING} record before the lock and the write.
     *
     * @throws ToolErrorException {@code INTERNAL} if the record could not be written; nothing
     *     may be written then
     */
    public void pending() {
        if (pending) {
            throw new IllegalStateException("The pending audit record was already written");
        }
        try {
            d.port().append(record(AuditOutcome.PENDING, Optional.empty()));
        } catch (RuntimeException e) {
            LOG.error("Pending audit record {} could not be written ({})", auditId,
                e.getClass().getSimpleName());
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; nothing was written", AUDIT_CAUSE,
                AUDIT_NEXT_STEP));
        }
        pending = true;
    }

    /**
     * Appends the final record after {@link #pending()}, with the same {@code auditId}.
     *
     * @param outcome {@code EXECUTED}, {@code REFUSED} or {@code FAILED}
     * @param text    the outcome text (codes and messages, never content)
     * @return a warning naming the {@code auditId} if the record could not be written
     */
    public Optional<String> outcome(AuditOutcome outcome, String text) {
        if (!FINAL.contains(outcome)) {
            throw new IllegalArgumentException("Not a final outcome: " + outcome);
        }
        Objects.requireNonNull(text, "text");
        if (!pending) {
            throw new IllegalStateException("No pending audit record");
        }
        if (finished) {
            throw new IllegalStateException("The final audit record was already appended");
        }
        finished = true;
        try {
            d.port().append(record(outcome, Optional.of(text)));
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.error("The final audit record {} ({}) could not be written ({})", auditId,
                outcome, e.getClass().getSimpleName());
            return Optional.of("The final audit record could not be written (only the pending"
                + " record " + auditId + " exists); check the audit directory (auditDirectory"
                + " in the configuration)");
        }
    }

    private AuditRecord record(AuditOutcome outcome, Optional<String> text) {
        return new AuditRecord(auditId, d.clock().instant(), d.profile(), NODE, Optional.empty(),
            CAPABILITY, AuditRecord.Step.EXECUTE, inputs(), Optional.empty(), outcome, text,
            mcpClient);
    }

    /** The inputs in their fixed order (D-13). */
    private Map<String, String> inputs() {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("name", name);
        inputs.put("mode", mode);
        sizeBefore.ifPresent(bytes -> inputs.put("sizeBefore", Long.toString(bytes)));
        sizeAfter.ifPresent(bytes -> inputs.put("sizeAfter", Long.toString(bytes)));
        return inputs;
    }
}
