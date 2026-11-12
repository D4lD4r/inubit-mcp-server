package de.dadecker.inubit.mcp.application.nodetools;

import java.util.Objects;
import java.util.Optional;

/**
 * The input of one {@code list_cluster_nodes} call (feature 024), as the tool received it after
 * the input schema.
 *
 * @param target   the id of exactly one server ({@code <group>/<node>})
 * @param nodeName the member whose details are asked for, if given
 */
public record ClusterNodesRequest(String target, Optional<String> nodeName) {

    public ClusterNodesRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(nodeName, "nodeName");
    }
}
