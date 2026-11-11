package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.QueueEntryService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 020 T025: {@code read_queue_entry} over MCP (contracts/mcp-tools-delta.md) — the
 * contract's description, annotations and input schema, the schema rejecting malformed calls
 * before the service, the arguments reaching the service, and every result shape validating
 * against {@code read_queue_entry.output.json}. The real {@link QueueEntryService} runs over fakes
 * of the queueLog lookup and the content port; nothing is sent anywhere.
 */
@Timeout(60)
class ReadQueueEntryToolTest {

    /** The description of contracts/mcp-tools-delta.md, rendered for the test profile. */
    private static final String DESCRIPTION = "[acme] Reads the content of one INUBIT Queue"
        + " Manager entry in state ERROR: the entry's metadata, the data stream that ended in the"
        + " queue (text cut at maxChars; binary content only as size and kind) and the workflow"
        + " variables, with credentials masked as ${secret:…}. Use it after find_processes or"
        + " query_logs (logType queueLog) showed an ERROR entry whose cause is not in the error"
        + " text. UNSUPPORTED: uses an undocumented internal INUBIT interface (the Workbench's"
        + " Queue Manager \"View file\") without vendor support or stability guarantee; it may"
        + " stop working or behave differently after any INUBIT update; for diagnosis only."
        + " Returns business data of the entry; credentials under unusual names may not be"
        + " masked. Available only for groups with unsupported.queueEntryRead.";
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final String PID = "1234";
    private static final String FILE = "Module-0001(4711)@1234@0a1b2c3d";
    private static final String OTHER_FILE = "Module-0003(4712)@1234@0a1b2c3e";
    private static final Instant SINCE = Instant.parse("2026-11-10T08:15:30Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<String> lookups = new CopyOnWriteArrayList<>();
    private final List<QueueEntryRef> reads = new CopyOnWriteArrayList<>();
    private List<QueueEntryRow> rows = List.of(errorRow(FILE));
    private RawEntryContent content = content("<Order><Id>Order-0001</Id></Order>",
        List.of());
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        MutableClock clock = new MutableClock(Instant.parse("2026-11-10T09:00:00Z"));
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        QueueEntryService service = new QueueEntryService(new TargetResolver(List.of(TEST, PROD)),
            node -> node.equals(TEST),
            FakeVersionDetection.gateWithoutClients(versions, new FakeVersionDetection(versions),
                CapabilityMatrix.production()),
            node -> lookup(), node -> (ref, version, parts) -> {
                reads.add(ref);
                return content;
            }, scrubber::scrub);
        client = McpTestClient.start(List.of(new ReadQueueEntryTool(service)), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement -------------------------------------------------------------------------

    @Test
    void theToolIsAReadOnlyIdempotentOpenWorldToolWithTheContractDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("read_queue_entry");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString()).isEqualTo("Read a Queue Manager entry");
        assertThat(annotations.path("readOnlyHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "processId");
        assertThat(schema.path("properties").propertyNames())
            .containsExactly("target", "processId", "fileName", "maxChars");
        assertThat(schema.path("properties").path("maxChars").path("default").asInt())
            .isEqualTo(20_000);
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("target").path("description").asString())
            .isEqualTo("Id of exactly one node (`<group>/<node>`); group ids are rejected");
    }

    @Test
    void theDescriptionStatesTheWarningsOfTheUnsupportedInterface() {
        String description = ReadQueueEntryTool.DESCRIPTION.toLowerCase(Locale.ROOT);

        assertThat(description).contains("unsupported:", "undocumented",
            "without vendor support", "any inubit update", "diagnosis only", "business data");
    }

    /** T040: docs/tools.md quotes the description as announced for a profile {@code acme}. */
    @Test
    void docsToolsQuotesTheDescriptionAsRendered() throws IOException {
        String tools = Files.readString(Path.of("docs/tools.md"))
            .replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");

        assertThat(tools).contains(
            "[acme] " + Terminology.DEFAULT.render(ReadQueueEntryTool.DESCRIPTION));
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRejectsMalformedCallsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            with("target", "test"),
            with("target", "Test/node1"),
            with("target", "test/node1/x"),
            with("processId", "abc"),
            with("processId", "0"),
            with("processId", "01234"),
            with("processId", "12345678901234567890"),
            with("processId", "0f0e0d0c-0000-4000-8000-000000000001"),
            with("fileName", "Module-0001/x"),
            with("fileName", "Module-0001\\x"),
            with("fileName", "Module-0001\u0000x"),
            with("fileName", "Module-0001\nx"),
            with("fileName", "Module-0001\u0085x"),
            with("fileName", ""),
            with("fileName", "x".repeat(256)),
            with("maxChars", 0),
            with("maxChars", 100_001),
            with("unknown", true)));
        Map<String, Object> withoutProcessId = with("target", "test/node1");
        withoutProcessId.remove("processId");
        calls.add(withoutProcessId);

