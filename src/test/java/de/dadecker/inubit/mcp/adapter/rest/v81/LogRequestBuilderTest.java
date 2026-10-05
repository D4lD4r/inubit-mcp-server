package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.LogRequest;
import de.dadecker.inubit.mcp.adapter.rest.v81.LogRequestPlan.Window;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * T067: the {@code <logRequest>} bodies (research R-9, spike S-5), the paging rule and the
 * per-log-type filter table (contracts/mcp-tools.md §4).
 */
class LogRequestBuilderTest {

    private static final Instant SINCE = Instant.ofEpochMilli(1_790_258_322_000L);
    private static final Instant UNTIL = Instant.ofEpochMilli(1_790_863_122_000L);

    private static LogQuery query(LogType type) {
        return new LogQuery(type, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Set.of(), Optional.empty(), 0, 50);
    }

    private static LogQuery with(LogQuery q, Optional<Instant> since, Optional<Instant> until,
        Optional<String> workflow, Optional<String> processId, Set<Severity> severities,
        Optional<String> text, int offset, int limit) {
        return new LogQuery(q.logType(), since, until, workflow, processId, severities, text,
            offset, limit);
    }

    private static ToolError invalid(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected INVALID_INPUT");
    }

    private static Element root(String xml) {
        Document document = XmlSupport.parse(xml.getBytes(StandardCharsets.UTF_8));
        return document.getDocumentElement();
    }

    private static List<String> filterTexts(String xml) {
        return XmlSupport.descendants(root(xml), "filtering").stream()
            .map(filtering -> XmlSupport.children(filtering, "field").get(0).getTextContent()
                + " " + XmlSupport.children(filtering, "comparison").get(0).getTextContent()
                + " " + String.join("..", XmlSupport.descendants(filtering, "value").stream()
                    .map(Element::getTextContent).toList())
                + String.join("..", XmlSupport.descendants(filtering, "min").stream()
                    .map(Element::getTextContent).toList())
                + (XmlSupport.descendants(filtering, "max").isEmpty() ? ""
                    : ".." + XmlSupport.descendants(filtering, "max").get(0).getTextContent()))
            .toList();
    }

    private static String text(String xml, String element) {
        return XmlSupport.descendants(root(xml), element).get(0).getTextContent();
    }

    // --- document shape ----------------------------------------------------------------------

    @Test
    void theDocumentMatchesTheRecordedSystemLogRequest() {
        String xml = LogRequestBuilder.xml(0, 5, List.of(
                new LogFilter.Between("startTime", SINCE.toEpochMilli(), UNTIL.toEpochMilli()),
                new LogFilter.Like("message", "error")),
            Optional.of("startTime"));

        assertThat(xml.strip().replace("\r\n", "\n"))
            .isEqualTo(RestFixtures.text("log_systemLog_filtered.request.xml").strip()
                .replace("\r\n", "\n"));
    }

    @Test
    void theDocumentMatchesTheRecordedQueueLogRequest() {
        String xml = LogRequestBuilder.xml(0, 5, List.of(new LogFilter.Equal("status", "Error")),
            Optional.of("startTime"));

        assertThat(xml.strip().replace("\r\n", "\n"))
            .isEqualTo(RestFixtures.text("log_queueLog_error.request.xml").strip()
                .replace("\r\n", "\n"));
    }

    @Test
    void noOfItemsIsAlwaysSentAndSortingIsOmittedWithoutTimeField() {
        String xml = LogRequestBuilder.xml(20, 10, List.of(), Optional.empty());

        assertThat(text(xml, "startIndex")).isEqualTo("20");
        assertThat(text(xml, "noOfItems")).isEqualTo("10");
        assertThat(XmlSupport.descendants(root(xml), "sorting")).isEmpty();
        assertThat(XmlSupport.descendants(root(xml), "filtering")).isEmpty();
    }

    @Test
    void valuesAreXmlEscapedAndLikeWildcardsArePassedUnchanged() {
        String xml = LogRequestBuilder.xml(0, 5, List.of(
                new LogFilter.Equal("workflowName", "A&B <x> \"q\" 'a'"),
                new LogFilter.Like("message", "G_IP%</value><x>")),
            Optional.of("startTime"));

        assertThat(xml).doesNotContain("<x>").contains("A&amp;B &lt;x&gt;");
        assertThat(filterTexts(xml)).containsExactly(
            "workflowName EQUAL A&B <x> \"q\" 'a'",
            "message LIKE G_IP%</value><x>");
    }

