package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Duration;

/**
 * Asks the local StartCLI client of a node for its version and the server range it declares
 * (research D-7, FR-002), without credentials and without contacting the INUBIT server.
 * Caching and the retry back-off are the caller's concern; every call runs the probe.
 */
public interface ClientVersionPort {

    /**
     * Probes the client configured for {@code node}, bounded by {@code timeout}. A client that
     * prints a version but no range is a success with an empty range.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code CLI_UNAVAILABLE} if
     *     the node has no StartCLI installation or the client reports no version,
     *     {@code TIMEOUT} if it does not finish within {@code timeout}
     */
    ClientVersion probe(NodeId node, Duration timeout);
}