        for (Map<String, Object> arguments : calls) {
            JsonNode result = client.callTool("read_queue_entry", arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
            assertThat(result.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(lookups).as("the service sent nothing").isEmpty();
        assertThat(reads).isEmpty();
    }

    @Test
    void theLimitsOfTheSchemaAreInclusive() {
        rows = List.of(errorRow("x".repeat(255)));

        assertMatchesOutputSchema("read_queue_entry",
            client.callTool("read_queue_entry", with("fileName", "x".repeat(255))));
        rows = List.of(errorRow(FILE));
        assertMatchesOutputSchema("read_queue_entry",
            client.callTool("read_queue_entry", with("maxChars", 1)));
        assertMatchesOutputSchema("read_queue_entry",
            client.callTool("read_queue_entry", with("maxChars", 100_000)));
        assertMatchesOutputSchema("read_queue_entry",
            client.callTool("read_queue_entry", with("processId", "1234567890123456789")));
    }

    // --- arguments and results ----------------------------------------------------------------

    @Test
    void fileNameAndMaxCharsReachTheService() {
        rows = List.of(errorRow(FILE), errorRow(OTHER_FILE));
        Map<String, Object> arguments = with("fileName", OTHER_FILE);
        arguments.put("maxChars", 7);

        JsonNode result = client.callTool("read_queue_entry", arguments);

        assertMatchesOutputSchema("read_queue_entry", result);
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("node").asString()).isEqualTo("test/node1");
        assertThat(structured.path("entry").path("fileName").asString()).isEqualTo(OTHER_FILE);
        assertThat(structured.path("data").path("text").asString()).isEqualTo("<Order>");
        assertThat(structured.path("data").path("truncated").asBoolean()).isTrue();
        assertThat(reads).extracting(QueueEntryRef::fileName).containsExactly(OTHER_FILE);
    }

    @Test
    void aTextEntryWithVariablesMatchesTheOutputSchemaWithoutLeaks() {
        content = content("<soap:Envelope><Password>" + TestSecrets.CONFIGURED_PASSWORD
            + "</Password><Order>Order-0001</Order></soap:Envelope>", List.of(
                new RawVariable("ISFileSize", Optional.of("Long"), "772"),
                new RawVariable("apiToken", Optional.empty(),
                    TestSecrets.CREDENTIAL_PAYLOAD_VALUE),
                new RawVariable("n".repeat(250), Optional.empty(), "v"),
                new RawVariable("var.order", Optional.of("XmlDocument"),
                    "<Order>" + "x".repeat(3_000) + "</Order>"),
                new RawVariable("var.archive", Optional.empty(), "PK\u0003\u0004\u0000\u0000"),
                new RawVariable("var.empty", Optional.empty(), "")));

        JsonNode result = client.callTool("read_queue_entry", valid());

        assertMatchesOutputSchema("read_queue_entry", result);
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("entry").path("state").asString()).isEqualTo("ERROR");
        assertThat(structured.path("entry").path("since").asString())
            .isEqualTo("2026-11-10T08:15:30Z");
        assertThat(structured.path("data").path("kind").asString()).isEqualTo("text");
        assertThat(structured.path("variables").path("totalCount").asInt()).isEqualTo(6);
        assertThat(structured.path("variables").path("items").get(1).path("masked").asBoolean())
            .isTrue();
        assertThat(structured.path("warnings").get(0).asString())
            .isEqualTo("Read through an unsupported, undocumented INUBIT interface;"
                + " diagnosis only");
        TestSecrets.assertNoLeak(result.toString());
    }

    @Test
    void binaryEmptyAndUnavailablePartsMatchTheOutputSchema() {
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x01, 0x02};
        List<RawEntryContent> shapes = List.of(
            new RawEntryContent(new PartResult.Read<>(zip), new PartResult.NotFound<>()),
            new RawEntryContent(new PartResult.Read<>(new byte[0]),
                new PartResult.Failed<>("INUBIT reported an error: Module-0001 failed")),
            new RawEntryContent(new PartResult.NotFound<>(),
                new PartResult.Read<>(List.of())));

