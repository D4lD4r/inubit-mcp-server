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
 * built without REST client. The process query port is the {@link V61ProcessQueryAdapter}, the
 * health port the {@link V61HealthAdapter}; every other port is a {@link RefusingPorts} stub.
 * Both StartCLI adapters of a node use the same shared client probe and concurrency limiter
 * (research D-8, D-9).
 */
public final class V61PortSet {

    /** The versions this port set serves. */
    public static final VersionRange RANGE = new VersionRange(6, 1, 6, 1);

    private V61PortSet() {
    }

    /**
     * The {@link PortSetRegistry.Factory} of 6.1 nodes: a {@link V61ProcessQueryAdapter} per node,
     * all other ports refusing; {@code probe} and {@code limiter} are shared by every node (one
     * instance each).
     */
    public static PortSetRegistry.Factory factory(CliClientProbe probe,
        CliConcurrencyLimiter limiter) {
        return node -> {
            RefusingPorts refusing = new RefusingPorts(node.server().id());
            return new PortSet(new V61ProcessQueryAdapter(node.server().id(),
                connection(node, probe, limiter), classifier(node)), refusing, refusing, refusing,
                refusing, refusing, refusing);
        };
    }

    /**
     * The {@link PortSetRegistry.CliHealthFactory} of 6.1 nodes: a {@link V61HealthAdapter} per
     * node; {@code probe} and {@code limiter} are shared by every node (one instance each).
     */
    public static PortSetRegistry.CliHealthFactory cliHealth(CliClientProbe probe,
        CliConcurrencyLimiter limiter) {
        return node -> new V61HealthAdapter(node.server().id(), connection(node, probe, limiter),
            classifier(node));
    }

    private static V61Connection connection(PortSetRegistry.NodeResources node,
        CliClientProbe probe, CliConcurrencyLimiter limiter) {
        return new V61Connection(node.server(), node.credentials(), node.guard(),
            node.cliRunner(), probe, limiter);
    }

    private static CliOutputClassifier classifier(PortSetRegistry.NodeResources node) {
        return new CliOutputClassifier(node.scrubber(), node.server().secretLabels());
    }
}
