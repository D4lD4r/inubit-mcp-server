package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.nodetools.IoLogRequest;
import de.dadecker.inubit.mcp.domain.model.BinarySignature;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.node.IoLogEntry;
import de.dadecker.inubit.mcp.domain.model.node.IoLogResult;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 024 T030: {@code read_io_log} over MCP (contracts/mcp-tools-delta.md) — the contract's
 * description, annotations and input schema, the schema refusing malformed calls before the
 * service, the arguments reaching the service, and every result shape validating against
 * {@code read_io_log.output.json}. The service is replaced by a function returning a prepared
 * {@link IoLogResult}; nothing is sent anywhere.
 */
@Timeout(60)
class ReadIoLogToolTest {

    /** contracts/mcp-tools-delta.md "Description", copied here to pin the wording. */
    private static final String CONTRACT_DESCRIPTION = "Lists the logged input and output"
        + " messages of the System Connector and Message Logger Utility modules of one process run"
        + " on one INUBIT {node}, as the Workbench's input/output logging view shows them, or"
        + " returns one of them. Call it with workflow and processId (from query_logs) to get the"
        + " list; call it again with message set to a listed ref to get that message's text,"
        + " masked and cut at maxChars, with its full size; binary messages only by size and type."
        + " Input/output logging must be switched on for the process and its modules in INUBIT."
        + " Messages can contain business and personal data that masking does not cover."
        + " UNSUPPORTED: it uses an undocumented internal INUBIT interface without vendor support"
        + " or stability guarantee, for diagnosis only; it may stop working after any INUBIT"
        + " update; INUBIT requires a right of the configured user for the listing (FORBIDDEN"
        + " without it); available only for groups with unsupported.ioLogRead. Changes nothing on"
        + " INUBIT.";
    /** contracts/mcp-tools-delta.md "Output", the listing example verbatim. */
    private static final String CONTRACT_LISTING = """
        {
          "node": "test/node1",
          "inubitNode": "node1",
          "workflow": "Orders-Inbound",
          "processId": "4711",
          "total": 4,
          "matching": 4,
          "entries": [
            { "ref": "Q2Fh7tZk1oVYbP3x9w-aAA", "moduleName": "Order Connector", "moduleId": "Module-0003",
              "direction": "INPUT", "index": 1 },
            { "ref": "m0kq3T6cJYb1hX9Z2s_eRw", "moduleName": "Order Connector", "moduleId": "Module-0003",
              "direction": "OUTPUT", "index": 1 }
          ],
          "truncated": false,
          "hints": [],
          "warnings": ["Read through an unsupported, undocumented INUBIT interface; diagnosis only"]
        }""";
    /** contracts/mcp-tools-delta.md "Output", the {@code message} of a read verbatim. */
    private static final String CONTRACT_MESSAGE = """
        {
          "ref": "Q2Fh7tZk1oVYbP3x9w-aAA", "moduleName": "Order Connector", "moduleId": "Module-0003",
          "direction": "INPUT", "index": 1,
          "kind": "text", "charset": "UTF-8", "sizeBytes": 1834, "textChars": 1790,
          "text": "<order><customer>acme</customer><password>${secret:data.password}</password></order>",
          "cut": false
        }""";
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final IoLogEntry INPUT =
        IoLogEntry.parse("4711", "4711@@@Order Connector(Module-0003)@@@Input.1");
    private static final IoLogEntry OUTPUT =
        IoLogEntry.parse("4711", "4711@@@Order Connector(Module-0003)@@@Output.1");
    private static final IoLogEntry RAW = IoLogEntry.parse("4711", "4711@@@Audit Logger@@@Input.2");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<IoLogRequest> requests = new CopyOnWriteArrayList<>();
    private IoLogResult result;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        result = listing(List.of(INPUT, OUTPUT), Optional.empty(), List.of());
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        client = McpTestClient.start(List.of(new ReadIoLogTool(request -> {
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

        assertThat(tool.path("name").asString()).isEqualTo("read_io_log");
        assertThat(ReadIoLogTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("Read INUBIT module input/output logging");
        assertThat(annotations.path("readOnlyHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "workflow", "processId");
        assertThat(schema.path("properties").propertyNames()).containsExactly("target",
            "workflow", "processId", "message", "module", "maxChars");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("target").path("description").asString())
            .isEqualTo("Id of exactly one node (`<group>/<node>`)");
        assertThat(schema.path("properties").path("maxChars").path("default").asInt())
            .isEqualTo(20_000);
    }

    @Test
    void theDescriptionStatesTheWarningsOfTheUnsupportedInterface() {
        String description = ReadIoLogTool.DESCRIPTION.toLowerCase(Locale.ROOT);

        assertThat(description).contains("unsupported:", "undocumented",
            "without vendor support", "diagnosis only", "any inubit update",
            "business and personal data", "a right of the configured user",
            "unsupported.iologread", "masked", "changes nothing on inubit")
            .as("T018 spike: the listing is refused without a right of the account")
            .doesNotContain("no right beyond the login");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRefusesMalformedCallsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            with("target", "test"),
            with("target", "test/node1/x"),
            with("workflow", ""),
            with("workflow", "Orders@@@Inbound"),
            with("workflow", "Orders###Inbound"),
            with("workflow", "Orders\nInbound"),
            with("workflow", "Orders\tInbound"),
            with("workflow", "Orders\u0000Inbound"),
            with("workflow", "Orders\u007FInbound"),
            with("workflow", "Orders\u0085Inbound"),
            with("workflow", "x".repeat(201)),
            with("processId", ""),
            with("processId", "47 11"),
            with("processId", "x".repeat(65)),
            with("message", "Q2Fh7tZk1oVYbP3x9w-aA"),
            with("message", "Q2Fh7tZk1oVYbP3x9w-aAAA"),
            with("message", "Q2Fh7tZk1oVYbP3x9w+aAA"),
            with("message", "Q2Fh7tZk1oVYbP3x9w/aA="),
            with("module", ""),
            with("module", "Order\nConnector"),
            with("module", "x".repeat(201)),
            with("maxChars", 0),
            with("maxChars", 100_001),
            with("maxChars", "20000"),
            with("unknown", true)));
        for (String required : List.of("target", "workflow", "processId")) {
            Map<String, Object> without = valid();
            without.remove(required);
            calls.add(without);
        }

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("read_io_log", arguments);
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
            with("workflow", "x"),
            with("workflow", "x".repeat(200)),
            with("workflow", "Orders@@Inbound ## (ÄÖÜ)"),
            with("processId", "x".repeat(64)),
            with("message", "Q2Fh7tZk1oVYbP3x9w-aAA"),
            with("module", "Module-0003"),
            with("module", "x".repeat(200)),
            with("maxChars", 1),
            with("maxChars", 100_000));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("read_io_log", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isFalse();
        }
        assertThat(requests).hasSize(calls.size());
    }

    // --- arguments ----------------------------------------------------------------------------

    @Test
    void theArgumentsReachTheServiceAsTheRequest() {
        client.callTool("read_io_log", valid());
        Map<String, Object> all = valid();
        all.put("message", "Q2Fh7tZk1oVYbP3x9w-aAA");
        all.put("module", "Order Connector");
        all.put("maxChars", 500);
        client.callTool("read_io_log", all);

        assertThat(requests).containsExactly(
            new IoLogRequest("test/node1", "Orders-Inbound", "4711", Optional.empty(),
                Optional.empty(), OptionalInt.empty()),
            new IoLogRequest("test/node1", "Orders-Inbound", "4711",
                Optional.of("Q2Fh7tZk1oVYbP3x9w-aAA"), Optional.of("Order Connector"),
                OptionalInt.of(500)));
    }

    // --- output variants ----------------------------------------------------------------------

    @Test
    void aListingHasTheContractShapeWithoutMessage() {
        JsonNode content = call();

        assertThat(content.propertyNames()).containsExactly("node", "inubitNode", "workflow",
            "processId", "total", "matching", "entries", "truncated", "hints", "warnings");
        assertThat(content.path("node").asString()).isEqualTo("test/node1");
        assertThat(content.path("inubitNode").asString()).isEqualTo("node1");
        assertThat(content.path("workflow").asString()).isEqualTo("Orders-Inbound");
        assertThat(content.path("processId").asString()).isEqualTo("4711");
        assertThat(content.path("total").asInt()).isEqualTo(2);
        assertThat(content.path("matching").asInt()).isEqualTo(2);
        assertThat(content.path("entries")).isEqualTo(json("""
            [{"ref": "%s", "moduleName": "Order Connector", "moduleId": "Module-0003",
              "direction": "INPUT", "index": 1},
             {"ref": "%s", "moduleName": "Order Connector", "moduleId": "Module-0003",
              "direction": "OUTPUT", "index": 1}]""".formatted(INPUT.ref().value(),
            OUTPUT.ref().value())));
        assertThat(content.path("truncated").asBoolean(true)).isFalse();
        assertThat(content.path("hints")).isEmpty();
        assertThat(content.path("warnings")).extracting(JsonNode::asString)
            .containsExactly(IoLogResult.WARNING);
    }

    @Test
    void aRawModulePartHasNoModuleIdAndAnEmptyListingItsHint() {
        result = new IoLogResult(TEST, "node1", "Orders-Inbound", "4711", 3, 1, List.of(RAW),
            true, Optional.empty(), List.of(IoLogResult.RAW_MODULE_HINT),
            List.of(IoLogResult.WARNING));

        JsonNode content = call();

        assertThat(content.path("total").asInt()).isEqualTo(3);
        assertThat(content.path("matching").asInt()).isEqualTo(1);
        assertThat(content.path("truncated").asBoolean()).isTrue();
        assertThat(content.path("entries").get(0)).isEqualTo(json("""
            {"ref": "%s", "moduleName": "Audit Logger", "direction": "INPUT", "index": 2}"""
            .formatted(RAW.ref().value())));

        result = listing(List.of(), Optional.empty(), List.of(IoLogResult.EMPTY_LISTING_HINT));
        JsonNode empty = call();

        assertThat(empty.path("entries")).isEmpty();
        assertThat(empty.path("hints")).extracting(JsonNode::asString)
            .containsExactly(IoLogResult.EMPTY_LISTING_HINT);
    }

    @Test
    void aTextMessageHasTheContractShape() {
        String text = "<order><customer>acme</customer><password>${secret:data.password}"
            + "</password></order>";
        result = listing(List.of(INPUT, OUTPUT), Optional.of(new IoLogResult.Message(INPUT,
            "text", 1_834, Optional.of("UTF-8"), Optional.empty(), Optional.of(1_790),
            Optional.of(text), Optional.of(false), Optional.empty())), List.of());

        JsonNode content = call();

        assertThat(content.propertyNames()).containsExactly("node", "inubitNode", "workflow",
            "processId", "total", "matching", "entries", "truncated", "message", "hints",
            "warnings");
        assertThat(content.path("message")).isEqualTo(json(CONTRACT_MESSAGE
            .replace("Q2Fh7tZk1oVYbP3x9w-aAA", INPUT.ref().value())));
        assertThat(content.path("message").propertyNames()).containsExactly("ref", "moduleName",
            "moduleId", "direction", "index", "kind", "charset", "sizeBytes", "textChars", "text",
            "cut");
    }

    @Test
    void aBinaryAnEmptyAndATooLargeMessageCarryNoTextAndTheirHintIsTopLevel() {
        String binaryHint = "Binary content is not returned";
        result = listing(List.of(INPUT), Optional.of(new IoLogResult.Message(INPUT, "binary", 20,
            Optional.empty(), Optional.of(BinarySignature.GZIP), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(binaryHint))), List.of(binaryHint));

        JsonNode binary = call().path("message");

        assertThat(binary.path("kind").asString()).isEqualTo("binary");
        assertThat(binary.path("detected").asString()).isEqualTo("GZIP");
        assertThat(binary.path("sizeBytes").asLong()).isEqualTo(20);
        assertThat(binary.has("text")).isFalse();
        assertThat(binary.has("hint")).as("the hint is in hints").isFalse();

        result = listing(List.of(INPUT), Optional.of(new IoLogResult.Message(INPUT, "empty", 0,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(IoLogResult.Message.EMPTY_HINT))),
            List.of(IoLogResult.Message.EMPTY_HINT));
        JsonNode empty = call();

        assertThat(empty.path("message").path("kind").asString()).isEqualTo("empty");
        assertThat(empty.path("hints")).extracting(JsonNode::asString)
            .containsExactly(IoLogResult.Message.EMPTY_HINT);

        IoLogResult.Message tooLarge = IoLogResult.Message.tooLarge(INPUT, 11L * 1024 * 1024);
        result = listing(List.of(INPUT), Optional.of(tooLarge), tooLarge.hint().stream().toList());
        JsonNode large = call().path("message");

        assertThat(large.path("kind").asString()).isEqualTo("tooLarge");
        assertThat(large.path("sizeBytes").asLong()).isEqualTo(11L * 1024 * 1024);
        assertThat(large.has("text")).isFalse();
    }

    @Test
    void aConfiguredCredentialInTheTextIsScrubbed() {
        String text = "Authorization: Basic " + TestSecrets.CONFIGURED_PASSWORD;
        result = listing(List.of(INPUT), Optional.of(new IoLogResult.Message(INPUT, "text",
            text.length(), Optional.of("UTF-8"), Optional.empty(), Optional.of(text.length()),
            Optional.of(text), Optional.of(false), Optional.empty())), List.of());

        JsonNode response = client.callTool("read_io_log", valid());

        assertMatchesOutputSchema("read_io_log", response);
        TestSecrets.assertNoLeak(response.toString());
    }

    @Test
    void theContractExamplesMatchTheOutputSchema() {
        Map<String, Object> listing = map(CONTRACT_LISTING);
        Map<String, Object> read = new HashMap<>(listing);
        read.put("message", map(CONTRACT_MESSAGE));

        assertThat(errors(listing)).isNull();
        assertThat(errors(read)).isNull();
        Map<String, Object> unknownField = new HashMap<>(listing);
        unknownField.put("raw", "x");
        assertThat(errors(unknownField)).isNotNull();
        Map<String, Object> unknownKind = new HashMap<>(listing);
        Map<String, Object> message = new HashMap<>(map(CONTRACT_MESSAGE));
        message.put("kind", "unavailable");
        unknownKind.put("message", message);
        assertThat(errors(unknownKind)).isNotNull();
        Map<String, Object> unknownDirection = new HashMap<>(listing);
        unknownDirection.put("entries", List.of(Map.of("ref", "Q2Fh7tZk1oVYbP3x9w-aAA",
            "moduleName", "x", "direction", "IN", "index", 1)));
        assertThat(errors(unknownDirection)).isNotNull();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool with valid arguments; the result validates against the output schema. */
    private JsonNode call() {
        JsonNode response = client.callTool("read_io_log", valid());
        assertMatchesOutputSchema("read_io_log", response);
        return response.path("structuredContent");
    }

    private static IoLogResult listing(List<IoLogEntry> entries,
        Optional<IoLogResult.Message> message, List<String> hints) {
        return new IoLogResult(TEST, "node1", "Orders-Inbound", "4711", entries.size(),
            entries.size(), entries, false, message, hints, List.of(IoLogResult.WARNING));
    }

    private static Map<String, Object> valid() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("target", "test/node1");
        arguments.put("workflow", "Orders-Inbound");
        arguments.put("processId", "4711");
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
            .load("schemas/read_io_log.output.json"));
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
