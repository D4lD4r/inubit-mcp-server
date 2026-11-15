package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inubit.ibis.client.rest.InubitClientFactory;
import com.inubit.ibis.configuration.utils.ConnectionData;
import com.inubit.ibis.server.cache.NodeName;
import com.inubit.ibis.server.cluster.ClusterClient;
import com.inubit.ibis.server.cluster.data.Node;
import com.inubit.ibis.server.cluster.data.NodeList;
import com.inubit.ibis.server.datafacade.NodeInformation;
import com.inubit.ibis.soap.IBISSoapClient;
import com.inubit.ibis.utils.I_IBISClientInterface;
import com.inubit.ibis.utils.property.type.IBISProperty;
import com.inubit.ibis.utils.property.type.MapProperty;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Call;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Failure;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Member;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Property;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fake vendor classes of feature 024 (research D-27) under {@code com.inubit.ibis}: the node
 * list, a node's information map, node configuration properties and the I/O logging listing
 * answer as a {@link FakeInubitScript} scripts them; the shared recorder lists every vendor call
 * in order, and the methods the helper must never call fail the test.
 */
class FakeVendorClassesTest {

    private static final String USER = "jdoe";
    private static final String OWN = "node-0001";
    private static final String OTHER = "node-0002";
    private static final String LISTING = "inputOutputMessageDetails";
    private static final String OBJECT = "p-0001@@@Workflow-0001";
    private static final String ENTRY = "p-0001@@@Mapper(m-0001)@@@Input.1";

    @Test
    void getNodesListsTheScriptedMembersInOrder() throws Exception {
        FakeInubitScript script = new FakeInubitScript().members(
            new Member(OWN, "ACTIVE", "8.1.17", "zone-a", "id-0001", 3_600_000L),
            Member.unanswered(OTHER),
            new Member("node-0003", "MAINTENANCE", "8.1.17", "zone-b", "id-0003", 0L));
        NodeList nodes;
        try (FakeInubitScript.Activation ignored = script.activate()) {
            nodes = cluster(soapClient()).getNodes();
        }

        assertThat(nodes).hasSize(3);
        Node own = nodes.get(0);
        assertThat(own.getNodeName().getName()).isEqualTo(OWN);
        assertThat(own.getStatus()).isEqualTo(Node.Status.ACTIVE);
        assertThat(own.getVersion()).isEqualTo("8.1.17");
        assertThat(own.getZoneName()).isEqualTo("zone-a");
        assertThat(own.getNodeID()).isEqualTo("id-0001");
        assertThat(own.getUptime()).isEqualTo(3_600_000L);
        Node unanswered = nodes.get(1);
        assertThat(unanswered.getNodeName().getName()).isEqualTo(OTHER);
        assertThat(unanswered.getStatus()).isNull();
        assertThat(unanswered.getVersion()).isNull();
        assertThat(unanswered.getZoneName()).isNull();
        assertThat(unanswered.getNodeID()).isNull();
        assertThat(unanswered.getUptime()).isZero();
        assertThat(unanswered.getNodeInformation()).isNull();
        assertThat(nodes.get(2).getStatus()).isEqualTo(Node.Status.MAINTENANCE);
        assertThat(nodes.getByName(OTHER)).isSameAs(unanswered);
        assertThat(nodes.getByName("node-0009")).isNull();
        assertThat(Node.Status.values()).extracting(Enum::name)
            .containsExactly("ACTIVE", "MAINTENANCE", "OFFLINE");
    }

    @Test
    void getNodeOfANameReturnsItsNodeInformationWithNestedMaps() throws Exception {
        Map<String, Object> jdk = new LinkedHashMap<>();
        jdk.put("Version", "17.0.99");
        jdk.put("Vendor", null);
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("ServerVersion", "8.1.17");
        entries.put("JDK", jdk);
        entries.put("InstallDir", "/opt/inubit/example");
        FakeInubitScript script = new FakeInubitScript().nodeInformation(OTHER, entries);
        Node node;
        try (FakeInubitScript.Activation ignored = script.activate()) {
            node = cluster(soapClient()).getNode(NodeName.nodeName(OTHER));
        }

        assertThat(node.getNodeName().getName()).isEqualTo(OTHER);
        NodeInformation information = node.getNodeInformation();
        MapProperty map = information.getNodeInformationMap();
        assertThat(map.getPropertyNames()).containsExactly("ServerVersion", "JDK", "InstallDir");
        assertThat(map.get("ServerVersion").getStringValue()).isEqualTo("8.1.17");
        assertThat(map.get("JDK")).isInstanceOf(MapProperty.class);
        MapProperty nested = (MapProperty) map.get("JDK");
        assertThat(nested.getPropertyNames()).containsExactly("Version", "Vendor");
        assertThat(nested.get("Version").getStringValue()).isEqualTo("17.0.99");
        assertThat(nested.get("Vendor").getStringValue()).isNull();
        assertThat(map.get("missing")).isNull();
        assertThat(script.calls()).contains(call("ClusterClient.getNode", OTHER));
    }

