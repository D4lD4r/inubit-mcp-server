package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ResultMapper (T048): structuredContent plus compact JSON text, ToolError, scrubbing. */
class ResultMapperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "Very-Secret-Pw-123";

    private final SecretScrubber scrubber = new SecretScrubber();
    private final ResultMapper mapper = new ResultMapper(scrubber);

    record Item(NodeId node, Instant at, Optional<String> note, Optional<Integer> count,
        List<String> tags) {
    }

    record Result(List<Item> items) {
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void successMapsRecordsToStructuredContentAndCompactText() {
        Result payload = new Result(List.of(new Item(NodeId.parse("qa/node2"),
            Instant.parse("2026-10-01T08:00:00Z"), Optional.empty(), Optional.of(3),
            List.of("a"))));

        CallToolResult result = mapper.success(payload);

        assertThat(result.isError()).isFalse();
        Map<String, Object> item = (Map<String, Object>) ((List<?>) structured(result)
            .get("items")).get(0);
        assertThat(item).containsEntry("node", "qa/node2")
            .containsEntry("at", "2026-10-01T08:00:00Z")
            .containsEntry("count", 3)
            .containsEntry("tags", List.of("a"))
            .doesNotContainKey("note");
        assertThat(text(result)).isEqualTo("{\"items\":[{\"node\":\"qa/node2\","
            + "\"at\":\"2026-10-01T08:00:00Z\",\"count\":3,\"tags\":[\"a\"]}]}");
    }

    @Test
    void successScrubsEveryStringValueInBothChannels() {
        scrubber.register(SECRET);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "login with " + SECRET + " failed");
        payload.put("nested", Map.of("list", List.of(SECRET)));

        CallToolResult result = mapper.success(payload);

        assertThat(String.valueOf(result.structuredContent())).doesNotContain(SECRET);
        assertThat(structured(result)).containsEntry("message", "login with *** failed");
        assertThat(text(result)).doesNotContain(SECRET).contains("login with *** failed");
    }

    @Test
    void keysAreNotScrubbed() {
        // Phase 3 review m4: keys come from the output schemas or are INUBIT names (e.g.
        // systemInfo.raw); scrubbing them could merge or mangle keys, and values are scrubbed
        scrubber.register("Version");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("raw", Map.of("Version", "8.1.17"));
        payload.put("note", "Version");

        CallToolResult result = mapper.success(payload);

        assertThat(text(result)).isEqualTo("{\"raw\":{\"Version\":\"8.1.17\"},\"note\":\"***\"}");
    }

    @Test
    void errorMapsTheToolErrorAsPayloadWithIsError() {
        ToolError error = ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, "Bad answer", "Server bug",
            "See excerpt").withNode(NodeId.parse("dev/node1")).withExcerpt("<html>x");

        CallToolResult result = mapper.error(error);

        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent()).isNull();
        JsonNode payload = JSON.readTree(text(result));
        assertThat(payload.path("error").path("code").asString())
            .isEqualTo("UNEXPECTED_RESPONSE");
        assertThat(payload.path("error").path("message").asString()).isEqualTo("Bad answer");
        assertThat(payload.path("error").path("likelyCause").asString()).isEqualTo("Server bug");
        assertThat(payload.path("error").path("nextStep").asString()).isEqualTo("See excerpt");
        assertThat(payload.path("error").path("node").asString()).isEqualTo("dev/node1");
        assertThat(payload.path("error").path("excerpt").asString()).isEqualTo("<html>x");
    }

    @Test
    void errorTextIsScrubbed() {
        scrubber.register(SECRET);
        ToolError error = ToolError.of(ErrorCode.AUTH_FAILED, "rejected " + SECRET, SECRET,
            "next " + SECRET).withExcerpt("excerpt " + SECRET);

        String text = text(mapper.error(error));

        assertThat(text).doesNotContain(SECRET).contains("rejected ***");
    }

    @Test
    void invokeCatchesToolErrorsAndUnexpectedExceptions() {
        EchoTestTool tool = new EchoTestTool();

        CallToolResult ok = mapper.invoke(tool, Map.of("message", "m"));
        CallToolResult toolError = mapper.invoke(tool, Map.of("mode", "toolError"));
        CallToolResult crash = mapper.invoke(tool, Map.of("mode", "crash", "message", "detail"));

        assertThat(ok.isError()).isFalse();
        assertThat(structured(ok)).containsEntry("echo", "m");
        assertThat(toolError.isError()).isTrue();
        assertThat(text(toolError)).contains("\"NOT_FOUND\"");
        assertThat(crash.isError()).isTrue();
        assertThat(JSON.readTree(text(crash)).path("error").path("code").asString())
            .isEqualTo("INTERNAL");
        assertThat(text(crash)).doesNotContain("detail");
    }

    @Test
    void aPayloadThatIsNotAJsonObjectIsAnInternalError() {
        CallToolResult result = mapper.success(List.of("not", "an", "object"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("\"INTERNAL\"");
    }

    @Test
    void invokeTreatsMissingArgumentsAsEmpty() {
        CallToolResult result = mapper.invoke(new EchoTestTool(), null);

        assertThat(result.isError()).isFalse();
        assertThat(structured(result)).containsEntry("echo", "");
    }
}
