package com.inubit.ibis.server.datafacade;

import com.inubit.ibis.utils.property.type.MapProperty;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;

/**
 * Test fake (feature 024, research "Static facts") of the vendor's node information, a local
 * value object wrapping the node information map: its calls are not recorded.
 */
public class NodeInformation {

    static {
        FakeInubitScript.initializing("NodeInformation");
    }

    private final MapProperty nodeInformationMap;

    public NodeInformation(MapProperty nodeInformationMap) {
        this.nodeInformationMap = nodeInformationMap;
    }

    public MapProperty getNodeInformationMap() {
        return nodeInformationMap;
    }
}
