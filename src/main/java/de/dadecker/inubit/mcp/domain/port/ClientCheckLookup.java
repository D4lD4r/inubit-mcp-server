package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ClientCheck;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Objects;

/**
 * Read-only view of the recorded StartCLI client checks per node and client (feature 017,
 * research D-4): the adapter layer's launch guard asks it before it starts a client, without
 * depending on the application layer. Implementations are thread-safe and never probe.
 */
public interface ClientCheckLookup {

    /** No client check recorded for any node (0.8.0 behaviour of a launch). */
    ClientCheckLookup NONE = (node, key) -> Recorded.Absent.NOTHING;

    /**
     * What is recorded for the client {@code key} of {@code node} and for the node's other
     * clients, answered from one snapshot of the node's checks: a probe recording between two
     * separate questions cannot make the answers contradict each other.
     */
    Recorded recorded(NodeId node, ClientKey key);

    /** The answer of {@link #recorded}. */
    sealed interface Recorded {

        /** The recorded result or failure of the asked client. */
        record ForClient(ClientCheck check) implements Recorded {
            public ForClient {
                Objects.requireNonNull(check, "check");
            }
        }

        /** Nothing recorded for the asked client. */
        enum Absent implements Recorded {
            /** But a result or failure of another client of the node. */
            OTHER_CLIENTS_ONLY,
            /** And nothing for any other client of the node either. */
            NOTHING
        }
    }
}
