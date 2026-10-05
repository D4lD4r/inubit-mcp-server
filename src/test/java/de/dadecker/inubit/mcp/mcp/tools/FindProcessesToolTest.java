package de.dadecker.inubit.mcp.mcp.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * T070: {@code find_processes} over MCP with the real adapters against WireMock
 * (contracts/mcp-tools.md §3, quickstart V4).
 */
@Timeout(60)
class FindProcessesToolTest {

    private static final String DESCRIPTION = "[acme] Find process instances on INUBIT nodes, e.g."
        + " failed (ERROR) or hanging ones, filtered by workflow, state, and time. Use the"
        + " returned processId and time range with `query_logs` to find the cause.";
    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    private static WireMockServer dev;
    private static WireMockServer integration;

    private DiagnosisToolFixture fixture;

    @BeforeAll
    static void start() {
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        integration = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        dev.stop();
        integration.stop();
    }

    @BeforeEach
    void setUp() {
        dev.resetAll();
        integration.resetAll();
        for (WireMockServer server : List.of(dev, integration)) {
            server.stubFor(post(urlPathEqualTo(QUEUE_LOG))
                .withRequestBody(containing("<value>Error</value>"))
                .willReturn(RestFixtures.response("log_queueLog_error", "json")));
            server.stubFor(post(urlPathEqualTo(QUEUE_LOG))
                .willReturn(RestFixtures.response("log_queueLog", "json")).atPriority(10));
        }
        fixture = new DiagnosisToolFixture(dev, integration, FindProcessesTool::new);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return fixture.client.callTool("find_processes", arguments);
    }

