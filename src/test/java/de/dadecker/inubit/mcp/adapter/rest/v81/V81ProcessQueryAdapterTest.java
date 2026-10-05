package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.charset.StandardCharsets;
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
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

/**
 * T066: {@code find_processes} on the 8.1 Queue Manager view {@code POST /ibis/rest/log/queueLog}
 * (research R-8, R-9; spike S-4).
 */
@Timeout(30)
class V81ProcessQueryAdapterTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String PATH = "/ibis/rest/log/queueLog";
    private static final String PASSWORD = "process-adapter-test-pw";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(60);
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

    private V81ProcessQueryAdapter adapter() {
        return new V81ProcessQueryAdapter(ID, client);
    }

    private static ProcessQuery query(Set<ProcessState> states, boolean hangingOnly,
        Optional<String> workflow, Optional<String> tag, Optional<Instant> since,
        Optional<Instant> until, int offset, int limit) {
        return new ProcessQuery(states, hangingOnly, THRESHOLD, workflow, tag, since, until,
            offset, limit, NOW);
    }

    private static ProcessQuery states(Set<ProcessState> states, int offset, int limit) {
        return query(states, false, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), offset, limit);
    }

    private static ResponseDefinitionBuilder json(long total, List<Map<String, Object>> rows) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("total", total);
        content.put("success", true);
        content.put("count", rows.size());
        if (!rows.isEmpty()) {
            content.put("row", rows);
        }
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody(JSON.writeValueAsString(Map.of("queueLog", content)));
    }

    private static Map<String, Object> row(long workflowId, String status, long startTime,
        String module) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workflowId", workflowId);
        row.put("globalPId", workflowId);
        row.put("workflowName", "W");
        row.put("moduleName", module);
        row.put("startTime", startTime);
        row.put("status", Map.of("level", 0, "content", status));
        return row;
    }

    private static void stubStatus(String status, ResponseDefinitionBuilder response) {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .withRequestBody(containing("<value>" + status + "</value>"))
            .willReturn(response));
    }

    private static List<String> bodies() {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("format", equalTo("json"))).stream()
            .map(request -> request.getBodyAsString()).toList();
    }

    private static List<String> filters(String body) {
        Element root = XmlSupport.parse(body.getBytes(StandardCharsets.UTF_8))
            .getDocumentElement();
        return XmlSupport.descendants(root, "filtering").stream().map(filtering -> {
            String field = XmlSupport.descendants(filtering, "field").get(0).getTextContent();
            String comparison = XmlSupport.descendants(filtering, "comparison").get(0)
                .getTextContent();
            List<Element> value = XmlSupport.descendants(filtering, "value");
            return value.isEmpty()
                ? field + " " + comparison + " "
                    + XmlSupport.descendants(filtering, "min").get(0).getTextContent() + ".."
                    + XmlSupport.descendants(filtering, "max").get(0).getTextContent()
                : field + " " + comparison + " " + value.get(0).getTextContent();
        }).toList();
    }

    private static String element(String body, String name) {
        return XmlSupport.descendants(XmlSupport.parse(body.getBytes(StandardCharsets.UTF_8))
            .getDocumentElement(), name).get(0).getTextContent();
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    // --- requests ------------------------------------------------------------------------------

    @Test
    void withoutStatesASingleRequestWithoutStatusFilterIsSent() {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(RestFixtures.response("log_queueLog", "json")));

        Page<ProcessInstance> page = adapter().find(states(Set.of(), 40, 5));

        assertThat(bodies()).singleElement().satisfies(body -> {
            assertThat(filters(body)).isEmpty();
            assertThat(element(body, "startIndex")).isEqualTo("40");
            assertThat(element(body, "noOfItems")).isEqualTo("5");
            assertThat(element(body, "order")).isEqualTo("DESCENDING");
            assertThat(XmlSupport.descendants(XmlSupport.parse(body.getBytes(
                    StandardCharsets.UTF_8)).getDocumentElement(), "sorting").get(0)
                .getTextContent()).contains("startTime");
        });
        assertThat(page.total()).hasValue(706);
        assertThat(page.items()).hasSize(5);
        assertThat(page.offset()).isEqualTo(40);
        assertThat(page.nextOffset()).hasValue(45);
        assertThat(page.items().get(0).processId()).isEqualTo("110190387");
    }

    @Test
    void oneStateWithOneRawValueIsASingleStatusRequest() {
        stubStatus("Error", RestFixtures.response("log_queueLog_error", "json"));

        Page<ProcessInstance> page = adapter().find(states(Set.of(ProcessState.ERROR), 10, 5));

        assertThat(bodies()).singleElement().satisfies(body -> {
            assertThat(filters(body)).containsExactly("status EQUAL Error");
            assertThat(element(body, "startIndex")).isEqualTo("10");
            assertThat(element(body, "noOfItems")).isEqualTo("5");
        });
        assertThat(page.items()).extracting(ProcessInstance::state)
            .containsOnly(ProcessState.ERROR);
        assertThat(page.total()).hasValue(706);
    }

    @Test
    void waitingIsMergedFromWaitingAndRetrySortedSlicedAndTotalled() {
        stubStatus("Waiting", json(10, List.of(row(1, "Waiting", 500, "a"),
            row(2, "Waiting", 300, "b"), row(3, "Waiting", 100, "c"))));
        stubStatus("Retry", json(2, List.of(row(4, "Retry", 400, "d"),
            row(5, "Retry", 200, "e"))));

        Page<ProcessInstance> page = adapter().find(states(Set.of(ProcessState.WAITING), 1, 3));

        assertThat(bodies()).hasSize(2).allSatisfy(body -> {
            assertThat(element(body, "startIndex")).isEqualTo("0");
            assertThat(element(body, "noOfItems")).isEqualTo("4");
        });
        assertThat(bodies()).extracting(V81ProcessQueryAdapterTest::filters)
            .containsExactlyInAnyOrder(List.of("status EQUAL Waiting"),
                List.of("status EQUAL Retry"));
        // merged 500 400 300 200 100 → [1, 4) = 400 300 200
        assertThat(page.items()).extracting(instance -> instance.since().toEpochMilli())
            .containsExactly(400L, 300L, 200L);
        assertThat(page.items()).extracting(ProcessInstance::rawState)
            .containsExactly("Retry", "Waiting", "Retry");
        assertThat(page.total()).hasValue(12);
        assertThat(page.nextOffset()).hasValue(4);
    }

    @Test
    void severalStatesAreOneRequestPerRawValue() {
        stubStatus("Error", json(0, List.of()));
        stubStatus("Queued", json(0, List.of()));

        Page<ProcessInstance> page = adapter().find(states(
            Set.of(ProcessState.QUEUED, ProcessState.ERROR), 0, 5));

        assertThat(bodies()).extracting(V81ProcessQueryAdapterTest::filters)
            .containsExactlyInAnyOrder(List.of("status EQUAL Error"),
                List.of("status EQUAL Queued"));
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).hasValue(0);
    }

    @Test
    void workflowTagAndTimeRangeAreServerSideFilters() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(json(0, List.of())));
        Instant since = Instant.parse("2026-09-30T12:00:00Z");
        Instant until = Instant.parse("2026-10-01T11:00:00Z");

        adapter().find(query(Set.of(), false, Optional.of("Order Import & <Co>"),
            Optional.of("v1"), Optional.of(since), Optional.of(until), 0, 5));
        adapter().find(query(Set.of(), false, Optional.empty(), Optional.empty(),
            Optional.of(since), Optional.empty(), 0, 5));

        List<String> bodies = bodies();
        assertThat(bodies.get(0)).contains("Order Import &amp; &lt;Co&gt;");
        assertThat(filters(bodies.get(0))).containsExactly(
            "startTime BETWEEN " + since.toEpochMilli() + ".." + until.toEpochMilli(),
            "workflowName EQUAL Order Import & <Co>",
            "tag EQUAL v1");
        assertThat(filters(bodies.get(1))).containsExactly(
            "startTime GREATER " + (since.toEpochMilli() - 1));
    }

    @Test
    void hangingOnlyQueriesTheNonFinalRawStatesOlderThanTheThreshold() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(json(0, List.of())));

        adapter().find(query(Set.of(), true, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), 0, 5));

        long hangingBefore = NOW.minus(THRESHOLD).toEpochMilli();
        assertThat(bodies()).extracting(V81ProcessQueryAdapterTest::filters)
            .containsExactlyInAnyOrder(
                List.of("startTime LESSER " + hangingBefore, "status EQUAL Processing"),
                List.of("startTime LESSER " + hangingBefore, "status EQUAL Waiting"),
                List.of("startTime LESSER " + hangingBefore, "status EQUAL Retry"),
                List.of("startTime LESSER " + hangingBefore, "status EQUAL Queued"));
    }

    @Test
    void hangingOnlyIsIntersectedWithTheStates() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(json(0, List.of())));

        adapter().find(query(Set.of(ProcessState.WAITING, ProcessState.ERROR), true,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 5));

        assertThat(bodies()).extracting(body -> filters(body).get(1))
            .containsExactlyInAnyOrder("status EQUAL Waiting", "status EQUAL Retry");
    }

    @Test
    void errorWithHangingOnlyIsAnEmptyPageWithoutRequest() {
        Page<ProcessInstance> page = adapter().find(query(Set.of(ProcessState.ERROR), true,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 5));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).hasValue(0);
        assertThat(page.nextOffset()).isEmpty();
        assertThat(wireMock.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void offsetPlusLimitAboveTenThousandIsInvalidInputWithoutRequest() {
        ToolError error = errorOf(() -> adapter().find(states(Set.of(), 9_960, 50)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(wireMock.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    // --- rows ----------------------------------------------------------------------------------

    @Test
    void rowsWithTheSameProcessIdStaySeparate() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(json(3, List.of(
            row(7, "Error", 300, "a"), row(7, "Error", 200, "b"), row(8, "Error", 100, "c")))));

        Page<ProcessInstance> page = adapter().find(states(Set.of(), 0, 5));

        assertThat(page.items()).extracting(ProcessInstance::processId)
            .containsExactly("7", "7", "8");
        assertThat(page.items()).extracting(instance -> instance.module().orElseThrow())
            .containsExactly("a", "b", "c");
        assertThat(page.nextOffset()).isEmpty();
    }

    @Test
    void anEmptyResultIsAnEmptyPage() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"queueLog\":{\"total\":0,\"success\":true,\"count\":0}}")));

        Page<ProcessInstance> page = adapter().find(states(Set.of(), 0, 5));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).hasValue(0);
        assertThat(page.truncated()).isFalse();
    }

    @Test
    void aCutRowMarksThePageTruncated() {
        Map<String, Object> row = row(7, "Error", 300, "m".repeat(5_000));
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(json(1, List.of(row))));

        Page<ProcessInstance> page = adapter().find(states(Set.of(), 0, 5));

        assertThat(page.truncated()).isTrue();
        assertThat(page.items().get(0).module()).hasValueSatisfying(module ->
            assertThat(module).hasSizeLessThanOrEqualTo(200));
    }

    @Test
    void findByProcessIdFiltersOnTheWorkflowIdAndKeepsEveryRow() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(json(2, List.of(
            row(110190387, "Error", 300, "a"), row(110190387, "Error", 200, "b")))));

        ProcessRows rows = adapter().findByProcessId("110190387", NOW, THRESHOLD);

        assertThat(rows.rows()).hasSize(2).extracting(ProcessInstance::processId)
            .containsOnly("110190387");
        assertThat(rows.total()).isEqualTo(2);
        assertThat(rows.truncated()).isFalse();
        assertThat(bodies()).singleElement().satisfies(body -> {
            assertThat(filters(body)).containsExactly("workflowId EQUAL 110190387");
            assertThat(element(body, "startIndex")).isEqualTo("0");
            assertThat(element(body, "noOfItems")).isEqualTo("100");
        });
    }

    @Test
    void findByProcessIdReportsWhenTheRowCapWasHit() {
        // review D6: US4 must know that it did not see every row
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < ProcessQueryPort.MAX_ROWS_PER_PROCESS; i++) {
            rows.add(row(7, "Error", 1_000 - i, "m" + i));
        }
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(json(150, rows)));

        ProcessRows result = adapter().findByProcessId("7", NOW, THRESHOLD);

        assertThat(result.rows()).hasSize(ProcessQueryPort.MAX_ROWS_PER_PROCESS);
        assertThat(result.total()).isEqualTo(150);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void mergedRequestsAnsweredWith401MakeExactlyOneAuthenticatedRequest() {
        // review D7: hangingOnly = 4 requests; the guard lets one login attempt through
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)
            .withHeader("Content-Type", "text/html").withBody("<html>401</html>")));

        ToolError error = errorOf(() -> adapter().find(query(Set.of(), true, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), 0, 5)));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH))
            .withHeader("Authorization",
                com.github.tomakehurst.wiremock.client.WireMock.matching("Basic .+"))))
            .hasSize(1);
    }

    @Test
    void valuesThatCannotBeSentAreInvalidInputWithoutServerAndRequest() {
        // review D5, D8
        ProcessQuery badWorkflow = query(Set.of(), false, Optional.of("W\u0001"),
            Optional.empty(), Optional.empty(), Optional.empty(), 0, 5);
        ProcessQuery badTag = query(Set.of(), false, Optional.empty(), Optional.of("\u0002"),
            Optional.empty(), Optional.empty(), 0, 5);
        ProcessQuery tooDeep = states(Set.of(), 9_960, 50);

        for (ProcessQuery query : List.of(badWorkflow, badTag, tooDeep)) {
            ToolError validated = errorOf(() -> adapter().validate(query));
            ToolError found = errorOf(() -> adapter().find(query));
            assertThat(validated.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(validated.node()).isEmpty();
            assertThat(found.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(found.node()).isEmpty();
        }
        assertThat(wireMock.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void anHtmlErrorPageIsAnUnexpectedResponseWithAScrubbedExcerpt() {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
            .willReturn(RestFixtures.response("log_processLog", "html")));

        ToolError error = errorOf(() -> adapter().find(states(Set.of(), 0, 5)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).contains("HTTP 500");
        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> {
            assertThat(excerpt).doesNotContain("<html", PASSWORD);
            assertThat(excerpt).isNotBlank();
        });
    }
}
