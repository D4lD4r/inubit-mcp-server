package de.dadecker.inubit.mcp.mcp.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.JSON;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * T070: {@code query_logs} over MCP with the real adapters against WireMock
 * (contracts/mcp-tools.md §4, quickstart V5/V6).
 */
@Timeout(60)
class QueryLogsToolTest {

    private static final String DESCRIPTION = "[acme] Read INUBIT log entries (systemLog = workflow"
        + " executions, queueLog, auditLog, schedulerLog, connectionLog, keyManagerLog,"
        + " webserviceManager) filtered by time, workflow, severity, process ID, or text."
        + " `text` is a case-sensitive substring match in which `%` matches any sequence and `_`"
        + " any single character. Newest first, paginated.";
    private static final String SYSTEM_LOG = "/ibis/rest/log/systemLog";
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
            server.stubFor(post(urlPathEqualTo(SYSTEM_LOG))
                .willReturn(RestFixtures.response("log_systemLog_filtered", "json")));
        }
        fixture = new DiagnosisToolFixture(dev, integration, QueryLogsTool::new);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return fixture.client.callTool("query_logs", arguments);
    }

    @Test
    void theToolIsListedReadOnlyIdempotentAndOpenWorldWithTheContractSchema() {
        JsonNode tool = fixture.client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("query_logs");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION)
            .contains("case-sensitive", "`%`", "`_`");
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
        JsonNode input = tool.path("inputSchema");
        assertThat(input.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(input.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "logType");
        assertThat(input.path("properties").path("logType").path("enum"))
            .extracting(JsonNode::asString).containsExactly("systemLog", "queueLog",
                "connectionLog", "schedulerLog", "auditLog", "keyManagerLog",
                "webserviceManager");
        assertThat(input.path("properties").path("limit").path("minimum").asInt()).isEqualTo(1);
        assertThat(input.path("properties").path("limit").path("maximum").asInt())
            .isEqualTo(100);
        assertThat(input.path("properties").path("text").path("description").asString())
            .contains("Case-sensitive", "% and _ are wildcards");
    }

    @Test
    void processLogIsRejectedByTheSchemaListingTheSupportedLogTypes() {
        JsonNode result = call(Map.of("target", "dev", "logType", "processLog"));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.has("structuredContent")).isFalse();
        assertThat(result.path("content").get(0).path("text").asString())
            .contains("systemLog", "webserviceManager");
        assertThat(dev.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void failedWorkflowExecutionsOfAProcessAreReadFromTheSystemLog() {
        // quickstart V5
        JsonNode result = call(Map.of("target", "dev/node1", "logType", "systemLog",
            "processId", "110219899", "severity", List.of("ERROR"), "since", "PT24H",
            "text", "SAX%Exception", "limit", 5));

        assertMatchesOutputSchema("query_logs", result);
        JsonNode page = result.path("structuredContent").path("results").get(0).path("page");
        assertThat(page.path("total").asLong()).isEqualTo(780);
        JsonNode first = page.path("items").get(0);
        assertThat(first.path("logType").asString()).isEqualTo("systemLog");
        assertThat(first.path("severity").asString()).isEqualTo("ERROR");
        assertThat(first.path("rawSeverity").asString()).isEqualTo("false");
        assertThat(first.path("message").asString()).contains("SAXParseException");
        assertThat(first.path("timestamp").asString()).isEqualTo("2026-10-01T13:29:33.748Z");
        assertThat(first.path("fields").isObject()).isTrue();
        assertThat(dev.findAll(postRequestedFor(urlPathEqualTo(SYSTEM_LOG)))).singleElement()
            .satisfies(request -> assertThat(request.getBodyAsString()).contains(
                "<field>workflowId</field>", "<value>110219899</value>",
                "<field>success</field>", "<value>false</value>",
                "<field>message</field>", "<value>SAX%Exception</value>",
                "<comparison>LIKE</comparison>", "<noOfItems>5</noOfItems>"));
    }

    @Test
    void anUnsupportedFilterIsAnInvalidInputNamingTheSupportedFilters() {
        JsonNode error = toolError(call(Map.of("target", "dev", "logType", "auditLog",
            "workflow", "Workflow-0424")));

        assertThat(error.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.path("message").asString()).contains("workflow", "auditLog");
        assertThat(error.path("nextStep").asString()).contains("since/until", "severity",
            "text");
        assertThat(error.has("node")).as("review D8: the same for every server").isFalse();
        assertThat(dev.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void aCharacterThatXmlDoesNotAllowIsAnInvalidInputWithoutRequest() {
        // review D5
        JsonNode error = toolError(call(Map.of("target", "dev", "logType", "systemLog",
            "text", "a\u0001b")));

        assertThat(error.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.path("message").asString()).contains("U+0001");
        assertThat(error.has("node")).isFalse();
        assertThat(dev.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void aStageReportsEachServerAndAnUnreachableServerOnlyGetsItsError() {
        JsonNode result = call(Map.of("target", "qa", "logType", "systemLog", "limit", 2));

        assertMatchesOutputSchema("query_logs", result);
        JsonNode results = result.path("structuredContent").path("results");
        assertThat(results).extracting(entry -> entry.path("node").asString())
            .containsExactly("qa/node1", "qa/node2");
        assertThat(results.get(0).path("page").path("items")).hasSize(2);
        assertThat(results.get(1).path("error").path("code").asString())
            .isEqualTo("UNREACHABLE");
    }

    @Test
    void aLargeResultIsTheFirstPageWithTotalAndNextOffsetWithinTheSizeLimit() {
        // quickstart V6: "Show the last 500 systemLog errors"
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("success", Map.of("level", 0, "content", false));
            row.put("startTime", 1_790_861_373_748L - i);
            row.put("workflowName", "W" + i);
            row.put("message", "failure " + i + " " + "x".repeat(1_900));
            rows.add(row);
        }
        String body = JSON.writeValueAsString(Map.of("systemLog", Map.of("total", 500,
            "success", true, "count", 100, "row", rows)));
        dev.stubFor(post(urlPathEqualTo(SYSTEM_LOG)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json").withBody(body)));

        JsonNode result = call(Map.of("target", "dev/node1", "logType", "systemLog",
            "severity", List.of("ERROR"), "limit", 100));

        assertMatchesOutputSchema("query_logs", result);
        JsonNode page = result.path("structuredContent").path("results").get(0).path("page");
        int items = page.path("items").size();
        assertThat(items).isBetween(1, 99);
        assertThat(page.path("total").asLong()).isEqualTo(500);
        assertThat(page.path("truncated").asBoolean()).isTrue();
        assertThat(page.path("nextOffset").asInt()).isEqualTo(items);
        assertThat(result.path("content").get(0).path("text").asString().length())
            .isLessThanOrEqualTo(ResultLimiter.DEFAULT_MAX_CHARS + 1_000);
    }

    @Test
    void schemaViolationsAreRejectedBeforeTheToolRuns() {
        List<Map<String, Object>> invalid = List.of(
            Map.of("target", "dev", "logType", "systemLog", "limit", 101),
            Map.of("target", "dev", "logType", "systemLog", "offset", 4_294_967_296L),
            Map.of("target", "dev", "logType", "systemLog", "severity", List.of("FATAL")),
            Map.of("target", "dev", "logType", "systemLog", "processId", "1; DROP"),
            Map.of("target", "dev", "logType", "systemLog", "text", "x".repeat(201)),
            Map.of("target", "dev"),
            Map.of("target", "dev", "logType", "systemLog", "verbose", true));

        for (Map<String, Object> arguments : invalid) {
            JsonNode result = call(arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(result.path("content").get(0).path("text").asString())
                .doesNotContain("\"code\"");
        }
        assertThat(dev.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void noCredentialReachesTheResult() {
        JsonNode result = call(Map.of("target", "qa", "logType", "systemLog"));

        assertThat(result.toString()).doesNotContain(DiagnosisToolFixture.PASSWORD,
            "Authorization");
    }
}
