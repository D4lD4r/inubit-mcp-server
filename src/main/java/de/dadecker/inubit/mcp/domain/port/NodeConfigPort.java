package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import java.util.List;

/**
 * Reads allowlisted configuration values of the answering INUBIT node through an unsupported
 * INUBIT interface (feature 024, research D-10). Thread-safe.
 */
public interface NodeConfigPort {

    /**
     * The values of {@code names} on the node that answers for a server running
     * {@code version}, in request order. {@code deadlineNanos} is the call deadline as a
     * {@link System#nanoTime()} value set by the service.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException for a failure of the whole
     *     call (e.g. login, {@code TIMEOUT}); a name that cannot be read is a
     *     {@link NodeConfigValue.Failed}
     */
    NodeConfigValues values(List<NodeConfigName> names, InubitVersion version,
        long deadlineNanos);
}
