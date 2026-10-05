package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * A module used by a diagram (data-model.md → InventoryDetail {@code modules}): a {@code Node} of
 * {@code GET /model/modelByName/{name}}. A module that occurs several times in the diagram is
 * listed once per node.
 *
 * @param type   the node type, e.g. {@code twWorkflowConnector}
 * @param nodeId the node id inside the diagram
 */
public record ModuleRef(String name, String type, String nodeId) {

    public ModuleRef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(nodeId, "nodeId");
    }
}
