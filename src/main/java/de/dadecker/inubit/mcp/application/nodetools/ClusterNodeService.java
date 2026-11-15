package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNode;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodesResult;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code list_cluster_nodes} (feature 024, FR-010, FR-011, research D-7,
 * D-22; decision flow and texts per contracts/mcp-tools-delta.md). One call, nothing sent before
 * step 4:
 * <ol>
 *   <li>{@code nodeName}, if given, has 1–128 characters of {@code [A-Za-z0-9_.:-]}, else
 *       {@code INVALID_INPUT} (the name is not echoed).</li>
 *   <li>{@link TargetResolver#resolveSingleServer}: exactly one server.</li>
 *   <li>The group's opt-in {@code unsupported.nodeStatusRead}; without it {@code NOT_CONFIGURED}
 *       (no detection, no helper).</li>
 *   <li>{@link VersionGate#admitWithDetection} for {@code CLUSTER_NODES_READ} over StartCLI's
 *       client: {@code UNSUPPORTED_VERSION} or the detection's error.</li>
 *   <li>The call deadline, now + the server's {@code cliTimeout}; within it the server's login
 *       credentials ({@link GatewayFactory#prepareCredentials}); still being read →
 *       {@code TIMEOUT} naming {@code cliTimeout}.</li>
 *   <li>One {@link de.dadecker.inubit.mcp.domain.port.ClusterNodePort#nodes} run with the
 *       deadline; its errors pass through (one without a node names the server).</li>
 *   <li>The result and one INFO line with the server, the member count, the details name and the
 *       outcome; never a value.</li>
 * </ol>
 * The port's answer is the result as it is: the adapter already caps the members at
 * {@value ClusterNodes#MAX_NODES}, refuses a {@code nodeName} INUBIT does not know with
 * {@code INVALID_INPUT} naming the known members, and returns every detail value masked with
 * {@link NodeTextMasking} (the credentials of every configured node, then the 020 rules) and cut
 * to {@value de.dadecker.inubit.mcp.domain.model.node.NodeDetail#MAX_VALUE_CHARS} characters,
 * which {@link de.dadecker.inubit.mcp.domain.model.node.NodeDetail} enforces. Masking again here
 * would only risk a second cut; the {@code ResultMapper} scrubs every string of the result once
 * more.
 *
 * <p>Thread-safe; it keeps no state of its own.
 */
public final class ClusterNodeService {

    /** The tool name, the start of the gate's refusals and of the INFO line. */
    static final String TOOL = "list_cluster_nodes";

    private static final Logger LOG = LoggerFactory.getLogger(ClusterNodeService.class);
    private static final String UNKNOWN = "-";

    private final TargetResolver targets;
    private final Predicate<NodeId> optedIn;
    private final VersionGate gate;
    private final GatewayFactory gateways;
    private final Function<NodeId, Duration> cliTimeouts;
    private final LongSupplier nanoTime;

    /**
     * @param optedIn     whether the node's group sets {@code unsupported.nodeStatusRead: true}
     * @param gateways    the server's credentials and its cluster-node port
     * @param cliTimeouts the {@code cliTimeout} of a node, the call's budget
     * @param nanoTime    the clock of the call deadline ({@link System#nanoTime()})
     */
    public ClusterNodeService(TargetResolver targets, Predicate<NodeId> optedIn, VersionGate gate,
        GatewayFactory gateways, Function<NodeId, Duration> cliTimeouts, LongSupplier nanoTime) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.optedIn = Objects.requireNonNull(optedIn, "optedIn");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.cliTimeouts = Objects.requireNonNull(cliTimeouts, "cliTimeouts");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Lists the cluster members as the class description says; logs one INFO line either way.
     *
     * @throws ToolErrorException as described in the class description
     */
    public ClusterNodesResult list(ClusterNodesRequest request) {
        Objects.requireNonNull(request, "request");
        NodeId node = null;
        ClusterNodes cluster = null;
        String outcome = ErrorCode.INTERNAL.name();
        try {
            checkNodeName(request.nodeName());
            node = targets.resolveSingleServer(request.target());
            if (!optedIn.test(node)) {
                throw notConfigured(node);
            }
            VersionGate.Admission admission = gate.admitWithDetection(TOOL, node,
                Capability.CLUSTER_NODES_READ, Capability.Kind.READ, Set.of(Channel.CLI));
            cluster = read(node, admission, request.nodeName());
            ClusterNodesResult result = result(node, cluster, admission);
            outcome = "OK";
            return result;
        } catch (ToolErrorException e) {
            if (node == null) {
                node = e.error().node().orElse(null);
            }
            outcome = e.error().code().name();
            throw e;
        } finally {
            log(node, cluster, request.nodeName(), outcome);
        }
    }

    /** Steps 5 and 6: the call deadline, the server's login credentials, the port. */
    private ClusterNodes read(NodeId node, VersionGate.Admission admission,
        Optional<String> nodeName) {
        // the gate admits CLUSTER_NODES_READ only on a known major.minor
        InubitVersion version = admission.version().orElseThrow(() -> new IllegalStateException(
            TOOL + " on " + node + " was admitted without a version"));
        Duration budget = cliTimeouts.apply(node);
        long deadlineNanos = nanoTime.getAsLong() + budget.toNanos();
        try {
            if (gateways.prepareCredentials(node, remaining(deadlineNanos))
                == CredentialState.PENDING) {
                throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, TOOL + " on " + node
                        + " did not finish within cliTimeout (" + Durations.human(budget)
                        + "): the login credentials of " + node + " were not read in time",
                    "The first read of the login credentials from their store took longer than"
                        + " cliTimeout; it continues, so a later call finds them",
                    "Retry in a moment; raise cliTimeout of " + node + " if the credential store"
                        + " is just slow").withNode(node));
            }
            return gateways.clusterNodes(node).nodes(nodeName, version, deadlineNanos);
        } catch (ToolErrorException e) {
            // an adapter error without a node (e.g. the PortSet's CLI_UNAVAILABLE) names the server
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(node));
        }
    }

    /** Step 7: the port's answer with the warnings and the hint of cut details. */
    private static ClusterNodesResult result(NodeId node, ClusterNodes cluster,
        VersionGate.Admission admission) {
        List<String> hints = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        warnings.add(ClusterNodesResult.WARNING);
        warnings.addAll(admission.warnings());
        if (cluster.details().orElse(null) instanceof ClusterNodes.Available available) {
            warnings.add(ClusterNodesResult.DETAILS_WARNING);
            if (available.truncated()) {
                hints.add(ClusterNodesResult.DETAILS_TRUNCATED_HINT);
            }
        }
        return new ClusterNodesResult(node, cluster, hints, warnings);
    }

    /**
     * The one INFO line of a call (D-22): the server once known, the member count once read, the
     * details name once valid; never a value.
     */
    private static void log(NodeId node, ClusterNodes cluster, Optional<String> nodeName,
        String outcome) {
        LOG.info("{} on {}: {} node(s), details {}, outcome {}", TOOL,
            node == null ? UNKNOWN : node.toString(),
            cluster == null ? UNKNOWN : String.valueOf(cluster.total()),
            nodeName.filter(ClusterNode::isValidName).orElse(UNKNOWN), outcome);
    }

    /** What is left of the call deadline, never negative. */
    private Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - nanoTime.getAsLong();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    private static void checkNodeName(Optional<String> nodeName) {
        if (nodeName.isPresent() && !ClusterNode.isValidName(nodeName.get())) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "Invalid nodeName: expected 1–128 characters of A-Z, a-z, 0-9, '_', '.', ':' and"
                    + " '-'",
                "nodeName is the name of a node as list_cluster_nodes lists it",
                "Use a name from the list, or leave nodeName out"));
        }
    }

    private ToolErrorException notConfigured(NodeId node) {
        String group = targets.terms().render("{group}") + " " + node.group();
        return new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED, TOOL
                + " is not enabled for " + group + ": unsupported.nodeStatusRead is not set",
            "Listing the cluster's nodes uses an unsupported, undocumented INUBIT interface and is"
                + " off unless the " + targets.terms().render("{group}") + " opts in",
            "Set unsupported.nodeStatusRead: true on " + group + " and restart the MCP client"
                + " (unsupported INUBIT functionality, see docs/setup.md \"Unsupported"
                + " functions\")").withNode(node));
    }
}
