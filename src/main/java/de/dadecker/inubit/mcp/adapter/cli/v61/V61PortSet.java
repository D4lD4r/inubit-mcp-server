package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.adapter.PortSet;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.RefusingPorts;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.domain.model.VersionRange;

/**
 * The INUBIT 6.1 port set (feature 011, research D-4), registered as CLI-only for {@link #RANGE}:
 * built without REST client. Every port 6.1 does not offer is a {@link RefusingPorts} stub; the
 * StartCLI adapter for the process query ({@code V61ProcessQueryAdapter}) replaces its refusal
 * once it exists. The health port is the {@link V61HealthAdapter}.
 */
public final class V61PortSet {

    /** The versions this port set serves. */
    public static final VersionRange RANGE = new VersionRange(6, 1, 6, 1);

    private V61PortSet() {
    }

    /** {@link PortSetRegistry.Factory}: the port set of a 6.1 node. */
    public static PortSet create(PortSetRegistry.NodeResources node) {
        RefusingPorts refusing = new RefusingPorts(node.server().id());
        return new PortSet(refusing, refusing, refusing, refusing, refusing, refusing, refusing);
    }

    /**
     * The {@link PortSetRegistry.CliHealthFactory} of 6.1 nodes: a {@link V61HealthAdapter} per
     * node; {@code probe} and {@code limiter} are shared by every node (one instance each).
     */
    public static PortSetRegistry.CliHealthFactory cliHealth(CliClientProbe probe,
        CliConcurrencyLimiter limiter) {
        return node -> new V61HealthAdapter(node.server().id(),
            new V61Connection(node.server(), node.credentials(), node.guard(), node.cliRunner(),
                probe, limiter),
            new CliOutputClassifier(node.scrubber(), node.server().secretLabels()));
    }
}