    @Test
    void timeRangesAreBetweenOrOpenGreaterLesserInEpochMillis() {
        assertThat(LogFilter.timeRange("startTime", Optional.of(SINCE), Optional.of(UNTIL)))
            .contains(new LogFilter.Between("startTime", SINCE.toEpochMilli(),
                UNTIL.toEpochMilli()));
        assertThat(LogFilter.timeRange("startTime", Optional.of(SINCE), Optional.empty()))
            .contains(new LogFilter.Greater("startTime", SINCE.toEpochMilli() - 1));
        assertThat(LogFilter.timeRange("time", Optional.empty(), Optional.of(UNTIL)))
            .contains(new LogFilter.Lesser("time", UNTIL.toEpochMilli() + 1));
        assertThat(LogFilter.timeRange("time", Optional.empty(), Optional.empty())).isEmpty();

        String xml = LogRequestBuilder.xml(0, 5, List.of(
            new LogFilter.Greater("startTime", 5), new LogFilter.Lesser("endTime", 9)),
            Optional.of("startTime"));
        assertThat(filterTexts(xml)).containsExactly("startTime GREATER 5", "endTime LESSER 9");
    }

    // --- query_logs mapping (filter table) -------------------------------------------------

    @Test
    void aSystemLogQueryUsesTheTableFieldsInASingleRequest() {
        LogRequestPlan plan = LogRequestBuilder.plan(with(query(LogType.SYSTEM_LOG),
            Optional.of(SINCE), Optional.of(UNTIL), Optional.of("Workflow-0424"),
            Optional.of("110219899"), Set.of(Severity.ERROR), Optional.of("SAX%Exception"),
            40, 20));

        assertThat(plan.merged()).isFalse();
        LogRequest request = plan.requests().get(0);
        assertThat(plan.requests()).hasSize(1);
        assertThat(request.startIndex()).isEqualTo(40);
        assertThat(request.noOfItems()).isEqualTo(20);
        assertThat(text(request.body(), "startIndex")).isEqualTo("40");
        assertThat(text(request.body(), "noOfItems")).isEqualTo("20");
        assertThat(filterTexts(request.body())).containsExactly(
            "startTime BETWEEN " + SINCE.toEpochMilli() + ".." + UNTIL.toEpochMilli(),
            "workflowName EQUAL Workflow-0424",
            "workflowId EQUAL 110219899",
            "message LIKE SAX%Exception",
            "success EQUAL false");
        assertThat(text(request.body(), "order")).isEqualTo("DESCENDING");
        assertThat(XmlSupport.descendants(root(request.body()), "sorting").get(0)
            .getElementsByTagName("field").item(0).getTextContent()).isEqualTo("startTime");
    }

    @Test
    void aNonNumericProcessIdIsTheGlobalProcessId() {
        LogRequestPlan plan = LogRequestBuilder.plan(with(query(LogType.QUEUE_LOG),
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of("813399ff-d909-4011-b4e4-3226deaf1df3"), Set.of(), Optional.empty(), 0,
            50));

        assertThat(filterTexts(plan.requests().get(0).body()))
            .containsExactly("globalPId EQUAL 813399ff-d909-4011-b4e4-3226deaf1df3");
    }

    @Test
    void eachLogTypeUsesItsTimeAndTextField() {
        record Expected(LogType type, String time, String text) {
        }
        for (Expected expected : List.of(
            new Expected(LogType.SYSTEM_LOG, "startTime", "message"),
            new Expected(LogType.QUEUE_LOG, "startTime", "moduleName"),
            new Expected(LogType.AUDIT_LOG, "time", "message"),
            new Expected(LogType.SCHEDULER_LOG, "nextStartTime", "moduleName"),
            new Expected(LogType.CONNECTION_LOG, "lastConnection", "systemType"),
            new Expected(LogType.KEY_MANAGER_LOG, "validity", "name"))) {
            LogRequestPlan plan = LogRequestBuilder.plan(with(query(expected.type()),
                Optional.of(SINCE), Optional.empty(), Optional.empty(), Optional.empty(),
                Set.of(), Optional.of("x"), 0, 5));
            String body = plan.requests().get(0).body();
            assertThat(filterTexts(body)).as(expected.type().logName()).containsExactly(
                expected.time() + " GREATER " + (SINCE.toEpochMilli() - 1),
                expected.text() + " LIKE x");
            assertThat(XmlSupport.descendants(root(body), "sorting").get(0)
                .getElementsByTagName("field").item(0).getTextContent())
                .isEqualTo(expected.time());
        }
        LogRequestPlan webservices = LogRequestBuilder.plan(with(
            query(LogType.WEBSERVICE_MANAGER), Optional.empty(), Optional.empty(),
            Optional.of("W"), Optional.empty(), Set.of(), Optional.of("y"), 0, 5));
        String body = webservices.requests().get(0).body();
        assertThat(filterTexts(body)).containsExactly("workflowName EQUAL W",
            "moduleName LIKE y");
        assertThat(XmlSupport.descendants(root(body), "sorting")).isEmpty();
    }