    @Test
    void theToolIsListedReadOnlyIdempotentAndOpenWorldWithTheContractSchema() {
        JsonNode tool = fixture.client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("find_processes");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
        JsonNode input = tool.path("inputSchema");
        assertThat(input.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(input.path("required")).extracting(JsonNode::asString)
            .containsExactly("target");
        assertThat(input.path("properties").propertyNames()).containsExactlyInAnyOrder(
            "target", "states", "hangingOnly", "hangingThresholdMinutes", "workflow", "tag",
            "since", "until", "offset", "limit");
        assertThat(input.path("properties").path("limit").path("minimum").asInt()).isEqualTo(1);
        assertThat(input.path("properties").path("limit").path("maximum").asInt())
            .isEqualTo(100);
        assertThat(input.path("properties").path("offset").path("maximum").asInt())
            .isEqualTo(9_999);
        assertThat(input.path("properties").path("states").path("items").path("enum"))
            .extracting(JsonNode::asString)
            .containsExactly("ERROR", "ACTIVE", "WAITING", "QUEUED");
        assertThat(tool.path("outputSchema").path("properties").has("results")).isTrue();
    }

    @Test
    void failedProcessesOfTheLastDayAreListedWithIdWorkflowModuleAndSince() {
        // quickstart V4
        JsonNode result = call(Map.of("target", "dev/node1", "states", List.of("ERROR"),
            "since", "PT24H", "limit", 5));

        assertMatchesOutputSchema("find_processes", result);
        JsonNode results = result.path("structuredContent").path("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("node").asString()).isEqualTo("dev/node1");
        JsonNode page = results.get(0).path("page");
        assertThat(page.path("total").asLong()).isEqualTo(706);
        assertThat(page.path("nextOffset").asInt()).isEqualTo(5);
        assertThat(page.path("truncated").asBoolean(true)).isFalse();
        JsonNode first = page.path("items").get(0);
        assertThat(first.path("processId").asString()).isEqualTo("110219899");
        assertThat(first.path("state").asString()).isEqualTo("ERROR");
        assertThat(first.path("rawState").asString()).isEqualTo("Error");
        assertThat(first.path("workflow").asString()).isEqualTo("Workflow-0424");
        assertThat(first.path("module").asString()).isEqualTo("Module-0036(23379903)");
        assertThat(Instant.parse(first.path("since").asString()))
            .isEqualTo(Instant.ofEpochMilli(1_790_861_373_734L));
        assertThat(Duration.parse(first.path("timeInState").asString())).isPositive();
        assertThat(first.path("hanging").asBoolean(true)).isFalse();
        assertThat(dev.findAll(postRequestedFor(urlPathEqualTo(QUEUE_LOG)))).singleElement()
            .satisfies(request -> assertThat(request.getBodyAsString()).contains(
                "<field>status</field>", "<value>Error</value>", "<comparison>GREATER",
                "<noOfItems>5</noOfItems>"));
    }

    @Test
    void aStageListsEachServerAndAnUnreachableServerOnlyGetsItsError() {
        JsonNode result = call(Map.of("target", "qa", "limit", 2));

        assertMatchesOutputSchema("find_processes", result);
        JsonNode results = result.path("structuredContent").path("results");
        assertThat(results).extracting(entry -> entry.path("node").asString())
            .containsExactly("qa/node1", "qa/node2");
        assertThat(results.get(0).path("page").path("items")).hasSize(2)
            .allSatisfy(item -> assertThat(item.path("node").asString())
                .isEqualTo("qa/node1"));
        assertThat(results.get(0).has("error")).isFalse();
        assertThat(results.get(1).has("page")).isFalse();
        assertThat(results.get(1).path("error").path("code").asString())
            .isEqualTo("UNREACHABLE");
        assertThat(results.get(1).path("error").path("node").asString())
            .isEqualTo("qa/node2");
    }

    @Test
    void errorWithHangingOnlyIsAnEmptyPageWithoutRequest() {
        JsonNode result = call(Map.of("target", "dev/node1", "states", List.of("ERROR"),
            "hangingOnly", true));

        assertMatchesOutputSchema("find_processes", result);
        JsonNode page = result.path("structuredContent").path("results").get(0).path("page");
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("total").asLong(-1)).isZero();
        assertThat(dev.getAllServeEvents()).isEmpty();
    }

    @Test
    void schemaViolationsAreRejectedBeforeTheToolRuns() {
        List<Map<String, Object>> invalid = List.of(
            Map.of("target", "dev", "limit", 101),
            Map.of("target", "dev", "limit", 0),
            Map.of("target", "dev", "states", List.of("RUNNING")),
            Map.of("target", "dev", "verbose", true),
            Map.of("target", "DEV/Inubit 1"),
            Map.of("target", "dev", "hangingThresholdMinutes", 0),
            Map.of("target", "dev", "offset", 4_294_967_296L),
            Map.of("target", "dev", "offset", 10_000),
            Map.of("states", List.of("ERROR")));

        for (Map<String, Object> arguments : invalid) {
            JsonNode result = call(arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
            assertThat(result.path("content").get(0).path("text").asString())
                .doesNotContain("\"code\"");
        }
        assertThat(dev.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void semanticallyInvalidInputIsAnInvalidInputToolError() {
        JsonNode badTime = toolError(call(Map.of("target", "dev", "since", "yesterday")));
        JsonNode tooDeep = toolError(call(Map.of("target", "dev", "offset", 9_990,
            "limit", 20)));
        JsonNode unknown = toolError(call(Map.of("target", "staging")));
        JsonNode control = toolError(call(Map.of("target", "dev", "workflow", "W\u0001")));

        assertThat(badTime.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(badTime.path("nextStep").asString()).contains("PT24H");
        assertThat(tooDeep.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(unknown.path("code").asString()).isEqualTo("TARGET_UNKNOWN");
        assertThat(control.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(control.has("node")).as("review D8").isFalse();
        assertThat(dev.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void noCredentialReachesTheResult() {
        JsonNode result = call(Map.of("target", "qa"));

        assertThat(result.toString()).doesNotContain(DiagnosisToolFixture.PASSWORD,
            "Authorization", "jdoe");
    }
}
