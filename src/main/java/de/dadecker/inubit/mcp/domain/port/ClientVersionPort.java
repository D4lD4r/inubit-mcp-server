package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks the local StartCLI client of a node for its version and the server range it declares
 * (research D-7, FR-002), without credentials and without contacting the INUBIT server.
 * Caching and the retry back-off are the caller's concern; every call runs the probe.
 */
public interface ClientVersionPort {

    /**
     * Probes the client chosen for {@code node} at {@code version} — the caller's own snapshot,
     * never a fresh read of the shared state (feature 017, research D-5) — bounded by
     * {@code timeout}. A client that prints a version but no range is a success with an empty
     * range.
     *
     * @return the client's version and the key of the client that was launched
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code CLI_UNAVAILABLE} if
     *     the node has no StartCLI installation or the client reports no version,
     *     {@code TIMEOUT} if it does not finish within {@code timeout}
     */
    ProbedClient probe(NodeId node, Optional<InubitVersion> version, Duration timeout);
}
