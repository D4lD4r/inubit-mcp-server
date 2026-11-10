package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.PartnerAudit;
import de.dadecker.inubit.mcp.application.PartnerService;
import de.dadecker.inubit.mcp.application.PartnerService.CloudWorkflows;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.application.PartnerService.PartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.ServerView;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * {@code find_partners} (feature 018, contracts/find_partners.md): the partners of the INUBIT
 * Partner Management in the owner's system diagrams of one stage or server ({@link
 * PartnerService}). Read-only for INUBIT; secrets only as "set"/"not set".
 *
 * <p>Applies the input defaults ({@code offset} 0, {@code limit}
 * {@value PartnerQuery#DEFAULT_LIMIT}, {@code refresh} false). An input the schema lets through
 * but {@link PartnerQuery} refuses (e.g. a {@code compareWith} that overlaps {@code target}) is
 * {@code INVALID_INPUT} with one {@code REFUSED} audit record whose node is the target as
 * requested; every other audit is written by the service.
 *
 * <p>{@code compareWith} is in the schema but not implemented yet: a call with it is refused the
 * same way before any server is contacted (phase 3 review; T026 lifts this refusal).
 */
public final class FindPartnersTool implements ToolHandler {

    static final String DESCRIPTION = "Find partners in the INUBIT Partner Management of the"
        + " owner's system diagrams on one {group} or {node}. With search: partners whose Id,"
        + " name or another non-secret partner field contains the term (case-insensitive), each"
        + " with its system diagram, communication cloud, partner fields and, per protocol"
        + " entry, the referenced technical workflow, the protocol settings and the additional"
        + " parameters. Without search: every partner as a summary (per entry only protocol,"
        + " workflow, ServerName and Login), ordered by system diagram, communication cloud, Id"
        + " and name. systemDiagram, protocol and workflow narrow both; with protocol or"
        + " workflow only the matching entries are shown. Passwords, keys and other secrets are"
        + " only shown as set or not set. Lists the system diagrams read and, if a search"
        + " matches nothing, similar partner Ids and names. Read-only; every call is audited.";

    private final PartnerService service;
    private final PartnerAudit.Dependencies audits;

    /**
     * @param service the partner reads of the profile
     * @param audits  the audit of a call whose input is refused before the service is called
     */
    public FindPartnersTool(PartnerService service, PartnerAudit.Dependencies audits) {
        this.service = Objects.requireNonNull(service, "service");
        this.audits = Objects.requireNonNull(audits, "audits");
    }

    @Override
    public String name() {
        return "find_partners";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Find partners in the INUBIT Partner Management", true, true);
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    /** The arguments are validated by the SDK against {@code find_partners.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        String target = args.optionalString("target").orElse(null);
        Optional<String> compareWith = args.optionalString("compareWith");
        Optional<String> owner = args.optionalString("owner");
        Optional<String> search = args.optionalString("search");
        Optional<String> systemDiagram = args.optionalString("systemDiagram");
        Optional<String> protocol = args.optionalString("protocol");
        Optional<String> workflow = args.optionalString("workflow");
        PartnerQuery query;
        try {
            query = new PartnerQuery(target, compareWith, owner, search, systemDiagram, protocol,
                workflow, args.integer("offset", 0),
                args.integer("limit", PartnerQuery.DEFAULT_LIMIT), args.flag("refresh"),
                context.client());
            refuseUnsupported(query);
        } catch (ToolErrorException e) {
            throw new PartnerAudit(audits, target, compareWith, owner, search, systemDiagram,
                protocol, workflow, context.client()).refused(e.error()).exception();
        }
        return Result.of(service.find(query), query);
    }

    /** @throws ToolErrorException {@code INVALID_INPUT} if {@code compareWith} is given */
    private static void refuseUnsupported(PartnerQuery query) {
        if (query.compareWith().isPresent()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "compareWith is not supported yet",
                "This version of find_partners does not implement compareWith yet",
                "Call again without compareWith"));
        }
    }

    /**
     * The single-server shape of the contract: the page of the service with {@code total} and
     * {@code nextOffset}. Until the size bound (T024) nothing is cut, so {@code truncated} is
     * false.
     *
     * @param mode {@code search} with a search term, else {@code list}
     */
    record Result(UUID callId, String mode, boolean compared, List<ServerView> servers,
        List<CloudWorkflows> referencableWorkflows, List<PartnerView> partners,
        Optional<List<String>> similarValues, int total, int offset, int limit,
        OptionalInt nextOffset, boolean truncated) {

        static Result of(Found found, PartnerQuery query) {
            return new Result(found.callId(), query.search().isPresent() ? "search" : "list",
                false, found.servers(), found.referencableWorkflows(), found.partners(),
                found.similarValues(), found.total(), query.offset(), query.limit(),
                found.nextOffset(), false);
        }
    }
}
