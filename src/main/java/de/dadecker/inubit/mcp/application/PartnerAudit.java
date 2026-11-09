package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit records of one {@code find_partners} call (feature 018, FR-020, research D-8): one
 * instance per call, one {@code callId} shared by all its records, each record with its own
 * {@code auditId}, {@code step} {@code EXECUTE}.
 *
 * <p>A refusal before any server is contacted is one {@code REFUSED} record ({@link #refused});
 * otherwise, after the fan-out, {@link #perServer} writes one record per targeted or compared
 * server with its counts in {@code details}. No audit record, no partner data: any record that
 * cannot be written becomes {@code INTERNAL} naming the {@code callId}; records already written
 * stay (append-only).
 *
 * <p>A refusal names no server: like {@link ArchiveExportAudit#refused}, its {@code node} is the
 * {@code target} as requested (a stage id, a server id, or malformed text, cut to
 * {@value #MAX_AUDITED_INPUT} chars; a missing target is the empty text and no {@code target}
 * input), and {@code group} is set only if the target parses. This keeps
 * {@link AuditRecord#node()} required and existing audit readers unchanged.
 *
 * <p>The inputs are the request only (target, compareWith, owner, search, systemDiagram,
 * protocol, workflow), each cut to {@value #MAX_AUDITED_INPUT} chars; never results, partner
 * settings or secrets. The {@code AuditLog} scrubs every value as for every tool.
 */
public final class PartnerAudit {

    /** The tool name, the {@code capability} of every record. */
    public static final String CAPABILITY = "find_partners";

    private static final Logger LOG = LoggerFactory.getLogger(PartnerAudit.class);
    private static final int MAX_AUDITED_INPUT = 500;
    private static final Set<AuditOutcome> PER_SERVER =
        EnumSet.of(AuditOutcome.EXECUTED, AuditOutcome.REFUSED, AuditOutcome.FAILED);
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
     * @param ids     the source of the {@code callId} (drawn once per call) and of each record's
     *                {@code auditId}
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
    private final UUID callId;
    private final String target;
    private final Map<String, String> inputs;
    private final Optional<String> mcpClient;

    /**
     * Starts the audit of one call with its inputs as requested (also when they are invalid).
     *
     * @param target    the stage or server id as requested (also when unknown, malformed,
     *                  blank, or {@code null} when missing)
     * @param mcpClient the MCP client's {@code name/version}, if sent
     */
    public PartnerAudit(Dependencies dependencies, String target, Optional<String> compareWith,
        Optional<String> owner, Optional<String> search, Optional<String> systemDiagram,
        Optional<String> protocol, Optional<String> workflow, Optional<String> mcpClient) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
        this.callId = Objects.requireNonNull(d.ids().get(), "callId");
        this.target = target == null ? "" : target;
        Map<String, String> requested = new LinkedHashMap<>();
        if (target != null) {
            requested.put("target", bounded(target));
        }
        compareWith.ifPresent(value -> requested.put("compareWith", bounded(value)));
        owner.ifPresent(value -> requested.put("owner", bounded(value)));
        search.ifPresent(value -> requested.put("search", bounded(value)));
        systemDiagram.ifPresent(value -> requested.put("systemDiagram", bounded(value)));
        protocol.ifPresent(value -> requested.put("protocol", bounded(value)));
        workflow.ifPresent(value -> requested.put("workflow", bounded(value)));
        this.inputs = requested;
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
    }

    /** Starts the audit of a call with a validated query. */
    public static PartnerAudit of(Dependencies dependencies, PartnerQuery query) {
        return new PartnerAudit(dependencies, query.target(), query.compareWith(), query.owner(),
            query.search(), query.systemDiagram(), query.protocol(), query.workflow(),
            query.mcpClient());
    }

    /** The id shared by every record of this call. */
    public UUID callId() {
        return callId;
    }

    /**
     * Audits a refusal before any server is contacted as one {@code REFUSED} record.
     *
     * @return {@code error}, or {@code INTERNAL} naming the {@code callId} and carrying the
     *     refusal's code and message if the record could not be written
     */
    public ToolErrorException refused(ToolError error) {
        try {
            d.port().append(record(bounded(target), requestedGroup(), AuditOutcome.REFUSED,
                Optional.of(error.code() + ": " + error.message()), Map.of()));
        } catch (RuntimeException e) {
            LOG.error("Audit record of call {} for a refusal ({}) could not be written ({})",
                callId, error.code(), e.getClass().getSimpleName());
            return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record of call " + callId + " could not be written; the call was"
                    + " refused anyway (" + error.code() + ": " + error.message()
                    + "); no partner data was read", AUDIT_CAUSE, AUDIT_NEXT_STEP));
        }
        return new ToolErrorException(error);
    }

    /**
     * Appends the record of one targeted or compared server.
     *
     * @param outcome {@code EXECUTED} (data returned, also from the cache), {@code REFUSED} or
     *                {@code FAILED}
     * @throws ToolErrorException {@code INTERNAL} naming the {@code callId} if the record could
     *     not be written; no partner data may be returned for any server then
     */
    public void perServer(NodeId node, AuditOutcome outcome, boolean fromCache,
        int systemDiagramsRead, int partnersReturned, int valuesMasked) {
        Objects.requireNonNull(node, "node");
        if (!PER_SERVER.contains(outcome)) {
            throw new IllegalArgumentException("Not a per-server outcome: " + outcome);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fromCache", fromCache);
        details.put("systemDiagramsRead", systemDiagramsRead);
        details.put("partnersReturned", partnersReturned);
        details.put("valuesMasked", valuesMasked);
        try {
            d.port().append(record(node.value(), Optional.of(node.group().value()), outcome,
                Optional.empty(), details));
        } catch (RuntimeException e) {
            LOG.error("Audit record of call {} for {} ({}) could not be written ({})", callId,
                node.value(), outcome, e.getClass().getSimpleName());
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record of call " + callId + " could not be written; no partner data"
                    + " is returned", AUDIT_CAUSE, AUDIT_NEXT_STEP));
        }
    }

    private AuditRecord record(String node, Optional<String> group, AuditOutcome outcome,
        Optional<String> reason, Map<String, Object> details) {
        return new AuditRecord(d.ids().get(), d.clock().instant(), d.profile(), node, group,
            CAPABILITY, AuditRecord.Step.EXECUTE, inputs, Optional.empty(), outcome, reason,
            mcpClient, Optional.of(callId), details);
    }

    private Optional<String> requestedGroup() {
        try {
            return Optional.of(switch (Target.parse(target)) {
                case Target.Group group -> group.id().value();
                case Target.Node requested -> requested.id().group().value();
            });
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** {@code value} cut to {@value #MAX_AUDITED_INPUT} chars, never inside a surrogate pair. */
    private static String bounded(String value) {
        if (value.length() <= MAX_AUDITED_INPUT) {
            return value;
        }
        int end = Character.isLowSurrogate(value.charAt(MAX_AUDITED_INPUT))
            && Character.isHighSurrogate(value.charAt(MAX_AUDITED_INPUT - 1))
            ? MAX_AUDITED_INPUT - 1 : MAX_AUDITED_INPUT;
        return value.substring(0, end);
    }
}