    @Test
    void getNodeConfigurationPropertyAnswersTheScriptedPropertyOrNull() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .node(OWN)
            .property("ClusterName", new Property(String.class, "cluster-a", "Cluster name",
                "Name of the cluster", "Cluster", true, false));
        IBISProperty property;
        IBISProperty absent;
        try (FakeInubitScript.Activation ignored = script.activate()) {
            ClusterClient cluster = cluster(soapClient());
            NodeName own = cluster.getNode().getNodeName();
            property = cluster.getNodeConfigurationProperty(own, "ClusterName");
            absent = cluster.getNodeConfigurationProperty(own, "noOfRetries");
        }

        assertThat(property.getName()).isEqualTo("ClusterName");
        assertThat(property.getStringValue()).isEqualTo("cluster-a");
        assertThat(property.getType()).isEqualTo(String.class);
        assertThat(property.getDisplayName()).isEqualTo("Cluster name");
        assertThat(property.getShortDescription()).isEqualTo("Name of the cluster");
        assertThat(property.getCategory()).isEqualTo("Cluster");
        assertThat(property.isEditable()).isTrue();
        assertThat(property.isDeprecated()).isFalse();
        assertThat(absent).as("the server answers null for an unknown name").isNull();
        assertThat(script.calls()).endsWith(
            call("ClusterClient.getNodeConfigurationProperty", OWN, "ClusterName"),
            call("ClusterClient.getNodeConfigurationProperty", OWN, "noOfRetries"));
    }

    @Test
    void getObjectAnswersTheScriptedListingOnBothClients() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .object(OBJECT, LISTING, ENTRY + "###p-0001@@@Mapper(m-0001)@@@Output.1")
            .object("p-0001@@@Workflow-0002", LISTING, "");
        String soap;
        String rest;
        String empty;
        try (FakeInubitScript.Activation ignored = script.activate()) {
            soap = soapClient().getObject(OBJECT, USER, LISTING);
            I_IBISClientInterface restClient = restClient();
            rest = restClient.getObject(OBJECT, USER, LISTING);
            empty = restClient.getObject("p-0001@@@Workflow-0002", USER, LISTING);
        }

        assertThat(soap).isEqualTo(ENTRY + "###p-0001@@@Mapper(m-0001)@@@Output.1");
        assertThat(rest).isEqualTo(soap);
        assertThat(empty).isEmpty();
        assertThat(script.calls()).contains(
            call("IBISSoapClient.getObject", OBJECT, USER, LISTING),
            call("RestClient.getObject", OBJECT, USER, LISTING),
            call("RestClient.getObject", "p-0001@@@Workflow-0002", USER, LISTING));
    }

    @Test
    void scriptedFailuresAreThrownAfterTheCallIsRecorded() throws Exception {
        Failure refused = new Failure(ConnectException.class.getName(), "connection refused");
        FakeInubitScript script = new FakeInubitScript()
            .node(OWN)
            .membersFail(new Failure(IllegalStateException.class.getName(), "cluster down"),
                refused)
            .nodeInformationFails(OTHER, refused)
            .propertyFails("ZoneName", refused)
            .objectFails(OBJECT, LISTING, refused);
        try (FakeInubitScript.Activation ignored = script.activate()) {
            IBISSoapClient client = soapClient();
            ClusterClient cluster = cluster(client);
            NodeName own = cluster.getNode().getNodeName();
            assertThatThrownBy(cluster::getNodes)
                .isInstanceOf(IllegalStateException.class).hasMessage("cluster down")
                .hasCauseInstanceOf(ConnectException.class);
            assertThatThrownBy(() -> cluster.getNode(NodeName.nodeName(OTHER)))
                .isInstanceOf(ConnectException.class);
            assertThatThrownBy(() -> cluster.getNodeConfigurationProperty(own, "ZoneName"))
                .isInstanceOf(ConnectException.class);
            assertThatThrownBy(() -> client.getObject(OBJECT, USER, LISTING))
                .isInstanceOf(ConnectException.class);
        }
        assertThat(script.calls()).extracting(Call::name).endsWith("ClusterClient.getNodes",
            "ClusterClient.getNode", "ClusterClient.getNodeConfigurationProperty",
            "IBISSoapClient.getObject");
    }

    @Test
    void unscriptedAnswersAreFailuresNamingWhatIsMissing() throws Exception {
        FakeInubitScript script = new FakeInubitScript();
        try (FakeInubitScript.Activation ignored = script.activate()) {
            IBISSoapClient client = soapClient();
            ClusterClient cluster = cluster(client);
            assertThatThrownBy(cluster::getNodes).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no members scripted");
            assertThatThrownBy(() -> cluster.getNode(NodeName.nodeName(OTHER)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(OTHER);
            assertThatThrownBy(() -> client.getObject(OBJECT, USER, LISTING))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(OBJECT);
        }
    }

    @Test
    void theRecorderListsEveryVendorCallOfAnOperationInOrder() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .node(OWN)
            .members(new Member(OWN, "ACTIVE", "8.1.17", "zone-a", "id-0001", 1L))
            .nodeInformation(OWN, Map.of("ServerVersion", "8.1.17"))
            .property("NodeName", new Property(String.class, OWN, "-", "-", "-", false, false))
            .object(OBJECT, LISTING, ENTRY);
        try (FakeInubitScript.Activation ignored = script.activate()) {
            IBISSoapClient client = soapClient();
            client.loginUserCli(USER, TestSecrets.CONFIGURED_PASSWORD);
            ClusterClient cluster = cluster(client);
            NodeName own = cluster.getNode().getNodeName();
            cluster.getNodes();
            cluster.getNode(own);
            cluster.getNodeConfigurationProperty(own, "NodeName");
            client.getObject(OBJECT, USER, LISTING);
            client.logoutUser(USER);
        }

        assertThat(script.calls()).containsExactly(
            call("IBISSoapClient.<init>", "https://inubit.example.test/ibis?isCli", "null",
                "null", "null", "null", "true"),
            call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "IBISSoapClient", USER, "fake-client-uid-0001", "null"),
            call("ClusterClient.getNode"),
            call("ClusterClient.getNodes"),
            call("ClusterClient.getNode", OWN),
            call("ClusterClient.getNodeConfigurationProperty", OWN, "NodeName"),
            call("IBISSoapClient.getObject", OBJECT, USER, LISTING),
            call("IBISSoapClient.logoutUser", USER));
    }

    @Test
    void forbiddenClusterMethodsFailTheTestAndAreRecorded() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(OWN);
        try (FakeInubitScript.Activation ignored = script.activate()) {
            ClusterClient cluster = cluster(soapClient());
            NodeName own = cluster.getNode().getNodeName();
            IBISProperty property = new IBISProperty("ClusterName", String.class, "cluster-b",
                null, null, null, true, false);
            assertThatThrownBy(() -> cluster.setNodeConfigurationProperty(own, property))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ClusterClient.setNodeConfigurationProperty");
            assertThatThrownBy(() -> cluster.changeTemporaryLogLevel("com.inubit.ibis", "DEBUG"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ClusterClient.changeTemporaryLogLevel");
            assertThatThrownBy(cluster::resetTemporaryLogLevel)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ClusterClient.resetTemporaryLogLevel");
            assertThatThrownBy(cluster::startGarbageCollector)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ClusterClient.startGarbageCollector");
        }
        assertThat(script.calls()).extracting(Call::name).endsWith(
            "ClusterClient.setNodeConfigurationProperty", "ClusterClient.changeTemporaryLogLevel",
            "ClusterClient.resetTemporaryLogLevel", "ClusterClient.startGarbageCollector");
    }

    /** The scripted answers survive the serialization a child JVM's script goes through. */
    @Test
    void theScriptedAnswersAreSerializable() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("Name", "zone-a");
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("Zone", nested);
        FakeInubitScript copy = copy(new FakeInubitScript()
            .members(new Member(OWN, "OFFLINE", "9.0.10", "zone-a", "id-0001", 5L))
            .nodeInformation(OWN, entries)
            .property("ZoneName", new Property(String.class, "zone-a", "Zone", "Zone of the node",
                "Cluster", true, false))
            .object(OBJECT, LISTING, ENTRY));
        try (FakeInubitScript.Activation ignored = copy.activate()) {
            ClusterClient cluster = cluster(soapClient());
            assertThat(cluster.getNodes().get(0).getStatus()).isEqualTo(Node.Status.OFFLINE);
            MapProperty zone = (MapProperty) cluster.getNode(NodeName.nodeName(OWN))
                .getNodeInformation().getNodeInformationMap().get("Zone");
            assertThat(zone.get("Name").getStringValue()).isEqualTo("zone-a");
            assertThat(cluster.getNodeConfigurationProperty(NodeName.nodeName(OWN), "ZoneName")
                .getShortDescription()).isEqualTo("Zone of the node");
            assertThat(restClient().getObject(OBJECT, USER, LISTING)).isEqualTo(ENTRY);
        }
    }

    private static IBISSoapClient soapClient() {
        return new IBISSoapClient("https://inubit.example.test/ibis?isCli", null, null, null,
            null, true);
    }

    private static I_IBISClientInterface restClient() {
        ConnectionData connection = new ConnectionData();
        connection.setServerURL("https://inubit.example.test/ibis");
        return new InubitClientFactory().getRestInstance(connection);
    }

    private static ClusterClient cluster(I_IBISClientInterface client) {
        return new ClusterClient(client, USER, "fake-client-uid-0001", null);
    }

    private static FakeInubitScript copy(FakeInubitScript script) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(script);
        }
        try (ObjectInputStream in = new ObjectInputStream(
            new ByteArrayInputStream(bytes.toByteArray()))) {
            return (FakeInubitScript) in.readObject();
        }
    }

    private static Call call(String name, String... arguments) {
        return new Call(name, Arrays.asList(arguments));
    }
}
