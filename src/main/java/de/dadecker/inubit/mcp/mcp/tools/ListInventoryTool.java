package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.InventoryService.ListRequest;
import de.dadecker.inubit.mcp.application.InventoryService.Listing;
import de.dadecker.inubit.mcp.application.InventoryService;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code list_inventory} (contracts/mcp-tools.md §5, US3): one page of diagrams (REST) or modules
 * (CLI module export) per resolved server, sorted by name, with {@code collectedAt} of the cached
 * data; module lists also state which workflows use each module and whether that usage is
 * complete ({@code usageComplete}, T126); a server that fails gets only its {@code error}.
 */
public final class ListInventoryTool implements ToolHandler {

    static final String DESCRIPTION = "List diagrams (technical workflows, BPDs, process maps, …)"
        + " or modules on one INUBIT {node} or on all {nodes} of one {group}, filtered by name,"
        + " type, or INUBIT diagram/module group.";

    private final InventoryService inventory;

    public ListInventoryTool(InventoryService inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public String name() {
        return "list_inventory";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("List INUBIT diagrams and modules", true);
    }

    /** The arguments are validated by the SDK against {@code list_inventory.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        ListRequest request = new ListRequest(
            args.string("target"),
            InventoryKind.valueOf(args.string("kind")),
            args.optionalString("nameContains"),
            args.optionalString("type"),
            args.optionalString("group"),
            args.integer("offset", 0),
            args.integer("limit", 50),
            args.flag("refresh"));
        return Result.of(inventory.list(request));
    }

    /** {@code {results: [{node, collectedAt?, usageComplete?, page?, error?}]}}. */
    record Result(List<Entry> results) {

        record Entry(NodeId node, Optional<Instant> collectedAt,
            Optional<Boolean> usageComplete, Optional<Page<InventoryItem>> page,
            Optional<ToolError> error) {
        }

        static Result of(List<NodeResult<Listing>> results) {
            return new Result(results.stream()
                .map(result -> new Entry(result.node(),
                    result.payload().map(Listing::collectedAt),
                    result.payload().flatMap(Listing::usageComplete),
                    result.payload().map(Listing::page), result.error()))
                .toList());
        }
    }
}
