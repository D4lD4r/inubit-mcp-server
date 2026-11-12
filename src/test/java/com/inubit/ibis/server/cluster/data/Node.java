package com.inubit.ibis.server.cluster.data;

import com.inubit.ibis.server.cache.NodeName;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;

/**
 * Test fake (feature 021, research D-22) of the vendor's cluster node, a local value object: its
 * calls are not recorded. The constructor is the fake's own; the helper must not use it.
 */
public class Node {

    static {
        FakeInubitScript.initializing("Node");
    }

    private final NodeName nodeName;

    public Node(NodeName nodeName) {
        this.nodeName = nodeName;
    }

    public NodeName getNodeName() {
        return nodeName;
    }
}
