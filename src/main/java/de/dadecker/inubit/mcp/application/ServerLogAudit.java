package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogBounds;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit records of one {@code download_server_log} call (feature 021, FR-015, research
 * D-18), built like {@link ArchiveExportAudit}: one instance per call, one {@code auditId},
 * {@code step} {@code EXECUTE}.
 *
 * <p>A refusal before the {@code PENDING} record (input, target, opt-in, folder rule) is one
 * {@code REFUSED} record ({@link #refused}); otherwise a {@code PENDING} record ({@link #pending})
 * precedes the version gate and the download, and {@link #outcome} appends the final record
 * ({@code EXECUTED}, {@code FAILED}, or {@code REFUSED} by the version gate) with the same
 * {@code auditId}. No audit record, no download: a refusal or a {@code PENDING} record that cannot
 * be written becomes {@code INTERNAL}. The inputs record only whether {@code contains} was given,
 * never its text, and no record carries log content; each text value is cut to
 * {@value #MAX_AUDITED_INPUT} chars.
 */
public final class ServerLogAudit {

    /** The tool name, the {@code capability} of every record. */
    public static final String CAPABILITY = "download_server_log";

    private static final Logger LOG = LoggerFactory.getLogger(ServerLogAudit.class);
    private static final int MAX_AUDITED_INPUT = 500;
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
    private final String log;
    private final String targetFolder;
    private final int tailLines;
    private final int maxChars;
    private final boolean containsGiven;
    private final Optional<String> mcpClient;
    private NodeId node;
    private Optional<String> account = Optional.empty();
    private Optional<Path> file = Optional.empty();
    private OptionalLong sizeBytes = OptionalLong.empty();
    private Optional<String> inubitNode = Optional.empty();
    private boolean pending;
    private boolean finished;

    /**
     * Starts the audit of one call with its inputs as requested.
     *
     * @param target        the server id as requested (also when unknown, a group or malformed)
     * @param log           the requested {@code log} (also when invalid)
     * @param targetFolder  the requested folder
     * @param tailLines     the requested {@code tailLines}; absent: the default
     * @param maxChars      the requested {@code maxChars}; absent: the default
     * @param containsGiven whether a {@code contains} text was given (the text is never audited)
     * @param mcpClient     the MCP client's {@code name/version}, if sent
     */
    public ServerLogAudit(Dependencies dependencies, String target, String log,
        String targetFolder, OptionalInt tailLines, OptionalInt maxChars, boolean containsGiven,
        Optional<String> mcpClient) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
        this.auditId = Objects.requireNonNull(d.ids().get(), "auditId");
        this.target = Objects.requireNonNull(target, "target");
        this.log = Objects.requireNonNull(log, "log");
        this.targetFolder = Objects.requireNonNull(targetFolder, "targetFolder");
        this.tailLines = tailLines.orElse(ServerLogBounds.DEFAULT_TAIL_LINES);
        this.maxChars = maxChars.orElse(ServerLogBounds.DEFAULT_MAX_CHARS);
        this.containsGiven = containsGiven;
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
    }

    /** The id of every record of this call. */
    public UUID auditId() {
        return auditId;
    }

    /** The resolved server and its INUBIT account. */
    public void node(NodeId resolved, Optional<String> serverAccount) {
        this.node = Objects.requireNonNull(resolved, "resolved");
        this.account = Objects.requireNonNull(serverAccount, "serverAccount");
    }

    /**
     * The downloaded log once fetched: its size and, for the web service log, the INUBIT cluster
     * node that served it.
     */
    public void downloaded(long size, Optional<String> servingNode) {
        this.sizeBytes = OptionalLong.of(size);
        this.inubitNode = Objects.requireNonNull(servingNode, "servingNode");
    }

    /** The file written in {@code targetFolder} once placed. */
    public void file(Path written) {
        this.file = Optional.of(written);
    }

    /**
     * Audits a refusal before {@link #pending()} (input, target, opt-in, folder rule) as one
     * {@code REFUSED} record.
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
                    + "); nothing was downloaded or written", AUDIT_CAUSE, AUDIT_NEXT_STEP));
        }
        return new ToolErrorException(error);
    }

    /**
     * Appends the {@code PENDING} record, forced to disk before the version gate and the download.
     *
     * @throws ToolErrorException {@code INTERNAL} if the record could not be written; nothing
     *     may be downloaded then
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
                "The audit record could not be written; nothing was downloaded", AUDIT_CAUSE,
                AUDIT_NEXT_STEP);
            throw new ToolErrorException(node == null ? error : error.withNode(node));
        }
        pending = true;
    }

    /**
     * Appends the final record after {@link #pending()}, with the same {@code auditId}.
     *
     * @param outcome {@code EXECUTED}, {@code FAILED}, or {@code REFUSED} (version gate)
     * @param text    the outcome text (error code and message, never log content)
     * @return a result warning naming the {@code auditId} if the record could not be written
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
            AuditRecord.Step.EXECUTE, inputs(), account, outcome, text, mcpClient,
            Optional.empty(), details());
    }

    /** The inputs in their fixed order (D-18). */
    private Map<String, String> inputs() {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("log", bounded(log));
        inputs.put("targetFolder", bounded(targetFolder));
        inputs.put("tailLines", Integer.toString(tailLines));
        inputs.put("maxChars", Integer.toString(maxChars));
        inputs.put("containsGiven", Boolean.toString(containsGiven));
        return inputs;
    }

    /** The details in their fixed order (D-18), each once known. */
    private Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        file.ifPresent(path -> details.put("file", bounded(path.toString())));
        sizeBytes.ifPresent(size -> details.put("sizeBytes", size));
        inubitNode.ifPresent(name -> details.put("inubitNode", bounded(name)));
        return details;
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