    @Test
    void aSeverityWithSeveralRawValuesIsMergedRequestsWithTheWindowFromZero() {
        LogRequestPlan plan = LogRequestBuilder.plan(with(query(LogType.QUEUE_LOG),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Set.of(Severity.WARN), Optional.empty(), 30, 20));

        assertThat(plan.merged()).isTrue();
        assertThat(plan.requests()).extracting(LogRequest::startIndex).containsOnly(0);
        assertThat(plan.requests()).extracting(LogRequest::noOfItems).containsOnly(50);
        assertThat(plan.requests()).extracting(request -> filterTexts(request.body()))
            .containsExactly(List.of("status EQUAL Waiting"), List.of("status EQUAL Retry"));
        assertThat(plan.requests()).extracting(request -> text(request.body(), "noOfItems"))
            .containsOnly("50");
    }

    @Test
    void severitiesOfSystemLogAreOneRequestPerSuccessValue() {
        LogRequestPlan plan = LogRequestBuilder.plan(with(query(LogType.SYSTEM_LOG),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Set.of(Severity.INFO, Severity.ERROR), Optional.empty(), 0, 10));

        assertThat(plan.merged()).isTrue();
        assertThat(plan.requests()).extracting(request -> filterTexts(request.body()))
            .containsExactly(List.of("success EQUAL false"), List.of("success EQUAL true"));
    }

    @Test
    void severitiesOfQueueLogFollowTheSeverityTable() {
        LogRequestPlan plan = LogRequestBuilder.plan(with(query(LogType.QUEUE_LOG),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Set.of(Severity.ERROR, Severity.INFO), Optional.empty(), 0, 10));

        assertThat(plan.requests()).extracting(request -> filterTexts(request.body()))
            .containsExactly(List.of("status EQUAL Error"), List.of("status EQUAL Queued"),
                List.of("status EQUAL Processing"));
    }

    // --- rejections ----------------------------------------------------------------------------

    @Test
    void unsupportedFiltersAreInvalidInputNamingTheSupportedOnes() {
        ToolError workflow = invalid(() -> LogRequestBuilder.validate(with(
            query(LogType.AUDIT_LOG), Optional.empty(), Optional.empty(), Optional.of("W"),
            Optional.empty(), Set.of(), Optional.empty(), 0, 5)));
        assertThat(workflow.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(workflow.message()).contains("workflow", "auditLog");
        assertThat(workflow.nextStep()).contains("since/until", "severity", "text");

        ToolError processId = invalid(() -> LogRequestBuilder.validate(with(
            query(LogType.SCHEDULER_LOG), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of("1"), Set.of(), Optional.empty(), 0, 5)));
        assertThat(processId.message()).contains("processId", "schedulerLog");

        ToolError time = invalid(() -> LogRequestBuilder.validate(with(
            query(LogType.WEBSERVICE_MANAGER), Optional.of(SINCE), Optional.empty(),
            Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), 0, 5)));
        assertThat(time.message()).contains("since", "webserviceManager");
    }

