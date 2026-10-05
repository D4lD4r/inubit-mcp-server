package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which technical workflows of a server's owner use which module (T126, finding F1; research
 * R-11): built by {@link ModuleUsageIndexer} from the module nodes of every technical workflow
 * ({@code GET /model/modelByName}). A node names the module it runs (on the recorded
 * development node all 4,730 nodes of 392 workflows matched a module of the owner by name, and
 * module names were unique per owner).
 *
 * <ul>
 *   <li>{@link #workflowsOf}: the workflows whose nodes name the module, joined with the
 *       connector's own {@code WorkflowName} from the module export; sorted by name
 *       (case-insensitive, then exact), without duplicates.
 *   <li>A module name shared by several modules of the owner (not seen on the recorded node,
 *       but INUBIT does not forbid it) is resolved by the node type:
 *       {@code tw<PluginName without spaces>}, e.g. {@code twXSLTConverter} for
 *       {@code XSLT Converter}; only nodes of the module's plugin type count then.
 *   <li>{@link #complete()} is false if a workflow could not be read (or not within the
 *       budget), or the diagram list itself failed ({@link #unavailable}); {@link #failure}
 *       then says why. An empty usage means "not used" only if the index is complete.
 * </ul>
 *
 * @param uses           module name → workflow → the node types under which it occurs there
 * @param workflowsRead  technical workflows whose nodes were read
 * @param workflowsTotal technical workflows of the owner
 */
public record ModuleUsage(Map<String, Map<String, Set<String>>> uses, int workflowsRead,
    int workflowsTotal, Optional<ToolError> failure) {

    /** Workflow names in a stable order: case-insensitive, then exact. */
    static final Comparator<String> ORDER = String.CASE_INSENSITIVE_ORDER
        .thenComparing(Comparator.naturalOrder());

    public ModuleUsage {
        uses = Map.copyOf(uses);
        failure = failure == null ? Optional.empty() : failure;
    }

    /** No index at all (e.g. the diagram list failed): only connector workflows are known. */
    public static ModuleUsage unavailable(ToolError failure) {
        return new ModuleUsage(Map.of(), 0, 0, Optional.of(failure));
    }

    /** True if every technical workflow was read. */
    public boolean complete() {
        return failure.isEmpty() && workflowsRead == workflowsTotal;
    }

    /**
     * The workflows that use {@code module}, sorted.
     *
     * @param nameShared true if another module of the owner has the same name; then only nodes
     *     of the module's plugin type count
     */
    public List<String> workflowsOf(ModuleEntry module, boolean nameShared) {
        Set<String> workflows = new TreeSet<>(ORDER);
        String plugin = normalized(module.item().type());
        uses.getOrDefault(module.item().name(), Map.of()).forEach((workflow, nodeTypes) -> {
            if (!nameShared || nodeTypes.stream().anyMatch(type ->
                normalized(type.startsWith("tw") ? type.substring(2) : type).equals(plugin))) {
                workflows.add(workflow);
            }
        });
        module.connectorWorkflow().ifPresent(workflows::add);
        return List.copyOf(new ArrayList<>(workflows));
    }

    private static String normalized(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
