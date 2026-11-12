package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.nodetools.ClusterNodesRequest;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNode;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodesResult;
import de.dadecker.inubit.mcp.domain.model.node.NodeDetail;
import de.dadecker.inubit.mcp.domain.model.node.NodeStatus;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code list_cluster_nodes} (feature 024, contracts/mcp-tools-delta.md, research D-25): lists the
 * members of the INUBIT cluster one server knows, optionally with the masked details of one member,
 * through an unsupported, undocumented INUBIT interface ({@code ClusterNodeService}). Offered only
 * for groups with {@code unsupported.nodeStatusRead}; the service repeats every input check.
 */
public final class ListClusterNodesTool implements ToolHandler {

    static final String DESCRIPTION = "Lists every INUBIT node the cluster of one {node} knows, as"
        + " the Workbench's Cluster Manager shows it: name, status (ACTIVE, MAINTENANCE, OFFLINE,"
        + " or UNKNOWN for a node that does not answer), INUBIT version, zone, node ID and uptime,"
        + " and names the node that answered. With nodeName it also returns that node's details"
        + " (server, JDK, operating system, memory, cluster and zone names, internal addresses and"
        + " file paths), masked. Use it to check whether a cluster is complete and healthy,"
        + " including nodes this profile does not configure. UNSUPPORTED: it uses an undocumented"
        + " internal INUBIT interface without vendor support or stability guarantee, for diagnosis"
        + " only; it may stop working after any INUBIT update; INUBIT checks no right beyond the"
        + " login; available only for groups with unsupported.nodeStatusRead. Changes nothing on"
        + " INUBIT.";

    private final Function<ClusterNodesRequest, ClusterNodesResult> list;

    /** @param list the listing of the profile ({@code ClusterNodeService::list}) */
    public ListClusterNodesTool(Function<ClusterNodesRequest, ClusterNodesResult> list) {
        this.list = Objects.requireNonNull(list, "list");
    }

    @Override
    public String name() {
        return "list_cluster_nodes";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("List the INUBIT cluster's nodes", true);
    }

    /** The arguments are validated by the SDK against {@code list_cluster_nodes.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        return Result.of(list.apply(new ClusterNodesRequest(args.string("target"),
            args.optionalString("nodeName"))));
    }

    /** The JSON shape of contracts/mcp-tools-delta.md "Output". */
    record Result(NodeId node, String inubitNode, int total, boolean truncated, List<Node> nodes,
        Optional<Details> details, List<String> hints, List<String> warnings) {

        static Result of(ClusterNodesResult result) {
            ClusterNodes cluster = result.cluster();
            return new Result(result.node(), cluster.answeringNode(), cluster.total(),
                cluster.truncated(), cluster.nodes().stream().map(Node::of).toList(),
                cluster.details().map(Details::of), result.hints(), result.warnings());
        }
    }

    /** A member; the uptime as ISO-8601 duration and in whole seconds. */
    record Node(String name, NodeStatus status, Optional<String> rawStatus,
        Optional<String> version, Optional<String> zone, Optional<String> nodeId,
        Optional<String> uptime, Optional<Long> uptimeSeconds) {

        static Node of(ClusterNode node) {
            return new Node(node.name(), node.status(), node.rawStatus(), node.version(),
                node.zone(), node.nodeId(), node.uptime().map(Duration::toString),
                node.uptime().map(Duration::toSeconds));
        }
    }

    /** {@code available: true} with pairs and {@code truncated}, or {@code false} with a reason. */
    record Details(String node, boolean available, Optional<List<Pair>> pairs,
        Optional<Boolean> truncated, Optional<String> reason) {

        static Details of(ClusterNodes.Details details) {
            return switch (details) {
                case ClusterNodes.Available available -> new Details(available.node(), true,
                    Optional.of(available.pairs().stream().map(Pair::of).toList()),
                    Optional.of(available.truncated()), Optional.empty());
                case ClusterNodes.Unavailable unavailable -> new Details(unavailable.node(), false,
                    Optional.empty(), Optional.empty(), Optional.of(unavailable.reason()));
            };
        }
    }

    /** {@code cut} only when the value was cut. */
    record Pair(String key, String value, Optional<Boolean> cut) {

        static Pair of(NodeDetail detail) {
            return new Pair(detail.key(), detail.value(),
                detail.cut() ? Optional.of(true) : Optional.empty());
        }
    }
}
