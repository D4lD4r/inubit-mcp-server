package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.nodetools.ClusterNodesRequest;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNode;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodesResult;
import de.dadecker.inubit.mcp.domain.model.node.NodeDetail;
import de.dadecker.inubit.mcp.domain.model.node.NodeStatus;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 024 T022: {@code list_cluster_nodes} over MCP (contracts/mcp-tools-delta.md) — the
 * contract's description, annotations and input schema, the schema refusing malformed calls before
 * the service, the arguments reaching the service, and every result shape validating against
 * {@code list_cluster_nodes.output.json}. The service is replaced by a function returning a
 * prepared {@link ClusterNodesResult}; nothing is sent anywhere.
 */
@Timeout(60)
class ListClusterNodesToolTest {

    /** contracts/mcp-tools-delta.md "Description", copied here to pin the wording. */
    private static final String CONTRACT_DESCRIPTION = "Lists every INUBIT node the cluster of one"
        + " {node} knows, as the Workbench's Cluster Manager shows it: name, status (ACTIVE,"
        + " MAINTENANCE, OFFLINE, or UNKNOWN for a node that does not answer), INUBIT version,"
        + " zone, node ID and uptime, and names the node that answered. With nodeName it also"
        + " returns that node's details (server, JDK, operating system, memory, cluster and zone"
        + " names, internal addresses and file paths), masked. Use it to check whether a cluster is"
        + " complete and healthy, including nodes this profile does not configure. UNSUPPORTED: it"
        + " uses an undocumented internal INUBIT interface without vendor support or stability"
        + " guarantee, for diagnosis only; it may stop working after any INUBIT update; INUBIT"
        + " checks no right beyond the login; available only for groups with"
        + " unsupported.nodeStatusRead. Changes nothing on INUBIT.";
    /** contracts/mcp-tools-delta.md "Output", the example verbatim. */
    private static final String CONTRACT_EXAMPLE = """
        {
          "node": "test/node1",
          "inubitNode": "node1",
          "total": 2,
          "truncated": false,
          "nodes": [
            { "name": "node1", "status": "ACTIVE", "version": "8.1.17", "zone": "zone-a",
              "nodeId": "a1b2c3", "uptime": "PT76H5M12S", "uptimeSeconds": 273912 },
            { "name": "node2", "status": "UNKNOWN" }
          ],
          "details": {
            "node": "node1",
            "available": true,
            "pairs": [
              { "key": "JDKVersion", "value": "17.0.9" },
              { "key": "InternalNodeAddress", "value": "10.0.0.11:7800" }
            ],
            "truncated": false
          },
          "hints": [],
          "warnings": [
            "Read through an unsupported, undocumented INUBIT interface; diagnosis only",
            "The node details contain internal addresses and file paths of the INUBIT installation"
          ]
        }""";
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final ClusterNode NODE1 = new ClusterNode("node1", NodeStatus.ACTIVE,
        Optional.empty(), Optional.of("8.1.17"), Optional.of("zone-a"), Optional.of("a1b2c3"),
        Optional.of(Duration.ofSeconds(273_912)));
    private static final ClusterNode NODE2 = new ClusterNode("node2", NodeStatus.UNKNOWN,
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<ClusterNodesRequest> requests = new CopyOnWriteArrayList<>();
    private ClusterNodesResult result;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        result = result(new ClusterNodes("node1", List.of(NODE1, NODE2), 2, false,
            Optional.empty()), List.of(), List.of(ClusterNodesResult.WARNING));
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        client = McpTestClient.start(List.of(new ListClusterNodesTool(request -> {
            requests.add(request);
            return result;
        })), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement -------------------------------------------------------------------------

    @Test
    void theToolIsAReadOnlyOpenWorldToolWithTheContractDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("list_cluster_nodes");
        assertThat(ListClusterNodesTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("List the INUBIT cluster's nodes");
        assertThat(annotations.path("readOnlyHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target");
        assertThat(schema.path("properties").propertyNames())
            .containsExactly("target", "nodeName");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("target").path("description").asString())
            .isEqualTo("Id of exactly one node (`<group>/<node>`)");
    }

    @Test
    void theDescriptionStatesTheWarningsOfTheUnsupportedInterface() {
        String description = ListClusterNodesTool.DESCRIPTION.toLowerCase(Locale.ROOT);

        assertThat(description).contains("unsupported:", "undocumented",
            "without vendor support", "diagnosis only", "any inubit update",
            "no right beyond the login", "unsupported.nodestatusread", "masked",
            "changes nothing on inubit");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRefusesMalformedCallsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            with("target", "test"),
            with("target", "Test/node1"),
            with("target", "test/node1/x"),
            with("nodeName", ""),
            with("nodeName", "node 1"),
            with("nodeName", "node/1"),
            with("nodeName", "node1\n"),
            with("nodeName", "nöde1"),
            with("nodeName", "x".repeat(129)),
            with("nodeName", 1),
            with("unknown", true)));
        Map<String, Object> withoutTarget = valid();
        withoutTarget.remove("target");
        calls.add(withoutTarget);

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("list_cluster_nodes", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(response.has("structuredContent")).isFalse();
            assertThat(response.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(requests).as("the service was not called").isEmpty();
    }

    @Test
    void theLimitsOfTheSchemaAreInclusive() {
        List<Map<String, Object>> calls = List.of(
            with("nodeName", "x"),
            with("nodeName", "x".repeat(128)),
            with("nodeName", "Node_1.zone-a:7800"));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("list_cluster_nodes", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isFalse();
        }
        assertThat(requests).hasSize(calls.size());
    }

    // --- arguments ----------------------------------------------------------------------------

    @Test
    void theArgumentsReachTheServiceAsTheRequest() {
        client.callTool("list_cluster_nodes", valid());
        client.callTool("list_cluster_nodes", with("nodeName", "node2"));

        assertThat(requests).containsExactly(
            new ClusterNodesRequest("test/node1", Optional.empty()),
            new ClusterNodesRequest("test/node1", Optional.of("node2")));
    }

    // --- output variants ----------------------------------------------------------------------

    @Test
    void aListingHasTheContractShapeWithoutDetails() {
        JsonNode content = call();

        assertThat(content.propertyNames()).containsExactly("node", "inubitNode", "total",
            "truncated", "nodes", "hints", "warnings");
        assertThat(content.path("node").asString()).isEqualTo("test/node1");
        assertThat(content.path("inubitNode").asString()).isEqualTo("node1");
        assertThat(content.path("total").asInt()).isEqualTo(2);
        assertThat(content.path("truncated").asBoolean(true)).isFalse();
        assertThat(content.path("nodes")).isEqualTo(json("""
            [{"name": "node1", "status": "ACTIVE", "version": "8.1.17", "zone": "zone-a",
              "nodeId": "a1b2c3", "uptime": "PT76H5M12S", "uptimeSeconds": 273912},
             {"name": "node2", "status": "UNKNOWN"}]"""));
        assertThat(content.path("hints")).isEmpty();
        assertThat(content.path("warnings")).extracting(JsonNode::asString)
            .containsExactly(ClusterNodesResult.WARNING);
    }

    @Test
    void aCutListingAndAnUnknownStatusTextAreNamed() {
        ClusterNode odd = new ClusterNode("node3", NodeStatus.UNKNOWN, Optional.of("STARTING"),
            Optional.of("8.1.17"), Optional.empty(), Optional.empty(),
            Optional.of(Duration.ofMillis(1_500)));
        result = result(new ClusterNodes("node1", List.of(NODE1, odd), 3, true, Optional.empty()),
            List.of(), List.of(ClusterNodesResult.WARNING));

        JsonNode content = call();

        assertThat(content.path("total").asInt()).isEqualTo(3);
        assertThat(content.path("truncated").asBoolean()).isTrue();
        assertThat(content.path("nodes").get(1)).isEqualTo(json("""
            {"name": "node3", "status": "UNKNOWN", "rawStatus": "STARTING", "version": "8.1.17",
             "uptime": "PT1.5S", "uptimeSeconds": 1}"""));
    }

    @Test
    void availableDetailsCarryTheirPairsTheCutMarkAndTheHint() {
        ClusterNodes.Available details = new ClusterNodes.Available("node2", List.of(
            new NodeDetail("JDKVersion", "17.0.9", false),
            new NodeDetail("ClassPath", "x".repeat(2_000), true)), true);
        result = result(new ClusterNodes("node1", List.of(NODE1, NODE2), 2, false,
            Optional.of(details)), List.of(ClusterNodesResult.DETAILS_TRUNCATED_HINT),
            List.of(ClusterNodesResult.WARNING, ClusterNodesResult.DETAILS_WARNING));

        JsonNode content = call();

        assertThat(content.propertyNames()).containsExactly("node", "inubitNode", "total",
            "truncated", "nodes", "details", "hints", "warnings");
        assertThat(content.path("details")).isEqualTo(json("""
            {"node": "node2", "available": true,
             "pairs": [{"key": "JDKVersion", "value": "17.0.9"},
                       {"key": "ClassPath", "value": "%s", "cut": true}],
             "truncated": true}""".formatted("x".repeat(2_000))));
        assertThat(content.path("hints")).extracting(JsonNode::asString)
            .containsExactly(ClusterNodesResult.DETAILS_TRUNCATED_HINT);
        assertThat(content.path("warnings")).extracting(JsonNode::asString)
            .containsExactly(ClusterNodesResult.WARNING, ClusterNodesResult.DETAILS_WARNING);
    }

    @Test
    void unavailableDetailsNameTheReasonAndTheListIsStillReturned() {
        result = result(new ClusterNodes("node1", List.of(NODE1, NODE2), 2, false,
            Optional.of(new ClusterNodes.Unavailable("node2", "INUBIT could not read the details"
                + " (" + TestSecrets.CONFIGURED_PASSWORD + ")"))), List.of(),
            List.of(ClusterNodesResult.WARNING));

        JsonNode response = client.callTool("list_cluster_nodes", with("nodeName", "node2"));

        assertMatchesOutputSchema("list_cluster_nodes", response);
        JsonNode content = response.path("structuredContent");
        assertThat(content.path("nodes")).hasSize(2);
        assertThat(content.path("details").propertyNames())
            .containsExactly("node", "available", "reason");
        assertThat(content.path("details").path("available").asBoolean(true)).isFalse();
        assertThat(content.path("details").path("reason").asString())
            .startsWith("INUBIT could not read the details (");
        TestSecrets.assertNoLeak(response.toString());
    }

    @Test
    void theContractExampleMatchesTheOutputSchema() {
        Map<String, Object> example = map(CONTRACT_EXAMPLE);

        assertThat(errors(example)).isNull();
        Map<String, Object> unknownField = new HashMap<>(example);
        unknownField.put("raw", "x");
        assertThat(errors(unknownField)).isNotNull();
        Map<String, Object> unknownStatus = new HashMap<>(example);
        unknownStatus.put("nodes", List.of(Map.of("name", "node1", "status", "STARTING")));
        assertThat(errors(unknownStatus)).isNotNull();
        Map<String, Object> mixedDetails = new HashMap<>(example);
        mixedDetails.put("details", Map.of("node", "node1", "available", false,
            "reason", "x", "pairs", List.of()));
        assertThat(errors(mixedDetails)).isNotNull();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool with valid arguments; the result validates against the output schema. */
    private JsonNode call() {
        JsonNode response = client.callTool("list_cluster_nodes", valid());
        assertMatchesOutputSchema("list_cluster_nodes", response);
        return response.path("structuredContent");
    }

    private static ClusterNodesResult result(ClusterNodes cluster, List<String> hints,
        List<String> warnings) {
        return new ClusterNodesResult(TEST, cluster, hints, warnings);
    }

    private static Map<String, Object> valid() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("target", "test/node1");
        return arguments;
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> arguments = valid();
        arguments.put(key, value);
        return arguments;
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String text) {
        return JSON.readValue(text, Map.class);
    }

    /** The validation errors of {@code result}, or {@code null} if it is valid. */
    private static String errors(Map<String, Object> result) {
        Map<String, Object> schema = map(SchemaResources.forClasspath()
            .load("schemas/list_cluster_nodes.output.json"));
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
