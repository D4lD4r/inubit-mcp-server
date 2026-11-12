package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.ServerLogRequest;
import de.dadecker.inubit.mcp.domain.model.LogExcerpt;
import de.dadecker.inubit.mcp.domain.model.LogWindow;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ServerLogResult;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
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
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 021 T030 (FR-001, FR-004, FR-014, D-20): {@code download_server_log} over MCP — the
 * contract's description, annotations and input schema, the schema rejecting malformed calls
 * before the service, the arguments reaching the service as a {@link ServerLogRequest}, and every
 * result variant of contracts/mcp-tools-delta.md "Output" in its contract shape, validating
 * against {@code download_server_log.output.json}. A fake service records the requests and answers
 * with a prepared {@link ServerLogResult}; nothing is downloaded or written.
 */
@Timeout(60)
class DownloadServerLogToolTest {

    /** contracts/mcp-tools-delta.md "Description", copied here to pin the wording. */
    private static final String CONTRACT_DESCRIPTION = "Downloads the trace log (trace.log) or the"
        + " web service log (webservice.log) of one INUBIT {node} into a local folder you name and"
        + " returns the newest lines, or the lines containing a search text, as a masked excerpt"
        + " with line numbers. Use it when a process or connector failed and find_processes,"
        + " query_logs or read_queue_entry do not show the cause (trace), or to see the SOAP"
        + " messages a web service connector really sent or received (webservice). The file is"
        + " written IN CLEAR TEXT: both logs may contain business data and credentials; only the"
        + " excerpt is masked. trace uses StartCLI's supported trace download. webservice is"
        + " UNSUPPORTED: it uses an undocumented internal INUBIT interface (the Workbench's Cluster"
        + " Manager log download) without vendor support or stability guarantee; it may stop"
        + " working after any INUBIT update; available only for groups with"
        + " unsupported.serverLogRead. The folder must be an absolute path outside the workspace,"
        + " the server's directories, git working trees and network paths; existing files are"
        + " never replaced. Changes nothing on INUBIT.";
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final Instant DOWNLOADED_AT = Instant.parse("2026-11-11T08:15:30Z");
    private static final String CLEAR_TEXT = "The log file contains clear-text content";
    private static final String UNSUPPORTED = "Read through an unsupported, undocumented INUBIT"
        + " interface; diagnosis only";
    private static final String WINDOW_HINT = "Only the last 50 MiB were scanned; line numbers"
        + " count the whole file";
    private static final long SIXTY_MIB = 60L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    private final List<ServerLogRequest> requests = new CopyOnWriteArrayList<>();
    private ServerLogResult result;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        result = result(ServerLogKind.TRACE, Optional.of(excerpt("first line\nsecond line\n")),
            List.of());
        client = McpTestClient.start(List.of(new DownloadServerLogTool(request -> {
            requests.add(request);
            return result;
        })), new SecretScrubber());
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement -------------------------------------------------------------------------

