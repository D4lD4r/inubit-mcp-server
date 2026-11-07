package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;

/**
 * Wraps single write ports of a test harness in the real {@link StaleReportingPorts} of their
 * node, as the gateway factory does in production (research D-10, D-24).
 */
public final class StaleReportingTestPorts {

    private StaleReportingTestPorts() {
    }

    /** {@code port} of {@code node}, reporting to {@code observer}. */
    public static ImportPort imports(NodeId node, VersionObserver observer, ImportPort port) {
        return new StaleReportingPorts(node, observer).new Imports(port);
    }

    /** {@code port} of {@code node}, reporting to {@code observer}. */
    public static TagPort tags(NodeId node, VersionObserver observer, TagPort port) {
        return new StaleReportingPorts(node, observer).new Tags(port);
    }
}
