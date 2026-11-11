package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SecretSpan;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The configured credentials of a profile (feature 019, FR-010 (a), research D-7, option A): every
 * node's main and end-to-end reads, started or joined all at once, each waited for on its own
 * virtual thread; the values themselves stay with the {@link SecretScrubber}.
 */
public final class ProfileSecrets implements ConfiguredSecrets {

    /** The nodes in node order. */
    private final List<NodeSecrets> nodes;
    private final SecretScrubber scrubber;

    public ProfileSecrets(Map<NodeId, NodeSecrets> nodes, SecretScrubber scrubber) {
        this.nodes = nodes.values().stream()
            .sorted(Comparator.comparing(node -> node.node().value())).toList();
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
    }

    /**
     * The longest store timeout of the nodes ({@link Duration#ZERO} without nodes): the bound of
     * {@link #prepare} for a tool call.
     */
    public Duration timeout() {
        return nodes.stream().map(NodeSecrets::timeout).max(Comparator.naturalOrder())
            .orElse(Duration.ZERO);
    }

    @Override
    public Readiness prepare(Duration bound) {
        Objects.requireNonNull(bound, "bound");
        if (nodes.isEmpty()) {
            return new Readiness.Ready();
        }
        List<Future<NodeSecrets.Settled>> main = new ArrayList<>();
        List<Future<NodeSecrets.Settled>> e2e = new ArrayList<>();
        // every wait is bounded by the store timeout, so close() returns in time
        try (ExecutorService waits = Executors.newVirtualThreadPerTaskExecutor()) {
            for (NodeSecrets node : nodes) {
                main.add(waits.submit(() -> node.awaitSettled(bound)));
                e2e.add(waits.submit(() -> node.awaitSettledE2e(bound)));
            }
        }
        List<Pending> pending = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeSecrets node = nodes.get(i);
            Optional<String> e2eState = state(e2e.get(i));
            state(main.get(i)).or(() -> e2eState).ifPresent(state ->
                pending.add(new Pending(node.node().value(), node.storeName(), state)));
        }
        return pending.isEmpty() ? new Readiness.Ready() : new Readiness.NotReady(pending);
    }

    /** {@code waiting} or the failure kind in lower case; empty if settled. */
    private static Optional<String> state(Future<NodeSecrets.Settled> wait) {
        NodeSecrets.Settled settled = wait.state() == Future.State.SUCCESS ? wait.resultNow()
            : new NodeSecrets.Settled.Waiting();
        return switch (settled) {
            case NodeSecrets.Settled.Held held -> Optional.empty();
            case NodeSecrets.Settled.NothingConfigured nothing -> Optional.empty();
            case NodeSecrets.Settled.Waiting waiting -> Optional.of("waiting");
            case NodeSecrets.Settled.Failed failed ->
                Optional.of(failed.kind().name().toLowerCase(Locale.ROOT));
        };
    }

    @Override
    public List<SecretSpan> occurrences(String text) {
        return scrubber.occurrences(text);
    }

    @Override
    public String toString() {
        return "ProfileSecrets" + nodes.stream().map(NodeSecrets::node)
            .map(NodeId::value).toList();
    }
}
