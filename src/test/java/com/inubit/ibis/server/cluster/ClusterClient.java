package com.inubit.ibis.server.cluster;

import com.inubit.ibis.configuration.utils.io.MultipartDownloadThread;
import com.inubit.ibis.server.cache.NodeName;
import com.inubit.ibis.server.cluster.data.Node;
import com.inubit.ibis.server.cluster.data.NodeList;
import com.inubit.ibis.server.datafacade.NodeInformation;
import com.inubit.ibis.utils.I_IBISClientInterface;
import com.inubit.ibis.utils.property.type.IBISProperty;
import com.inubit.ibis.utils.property.type.MapProperty;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import java.io.File;
import java.util.Map;

/**
 * Test fake (feature 021, research "Spike findings", D-22) of the vendor's cluster client, driven
 * by the active {@link FakeInubitScript}, which records every call; a node name argument is
 * recorded as its name. Feature 024 (research D-27) adds the node list, a named node with its
 * node information and the node configuration properties, and the methods the helper must never
 * call, which fail the test. The vendor's {@code changeTemporaryLogLevel} takes a log4j
 * {@code Level}, which is not on the test class path; the fake takes {@code Object}.
 */
public class ClusterClient {

    static {
        FakeInubitScript.initializing("ClusterClient");
    }

    public ClusterClient(I_IBISClientInterface client, String user, String clientUID,
        String additionalUserGroup) {
        FakeInubitScript.active().record("ClusterClient.<init>", client, user, clientUID,
            additionalUserGroup);
    }

    /**
     * The scripted node, the scripted failure, or a node lookup with the {@code null} scripted by
     * {@link FakeInubitScript#nodeLookupReturnsNull}.
     */
    public Node getNode() throws Exception {
        FakeInubitScript script = FakeInubitScript.active();
        script.record("ClusterClient.getNode");
        FakeInubitScript.NullFrom nullFrom = script.nodeLookupNull();
        if (nullFrom == FakeInubitScript.NullFrom.GET_NODE) {
            return null;
        }
        if (nullFrom == FakeInubitScript.NullFrom.GET_NODE_NAME) {
            return new Node(null);
        }
        return new Node(NodeName.nodeName(
            nullFrom == FakeInubitScript.NullFrom.GET_NAME ? null : script.node()));
    }

    /** The scripted members in order, or the scripted failure. */
    public NodeList getNodes() throws Exception {
        FakeInubitScript script = FakeInubitScript.active();
        script.record("ClusterClient.getNodes");
        NodeList nodes = new NodeList();
        for (FakeInubitScript.Member member : script.scriptedMembers()) {
            Node node = new Node(NodeName.nodeName(member.name()));
            node.setStatus(member.status() == null ? null : Node.Status.valueOf(member.status()));
            node.setVersion(member.version());
            node.setZoneName(member.zone());
            node.setNodeID(member.nodeId());
            node.setUptime(member.uptimeMs());
            nodes.add(node);
        }
        return nodes;
    }

    /** The node {@code nodeName} with its scripted node information, or the scripted failure. */
    public Node getNode(NodeName nodeName) throws Exception {
        FakeInubitScript script = FakeInubitScript.active();
        String name = nodeName == null ? null : nodeName.getName();
        script.record("ClusterClient.getNode", name);
        Map<String, ?> entries = script.nodeInformation(name);
        Node node = new Node(nodeName);
        node.setNodeInformation(new NodeInformation(map("nodeInformation", entries)));
        return node;
    }

    /** The scripted property {@code name}, {@code null} if none, or the scripted failure. */
    public IBISProperty getNodeConfigurationProperty(NodeName nodeName, String name)
        throws Exception {
        FakeInubitScript script = FakeInubitScript.active();
        script.record("ClusterClient.getNodeConfigurationProperty",
            nodeName == null ? null : nodeName.getName(), name);
        FakeInubitScript.Property property = script.property(name);
        return property == null ? null : new IBISProperty(name, property.type(),
            property.value(), property.displayName(), property.shortDescription(),
            property.category(), property.editable(), property.deprecated());
    }

    /** Never called by the helper (FR-032): fails the test. */
    public void setNodeConfigurationProperty(NodeName nodeName, IBISProperty property) {
        throw FakeInubitScript.active().forbidden("ClusterClient.setNodeConfigurationProperty",
            nodeName == null ? null : nodeName.getName(), property);
    }

    /** Never called by the helper: fails the test. */
    public void changeTemporaryLogLevel(String name, Object level) {
        throw FakeInubitScript.active().forbidden("ClusterClient.changeTemporaryLogLevel", name,
            String.valueOf(level));
    }

    /** Never called by the helper: fails the test. */
    public void resetTemporaryLogLevel() {
        throw FakeInubitScript.active().forbidden("ClusterClient.resetTemporaryLogLevel");
    }

    /** Never called by the helper: fails the test. */
    public void startGarbageCollector() {
        throw FakeInubitScript.active().forbidden("ClusterClient.startGarbageCollector");
    }

    /**
     * A not started thread that downloads the content the script holds for {@code fileName} to
     * {@code file}, or keeps its scripted failure; throws the failure scripted by
     * {@link FakeInubitScript#clusterDownloadFails}.
     */
    public MultipartDownloadThread clusterDownload(File file, NodeName nodeName,
        String fileName) throws Exception {
        FakeInubitScript script = FakeInubitScript.active();
        script.record("ClusterClient.clusterDownload", file,
            nodeName == null ? null : nodeName.getName(), fileName);
        script.clusterDownload();
        return new MultipartDownloadThread(fileName, file);
    }

    /** The map property {@code name} of scripted entries: texts, {@code null} or nested maps. */
    private static MapProperty map(String name, Map<String, ?> entries) {
        MapProperty map = new MapProperty(name);
        entries.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, ?> inner = (Map<String, ?>) nested;
                map.setProperty(map(key, inner));
            } else {
                map.setProperty(new IBISProperty(key, String.class, (String) value, null, null,
                    null, false, false));
            }
        });
        return map;
    }
}
