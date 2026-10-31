package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Optional;

/**
 * Receives what the adapters learn about the INUBIT version of a node (research D-4, D-10) and
 * answers which version is in effect, so that the adapter layer can report to the per-node
 * version state without depending on the application layer. Implementations are thread-safe.
 */
public interface VersionObserver {

    /** A detection read {@code version} from {@code node}. */
    void detected(NodeId node, InubitVersion version);

    /** A detection on {@code node} failed with {@code code}; a cancelled call is not reported. */
    default void detectionFailed(NodeId node, ErrorCode code) {
        detectionFailed(node, code, false);
    }

    /**
     * As {@link #detectionFailed(NodeId, ErrorCode)}; {@code endpointMissing} if the detection
     * request was answered with HTTP 404 (feature 011 FR-003).
     */
    void detectionFailed(NodeId node, ErrorCode code, boolean endpointMissing);

    /** {@code get_health} read {@code version} from {@code node}. */
    void observed(NodeId node, InubitVersion version);

    /** A response of {@code node} indicated that it may run another INUBIT version. */
    void stale(NodeId node);

    /** The version in effect for {@code node}; empty means unknown. */
    Optional<InubitVersion> effectiveVersion(NodeId node);
}