    @Test
    void theToolIsALocalWriteWithTheContractDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("download_server_log");
        assertThat(DownloadServerLogTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("Download an INUBIT server log");
        assertThat(annotations.path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "log", "targetFolder");
        assertThat(schema.path("properties").propertyNames()).containsExactly("target", "log",
            "targetFolder", "tailLines", "contains", "maxChars");
        assertThat(schema.path("properties").path("tailLines").path("default").asInt())
            .isEqualTo(200);
        assertThat(schema.path("properties").path("maxChars").path("default").asInt())
            .isEqualTo(20_000);
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("target").path("description").asString())
            .isEqualTo("Id of exactly one node (`<group>/<node>`); group ids are rejected");
    }

    @Test
    void theDescriptionStatesTheClearTextFileAndTheUnsupportedInterface() {
        String description = DownloadServerLogTool.DESCRIPTION.toLowerCase(Locale.ROOT);

        assertThat(description).contains("clear text", "business data and credentials",
            "supported trace download", "unsupported", "undocumented", "without vendor support",
            "any inubit update", "never replaced");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRejectsMalformedCallsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            with("target", "test"),
            with("target", "Test/node1"),
            with("target", "test/node1/x"),
            with("log", "server"),
            with("log", "TRACE"),
            with("targetFolder", ""),
            with("targetFolder", "x".repeat(4_097)),
            with("tailLines", 0),
            with("tailLines", 2_001),
            with("contains", ""),
            with("contains", "x".repeat(201)),
            with("contains", "a\u0000b"),
            with("contains", "a\nb"),
            with("contains", "a\u007Fb"),
            with("contains", "a\u0085b"),
            with("maxChars", 0),
            with("maxChars", 100_001),
            with("unknown", true)));
        for (String required : List.of("target", "log", "targetFolder")) {
            Map<String, Object> without = valid();
            without.remove(required);
            calls.add(without);
        }

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("download_server_log", arguments);
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
            with("tailLines", 1),
            with("tailLines", 2_000),
            with("maxChars", 1),
            with("maxChars", 100_000),
            with("contains", "x"),
            with("contains", "ü".repeat(200)),
            with("targetFolder", "x".repeat(4_096)),
            with("log", "webservice"));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("download_server_log", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isFalse();
        }
        assertThat(requests).hasSize(calls.size());
    }

    // --- arguments ----------------------------------------------------------------------------

    @Test
    void everyArgumentReachesTheServiceAsTheRequest() {
        Map<String, Object> arguments = valid();
        arguments.put("tailLines", 50);
        arguments.put("contains", "OrderService");
        arguments.put("maxChars", 1_000);

        client.callTool("download_server_log", arguments);

        assertThat(requests).containsExactly(new ServerLogRequest("test/node1", "trace",
            tmp.toString(), OptionalInt.of(50), Optional.of("OrderService"), OptionalInt.of(1_000),
            Optional.of("mcp-test-client/1.0")));
    }

    @Test
    void absentOptionalArgumentsAreLeftToTheService() {
        client.callTool("download_server_log", valid());

        assertThat(requests).containsExactly(new ServerLogRequest("test/node1", "trace",
            tmp.toString(), OptionalInt.empty(), Optional.empty(), OptionalInt.empty(),
            Optional.of("mcp-test-client/1.0")));
    }

    // --- output variants ----------------------------------------------------------------------

    @Test
    void aWebServiceLogResultHasTheContractShape() {
        result = result(ServerLogKind.WEBSERVICE, Optional.of(excerpt("first line\nsecond line\n")),
            List.of());

        JsonNode content = call();

        assertThat(content.propertyNames()).containsExactly("node", "log", "inubitNode", "file",
            "sizeBytes", "downloadedAt", "excerpt", "hints", "warnings");
        assertThat(content.path("node").asString()).isEqualTo("test/node1");
        assertThat(content.path("log").asString()).isEqualTo("webservice");
        assertThat(content.path("inubitNode").asString()).isEqualTo("node1");
        assertThat(content.path("file").asString()).isEqualTo(file(ServerLogKind.WEBSERVICE)
            .toString());
        assertThat(content.path("sizeBytes").asLong()).isEqualTo(734_003L);
        assertThat(content.path("downloadedAt").asString()).isEqualTo("2026-11-11T08:15:30Z");
        assertThat(content.path("excerpt")).isEqualTo(json("""
            {"kind": "text", "charset": "UTF-8",
             "window": {"startLine": 1, "lines": 2, "bytes": 23, "wholeFile": true},
             "selection": "tail", "returnedLines": 2,
             "lines": [{"number": 1, "text": "first line"}, {"number": 2, "text": "second line"}],
             "truncated": false}"""));
        assertThat(content.path("hints")).isEmpty();
        assertThat(content.path("warnings")).extracting(JsonNode::asString)
            .containsExactly(UNSUPPORTED, CLEAR_TEXT);
    }

    @Test
    void aTraceLogResultNamesNoInubitNode() {
        JsonNode content = call();

        assertThat(content.has("inubitNode")).isFalse();
        assertThat(content.path("log").asString()).isEqualTo("trace");
        assertThat(content.path("warnings")).extracting(JsonNode::asString)
            .containsExactly(CLEAR_TEXT);
    }

    @Test
    void aShortenedExcerptCountsTheReturnedLinesAndMarksTheCutLine() {
        result = result(ServerLogKind.TRACE, Optional.of(LogExcerpt.build(window(
            "aaaa\nbbbb\ncccccccccc\n"), 200, Optional.empty(), 6, text -> List.of())), List.of());

        JsonNode excerpt = call().path("excerpt");

        assertThat(excerpt.path("returnedLines").asInt()).isEqualTo(1);
        assertThat(excerpt.path("lines")).isEqualTo(json("""
            [{"number": 3, "text": "cccccc", "cut": true}]"""));
        assertThat(excerpt.path("truncated")).isEqualTo(json("""
            {"droppedLines": 2, "cutLines": 1}"""));
    }

    @Test
    void aSearchWithoutMatchReturnsNoLinesAndTheExcerptsHint() {
        result = result(ServerLogKind.TRACE, Optional.of(LogExcerpt.build(window(
            "first line\nsecond line\n"), 200, Optional.of("absent"), 20_000, text -> List.of())),
            List.of());

        JsonNode content = call();

        JsonNode excerpt = content.path("excerpt");
        assertThat(excerpt.path("selection").asString()).isEqualTo("contains");
        assertThat(excerpt.path("matchingLines").asInt()).isZero();
        assertThat(excerpt.path("returnedLines").asInt()).isZero();
        assertThat(excerpt.path("lines")).isEmpty();
        assertThat(content.path("hints")).extracting(JsonNode::asString)
            .containsExactly("No line of the scanned window contains the search text");
    }

    @Test
    void aSearchWithMatchesNamesAllMatchesOfTheWindow() {
        result = result(ServerLogKind.TRACE, Optional.of(LogExcerpt.build(window(
            "a 1\nb\na 2\na 3\n"), 2, Optional.of("A"), 20_000, text -> List.of())), List.of());

        JsonNode excerpt = call().path("excerpt");

        assertThat(excerpt.path("matchingLines").asInt()).isEqualTo(3);
        assertThat(excerpt.path("returnedLines").asInt()).isEqualTo(2);
        assertThat(excerpt.path("lines")).isEqualTo(json("""
            [{"number": 3, "text": "a 2"}, {"number": 4, "text": "a 3"}]"""));
    }

    @Test
    void aLogLargerThanTheWindowNamesTheWindowAndItsHint() {
        result = result(ServerLogKind.TRACE, Optional.of(LogExcerpt.build(new LogWindow(
            bytes("last line\n"), 412_876, false, SIXTY_MIB), 200, Optional.empty(), 20_000,
            text -> List.of())), List.of());

        JsonNode content = call();

        assertThat(content.path("excerpt").path("window")).isEqualTo(json("""
            {"startLine": 412877, "lines": 1, "bytes": 10, "wholeFile": false}"""));
        assertThat(content.path("excerpt").path("lines")).isEqualTo(json("""
            [{"number": 412877, "text": "last line"}]"""));
        assertThat(content.path("hints")).extracting(JsonNode::asString)
            .containsExactly(WINDOW_HINT);
    }

    /** T015: no charset (nothing was decoded), an empty window and both hints. */
    @Test
    void aWindowWithoutAWholeLineIsTextWithoutCharsetAndWithBothHints() {
        result = result(ServerLogKind.TRACE, Optional.of(LogExcerpt.build(new LogWindow(
            new byte[0], 0, false, SIXTY_MIB), 200, Optional.empty(), 20_000, text -> List.of())),
            List.of());

        JsonNode content = call();

        assertThat(content.path("excerpt")).isEqualTo(json("""
            {"kind": "text",
             "window": {"startLine": 1, "lines": 0, "bytes": 0, "wholeFile": false},
             "selection": "tail", "returnedLines": 0, "lines": [], "truncated": false}"""));
        assertThat(content.path("hints")).extracting(JsonNode::asString)
            .containsExactly(WINDOW_HINT, "No whole line in the last 50 MiB of the log");
    }

    @Test
    void aBinaryWindowNamesOnlyTheDetectedFormat() {
        byte[] gzip = {0x1F, (byte) 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03};
        result = result(ServerLogKind.TRACE, Optional.of(LogExcerpt.build(new LogWindow(gzip, 0,
            true, gzip.length), 200, Optional.empty(), 20_000, text -> List.of())), List.of());

        JsonNode content = call();

        assertThat(content.path("excerpt")).isEqualTo(json("""
            {"kind": "binary", "detected": "GZIP"}"""));
        assertThat(content.path("hints")).extracting(JsonNode::asString)
            .containsExactly("Binary content is not returned");
    }

    @Test
    void anEmptyLogIsOnlyItsKindWithTheServicesHint() {
        result = result(ServerLogKind.TRACE, Optional.of(LogExcerpt.build(new LogWindow(
            new byte[0], 0, true, 0), 200, Optional.empty(), 20_000, text -> List.of())),
            List.of("The log is empty"));

        JsonNode content = call();

        assertThat(content.path("excerpt")).isEqualTo(json("""
            {"kind": "empty"}"""));
        assertThat(content.path("hints")).extracting(JsonNode::asString)
            .containsExactly("The log is empty");
    }

    @Test
    void aWithheldExcerptIsOnlyItsKindWithTheServicesHint() {
        String hint = "The excerpt is withheld: the credentials of prod/node2 (credential store:"
            + " waiting) have not been read yet, so they could not be masked; every excerpt is"
            + " withheld until this source can be read; the file is written";
        result = result(ServerLogKind.TRACE, Optional.empty(), List.of(hint));

        JsonNode content = call();

        assertThat(content.path("excerpt")).isEqualTo(json("""
            {"kind": "withheld"}"""));
        assertThat(content.path("hints")).extracting(JsonNode::asString).containsExactly(hint);
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool with valid arguments; the result validates against the output schema. */
    private JsonNode call() {
        JsonNode response = client.callTool("download_server_log", valid());
        assertMatchesOutputSchema("download_server_log", response);
        return response.path("structuredContent");
    }

    private Map<String, Object> valid() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("target", "test/node1");
        arguments.put("log", "trace");
        arguments.put("targetFolder", tmp.toString());
        return arguments;
    }

    private Map<String, Object> with(String key, Object value) {
        Map<String, Object> arguments = valid();
        arguments.put(key, value);
        return arguments;
    }

    private ServerLogResult result(ServerLogKind log, Optional<LogExcerpt> excerpt,
        List<String> hints) {
        List<String> warnings = log == ServerLogKind.WEBSERVICE
            ? List.of(UNSUPPORTED, CLEAR_TEXT)
            : List.of(CLEAR_TEXT);
        return new ServerLogResult(TEST, log, log == ServerLogKind.WEBSERVICE
            ? Optional.of("node1") : Optional.empty(), file(log), 734_003L, DOWNLOADED_AT, excerpt,
            hints, warnings);
    }

    private Path file(ServerLogKind log) {
        return tmp.resolve("test-node1-" + log.wireName() + "-20261111-081530.log");
    }

    /** The excerpt of the last 200 lines of a whole file with {@code text}, nothing masked. */
    private static LogExcerpt excerpt(String text) {
        return LogExcerpt.build(window(text), 200, Optional.empty(), 20_000, line -> List.of());
    }

    private static LogWindow window(String text) {
        byte[] bytes = bytes(text);
        return new LogWindow(bytes, 0, true, bytes.length);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
