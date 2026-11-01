package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.adapter.PortSet;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.RefusingPorts;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;

/**
 * The INUBIT 6.1 port set (feature 011, research D-4), registered as CLI-only for {@link #RANGE}:
 * built without REST client. Every port 6.1 does not offer is a {@link RefusingPorts} stub; the
 * StartCLI adapters for the process query ({@code V61ProcessQueryAdapter}) and the health port
 * ({@code V61HealthAdapter}) replace their refusals once they exist.
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
     * {@link PortSetRegistry.CliHealthFactory}: the health port of a 6.1 node; refuses until
     * {@code V61HealthAdapter} exists (the matrix declares 6.1 monitoring unsupported until then,
     * so the version gate refuses first).
     */
    public static CliHealthPort cliHealth(PortSetRegistry.NodeResources node) {
        RefusingPorts refusing = new RefusingPorts(node.server().id());
        return budget -> {
            throw refusing.refusal();
        };
    }
}
