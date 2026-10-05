package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of {@code list_inventory} (data-model.md → InventoryItem).
 *
 * <ul>
 *   <li>Diagrams (REST {@code /model/models?user=<owner>}): {@code type} is the diagram type
 *       ({@code technical}, {@code bpd}, …), {@code group} the diagram group; {@code active},
 *       {@code lastChange}, {@code workflows} and {@code workflowCount} are absent (details
 *       only).
 *   <li>Modules (CLI module index): {@code type} is the plugin type ({@code PluginName}, e.g.
 *       {@code XSLT Converter}), {@code group} the module group ({@code ModuleGroupName}, which
 *       INUBIT names after the plugin type); {@code active} = {@code IsActive},
 *       {@code lastChange} = {@code LastUpdate} (the last content change, not the last check-in,
 *       S-6b).
 *   <li>Module usage (T126, finding F1): {@code workflows} names technical workflows of the
 *       owner that use the module (their nodes name it, or the module is a connector bound to
 *       the workflow), sorted by name, in a list at most the first few; {@code workflowCount}
 *       counts all of them. An empty list means "unused" only when the server's usage index is
 *       complete ({@code usageComplete}). Both are absent until the application layer has added
 *       the usage ({@link #withUsage}).
 * </ul>
 *
 * @param owner the configured owning Workbench user or group ({@code inventory.owner}, FR-016a)
 */
public record InventoryItem(NodeId node, InventoryKind kind, String name, String type,
    String group, String owner, Optional<Boolean> active, Optional<Instant> lastChange,
    Optional<List<String>> workflows, Optional<Integer> workflowCount) {

    public InventoryItem {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(owner, "owner");
        active = active == null ? Optional.empty() : active;
        lastChange = lastChange == null ? Optional.empty() : lastChange;
        workflows = workflows == null ? Optional.empty() : workflows.map(List::copyOf);
        workflowCount = workflowCount == null ? Optional.empty() : workflowCount;
    }

    /** A diagram entry of the diagram list. */
    public static InventoryItem diagram(NodeId node, String name, String type, String group,
        String owner) {
        return new InventoryItem(node, InventoryKind.DIAGRAM, name, type, group, owner,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A module entry of the module index, without usage. */
    public static InventoryItem module(NodeId node, String name, String type, String group,
        String owner, Optional<Boolean> active, Optional<Instant> lastChange) {
        return new InventoryItem(node, InventoryKind.MODULE, name, type, group, owner, active,
            lastChange, Optional.empty(), Optional.empty());
    }

    /**
     * This item with its usage: the first {@code listed} of {@code allWorkflows} and their
     * count.
     */
    public InventoryItem withUsage(List<String> allWorkflows, int listed) {
        return new InventoryItem(node, kind, name, type, group, owner, active, lastChange,
            Optional.of(allWorkflows.subList(0, Math.min(listed, allWorkflows.size()))),
            Optional.of(allWorkflows.size()));
    }
}
