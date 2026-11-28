package de.dadecker.inubit.mcp.domain.model.management;

import de.dadecker.inubit.mcp.domain.model.management.Unreadable.Kind;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the management check knows about one server and owner (data-model.md →
 * ManagementIndex, research D-4).
 *
 * @param owner           the owner whose system diagrams were read
 * @param systemDiagrams  all system diagrams of the owner in diagram-list order; empty if the
 *                        diagram list failed
 * @param externalSystems the external systems of the readable system diagrams, in diagram order,
 *                        then element order
 * @param unreadable      the items that could not be read, in the order met; a
 *                        {@link Kind#DIAGRAM_LIST} item is the only item when present
 * @param elementNodeIds  per system diagram with external system elements, their node ids in
 *                        the order of the diagram's element list ({@code modelByName}); exactly
 *                        the node ids of that diagram's external systems and unreadable
 *                        {@link Kind#ELEMENT} items, each once
 * @param collectedAt     when the load finished
 */
public record ManagementIndex(String owner, List<DiagramRef> systemDiagrams,
    List<ExternalSystem> externalSystems, List<Unreadable> unreadable,
    Map<DiagramRef, List<String>> elementNodeIds, Instant collectedAt) {

    public ManagementIndex {
        Objects.requireNonNull(owner, "owner");
        systemDiagrams = List.copyOf(systemDiagrams);
        externalSystems = List.copyOf(externalSystems);
        unreadable = List.copyOf(unreadable);
        elementNodeIds = copyOrder(elementNodeIds);
        Objects.requireNonNull(collectedAt, "collectedAt");
        if (unreadable.stream().anyMatch(item -> item.kind() == Kind.DIAGRAM_LIST)) {
            if (unreadable.size() > 1) {
                throw new IllegalArgumentException(
                    "a DIAGRAM_LIST item is the only unreadable item");
            }
            if (!systemDiagrams.isEmpty() || !externalSystems.isEmpty()) {
                throw new IllegalArgumentException(
                    "a failed diagram list has no system diagrams and no external systems");
            }
        }
        requireOrderMatchesElements(elementNodeIds, externalSystems, unreadable);
    }

    private static Map<DiagramRef, List<String>> copyOrder(Map<DiagramRef, List<String>> order) {
        Map<DiagramRef, List<String>> copy = new HashMap<>();
        Objects.requireNonNull(order, "elementNodeIds").forEach((sd, ids) -> {
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("elementNodeIds has an empty list for " + sd);
            }
            copy.put(sd, List.copyOf(ids));
        });
        return Map.copyOf(copy);
    }

    private static void requireOrderMatchesElements(Map<DiagramRef, List<String>> order,
        List<ExternalSystem> externalSystems, List<Unreadable> unreadable) {
        Map<DiagramRef, Set<String>> elements = new HashMap<>();
        externalSystems.forEach(system ->
            addOnce(elements, system.systemDiagram(), system.nodeId()));
        unreadable.stream()
            .filter(item -> item.kind() == Kind.ELEMENT)
            .forEach(item -> addOnce(elements, item.systemDiagram().orElseThrow(),
                item.nodeId().orElseThrow()));
        Map<DiagramRef, Set<String>> ordered = new HashMap<>();
        order.forEach((sd, ids) -> ids.forEach(id -> addOnce(ordered, sd, id)));
        if (!ordered.equals(elements)) {
            throw new IllegalArgumentException("elementNodeIds must list exactly the node ids of "
                + "the external systems and unreadable elements of each system diagram");
        }
    }

    private static void addOnce(Map<DiagramRef, Set<String>> ids, DiagramRef sd, String id) {
        if (!ids.computeIfAbsent(sd, key -> new HashSet<>()).add(id)) {
            throw new IllegalArgumentException("node id " + id + " occurs twice in " + sd);
        }
    }

    /** True if every item was read. */
    public boolean complete() {
        return unreadable.isEmpty();
    }

    /** The external systems whose linked names contain {@code name} exactly, in index order. */
    public List<ExternalSystem> linking(String name) {
        return externalSystems.stream()
            .filter(system -> system.linkedNames().contains(name))
            .toList();
    }

    /**
     * The number of system diagrams not read completely: the unreadable system diagrams plus the
     * system diagrams with at least one unreadable element.
     */
    public int systemDiagramsNotRead() {
        long diagrams = unreadable.stream()
            .filter(item -> item.kind() == Kind.SYSTEM_DIAGRAM)
            .count();
        long withUnreadableElement = unreadable.stream()
            .filter(item -> item.kind() == Kind.ELEMENT)
            .map(item -> item.systemDiagram().orElseThrow())
            .distinct()
            .count();
        return (int) (diagrams + withUnreadableElement);
    }
}
