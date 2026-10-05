package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.InventoryService.ItemRequest;
import de.dadecker.inubit.mcp.application.InventoryService.Lookup;
import de.dadecker.inubit.mcp.application.InventoryService;
import de.dadecker.inubit.mcp.domain.model.InventoryDetail;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code get_inventory_item} (contracts/mcp-tools.md §6, US3): the details of one diagram or
 * module per resolved server in the same structure (FR-017). A name that does not exist gives
 * {@code error.code = NOT_FOUND} together with an {@code item} holding similar names; a server
 * that fails gets only its {@code error}.
 */
public final class GetInventoryItemTool implements ToolHandler {

    static final String DESCRIPTION = "Show details of one diagram or module: version history"
        + " (version, check-in user and time, comment, tags), active flag, modules used"
        + " (diagrams), last change. Call it with the id of one {group} to compare its {nodes}.";

    private final InventoryService inventory;

    public GetInventoryItemTool(InventoryService inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public String name() {
        return "get_inventory_item";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Show an INUBIT diagram or module", true);
    }

    /** The arguments are validated by the SDK against {@code get_inventory_item.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        ItemRequest request = new ItemRequest(
            args.string("target"),
            InventoryKind.valueOf(args.string("kind")),
            args.string("name"),
            args.flag("refresh"));
        return Result.of(inventory.item(request));
    }

    /** {@code {results: [{node, collectedAt?, item?, error?}]}}. */
    record Result(List<Entry> results) {

        record Entry(NodeId node, Optional<Instant> collectedAt,
            Optional<InventoryDetail> item, Optional<ToolError> error) {
        }

        static Result of(List<NodeResult<Lookup>> results) {
            return new Result(results.stream()
                .map(result -> new Entry(result.node(),
                    result.payload().map(Lookup::collectedAt),
                    result.payload().map(Lookup::item), result.error()))
                .toList());
        }
    }
}
