package com.inubit.ibis.server.cluster.data;

import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import java.util.ArrayList;

/**
 * Test fake (feature 024, research "Static facts") of the vendor's node list, a local value
 * object: its calls are not recorded. The vendor's class implements {@code List<Node>} itself;
 * the fake extends {@link ArrayList}, so the helper may rely on {@code List} only.
 */
public class NodeList extends ArrayList<Node> {

    static {
        FakeInubitScript.initializing("NodeList");
    }

    /** The node named {@code name}, or {@code null}. */
    public Node getByName(String name) {
        return stream().filter(node -> node.getNodeName() != null
            && name.equals(node.getNodeName().getName())).findFirst().orElse(null);
    }
}