    @Test
    void severitiesOutsideTheSeverityTableAreInvalidInput() {
        ToolError debug = invalid(() -> LogRequestBuilder.validate(with(
            query(LogType.SYSTEM_LOG), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Set.of(Severity.DEBUG), Optional.empty(), 0, 5)));
        assertThat(debug.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(debug.message()).contains("DEBUG", "systemLog");
        assertThat(debug.nextStep()).contains("ERROR", "INFO");

        ToolError warn = invalid(() -> LogRequestBuilder.validate(with(
            query(LogType.AUDIT_LOG), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Set.of(Severity.WARN), Optional.empty(), 0, 5)));
        assertThat(warn.message()).contains("WARN");
    }

    @ParameterizedTest
    @EnumSource(value = LogType.class, names = {"CONNECTION_LOG", "SCHEDULER_LOG",
        "KEY_MANAGER_LOG", "WEBSERVICE_MANAGER"})
    void logTypesWithoutSeverityFieldRejectEverySeverity(LogType type) {
        ToolError error = invalid(() -> LogRequestBuilder.validate(with(query(type),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Set.of(Severity.ERROR), Optional.empty(), 0, 5)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("severity", type.logName());
    }

    @Test
    void offsetPlusLimitAboveTenThousandIsInvalidInputWithoutAPlan() {
        ToolError single = invalid(() -> LogRequestBuilder.plan(with(query(LogType.SYSTEM_LOG),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Set.of(),
            Optional.empty(), 9_951, 50)));
        ToolError merged = invalid(() -> LogRequestBuilder.plan(List.of(), "status",
            List.of("Waiting", "Retry"), Optional.of("startTime"), 9_990, 11));

        assertThat(single.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(single.message()).contains("10000");
        assertThat(merged.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(LogRequestBuilder.plan(List.of(), "status", List.of("Error"),
            Optional.of("startTime"), 9_950, 50).requests()).hasSize(1);
    }

    @Test
    void charactersThatXmlDoesNotAllowAreInvalidInputInsteadOfBeingDropped() {
        // review D5
        ToolError text = invalid(() -> LogRequestBuilder.validate(with(query(LogType.SYSTEM_LOG),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Set.of(),
            Optional.of("a\u0001b"), 0, 5)));
        ToolError workflow = invalid(() -> LogRequestBuilder.validate(with(
            query(LogType.SYSTEM_LOG), Optional.empty(), Optional.empty(),
            Optional.of("W\uFFFF"), Optional.empty(), Set.of(), Optional.empty(), 0, 5)));
        ToolError xml = invalid(() -> LogRequestBuilder.xml(0, 5,
            List.of(new LogFilter.Equal("tag", "x\u0000")), Optional.empty()));

        for (ToolError error : List.of(text, workflow, xml)) {
            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.node()).isEmpty();
        }
        assertThat(text.message()).contains("text", "U+0001");
        assertThat(workflow.message()).contains("workflow", "U+FFFF");
    }

    @Test
    void timesOutsideTheEpochMillisecondRangeAreInvalidInput() {
        // review D3
        for (Optional<Instant> since : List.of(Optional.of(Instant.MAX),
            Optional.of(Instant.ofEpochMilli(Long.MIN_VALUE)))) {
            ToolError error = invalid(() -> LogFilter.timeRange("startTime", since,
                Optional.empty()));
            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        ToolError until = invalid(() -> LogFilter.timeRange("startTime", Optional.empty(),
            Optional.of(Instant.ofEpochMilli(Long.MAX_VALUE))));
        assertThat(until.code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void processLogIsNotALogType() {
        assertThat(LogType.ofLogName("processLog")).isEmpty();
        assertThat(LogType.logNames()).containsExactly("systemLog", "queueLog",
            "connectionLog", "schedulerLog", "auditLog", "keyManagerLog", "webserviceManager");
    }

    // --- combining responses (paging rule) --------------------------------------------------

    @Test
    void aSingleRequestIsThePageWithTheResponseTotal() {
        LogRequestPlan plan = LogRequestBuilder.plan(List.of(), "status", List.of(),
            Optional.of("startTime"), 40, 3);

        assertThat(plan.merged()).isFalse();
        assertThat(plan.requests()).singleElement().satisfies(request -> {
            assertThat(request.startIndex()).isEqualTo(40);
            assertThat(request.noOfItems()).isEqualTo(3);
        });
        Window<Long> page = plan.combine(List.of(new Window<>(List.of(9L, 8L, 7L), 1_234)),
            Comparator.reverseOrder());
        assertThat(page.rows()).containsExactly(9L, 8L, 7L);
        assertThat(page.total()).isEqualTo(1_234);
    }

    @Test
    void mergedRequestsAreSortedNewestFirstSlicedAndTotalled() {
        LogRequestPlan plan = LogRequestBuilder.plan(List.of(), "status",
            List.of("Waiting", "Retry"), Optional.of("startTime"), 2, 3);

        assertThat(plan.merged()).isTrue();
        assertThat(plan.requests()).extracting(LogRequest::noOfItems).containsOnly(5);
        Window<Long> page = plan.combine(List.of(
                new Window<>(List.of(100L, 70L, 40L, 10L), 40),
                new Window<>(List.of(90L, 80L, 30L), 3)),
            Comparator.reverseOrder());
        // merged: 100 90 80 70 40 30 10 → [2, 5) = 80 70 40
        assertThat(page.rows()).containsExactly(80L, 70L, 40L);
        assertThat(page.total()).isEqualTo(43);

        Window<Long> beyond = LogRequestBuilder.plan(List.of(), "status",
                List.of("Waiting", "Retry"), Optional.of("startTime"), 10, 3)
            .combine(List.of(new Window<>(List.of(5L), 1), new Window<>(List.of(4L), 1)),
                Comparator.reverseOrder());
        assertThat(beyond.rows()).isEmpty();
        assertThat(beyond.total()).isEqualTo(2);
    }
}
