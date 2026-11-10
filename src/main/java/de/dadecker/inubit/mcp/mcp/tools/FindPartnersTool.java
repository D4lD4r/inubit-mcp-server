package de.dadecker.inubit.mcp.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.dadecker.inubit.mcp.application.PartnerAudit;
import de.dadecker.inubit.mcp.application.PartnerService;
import de.dadecker.inubit.mcp.application.PartnerService.CloudWorkflows;
import de.dadecker.inubit.mcp.application.PartnerService.EntryView;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.application.PartnerService.PartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.ServerView;
import de.dadecker.inubit.mcp.application.PartnerService.SettingView;
import de.dadecker.inubit.mcp.application.PartnerService.WorkflowView;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 */
public final class FindPartnersTool implements ToolHandler {

    static final String DESCRIPTION = "Find partners in the INUBIT Partner Management of the"
        + " owner's system diagrams on one {group} or {node}. With search: partners whose Id,"
        + " name or another non-secret partner field contains the term (case-insensitive);"
        + " without: all partners. Each partner is shown with its system diagram, communication"
        + " cloud, partner fields and, per protocol entry, the referenced technical workflow,"
        + " the protocol settings and the additional parameters. Passwords, keys and other"
        + " secrets are only shown as set or not set. Lists the system diagrams read and, if"
        + " nothing matches, similar partner Ids and names. Read-only; every call is audited.";

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
        } catch (ToolErrorException e) {
            throw new PartnerAudit(audits, target, compareWith, owner, search, systemDiagram,
                protocol, workflow, context.client()).refused(e.error());
        }
        return Result.of(service.find(query), query);
    }

    /**
     * The single-server shape of the contract. Until paging (T024) every matching partner is
     * returned: {@code total} is their number and nothing is cut.
     *
     * @param mode {@code search} with a search term, else {@code list}
     */
    record Result(UUID callId, String mode, boolean compared, List<ServerView> servers,
        List<CloudWorkflows> referencableWorkflows, List<Partner> partners,
        Optional<List<String>> similarValues, int total, int offset, int limit,
        boolean truncated) {

        static Result of(Found found, PartnerQuery query) {
            return new Result(found.callId(), query.search().isPresent() ? "search" : "list",
                false, found.servers(), found.referencableWorkflows(),
                found.partners().stream().map(Partner::of).toList(), found.similarValues(),
                found.partners().size(), query.offset(), query.limit(), false);
        }
    }

    /** {@link PartnerView} with the contract's workflow shape. */
    record Partner(DiagramRef systemDiagram, String cloud, Optional<String> moduleId,
        List<SettingView> fields, int maskedCount, List<Entry> entries) {

        static Partner of(PartnerView partner) {
            return new Partner(partner.systemDiagram(), partner.cloud(), partner.moduleId(),
                partner.fields(), partner.maskedCount(),
                partner.entries().stream().map(Entry::of).toList());
        }
    }

    /** {@link EntryView} with the contract's workflow shape. */
    record Entry(String protocol, Workflow workflow, Optional<String> format,
        List<SettingView> protocolSettings, List<SettingView> additionalParameters) {

        static Entry of(EntryView entry) {
            return new Entry(entry.protocol(), Workflow.of(entry.workflow()), entry.format(),
                entry.protocolSettings(), entry.additionalParameters());
        }
    }

    /**
     * {@code {"name": "…"}}, or {@code {"name": null, "unresolved": true}} for an entry whose
     * workflow reference does not resolve: {@code name} is written even when empty.
     */
    record Workflow(@JsonInclude(JsonInclude.Include.ALWAYS) Optional<String> name,
        Optional<Boolean> unresolved) {

        static Workflow of(WorkflowView workflow) {
            return new Workflow(workflow.name(), workflow.unresolved());
        }
    }
}
