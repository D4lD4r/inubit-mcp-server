package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Optional;

/**
 * Which StartCLI client a node uses at a version (feature 017, research D-7, D-19). The version is
 * the caller's snapshot and never re-read; implementations contact no node and launch no process.
 */
public interface ClientSelectionPort {

    /** The client {@code node} uses at {@code version}; empty if no client home applies. */
    Optional<ClientKey> current(NodeId node, Optional<InubitVersion> version);

    /** Whether the StartCLI script of the client {@code node} uses at {@code version} exists. */
    boolean available(NodeId node, Optional<InubitVersion> version);

    /**
     * Whether a client home is configured that {@code node} may use, installed or not, for the
     * tool announcement at startup: a node pinned to a version with minor considers only the
     * choice for that version, a pin without minor only the version-less sources, an
     * {@code AUTO} node every entry as well.
     */
    boolean anyCandidate(NodeId node);

    /**
     * The warning for an explicit servlet {@code cli.url} when {@code node} runs major 9 or later
     * at {@code version} (research D-19); empty otherwise.
     */
    Optional<String> warning(NodeId node, Optional<InubitVersion> version);
}
