package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import java.util.Optional;

/**
 * Lists the cluster members one INUBIT server knows through an unsupported INUBIT interface
 * (feature 024, research D-7). Thread-safe.
 */
public interface ClusterNodePort {

    /**
     * The members known to a server running {@code version}, with the details of the member
     * {@code details} when present. {@code deadlineNanos} is the call deadline as a
     * {@link System#nanoTime()} value set by the service.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException for a failure of the whole
     *     call (e.g. login, {@code TIMEOUT}); details that cannot be read are reported in
     *     {@link ClusterNodes#details()}
     */
    ClusterNodes nodes(Optional<String> details, InubitVersion version, long deadlineNanos);
}
