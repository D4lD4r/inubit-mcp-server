package de.dadecker.inubit.mcp.domain.model.node;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.List;
import java.util.Objects;

/**
 * The result of {@code list_cluster_nodes} (feature 024, research D-7; shape per
 * contracts/mcp-tools-delta.md): the server asked, the cluster members it knows with the
 * answering INUBIT node and, with {@code nodeName}, the member's details, plus hints and warnings.
 *
 * @param node    the server the call addressed
 * @param cluster the members, {@code total}, {@code truncated} and the details as the port returned
 *                them (detail values masked and bounded)
 */
public record ClusterNodesResult(NodeId node, ClusterNodes cluster, List<String> hints,
    List<String> warnings) {

    /** The warning of every result. */
    public static final String WARNING =
        "Read through an unsupported, undocumented INUBIT interface; diagnosis only";
    /** The warning of a result with details read. */
    public static final String DETAILS_WARNING =
        "The node details contain internal addresses and file paths of the INUBIT installation";
    /** The hint of details stopped by the pair cap or the helper's byte budget. */
    public static final String DETAILS_TRUNCATED_HINT =
        "Only part of the node details was returned";

    public ClusterNodesResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(cluster, "cluster");
        hints = List.copyOf(hints);
        warnings = List.copyOf(warnings);
    }
}
