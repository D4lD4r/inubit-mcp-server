package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit records of one {@code export_archives} call (feature 015, research R-12, R-13): one
 * instance per call, one {@code auditId}, {@code step} {@code EXECUTE}.
 *
 * <p>A static refusal is one {@code REFUSED} record ({@link #refused}); otherwise a
 * {@code PENDING} record ({@link #pending}) precedes the version gate and the export, and
 * {@link #outcome} appends the final record with the same {@code auditId}. No audit record, no
 * export: a refusal or a {@code PENDING} record that cannot be written becomes {@code INTERNAL}.
 * The inputs are names and flags only, never content or secret values; each value is cut to
 * {@value #MAX_AUDITED_INPUT} chars.
 */
public final class ArchiveExportAudit {

    /** The tool name, the {@code capability} of every record. */
    public static final String CAPABILITY = "export_archives";

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveExportAudit.class);
    private static final int MAX_AUDITED_INPUT = 500;
    private static final Set<AuditOutcome> FINAL = EnumSet.of(AuditOutcome.EXECUTED,
        AuditOutcome.REFUSED, AuditOutcome.FAILED, AuditOutcome.PARTIALLY_COMPLETED);
    private static final String AUDIT_CAUSE =
        "The audit directory is not writable, full, or not owned by this user";
    private static final String AUDIT_NEXT_STEP =
        "Fix the audit directory (auditDirectory in the configuration), then retry";

    /**
     * What every call's audit shares.
     *
     * @param port    the append-only audit log
     * @param clock   the time of each record
     * @param profile the name of the profile this MCP server serves
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
    private final String target;
    private final String diagramGroup;
    private final String targetFolder;
    private final boolean acknowledgeClearTextSecrets;
    private final Optional<String> reason;
    private final Optional<String> mcpClient;
    private Optional<String> owner;
    private List<String> workflows;
    private List<String> files = List.of();
    private NodeId node;
    private Optional<String> account = Optional.empty();
    private boolean pending;
    private boolean finished;

    /**
     * Starts the audit of one call with its inputs as requested.
     *
     * @param target    the group or node id as requested (also when unknown or malformed)
     * @param owner     the requested owner, if any ({@link #owner(String)} sets the default)
     * @param workflows the requested workflows; empty for all ({@link #workflows(List)} sets
     *                  the selected ones)
     * @param mcpClient the MCP client's {@code name/version}, if sent
     */
    public ArchiveExportAudit(Dependencies dependencies, String target, Optional<String> owner,
        String diagramGroup, String targetFolder, List<String> workflows,
        boolean acknowledgeClearTextSecrets, Optional<String> reason,
        Optional<String> mcpClient) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
        this.auditId = Objects.requireNonNull(d.ids().get(), "auditId");
        this.target = Objects.requireNonNull(target, "target");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.diagramGroup = Objects.requireNonNull(diagramGroup, "diagramGroup");
        this.targetFolder = Objects.requireNonNull(targetFolder, "targetFolder");
        this.workflows = List.copyOf(workflows);
        this.acknowledgeClearTextSecrets = acknowledgeClearTextSecrets;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
    }

    /** The id of every record of this call. */
    public UUID auditId() {
        return auditId;
    }

    /** The resolved node (a group target: its first node) and the server's INUBIT account. */
    public void node(NodeId resolved, Optional<String> serverAccount) {
        this.node = Objects.requireNonNull(resolved, "resolved");
        this.account = Objects.requireNonNull(serverAccount, "serverAccount");
    }

    /** The owner in effect (the requested one or the node's {@code inventory.owner}). */
    public void owner(String effective) {
        this.owner = Optional.of(effective);
    }

    /** The workflows of the call once selected (without {@code workflows}: all of the group). */
    public void workflows(List<String> selected) {
        this.workflows = List.copyOf(selected);
    }

    /** The archive file names (names only) once known. */
    public void files(List<String> names) {
        this.files = List.copyOf(names);
    }

    /**
     * Audits a static refusal (R-13 steps 1–2) as one {@code REFUSED} record.
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
                    + error.code() + ": " + error.message()
                    + "); nothing was exported or written", AUDIT_CAUSE, AUDIT_NEXT_STEP));
        }
        return new ToolErrorException(error);
    }

    /**
     * Appends the {@code PENDING} record, forced to disk before the version gate and the export.
     *
     * @throws ToolErrorException {@code INTERNAL} if the record could not be written; nothing
     *     may be exported then
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
            ToolError error = ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; nothing was exported", AUDIT_CAUSE,
                AUDIT_NEXT_STEP);
            throw new ToolErrorException(node == null ? error : error.withNode(node));
        }
        pending = true;
    }

    /**
     * Appends the final record after {@link #pending()}, with the same {@code auditId}.
     *
     * @param outcome {@code EXECUTED}, {@code REFUSED}, {@code FAILED} or
     *                {@code PARTIALLY_COMPLETED}
     * @param text    the outcome text (counts and codes, never content)
     * @return a call-level warning naming the {@code auditId} if the record could not be written
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
        return new AuditRecord(auditId, d.clock().instant(), d.profile(),
            node != null ? node.value() : bounded(target), group(), CAPABILITY,
            AuditRecord.Step.EXECUTE, inputs(), account, outcome, text, mcpClient);
    }

    /** The inputs in their fixed order (R-12). */
    private Map<String, String> inputs() {
        Map<String, String> inputs = new LinkedHashMap<>();
        owner.ifPresent(value -> inputs.put("owner", bounded(value)));
        inputs.put("diagramGroup", bounded(diagramGroup));
        inputs.put("targetFolder", bounded(targetFolder));
        for (int i = 0; i < workflows.size(); i++) {
            inputs.put("workflow." + (i + 1), bounded(workflows.get(i)));
        }
        for (int i = 0; i < files.size(); i++) {
            inputs.put("file." + (i + 1), bounded(files.get(i)));
        }
        inputs.put("acknowledgeClearTextSecrets", Boolean.toString(acknowledgeClearTextSecrets));
        reason.ifPresent(value -> inputs.put("reason", bounded(value)));
        return inputs;
    }

    private Optional<String> group() {
        if (node != null) {
            return Optional.of(node.group().value());
        }
        try {
            return Optional.of(switch (Target.parse(target)) {
                case Target.Group group -> group.id().value();
                case Target.Node requested -> requested.id().group().value();
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
