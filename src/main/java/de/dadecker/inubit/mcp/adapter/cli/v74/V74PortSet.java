package de.dadecker.inubit.mcp.adapter.cli.v74;

import de.dadecker.inubit.mcp.adapter.PortSet;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.RefusingPorts;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61HealthAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61PortSet;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61ProcessQueryAdapter;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionRange;

/**
 * The INUBIT 7.4 port set (feature 025, research D-5, spec FR-011), registered as CLI-only for
 * {@link #RANGE}: built without REST client. The process query port and the health port are the
 * 6.1 StartCLI adapters with the {@link V74Profile} (spec FR-024); every other port refuses with
 * {@code UNSUPPORTED_VERSION}. The version gate decides whether a call reaches an adapter.
 */
public final class V74PortSet {

    /** The versions this port set serves. */
    public static final VersionRange RANGE = new VersionRange(7, 4, 7, 4);

    private V74PortSet() {
    }

    /**
     * The {@link PortSetRegistry.Factory} of 7.4 nodes: a {@link V61ProcessQueryAdapter} with the
     * 6.1 connection and the 7.4 profile per node, every other port refusing; {@code probe} and
     * {@code limiter} are shared by every node (one instance each, as for 6.1).
     */
    public static PortSetRegistry.Factory factory(CliClientProbe probe,
        CliConcurrencyLimiter limiter) {
        return node -> {
            RefusingPorts refusing = refusing(node.server().id());
            return new PortSet(new V61ProcessQueryAdapter(node.server().id(),
                V61PortSet.connection(node, probe, limiter), V61PortSet.classifier(node),
                V74Profile.PROFILE), refusing, refusing, refusing, refusing, refusing, refusing,
                refusing, refusing, refusing, refusing, refusing, refusing, refusing, refusing,
                refusing, refusing, refusing, refusing);
        };
    }

    /**
     * The {@link PortSetRegistry.CliHealthFactory} of 7.4 nodes: a {@link V61HealthAdapter} with
     * the 6.1 connection and the 7.4 profile per node; {@code probe} and {@code limiter} are
     * shared by every node (one instance each, as for 6.1).
     */
    public static PortSetRegistry.CliHealthFactory cliHealth(CliClientProbe probe,
        CliConcurrencyLimiter limiter) {
        return node -> new V61HealthAdapter(node.server().id(),
            V61PortSet.connection(node, probe, limiter), V61PortSet.classifier(node),
            V74Profile.PROFILE);
    }

    private static RefusingPorts refusing(NodeId node) {
        return new RefusingPorts(node, "7.4",
            "INUBIT 7.4 is recognised, but no tool is verified on it yet");
    }
}