        for (RawEntryContent shape : shapes) {
            content = shape;
            assertMatchesOutputSchema("read_queue_entry",
                client.callTool("read_queue_entry", valid()));
        }
        content = shapes.get(0);
        JsonNode data = client.callTool("read_queue_entry", valid()).path("structuredContent")
            .path("data");
        assertThat(data.path("kind").asString()).isEqualTo("binary");
        assertThat(data.path("detected").asString()).isEqualTo("ZIP");
        assertThat(data.path("hint").asString()).isEqualTo("Binary content is not returned");
    }

    @Test
    void aTooLargeDataStreamIsUnavailableInTheOutputSchema() {
        rows = List.of(new QueueEntryRow(PID, Optional.empty(), ProcessState.ERROR, "Error",
            SINCE, "Workflow-0001", Optional.empty(), Optional.empty(), FILE, 12_582_912,
            new TreeMap<>()));
        content = new RawEntryContent(new PartResult.NotRequested<>("not requested"),
            new PartResult.Read<>(List.of()));

        JsonNode result = client.callTool("read_queue_entry", valid());

        assertMatchesOutputSchema("read_queue_entry", result);
        assertThat(result.path("structuredContent").path("data").path("reason").asString())
            .isEqualTo("too large (12582912 bytes; limit 10 MiB)");
    }

    @Test
    void theContractExampleMatchesTheOutputSchema() {
        Map<String, Object> example = Map.of(
            "node", "test/node1",
            "entry", Map.ofEntries(Map.entry("processId", "1234"),
                Map.entry("globalProcessId", "0f0e0d0c-0000-4000-8000-000000000001"),
                Map.entry("workflow", "Workflow-0001"), Map.entry("module", "Module-0001"),
                Map.entry("moduleType", "Throw"), Map.entry("state", "ERROR"),
                Map.entry("rawState", "Error"), Map.entry("since", "2026-11-10T08:15:30Z"),
                Map.entry("fileName", FILE), Map.entry("sizeBytes", 772),
                Map.entry("errorTexts", Map.of("userDefined1", "ACME-ERROR",
                    "userDefined2", "Wrong data type for receiver"))),
            "data", Map.of("available", true, "kind", "text", "charset", "UTF-8",
                "sizeBytes", 772, "totalChars", 772, "returnedChars", 772, "truncated", false,
                "text", "<soap:Envelope …>"),
            "variables", Map.of("available", true, "totalCount", 51, "returnedCount", 5,
                "truncated", true, "items", List.of(
                    Map.of("name", "ISFileSize", "system", true, "type", "Long",
                        "kind", "text", "value", "772", "masked", false),
                    Map.of("name", "apiToken", "system", false, "kind", "text",
                        "value", "${secret:variables.apiToken}", "masked", true),
                    Map.of("name", "var.order", "system", false, "type", "XmlDocument",
                        "kind", "text", "value", "<Order>", "masked", false,
                        "totalChars", 5120, "truncated", true),
                    Map.of("name", "var.archive", "system", false, "kind", "binary",
                        "sizeBytes", 1024, "detected", "GZIP", "masked", false),
                    Map.of("name", "n", "nameTruncated", true, "system", false,
                        "kind", "empty", "sizeBytes", 0, "masked", false))),
            "warnings", List.of("Read through an unsupported, undocumented INUBIT interface;"
                + " diagnosis only"));

        assertThat(errors(example)).isNull();
        Map<String, Object> unknownField = new HashMap<>(example);
        unknownField.put("content", "x");
        assertThat(errors(unknownField)).isNotNull();
        Map<String, Object> unknownKind = new HashMap<>(example);
        unknownKind.put("data", Map.of("available", true, "kind", "xml", "sizeBytes", 1));
        assertThat(errors(unknownKind)).isNotNull();
    }

    @Test
    void serviceErrorsAreToolErrors() {
        JsonNode error = toolError(client.callTool("read_queue_entry",
            with("target", "prod/node1")));

        assertThat(error.path("code").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(error.path("node").asString()).isEqualTo("prod/node1");
        assertThat(error.path("nextStep").asString()).contains("restart the MCP client");
        assertThat(lookups).isEmpty();
    }

    // --- helpers ------------------------------------------------------------------------------

    private ProcessQueryPort lookup() {
        return new ProcessQueryPort() {
            @Override
            public void validate(ProcessQuery query) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public Page<ProcessInstance> find(ProcessQuery query) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public ProcessRows findByProcessId(String processId, Instant now,
                Duration hangingThreshold) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public QueueEntries queueEntries(String processId) {
                lookups.add(processId);
                return new QueueEntries(rows.stream().map(row -> withProcessId(row, processId))
                    .toList(), rows.size());
            }
        };
    }

    private static Map<String, Object> valid() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("target", "test/node1");
        arguments.put("processId", PID);
        return arguments;
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> arguments = valid();
        arguments.put(key, value);
        return arguments;
    }

    private static QueueEntryRow errorRow(String fileName) {
        return new QueueEntryRow(PID, Optional.of("0f0e0d0c-0000-4000-8000-000000000001"),
            ProcessState.ERROR, "Error", SINCE, "Workflow-0001", Optional.of("Module-0001"),
            Optional.of("Throw"), fileName, 772,
            new TreeMap<>(Map.of("userDefined1", "ACME-ERROR")));
    }

    private static QueueEntryRow withProcessId(QueueEntryRow row, String processId) {
        return new QueueEntryRow(processId, row.globalProcessId(), row.state(), row.rawState(),
            row.since(), row.workflow(), row.module(), row.moduleType(), row.fileName(),
            row.fileSize(), row.errorTexts());
    }

    private static RawEntryContent content(String data, List<RawVariable> variables) {
        return new RawEntryContent(
            new PartResult.Read<>(data.getBytes(StandardCharsets.UTF_8)),
            new PartResult.Read<>(variables));
    }

    /** The validation errors of {@code result}, or {@code null} if it is valid. */
    @SuppressWarnings("unchecked")
    private static String errors(Map<String, Object> result) {
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/read_queue_entry.output.json"), Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
