package com.inubit.ibis.server.cluster;

import com.inubit.ibis.configuration.utils.io.MultipartDownloadThread;
import com.inubit.ibis.server.cache.NodeName;
import com.inubit.ibis.server.cluster.data.Node;
import com.inubit.ibis.utils.I_IBISClientInterface;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import java.io.File;

/**
 * Test fake (feature 021, research "Spike findings", D-22) of the vendor's cluster client, driven
 * by the active {@link FakeInubitScript}, which records every call; a node name argument is
 * recorded as its name.
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
}
