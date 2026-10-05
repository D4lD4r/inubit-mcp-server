package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T065: {@code queueLog} rows → {@link ProcessInstance} (research R-8, spike S-4; state table in
 * data-model.md → ProcessInstance).
 */
class QueueLogRowParserTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.ofEpochMilli(1_790_861_400_000L);
    private static final Duration THRESHOLD = Duration.ofMinutes(60);

    private static List<JsonNode> rows(String fixture) {
        JsonNode root = JSON.readTree(RestFixtures.bytes(fixture));
        return StreamSupport.stream(root.path("queueLog").path("row").spliterator(), false)
            .toList();
    }

    private static Bounded<ProcessInstance> parse(JsonNode row) {
        return QueueLogRowParser.parse(ID, row, NOW, THRESHOLD);
    }

    private static JsonNode row(String status, long startTime) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("owner", "OWNERS");
        row.put("workflowName", "W");
        row.put("moduleName", "M");
        row.put("workflowId", 42);
        row.put("globalPId", 42);
        row.put("startTime", startTime);
        row.put("status", Map.of("level", 1, "content", status));
        return JSON.valueToTree(row);
    }

    @Test
    void aRecordedErrorRowMapsEveryColumn() {
        Bounded<ProcessInstance> parsed = parse(rows("log_queueLog.json").get(0));

        ProcessInstance instance = parsed.value();
        assertThat(parsed.cut()).isFalse();
        assertThat(instance.node()).isEqualTo(ID);
        assertThat(instance.processId()).isEqualTo("110190387");
        assertThat(instance.globalProcessId()).contains("110190387");
        assertThat(instance.owner()).contains("OWNERS");
        assertThat(instance.priority()).contains("normal");
        assertThat(instance.inubitNode()).contains("ip-192-0-2-1");
        assertThat(instance.workflow()).contains("Workflow-0274");
        assertThat(instance.module()).contains("Module-0034(22540338)");
        assertThat(instance.moduleType()).contains("Throw");
        assertThat(instance.tag()).as("empty tag").isEmpty();
        assertThat(instance.since()).isEqualTo(Instant.ofEpochMilli(1_790_327_471_916L));
        assertThat(instance.state()).isEqualTo(ProcessState.ERROR);
        assertThat(instance.rawState()).isEqualTo("Error");
        assertThat(instance.timeInState()).isEqualTo(
            Duration.between(instance.since(), NOW).withNanos(0));
        assertThat(instance.hanging()).as("ERROR is never hanging").isFalse();
    }

    @Test
    void aGlobalProcessIdMayBeAUuid() {
        List<ProcessInstance> instances = rows("log_queueLog.json").stream()
            .map(row -> parse(row).value()).toList();

        assertThat(instances).extracting(instance -> instance.globalProcessId().orElseThrow())
            .contains("813399ff-d909-4011-b4e4-3226deaf1df3", "110190387");
        assertThat(instances).extracting(ProcessInstance::processId)
            .allMatch(id -> id.matches("^[0-9]{1,19}$"));
    }

    @Test
    void theRecordedErrorQueryRowsAreAllErrors() {
        assertThat(rows("log_queueLog_error.json")).extracting(row -> parse(row).value().state())
            .containsOnly(ProcessState.ERROR);
    }

    @ParameterizedTest
    @CsvSource({
        "Error, ERROR, false",
        "Waiting, WAITING, true",
        "Retry, WAITING, true",
        "Queued, QUEUED, true",
        "Processing, ACTIVE, true",
        "Suspended, OTHER, false",
        "error, OTHER, false"})
    void theStateTableDecidesStateAndHanging(String raw, ProcessState state, boolean hanging) {
        long twoHoursAgo = NOW.minus(Duration.ofHours(2)).toEpochMilli();
        long tenMinutesAgo = NOW.minus(Duration.ofMinutes(10)).toEpochMilli();

        ProcessInstance old = parse(row(raw, twoHoursAgo)).value();
        ProcessInstance recent = parse(row(raw, tenMinutesAgo)).value();

        assertThat(old.state()).isEqualTo(state);
        assertThat(old.rawState()).isEqualTo(raw);
        assertThat(old.hanging()).isEqualTo(hanging);
        assertThat(old.timeInState()).isEqualTo(Duration.ofHours(2));
        assertThat(recent.hanging()).as("below the threshold").isFalse();
    }

    @Test
    void exactlyTheThresholdIsNotYetHanging() {
        long atThreshold = NOW.minus(THRESHOLD).toEpochMilli();

        assertThat(parse(row("Waiting", atThreshold)).value().hanging()).isFalse();
        assertThat(parse(row("Waiting", atThreshold - 1_000)).value().hanging()).isTrue();
    }

    @Test
    void aFutureStartTimeHasNoNegativeTimeInState() {
        ProcessInstance instance = parse(row("Waiting", NOW.toEpochMilli() + 5_000)).value();

        assertThat(instance.timeInState()).isEqualTo(Duration.ZERO);
    }

    @Test
    void aRowWithoutWorkflowIdOrStartTimeIsAnUnexpectedResponse() {
        Map<String, Object> withoutId = new LinkedHashMap<>(Map.of("startTime", 1L,
            "status", Map.of("level", 0, "content", "Error")));
        Map<String, Object> withoutTime = new LinkedHashMap<>(Map.of("workflowId", 1,
            "status", Map.of("level", 0, "content", "Error")));

        for (Map<String, Object> row : List.of(withoutId, withoutTime)) {
            assertThatThrownBy(() -> parse(JSON.valueToTree(row)))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                    assertThat(e.error().node()).contains(ID);
                });
        }
    }

    @Test
    void aMissingStatusIsOther() {
        Map<String, Object> row = new LinkedHashMap<>(Map.of("workflowId", 7, "startTime", 1L));

        ProcessInstance instance = parse(JSON.valueToTree(row)).value();

        assertThat(instance.state()).isEqualTo(ProcessState.OTHER);
        assertThat(instance.rawState()).isEmpty();
        assertThat(instance.hanging()).isFalse();
    }

    @Test
    void oversizedTextFieldsAreCutWithTheMarkerAndNeverRejected() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workflowId", 110190387);
        row.put("globalPId", "g".repeat(10_000));
        row.put("startTime", 1_790_327_471_916L);
        row.put("status", Map.of("level", 0, "content", "Error"));
        row.put("tag", "t".repeat(10_000));
        row.put("moduleName", "m".repeat(10_000));
        row.put("moduleType", "y".repeat(10_000));
        row.put("workflowName", "w".repeat(10_000));
        row.put("node", "n".repeat(10_000));
        row.put("owner", "o".repeat(10_000));
        row.put("priority", "p".repeat(10_000));

        Bounded<ProcessInstance> parsed = parse(JSON.valueToTree(row));

        assertThat(parsed.cut()).isTrue();
        ProcessInstance instance = parsed.value();
        assertThat(ResultJson.size(instance)).isLessThanOrEqualTo(ResultLimiter.MAX_ITEM_CHARS);
        assertThat(instance.processId()).isEqualTo("110190387");
        assertThat(List.of(instance.tag(), instance.module(), instance.moduleType(),
            instance.workflow(), instance.inubitNode(), instance.owner(), instance.priority(),
            instance.globalProcessId()))
            .allSatisfy(value -> assertThat(value).hasValueSatisfying(text ->
                assertThat(text).endsWith(ResultLimiter.TRUNCATION_MARKER)
                    .hasSizeLessThanOrEqualTo(200)));
    }

    @Test
    void escapeHeavyTextFieldsAreShrunkUntilTheSerializedRowFits() {
        // review D1: 9 fields x 200 x U+0001 serialize to 6 chars per char (8,666 chars)
        for (String unit : List.of("\u0001", "\"", "\\", "\"\\\u0001")) {
            String text = unit.repeat(200 / unit.length() + 1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workflowId", 110190387);
            row.put("startTime", 1_790_327_471_916L);
            row.put("status", Map.of("level", 0, "content", text));
            for (String field : List.of("globalPId", "workflowName", "moduleName", "moduleType",
                "tag", "node", "owner", "priority")) {
                row.put(field, text);
            }

            Bounded<ProcessInstance> parsed = parse(JSON.valueToTree(row));

            assertThat(ResultJson.size(parsed.value())).as(unit)
                .isLessThanOrEqualTo(ResultLimiter.MAX_ITEM_CHARS);
            assertThat(parsed.cut()).isTrue();
            assertThat(parsed.value().processId()).isEqualTo("110190387");
        }
    }

    @Test
    void hangingIsDecidedOnTheUntruncatedTimeInState() {
        // review D4: 60 min 0.5 s is longer than 60 min, although timeInState shows 60 min
        ProcessInstance instance = parse(row("Waiting",
            NOW.minus(THRESHOLD).minusMillis(500).toEpochMilli())).value();

        assertThat(instance.timeInState()).isEqualTo(THRESHOLD);
        assertThat(instance.hanging()).isTrue();
    }

    @Test
    void everyRecordedRowFitsTheItemBound() {
        assertThat(rows("log_queueLog.json")).allSatisfy(row ->
            assertThat(ResultJson.size(parse(row).value()))
                .isLessThanOrEqualTo(ResultLimiter.MAX_ITEM_CHARS));
    }
}
