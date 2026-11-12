package com.inubit.ibis.server.cache;

import de.dadecker.inubit.mcp.testing.FakeInubitScript;

/**
 * Test fake (feature 021, research D-22) of the vendor's cluster node name, a local value object:
 * its calls are not recorded.
 */
public class NodeName {

    static {
        FakeInubitScript.initializing("NodeName");
    }

    private final String name;

    private NodeName(String name) {
        this.name = name;
    }

    public static NodeName nodeName(String name) {
        return new NodeName(name);
    }

    public String getName() {
        return name;
    }
}
