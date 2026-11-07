package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.port.ClientSelectionPort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A {@link ClientSelectionPort} with one client per node whatever its version
 * ({@link ClientKey#NONE}) and no address warning: the setting of the tests that are not about
 * the client choice of feature 017.
 */
public final class TestClientSelection implements ClientSelectionPort {

    private final Predicate<NodeId> withClient;

    private TestClientSelection(Predicate<NodeId> withClient) {
        this.withClient = Objects.requireNonNull(withClient, "withClient");
    }

    /** @param withClient the nodes whose StartCLI exists */
    public static ClientSelectionPort of(Predicate<NodeId> withClient) {
        return new TestClientSelection(withClient);
    }

    /** The nodes whose startup summary has {@link NodeSummary#cliAvailable()}. */
    public static ClientSelectionPort installed(List<NodeSummary> summaries) {
        return of(summaries.stream().filter(NodeSummary::cliAvailable).map(NodeSummary::id)
            .collect(Collectors.toUnmodifiableSet())::contains);
    }

    /** No node has a StartCLI installation. */
    public static ClientSelectionPort none() {
        return of(node -> false);
    }

    @Override
    public Optional<ClientKey> current(NodeId node, Optional<InubitVersion> version) {
        return Optional.of(ClientKey.NONE);
    }

    @Override
    public boolean available(NodeId node, Optional<InubitVersion> version) {
        return withClient.test(node);
    }

    @Override
    public boolean anyCandidate(NodeId node) {
        return withClient.test(node);
    }

    @Override
    public Optional<String> warning(NodeId node, Optional<InubitVersion> version) {
        return Optional.empty();
    }

    /** No client directory is named; final once the version is known. */
    @Override
    public NodeSummary.StartCli startCli(NodeId node, Optional<InubitVersion> version) {
        return NodeSummary.StartCli.none(version.isPresent());
    }
}
