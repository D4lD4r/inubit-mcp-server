package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * T068: the 8.1 log adapter against WireMock with the recorded {@code rest/log_*} fixtures
 * (research R-9, S-5; data-model.md → LogEntry).
 */
@Timeout(30)
class V81LogAdapterTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String PASSWORD = "log-adapter-test-pw";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static WireMockServer wireMock;

    private final SecretScrubber scrubber = new SecretScrubber();
    private InubitHttpClient client;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        client = new InubitHttpClient(TestNodeConfig.node().id(ID.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials("jdoe", scrubber.register(PASSWORD))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private V81LogAdapter adapter() {
        return new V81LogAdapter(ID, client);
    }

    private static LogQuery query(LogType type) {
        return new LogQuery(type, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Set.of(), Optional.empty(), 0, 5);
    }

    private static void stub(LogType type, ResponseDefinitionBuilder response) {
        wireMock.stubFor(post(urlPathEqualTo("/ibis/rest/log/" + type.logName()))
            .willReturn(response));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json;charset=UTF-8").withBody(body);
    }

    private static String response(String logName, long total, List<Map<String, Object>> rows) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("total", total);
        content.put("success", true);
        content.put("count", rows.size());
        if (!rows.isEmpty()) {
            content.put("row", rows);
        }
        return JSON.writeValueAsString(Map.of(logName, content));
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().node()).contains(ID);
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    // --- recorded fixtures ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(LogType.class)
    void everyRecordedLogTypeIsMappedWithItsTotal(LogType type) {
        stub(type, RestFixtures.response("log_" + type.logName(), "json"));

        Page<LogEntry> page = adapter().query(query(type));

        long total = JSON.readTree(RestFixtures.bytes("log_" + type.logName() + ".json"))
            .path(type.logName()).path("total").asLong();
        assertThat(page.total()).hasValue(total);
        assertThat(page.items()).hasSize(5);
        assertThat(page.offset()).isZero();
        assertThat(page.limit()).isEqualTo(5);
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).hasValue(5);
        assertThat(page.items()).allSatisfy(entry -> {
            assertThat(entry.node()).isEqualTo(ID);
            assertThat(entry.logType()).isEqualTo(type);
            assertThat(entry.fields()).doesNotContainKey("index");
            assertThat(entry.fields().values()).allSatisfy(value ->
                assertThat(value).isNotEmpty().hasSizeLessThanOrEqualTo(200));
            assertThat(ResultJson.size(entry)).isLessThanOrEqualTo(ResultLimiter.MAX_ITEM_CHARS);
        });
    }

    @Test
    void theRequestIsAnAuthenticatedXmlPostWithFormatJson() {
        stub(LogType.SYSTEM_LOG, RestFixtures.response("log_systemLog", "json"));

        adapter().query(query(LogType.SYSTEM_LOG));

        List<LoggedRequest> requests = wireMock.findAll(
            postRequestedFor(urlPathEqualTo("/ibis/rest/log/systemLog"))
                .withQueryParam("format", equalTo("json"))
                .withHeader("Content-Type", containing("application/xml"))
                .withHeader("Authorization", matching("Basic .+")));
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.getBodyAsString()).contains("<noOfItems>5</noOfItems>",
                "<startIndex>0</startIndex>", "<field>startTime</field>",
                "<order>DESCENDING</order>");
        });
    }

    @Test
    void aSystemLogRowMapsWorkflowProcessIdTimestampSeverityAndFields() {
        stub(LogType.SYSTEM_LOG, RestFixtures.response("log_systemLog", "json"));

        LogEntry entry = adapter().query(query(LogType.SYSTEM_LOG)).items().get(0);

        assertThat(entry.timestamp()).contains(Instant.ofEpochMilli(1_790_860_368_050L));
        assertThat(entry.workflow()).contains("Workflow-0335");
        assertThat(entry.processId()).contains("110219088");
        assertThat(entry.severity()).isEqualTo(Severity.INFO);
        assertThat(entry.rawSeverity()).contains("true");
        assertThat(entry.message()).isEmpty();
        assertThat(entry.fields()).containsEntry("inputModule", "Module-0035(7)")
            .containsEntry("outputModule", "Module-0047(9)")
            .containsEntry("duration", "88")
            .containsEntry("globalPId", "110219088")
            .containsEntry("endTime", "2026-10-01T13:12:48.138Z")
            .doesNotContainKeys("workflowName", "workflowId", "startTime", "success",
                "message", "userDefined1", "tag");
    }

    @Test
    void aFailedSystemLogRowIsAnErrorWithItsMessage() {
        stub(LogType.SYSTEM_LOG, RestFixtures.response("log_systemLog_filtered", "json"));

        LogEntry entry = adapter().query(query(LogType.SYSTEM_LOG)).items().get(0);

        assertThat(entry.severity()).isEqualTo(Severity.ERROR);
        assertThat(entry.rawSeverity()).contains("false");
        assertThat(entry.message()).hasValueSatisfying(message ->
            assertThat(message).contains("org.xml.sax.SAXParseException"));
    }

    @Test
    void queueLogRowsMapTheStatusToTheSeverityTable() {
        stub(LogType.QUEUE_LOG, json(response("queueLog", 4, List.of(
            Map.of("status", Map.of("level", 0, "content", "Error"), "startTime", 4L,
                "workflowName", "W", "moduleName", "M", "workflowId", 1, "globalPId",
                "813399ff-d909-4011-b4e4-3226deaf1df3", "nextStartTime", ""),
            Map.of("status", Map.of("level", 1, "content", "Waiting"), "startTime", 3L),
            Map.of("status", Map.of("level", 1, "content", "Queued"), "startTime", 2L),
            Map.of("status", Map.of("level", 1, "content", "Suspended"), "startTime", 1L)))));

        List<LogEntry> entries = adapter().query(query(LogType.QUEUE_LOG)).items();

        assertThat(entries).extracting(LogEntry::severity).containsExactly(Severity.ERROR,
            Severity.WARN, Severity.INFO, Severity.OTHER);
        assertThat(entries).extracting(entry -> entry.rawSeverity().orElseThrow())
            .containsExactly("Error", "Waiting", "Queued", "Suspended");
        LogEntry first = entries.get(0);
        assertThat(first.workflow()).contains("W");
        assertThat(first.module()).contains("M");
        assertThat(first.processId()).contains("1");
        assertThat(first.fields()).containsEntry("globalPId",
            "813399ff-d909-4011-b4e4-3226deaf1df3").doesNotContainKey("nextStartTime");
    }

    @Test
    void valueShapeVariantsAreAccepted() {
        stub(LogType.KEY_MANAGER_LOG, RestFixtures.response("log_keyManagerLog", "json"));
        stub(LogType.WEBSERVICE_MANAGER, RestFixtures.response("log_webserviceManager",
            "json"));
        stub(LogType.SCHEDULER_LOG, RestFixtures.response("log_schedulerLog", "json"));
        stub(LogType.AUDIT_LOG, json(response("auditLog", 1, List.of(Map.of(
            "success", Map.of("level", 0, "content", false), "time", 7L, "user", "user1")))));

        List<LogEntry> keys = adapter().query(query(LogType.KEY_MANAGER_LOG)).items();
        assertThat(keys.get(0).timestamp()).contains(Instant.ofEpochMilli(1_300_703_251_000L));
        assertThat(keys.get(2).timestamp()).contains(Instant.ofEpochMilli(87_766_209_626_000L));
        assertThat(keys).extracting(LogEntry::severity).containsOnly(Severity.OTHER);
        assertThat(keys).allSatisfy(entry -> assertThat(entry.rawSeverity()).isEmpty());

        List<LogEntry> services = adapter().query(query(LogType.WEBSERVICE_MANAGER)).items();
        assertThat(services).extracting(entry -> entry.fields().get("webserviceStatus"))
            .containsExactly("true", "true", "workflowNotActive", "true", "true");
        assertThat(services).allSatisfy(entry -> assertThat(entry.timestamp()).isEmpty());
        assertThat(services.get(0).module()).contains("Module-0038");

        List<LogEntry> scheduler = adapter().query(query(LogType.SCHEDULER_LOG)).items();
        assertThat(scheduler.get(0).timestamp()).contains(Instant.ofEpochMilli(
            1_790_863_200_491L));
        assertThat(scheduler.get(2).timestamp()).isEmpty();

        LogEntry audit = adapter().query(query(LogType.AUDIT_LOG)).items().get(0);
        assertThat(audit.severity()).isEqualTo(Severity.ERROR);
        assertThat(audit.rawSeverity()).contains("false");
        assertThat(audit.timestamp()).contains(Instant.ofEpochMilli(7));
        assertThat(audit.fields()).containsEntry("user", "user1");
    }

    @Test
    void aResponseWithoutRowsIsAnEmptyPage() {
        stub(LogType.SYSTEM_LOG, json("{\"systemLog\":{\"total\":0,\"success\":true,"
            + "\"count\":0}}"));

        Page<LogEntry> page = adapter().query(query(LogType.SYSTEM_LOG));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).hasValue(0);
        assertThat(page.nextOffset()).isEmpty();
        assertThat(page.truncated()).isFalse();
    }

    // --- size bounds ---------------------------------------------------------------------------

    @Test
    void oversizedRowsAreCutToTheItemBoundAndMarkThePageTruncated() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("success", false);
        row.put("startTime", 1_790_860_368_050L);
        row.put("workflowName", "W");
        row.put("message", "m".repeat(10_000));
        for (int i = 0; i < 40; i++) {
            row.put("column" + i, "v".repeat(1_000));
        }
        stub(LogType.SYSTEM_LOG, json(response("systemLog", 1, List.of(row))));

        Page<LogEntry> page = adapter().query(query(LogType.SYSTEM_LOG));

        assertThat(page.truncated()).isTrue();
        LogEntry entry = page.items().get(0);
        assertThat(ResultJson.size(entry)).isLessThanOrEqualTo(ResultLimiter.MAX_ITEM_CHARS);
        assertThat(entry.message()).hasValueSatisfying(message -> {
            assertThat(message).endsWith(ResultLimiter.TRUNCATION_MARKER);
            assertThat(message.length()).isLessThanOrEqualTo(ResultLimiter.MAX_MESSAGE_CHARS);
        });
        assertThat(entry.fields()).isNotEmpty().hasSizeLessThan(40);
        assertThat(entry.fields()).containsKey("column0");
        assertThat(entry.fields().values()).allSatisfy(value -> {
            assertThat(value).hasSize(200);
            assertThat(value).endsWith(ResultLimiter.TRUNCATION_MARKER);
        });
        assertThat(entry.workflow()).contains("W");
    }

    @Test
    void aLongMessageIsCutToTwoThousandChars() {
        stub(LogType.SYSTEM_LOG, json(response("systemLog", 1, List.of(Map.of(
            "success", false, "startTime", 1L, "message", "x".repeat(3_000))))));

        Page<LogEntry> page = adapter().query(query(LogType.SYSTEM_LOG));

        assertThat(page.truncated()).isTrue();
        assertThat(page.items().get(0).message()).hasValueSatisfying(message -> {
            assertThat(message).hasSize(ResultLimiter.MAX_MESSAGE_CHARS);
            assertThat(message).endsWith(ResultLimiter.TRUNCATION_MARKER);
        });
    }

    @Test
    void manyLongFieldsAreDroppedFromTheEndBeforeTheMessageIsCutFurther() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("success", true);
        row.put("startTime", 1L);
        row.put("message", "short message");
        for (int i = 0; i < 40; i++) {
            row.put("column" + i, "v".repeat(300));
        }
        stub(LogType.SYSTEM_LOG, json(response("systemLog", 1, List.of(row))));

        LogEntry entry = adapter().query(query(LogType.SYSTEM_LOG)).items().get(0);

        assertThat(entry.message()).contains("short message");
        assertThat(entry.fields().keySet()).startsWith("column0", "column1");
        assertThat(entry.fields()).doesNotContainKey("column39");
        assertThat(ResultJson.size(entry)).isLessThanOrEqualTo(ResultLimiter.MAX_ITEM_CHARS);
    }

    @Test
    void escapeHeavyRowsAreShrunkToTheItemBoundWithoutFailing() {
        // review D1: control chars, quotes and backslashes serialize to several chars each
        for (String unit : List.of("\u0001", "\"\\")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", Map.of("level", 0, "content", unit.repeat(500)));
            row.put("startTime", 1L);
            row.put("workflowName", unit.repeat(500));
            row.put("moduleName", unit.repeat(500));
            row.put("workflowId", unit.repeat(500));
            row.put("message", unit.repeat(3_000));
            for (int i = 0; i < 40; i++) {
                row.put("column" + i, unit.repeat(1_000));
            }
            stub(LogType.QUEUE_LOG, json(response("queueLog", 1, List.of(row))));

            Page<LogEntry> page = adapter().query(query(LogType.QUEUE_LOG));

            assertThat(page.truncated()).isTrue();
            assertThat(ResultJson.size(page.items().get(0))).as(unit)
                .isLessThanOrEqualTo(ResultLimiter.MAX_ITEM_CHARS);
        }
    }

    // --- merged requests, validation and failures ------------------------------------------

    @Test
    void mergedRequestsAnsweredWith401MakeExactlyOneAuthenticatedRequest() {
        // review D7: the credential guard lets one login attempt through while unconfirmed
        stub(LogType.QUEUE_LOG, aResponse().withStatus(401)
            .withHeader("Content-Type", "text/html").withBody("<html>401</html>"));

        ToolError error = errorOf(() -> adapter().query(new LogQuery(LogType.QUEUE_LOG,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Set.of(Severity.WARN, Severity.INFO), Optional.empty(), 0, 5)));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(wireMock.findAll(postRequestedFor(anyUrl())
            .withHeader("Authorization", matching("Basic .+")))).hasSize(1);
    }

    @Test
    void aSeverityWithTwoRawValuesMergesTwoRequestsNewestFirst() {
        wireMock.stubFor(post(urlPathEqualTo("/ibis/rest/log/queueLog"))
            .withRequestBody(containing("<value>Waiting</value>"))
            .willReturn(json(response("queueLog", 2, List.of(
                Map.of("status", Map.of("level", 1, "content", "Waiting"), "startTime", 30L),
                Map.of("status", Map.of("level", 1, "content", "Waiting"), "startTime", 10L))))));
        wireMock.stubFor(post(urlPathEqualTo("/ibis/rest/log/queueLog"))
            .withRequestBody(containing("<value>Retry</value>"))
            .willReturn(json(response("queueLog", 1, List.of(
                Map.of("status", Map.of("level", 1, "content", "Retry"), "startTime", 20L))))));

        Page<LogEntry> page = adapter().query(new LogQuery(LogType.QUEUE_LOG, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Set.of(Severity.WARN),
            Optional.empty(), 0, 5));

        assertThat(page.items()).extracting(entry -> entry.timestamp().orElseThrow()
            .toEpochMilli()).containsExactly(30L, 20L, 10L);
        assertThat(page.total()).hasValue(3);
        assertThat(wireMock.findAll(postRequestedFor(anyUrl()))).hasSize(2)
            .allSatisfy(request -> assertThat(request.getBodyAsString())
                .contains("<startIndex>0</startIndex>", "<noOfItems>5</noOfItems>"));
    }

    @Test
    void anUnsupportedFilterIsInvalidInputWithoutARequest() {
        LogQuery query = new LogQuery(LogType.AUDIT_LOG, Optional.empty(), Optional.empty(),
            Optional.of("W"), Optional.empty(), Set.of(), Optional.empty(), 0, 5);

        ToolError validated = errorOfAnyServer(() -> adapter().validate(query));
        ToolError queried = errorOfAnyServer(() -> adapter().query(query));

        assertThat(validated.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(queried.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        // review D8: the filter table is the same for every server
        assertThat(validated.node()).isEmpty();
        assertThat(queried.node()).isEmpty();
        assertThat(wireMock.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    private static ToolError errorOfAnyServer(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    @Test
    void anHtmlErrorPageIsAnUnexpectedResponseWithAScrubbedExcerpt() {
        stub(LogType.SYSTEM_LOG, aResponse().withStatus(400)
            .withHeader("Content-Type", "text/html;charset=UTF-8")
            .withBody("<html><body><h1>Bad Request</h1>" + PASSWORD + "</body></html>"));

        ToolError error = errorOf(() -> adapter().query(query(LogType.SYSTEM_LOG)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> {
            assertThat(excerpt).contains("Bad Request");
            assertThat(excerpt).doesNotContain(PASSWORD);
        });
    }

    @Test
    void aBodyThatIsNotTheLogJsonIsAnUnexpectedResponse() {
        stub(LogType.SYSTEM_LOG, json("{\"somethingElse\":{}}"));
        assertThat(errorOf(() -> adapter().query(query(LogType.SYSTEM_LOG))).code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);

        stub(LogType.SYSTEM_LOG, aResponse().withStatus(200)
            .withHeader("Content-Type", "text/html").withBody("<html>login</html>"));
        assertThat(errorOf(() -> adapter().query(query(LogType.SYSTEM_LOG))).code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }
}
