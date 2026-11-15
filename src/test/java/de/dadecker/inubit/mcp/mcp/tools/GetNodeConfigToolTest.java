package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.nodetools.NodeConfigRequest;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigResult;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 024 T036: {@code get_node_config} over MCP (contracts/mcp-tools-delta.md) — the
 * contract's description, annotations and input schema (its {@code enum} is exactly
 * {@link NodeConfigName}), the schema refusing any other name before the service (SC-003), the
 * arguments reaching the service, and every value shape validating against
 * {@code get_node_config.output.json}. The service is replaced by a function returning a prepared
 * {@link NodeConfigResult}; nothing is sent anywhere.
 */
@Timeout(60)
class GetNodeConfigToolTest {

    /** contracts/mcp-tools-delta.md "Description", copied here to pin the wording. */
    private static final String CONTRACT_DESCRIPTION = "Returns configuration values of the"
        + " INUBIT node one {node} reaches, from a fixed list of values that matter for diagnosis"
        + " and hold no secrets: OperationMode, ClusterName, ZoneName, NodeName,"
        + " LocalMaintenanceMode, ActivateClusterManager, ClusterDebug,"
        + " ContainerShutdownOnNodeFailure, RegisterMBeans, RegisterInternalMBeans,"
        + " RuntimeDataCacheXMXPercentage, socketTimeoutInMillisecs, indexEnabled, iterationCount,"
        + " DataSourceLocation, LogsDBDataSourceLocation, RepositoryDataSourceLocation,"
        + " ClusterBindAddress, noOfRetries, retryInterval. Without names it returns all of them;"
        + " per name value, type, display name, description, category and whether it is editable"
        + " or deprecated, or that the node has no such value. Any other name is refused."
        + " UNSUPPORTED: it uses an undocumented internal INUBIT interface without vendor support"
        + " or stability guarantee, for diagnosis only; it may stop working after any INUBIT"
        + " update; INUBIT checks no right beyond the login; available only for groups with"
        + " unsupported.nodeConfigRead. Changes nothing on INUBIT.";
    /** contracts/mcp-tools-delta.md "Output", the example verbatim. */
    private static final String CONTRACT_OUTPUT = """
        {
          "node": "test/node1",
          "inubitNode": "node1",
          "values": [
            { "name": "OperationMode", "present": true, "value": "CLUSTER", "type": "SelectProperty",
              "displayName": "Operation mode", "description": "Single or cluster operation",
              "category": "Cluster", "editable": true, "deprecated": false },
            { "name": "noOfRetries", "present": false }
          ],
          "warnings": ["Read through an unsupported, undocumented INUBIT interface; diagnosis only"]
        }""";
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeConfigValue.Present OPERATION_MODE = new NodeConfigValue.Present(
        NodeConfigName.OPERATION_MODE, "CLUSTER", false, "SelectProperty", "Operation mode",
        "Single or cluster operation", "Cluster", true, false);
    private static final NodeConfigValue.Absent NO_OF_RETRIES =
        new NodeConfigValue.Absent(NodeConfigName.NO_OF_RETRIES);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<NodeConfigRequest> requests = new CopyOnWriteArrayList<>();
    private NodeConfigResult result;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        result = result(List.of(OPERATION_MODE, NO_OF_RETRIES));
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        client = McpTestClient.start(List.of(new GetNodeConfigTool(request -> {
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

        assertThat(tool.path("name").asString()).isEqualTo("get_node_config");
        assertThat(GetNodeConfigTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("Read INUBIT node configuration values");
        assertThat(annotations.path("readOnlyHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target");
        assertThat(schema.path("properties").propertyNames()).containsExactly("target", "names");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("target").path("description").asString())
            .isEqualTo("Id of exactly one node (`<group>/<node>`)");
        JsonNode names = schema.path("properties").path("names");
        assertThat(names.path("minItems").asInt()).isEqualTo(1);
        assertThat(names.path("maxItems").asInt()).isEqualTo(20);
        assertThat(names.path("uniqueItems").asBoolean(false)).isTrue();
    }

    @Test
    void theSchemaEnumIsExactlyTheNodeConfigNames() {
        JsonNode items = client.listTools().path("tools").get(0).path("inputSchema")
            .path("properties").path("names").path("items");

        assertThat(items.path("enum")).extracting(JsonNode::asString)
            .containsExactlyElementsOf(wireNames());
    }

    @Test
    void theDescriptionListsTheTwentyNamesAndTheWarningsOfTheUnsupportedInterface() {
        assertThat(GetNodeConfigTool.DESCRIPTION).contains(String.join(", ", wireNames()));
        assertThat(GetNodeConfigTool.DESCRIPTION.toLowerCase(Locale.ROOT)).contains(
            "unsupported:", "undocumented", "without vendor support", "diagnosis only",
            "any inubit update", "no right beyond the login", "unsupported.nodeconfigread",
            "any other name is refused", "changes nothing on inubit");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRefusesAnyOtherNameAndMalformedCallsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            with("target", "test"),
            with("target", "test/node1/x"),
            with("names", List.of()),
            with("names", List.of("Database")),
            with("names", List.of("ClusterPassword")),
            with("names", List.of("initialRootUserPassword")),
            with("names", List.of("IdentityProvider")),
            with("names", List.of("")),
            with("names", List.of("x".repeat(10_000))),
            with("names", List.of("operationmode")),
            with("names", List.of("ClusterName", "Database")),
            with("names", List.of("ClusterName", "ClusterName")),
            with("names", "ClusterName"),
            with("names", List.of(1)),
            with("unknown", true)));
        Map<String, Object> withoutTarget = valid();
        withoutTarget.remove("target");
        calls.add(withoutTarget);

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("get_node_config", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(response.has("structuredContent")).isFalse();
            assertThat(response.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(requests).as("the service was not called").isEmpty();
    }

    @Test
    void everyListedNameAndAllOfThemTogetherPassTheSchema() {
        for (String name : wireNames()) {
            JsonNode response = client.callTool("get_node_config", with("names", List.of(name)));
            assertThat(response.path("isError").asBoolean()).as(name).isFalse();
        }
        JsonNode all = client.callTool("get_node_config", with("names", wireNames()));

        assertThat(all.path("isError").asBoolean()).isFalse();
        assertThat(requests).hasSize(NodeConfigName.values().length + 1);
    }

    // --- arguments ----------------------------------------------------------------------------

    @Test
    void theArgumentsReachTheServiceAndNoNamesIsAnEmptyList() {
        client.callTool("get_node_config", valid());
        client.callTool("get_node_config",
            with("names", List.of("noOfRetries", "OperationMode")));

        assertThat(requests).containsExactly(
            new NodeConfigRequest("test/node1", List.of()),
            new NodeConfigRequest("test/node1", List.of("noOfRetries", "OperationMode")));
    }

    // --- output variants ----------------------------------------------------------------------

    @Test
    void aPresentAndAnAbsentValueHaveTheContractShape() {
        JsonNode content = call();

        assertThat(content).isEqualTo(json(CONTRACT_OUTPUT));
        assertThat(content.propertyNames()).containsExactly("node", "inubitNode", "values",
            "warnings");
        assertThat(content.path("values").get(0).propertyNames()).containsExactly("name",
            "present", "value", "type", "displayName", "description", "category", "editable",
            "deprecated");
    }

    @Test
    void aCutValueIsMarkedAndAFailedNameCarriesItsErrorWhileTheOthersAreReturned() {
        String longValue = "x".repeat(NodeConfigValue.MAX_VALUE_CHARS);
        result = result(List.of(new NodeConfigValue.Present(NodeConfigName.CLUSTER_NAME,
                longValue, true, "String", "Cluster name", "Name of the cluster", "Cluster", true,
                false),
            new NodeConfigValue.Failed(NodeConfigName.ZONE_NAME, ErrorCode.UNEXPECTED_RESPONSE,
                "get_node_config got an unexpected answer for ZoneName"),
            NO_OF_RETRIES));

        JsonNode values = call().path("values");

        assertThat(values.get(0).path("cut").asBoolean(false)).isTrue();
        assertThat(values.get(0).path("value").asString()).hasSize(NodeConfigValue.MAX_VALUE_CHARS);
        assertThat(values.get(1)).isEqualTo(json("""
            {"name": "ZoneName", "error": {"code": "UNEXPECTED_RESPONSE",
              "message": "get_node_config got an unexpected answer for ZoneName"}}"""));
        assertThat(values.get(2)).isEqualTo(json("""
            {"name": "noOfRetries", "present": false}"""));
    }

    @Test
    void aConfiguredCredentialInAValueIsScrubbed() {
        result = result(List.of(new NodeConfigValue.Present(NodeConfigName.DATA_SOURCE_LOCATION,
            "jdbc:x;user=ibis;password=" + TestSecrets.CONFIGURED_PASSWORD, false, "String",
            "Data source", "JNDI name", "Database", true, false)));

        JsonNode response = client.callTool("get_node_config", valid());

        assertMatchesOutputSchema("get_node_config", response);
        TestSecrets.assertNoLeak(response.toString());
    }

    @Test
    void theContractExampleMatchesTheOutputSchema() {
        Map<String, Object> example = map(CONTRACT_OUTPUT);

        assertThat(errors(example)).isNull();
        Map<String, Object> unknownField = new HashMap<>(example);
        unknownField.put("raw", "x");
        assertThat(errors(unknownField)).isNotNull();
        Map<String, Object> unknownValueField = new HashMap<>(example);
        unknownValueField.put("values", List.of(Map.of("name", "noOfRetries", "present", false,
            "raw", "x")));
        assertThat(errors(unknownValueField)).isNotNull();
        Map<String, Object> withoutName = new HashMap<>(example);
        withoutName.put("values", List.of(Map.of("present", false)));
        assertThat(errors(withoutName)).isNotNull();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool with valid arguments; the result validates against the output schema. */
    private JsonNode call() {
        JsonNode response = client.callTool("get_node_config", valid());
        assertMatchesOutputSchema("get_node_config", response);
        return response.path("structuredContent");
    }

    private static NodeConfigResult result(List<NodeConfigValue> values) {
        return new NodeConfigResult(TEST, "node1", values, List.of(NodeConfigResult.WARNING));
    }

    private static List<String> wireNames() {
        return Arrays.stream(NodeConfigName.values()).map(NodeConfigName::wireName).toList();
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
            .load("schemas/get_node_config.output.json"));
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
