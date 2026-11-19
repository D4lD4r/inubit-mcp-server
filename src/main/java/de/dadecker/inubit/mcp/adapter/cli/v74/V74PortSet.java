package de.dadecker.inubit.mcp.adapter.cli.v74;

import de.dadecker.inubit.mcp.adapter.PortSet;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.RefusingPorts;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionRange;

/**
 * The INUBIT 7.4 port set (feature 025, research D-5, spec FR-011), registered as CLI-only for
 * {@link #RANGE}: built without REST client, every port and the health port refuse with
 * {@code UNSUPPORTED_VERSION} until a tool is verified on 7.4. The version gate refuses first;
 * this is the second line of defence, so nothing can reach a 7.4 node.
 */
public final class V74PortSet {

    /** The versions this port set serves. */
    public static final VersionRange RANGE = new VersionRange(7, 4, 7, 4);

    private V74PortSet() {
    }

    /** The {@link PortSetRegistry.Factory} of 7.4 nodes: every port refusing. */
    public static PortSetRegistry.Factory factory() {
        return node -> {
            RefusingPorts refusing = refusing(node.server().id());
            return new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
                refusing, refusing, refusing, refusing, refusing, refusing, refusing, refusing,
                refusing, refusing, refusing, refusing, refusing);
        };
    }

    /** The {@link PortSetRegistry.CliHealthFactory} of 7.4 nodes: a health port that refuses. */
    public static PortSetRegistry.CliHealthFactory cliHealth() {
        return node -> budget -> {
            throw refusing(node.server().id()).refusal();
        };
    }

    private static RefusingPorts refusing(NodeId node) {
        return new RefusingPorts(node, "7.4",
            "INUBIT 7.4 is recognised, but no tool is verified on it yet");
    }
}
