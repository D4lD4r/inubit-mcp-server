package de.dadecker.inubit.mcp.domain.model.node;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.List;
import java.util.Objects;

/**
 * The result of {@code get_node_config} (feature 024, research D-10; shape per
 * contracts/mcp-tools-delta.md): the server asked, the INUBIT node that answered, one value per
 * requested name, plus warnings.
 *
 * @param node          the server the call addressed
 * @param answeringNode the INUBIT node that answered ({@code inubitNode})
 * @param values        the port's values in request order (masked and bounded by the adapter)
 * @param warnings      {@link #WARNING} first, then the version gate's warnings
 */
public record NodeConfigResult(NodeId node, String answeringNode, List<NodeConfigValue> values,
    List<String> warnings) {

    /** The warning of every result. */
    public static final String WARNING = ClusterNodesResult.WARNING;

    public NodeConfigResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(answeringNode, "answeringNode");
        values = List.copyOf(values);
        warnings = List.copyOf(warnings);
    }
}
