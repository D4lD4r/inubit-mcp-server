package de.dadecker.inubit.mcp.domain.model.node;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The cluster members one server knows, in the order INUBIT returned them, and optionally the
 * details of one member (research D-7).
 *
 * @param answeringNode the INUBIT node that answered (name rule of {@link ClusterNode})
 * @param nodes         the first {@value #MAX_NODES} members
 * @param total         all members INUBIT returned
 * @param truncated     true exactly when {@code total} exceeds the listed members
 * @param details       present when the caller asked for the details of a member
 */
public record ClusterNodes(
    String answeringNode,
    List<ClusterNode> nodes,
    int total,
    boolean truncated,
    Optional<Details> details) {

    public static final int MAX_NODES = 200;
    public static final int MAX_PAIRS = 500;
    public static final int MAX_REASON_CHARS = 200;

    public ClusterNodes {
        ClusterNode.checkName(answeringNode);
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(details, "details");
        if (nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("At most " + MAX_NODES + " members are listed");
        }
        if (total < nodes.size() || truncated != (total > nodes.size())) {
            throw new IllegalArgumentException(
                "The total must not be below the listed members and truncated must mark the rest");
        }
    }

    /** The details of one member: its pairs, or why they could not be read. */
    public sealed interface Details {
        /** The member whose details were asked for. */
        String node();
    }

    /**
     * The details read.
     *
     * @param pairs     at most {@value ClusterNodes#MAX_PAIRS}, in the order received
     * @param truncated true when the helper's entry cap or byte budget stopped the pairs
     */
    public record Available(String node, List<NodeDetail> pairs, boolean truncated)
        implements Details {

        public Available {
            ClusterNode.checkName(node);
            pairs = List.copyOf(pairs);
            if (pairs.size() > MAX_PAIRS) {
                throw new IllegalArgumentException("At most " + MAX_PAIRS + " detail pairs");
            }
        }
    }

    /**
     * The details could not be read; the members are still listed.
     *
     * @param reason scrubbed, at most {@value ClusterNodes#MAX_REASON_CHARS} characters
     */
    public record Unavailable(String node, String reason) implements Details {

        public Unavailable {
            ClusterNode.checkName(node);
            Objects.requireNonNull(reason, "reason");
            if (reason.length() > MAX_REASON_CHARS) {
                throw new IllegalArgumentException(
                    "Invalid reason: more than " + MAX_REASON_CHARS + " characters");
            }
        }
    }
}
