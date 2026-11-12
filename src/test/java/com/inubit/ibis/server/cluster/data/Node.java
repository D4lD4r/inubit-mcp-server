package com.inubit.ibis.server.cluster.data;

import com.inubit.ibis.server.cache.NodeName;
import com.inubit.ibis.server.datafacade.NodeInformation;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;

/**
 * Test fake (feature 021, research D-22; feature 024, research "Static facts") of the vendor's
 * cluster node, a local value object: its calls are not recorded. The constructor is the fake's
 * own; the helper must not use it. The setters are the vendor's; the vendor keeps the node
 * information as serialized bytes, the fake keeps the object.
 */
public class Node {

    static {
        FakeInubitScript.initializing("Node");
    }

    /** The vendor's node states. */
    public enum Status {
        ACTIVE,
        MAINTENANCE,
        OFFLINE
    }

    private final NodeName nodeName;
    private Status status;
    private String version;
    private String zoneName;
    private long uptime;
    private String nodeID;
    private NodeInformation nodeInformation;

    public Node(NodeName nodeName) {
        this.nodeName = nodeName;
    }

    public NodeName getNodeName() {
        return nodeName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    /** Milliseconds. */
    public long getUptime() {
        return uptime;
    }

    public void setUptime(long uptime) {
        this.uptime = uptime;
    }

    public String getNodeID() {
        return nodeID;
    }

    public void setNodeID(String nodeID) {
        this.nodeID = nodeID;
    }

    public NodeInformation getNodeInformation() {
        return nodeInformation;
    }

    public void setNodeInformation(NodeInformation nodeInformation) {
        this.nodeInformation = nodeInformation;
    }
}
