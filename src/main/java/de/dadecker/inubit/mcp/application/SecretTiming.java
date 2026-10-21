package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The extra fan-out time per node for fetching its secrets on first use (feature 010, research
 * R-8): a node whose secrets come from AWS Parameter Store gets {@code fetchWait} plus
 * {@link #GRACE}, always, whether or not its secrets are held already (like the version gate's
 * detection and client probe of feature 009, it is only known inside the call whether it
 * fetches); every other node gets zero. The fetch is not counted against the node's own request
 * timeouts.
 *
 * @param awsNodes  the nodes whose secrets come from AWS Parameter Store
 * @param fetchWait how long a call waits for a pending fetch (the store timeout)
 */
public record SecretTiming(Set<NodeId> awsNodes, Duration fetchWait) {

    /** No node fetches its secrets. */
    public static final SecretTiming NONE = new SecretTiming(Set.of(), Duration.ZERO);

    /** How long a call whose wait ran out may still wait for the fetch to record its outcome. */
    static final Duration GRACE = Duration.ofSeconds(1);

    public SecretTiming {
        awsNodes = Set.copyOf(awsNodes);
        Objects.requireNonNull(fetchWait, "fetchWait");
    }

    /** The extra time of {@code node}'s fan-out branch: {@code fetchWait + GRACE} or zero. */
    public Duration allowance(NodeId node) {
        return awsNodes.contains(node) ? fetchWait.plus(GRACE) : Duration.ZERO;
    }
}
