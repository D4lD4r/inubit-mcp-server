package de.dadecker.inubit.mcp.domain.model.node;

import java.util.List;

/**
 * The configuration values one node answered (research D-10).
 *
 * @param answeringNode the INUBIT node that answered (name rule of {@link ClusterNode})
 * @param values        one value per requested name, in request order
 */
public record NodeConfigValues(String answeringNode, List<NodeConfigValue> values) {

    public NodeConfigValues {
        ClusterNode.checkName(answeringNode);
        values = List.copyOf(values);
    }
}
