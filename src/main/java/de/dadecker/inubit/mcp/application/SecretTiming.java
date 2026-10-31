package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The extra fan-out time per node for reading its secrets on first use (feature 010, research R-8;
 * feature 011, FR-008): while a node's credentials are not held, its branch gets the wait of its
 * store ({@code fetchWait}: 10 s for AWS Parameter Store, 60 s for the credential store, which may
 * wait for an access dialog) plus {@link #GRACE}; once they are held, nothing, so fan-out deadlines
 * are as they were before feature 010. The read is not counted against the node's own request
 * timeouts.
 *
 * @param fetchWait how long a call waits for a pending read of each node (its store timeout)
 * @param held      whether a node's credentials are held; never starts a read
 * @param stores    the display name of the credential store of each credential store node, for
 *                  the text of a branch that timed out while its read was pending
 */
public record SecretTiming(Map<NodeId, Duration> fetchWait, Predicate<NodeId> held,
    Map<NodeId, String> stores) {

    /** No node reads its secrets. */
    public static final SecretTiming NONE = new SecretTiming(Map.of(), node -> true);

    /** How long a call whose wait ran out may still wait for the read to record its outcome. */
    static final Duration GRACE = Duration.ofSeconds(1);

    public SecretTiming {
        fetchWait = Map.copyOf(fetchWait);
        Objects.requireNonNull(held, "held");
        stores = Map.copyOf(stores);
    }

    /** Without store names: a timed-out branch keeps the text of {@link FanOut}. */
    public SecretTiming(Map<NodeId, Duration> fetchWait, Predicate<NodeId> held) {
        this(fetchWait, held, Map.of());
    }

    /** The extra time of {@code node}'s fan-out branch: {@code fetchWait + GRACE} or zero. */
    public Duration allowance(NodeId node) {
        Duration wait = fetchWait.get(node);
        return wait == null || held.test(node) ? Duration.ZERO : wait.plus(GRACE);
    }

    /** The credential store nodes of {@code servers} whose credentials are not held now. */
    public Set<NodeId> pending(List<NodeId> servers) {
        return servers.stream().filter(stores::containsKey).filter(held.negate())
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * {@code results} with the {@code TIMEOUT} of a branch whose credential store read was
     * pending when the fan-out started (and is still) explained: the read may be waiting for an
     * access dialog (feature 011, review T013-T014 r1). Other results are returned unchanged.
     */
    public <T> List<NodeResult<T>> explainTimeouts(List<NodeResult<T>> results,
        Set<NodeId> pendingAtStart) {
        if (pendingAtStart.isEmpty()) {
            return results;
        }
        return results.stream().map(result -> explain(result, pendingAtStart)).toList();
    }

    private <T> NodeResult<T> explain(NodeResult<T> result, Set<NodeId> pendingAtStart) {
        Optional<ToolError> error = result.error();
        if (error.isEmpty() || error.get().code() != ErrorCode.TIMEOUT
            || !pendingAtStart.contains(result.node()) || held.test(result.node())) {
            return result;
        }
        String store = stores.get(result.node());
        ToolError timeout = error.get();
        return new NodeResult<>(result.node(), result.payload(), Optional.of(ToolError.of(
            ErrorCode.TIMEOUT, timeout.message(), "The credentials of " + result.node()
                + " are still being read from the " + store + "; the read may be waiting for an"
                + " access dialog",
            "Answer the access dialog of the " + store + " if one is open, then retry; the"
                + " read continues and the next call uses its result")
            .withNode(result.node())));
    }
}
