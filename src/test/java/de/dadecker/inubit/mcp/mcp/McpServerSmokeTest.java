package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T025: the real MCP server over piped stdio (research R-17): handshake, tool listing, result
 * mapping (structuredContent plus compact JSON text, ToolError as {@code isError}), SDK input and
 * output validation, scrubbing, and a protocol stream that carries nothing but JSON-RPC.
 */
@Timeout(60)
class McpServerSmokeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "s3cr3t-Pa55word-xyz";

    private Locale originalLocale;

    @BeforeEach
    void englishLocale() {
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH); // as Main does (research R-16)
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    private static void assertOnlyJsonRpcLines(String raw) {
        assertThat(raw).endsWith("\n");
        for (String line : raw.split("\n", -1)) {
            if (line.isEmpty()) {
                continue; // the split after the final newline
            }
            JsonNode message = JSON.readTree(line);
            assertThat(message.isObject()).as("line is a JSON object: %s", line).isTrue();
            assertThat(message.path("jsonrpc").asString()).as("line: %s", line).isEqualTo("2.0");
        }
    }

    @Test
    void initializeSucceedsWithServerInfoAndToolsCapability() {
        try (McpTestClient client = McpTestClient.start(List.of())) {
            JsonNode result = client.initialize();

            assertThat(result.path("protocolVersion").asString())
                .isEqualTo(McpTestClient.PROTOCOL_VERSION);
            assertThat(result.path("serverInfo").path("name").asString())
                .isEqualTo("inubit-mcp-server");
            assertThat(result.path("serverInfo").path("version").asString())
                .isEqualTo("0.0.0-test");
            assertThat(result.path("capabilities").has("tools")).isTrue();
        }
    }

    @Test
    void toolsListIsEmptyWithoutHandlers() {
        try (McpTestClient client = McpTestClient.start(List.of())) {
            client.initialize();

            JsonNode tools = client.listTools().path("tools");

            assertThat(tools.isArray()).isTrue();
            assertThat(tools.size()).isZero();
        }
    }

    @Test
    void protocolOutputCarriesOnlyJsonRpcLines() {
        String raw;
        try (McpTestClient client = McpTestClient.startOnGuardedStdout(
            List.of(new EchoTestTool()))) {
            System.out.println("stray stdout output must not reach the protocol stream");
            client.initialize();
            client.listTools();
            client.callTool(EchoTestTool.NAME, Map.of("message", "hi"));
            client.callTool(EchoTestTool.NAME, Map.of("mode", "crash"));
            client.callTool(EchoTestTool.NAME, Map.of("unknown", true));
            System.out.print("stray partial line without newline");
            System.out.flush();
            raw = client.rawOutput();
        }

        assertThat(raw).isNotEmpty();
        assertOnlyJsonRpcLines(raw); // also: ends with a newline, no partial line
        assertThat(raw).doesNotContain("stray");
    }

    @Test
    void toolsListShowsDescriptionSchemasAndAnnotations() {
        try (McpTestClient client = McpTestClient.start(List.of(new EchoTestTool()))) {
            client.initialize();

            JsonNode tools = client.listTools().path("tools");

            assertThat(tools.size()).isEqualTo(1);
            JsonNode tool = tools.get(0);
            assertThat(tool.path("name").asString()).isEqualTo(EchoTestTool.NAME);
            assertThat(tool.path("title").asString()).isEqualTo("Echo (test)");
            assertThat(tool.path("description").asString())
                .isEqualTo("[acme] Echoes the message (test tool).");
            assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean(true))
                .isFalse();
            assertThat(tool.path("inputSchema").path("properties").has("message")).isTrue();
            assertThat(tool.path("outputSchema").path("required").toString())
                .contains("echo", "node");
            JsonNode annotations = tool.path("annotations");
            assertThat(annotations.path("title").asString()).isEqualTo("Echo (test)");
            assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
            assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
            assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
            assertThat(annotations.path("openWorldHint").asBoolean(true)).isFalse();
        }
    }

    @Test
    void successReturnsStructuredContentAndTheSameCompactJsonAsText() {
        try (McpTestClient client = McpTestClient.start(List.of(new EchoTestTool()))) {
            client.initialize();

            JsonNode result = client.callTool(EchoTestTool.NAME, Map.of("message", "hello"));

            assertThat(result.path("isError").asBoolean(true)).isFalse();
            JsonNode structured = result.path("structuredContent");
            assertThat(structured.path("echo").asString()).isEqualTo("hello");
            assertThat(structured.path("node").asString()).isEqualTo("dev/node1");
            assertThat(structured.has("note")).as("empty Optional is omitted").isFalse();
            assertThat(result.path("content").size()).isEqualTo(1);
            JsonNode text = result.path("content").get(0);
            assertThat(text.path("type").asString()).isEqualTo("text");
            assertThat(text.path("text").asString()).doesNotContain("\n", " ");
            assertThat(JSON.readTree(text.path("text").asString())).isEqualTo(structured);
        }
    }

    @Test
    void toolErrorBecomesAnErrorResultWithTheToolErrorPayload() {
        EchoTestTool tool = new EchoTestTool();
        try (McpTestClient client = McpTestClient.start(List.of(tool))) {
            client.initialize();

            JsonNode result = client.callTool(EchoTestTool.NAME,
                Map.of("mode", "toolError", "message", "x"));

            assertThat(result.path("isError").asBoolean()).isTrue();
            JsonNode error = JSON.readTree(result.path("content").get(0).path("text").asString())
                .path("error");
            assertThat(error.path("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(error.path("message").asString()).isEqualTo("Nothing found for x");
            assertThat(error.path("likelyCause").asString()).isEqualTo("It does not exist");
            assertThat(error.path("nextStep").asString()).isEqualTo("Check the name");
            assertThat(error.path("node").asString()).isEqualTo("dev/node1");
            assertThat(error.has("excerpt")).isFalse();
            assertThat(result.has("structuredContent"))
                .as("no structuredContent that clients would validate against the outputSchema")
                .isFalse();
            assertThat(tool.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void secretsAreScrubbedFromResultsAndErrors() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(SECRET);
        try (McpTestClient client = McpTestClient.start(List.of(new EchoTestTool()), scrubber)) {
            client.initialize();

            JsonNode ok = client.callTool(EchoTestTool.NAME, Map.of("message", "pw " + SECRET));
            JsonNode toolError = client.callTool(EchoTestTool.NAME,
                Map.of("mode", "toolError", "message", SECRET));
            JsonNode crash = client.callTool(EchoTestTool.NAME,
                Map.of("mode", "crash", "message", SECRET));

            assertThat(ok.path("structuredContent").path("echo").asString()).isEqualTo("pw ***");
            assertThat(ok.toString()).doesNotContain(SECRET);
            assertThat(toolError.toString()).doesNotContain(SECRET).contains("***");
            assertThat(crash.toString()).doesNotContain(SECRET);
            assertThat(client.rawOutput()).doesNotContain(SECRET);
        }
    }

    @Test
    void unexpectedExceptionBecomesInternalErrorWithoutStackTrace() {
        try (McpTestClient client = McpTestClient.start(List.of(new EchoTestTool()))) {
            client.initialize();

            JsonNode result = client.callTool(EchoTestTool.NAME, Map.of("mode", "crash"));

            assertThat(result.path("isError").asBoolean()).isTrue();
            String text = result.path("content").get(0).path("text").asString();
            JsonNode error = JSON.readTree(text).path("error");
            assertThat(error.path("code").asString()).isEqualTo("INTERNAL");
            assertThat(error.path("message").asString()).contains(EchoTestTool.NAME);
            assertThat(text).doesNotContain("boom", "\tat ", "IllegalStateException.java");
        }
    }

    @Test
    void sdkRejectsInvalidInputInEnglishWithoutCallingTheHandler() {
        EchoTestTool tool = new EchoTestTool();
        try (McpTestClient client = McpTestClient.start(List.of(tool))) {
            client.initialize();

            JsonNode result = client.callTool(EchoTestTool.NAME, Map.of("unknown", true));

            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.toString()).contains("unknown");
            assertThat(tool.calls.get()).isZero();
        }
    }

    @Test
    void sdkValidatesTheOutputAgainstTheOutputSchema() {
        try (McpTestClient client = McpTestClient.start(List.of(new EchoTestTool()))) {
            client.initialize();

            JsonNode result = client.callTool(EchoTestTool.NAME, Map.of("mode", "badOutput"));

            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.toString()).contains("validation");
        }
    }

    @Test
    void concurrentToolCallsGetExactlyOneResponseEach() {
        // Phase 3 review M1: the SDK's stdio transport emits responses from several threads
        // into a non-serialized sink; without serialization some responses were lost
        try (McpTestClient client = McpTestClient.start(List.of(new EchoTestTool()))) {
            client.initialize();
            for (int burst = 0; burst < 5; burst++) {
                List<Map<String, Object>> calls = new java.util.ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    calls.add(Map.of("message", "b" + burst + "-" + i, "mode", "slow"));
                }

                Map<Long, List<JsonNode>> responses =
                    client.callToolsConcurrently(EchoTestTool.NAME, calls);

                assertThat(responses).hasSize(16);
                assertThat(responses.values()).as("burst %d", burst)
                    .allSatisfy(answers -> assertThat(answers).hasSize(1));
                assertThat(responses.values()).allSatisfy(answers -> assertThat(
                    answers.get(0).path("result").path("isError").asBoolean()).isFalse());
            }
            assertOnlyJsonRpcLines(client.rawOutput());
        }
    }

    @Test
    void serverStopsWhenTheInputIsClosed() {
        McpServerFactory factory = new McpServerFactory("0.0.0-test",
            McpTestClient.TEST_PROFILE, List.of(),
            SchemaResources.forClasspath(), new ResultMapper(new SecretScrubber()));
        McpTestClient client = McpTestClient.start(factory);
        client.initialize();

        client.close(); // closes stdin and waits; must not hang (class-level timeout)

        assertOnlyJsonRpcLines(client.rawOutput());
    }
}
