package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Feature 020 T012 (FR-008, research D-9): a {@code queueLog} row → {@link QueueEntryRow} with
 * what {@link QueueLogRowParser} drops: the uncut file and workflow names, the size as
 * {@code long} and the error texts {@code userDefined1..5}. Rows whose names cannot be used are
 * {@code UNEXPECTED_RESPONSE} without quoting the name.
 */
class QueueEntryRowParserTest {

    private static final NodeId ID = NodeId.parse("dev/node1");

    private static ObjectNode row(String rowsCase) {
        return (ObjectNode) QueueEntryRowFixtures.firstRow(rowsCase).deepCopy();
    }

    private static ToolError errorOf(JsonNode row) {
        try {
            QueueEntryRowParser.parse(ID, row);
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    private static void assertUnexpected(ToolError error, String forbidden) {
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).contains("dev/node1");
        String text = error.message() + error.likelyCause() + error.nextStep()
            + error.excerpt().orElse("");
        assertThat(text).doesNotContain(forbidden);
        TestSecrets.assertNoLeak(text);
    }

    @Test
    void aSyntheticErrorRowMapsEveryColumn() {
        QueueEntryRow row = QueueEntryRowParser.parse(ID, row("two_errors"));

        assertThat(row.processId()).isEqualTo("100000001");
        assertThat(row.globalProcessId()).contains("100000001");
        assertThat(row.state()).isEqualTo(ProcessState.ERROR);
        assertThat(row.rawState()).isEqualTo("Error");
        assertThat(row.since()).isEqualTo(Instant.ofEpochMilli(1_790_861_373_734L));
        assertThat(row.workflow()).isEqualTo("Workflow-0002");
        assertThat(row.module()).contains("Module-0002(1002)");
        assertThat(row.moduleType()).contains("XSLT Converter");
        assertThat(row.fileName())
            .isEqualTo("module-0002(1002)@100000001@00000000-0a00-0000-0000-000000000002"
                + "_1790861373736");
        assertThat(row.fileSize()).isEqualTo(19_218L);
        assertThat(row.errorTexts()).as("the empty userDefined3..5 are absent").containsExactly(
            Map.entry("userDefined1", "Mapping of Order-0001 failed"),
            Map.entry("userDefined2", "Element 'amount' is missing"));
    }

    @Test
    void aWaitingRowKeepsItsRawStateAndHasNoErrorTexts() {
        QueueEntryRow row = QueueEntryRowParser.parse(ID, row("error_waiting"));

        assertThat(row.state()).isEqualTo(ProcessState.WAITING);
        assertThat(row.rawState()).isEqualTo("Waiting");
        assertThat(row.fileSize()).isZero();
        assertThat(row.errorTexts()).isEmpty();
    }

    @Test
    void anUnknownStatusIsOtherWithTheRawState() {
        ObjectNode row = row("two_errors");
        row.putObject("status").put("level", 0).put("content", "Suspended");

        QueueEntryRow parsed = QueueEntryRowParser.parse(ID, row);

        assertThat(parsed.state()).isEqualTo(ProcessState.OTHER);
        assertThat(parsed.rawState()).isEqualTo("Suspended");
    }

    @Test
    void aFileNameOfExactly255CharactersIsKeptUncut() {
        QueueEntryRow row = QueueEntryRowParser.parse(ID, row("long_file_name"));

        assertThat(row.fileName()).hasSize(255).startsWith("module-0001(1001)@100000004@")
            .endsWith("_1790861373736").doesNotContain(ItemBounds.TRUNCATION_MARKER);
    }

    @Test
    void aFileNameOf256CharactersIsUnexpectedResponse() {
        ObjectNode row = row("long_file_name");
        String name = row.get("fileName").asString() + "0";
        row.put("fileName", name);

        assertUnexpected(errorOf(row), name);
    }

    @Test
    void theRecordedFileNameWithASlashIsUnexpectedResponse() {
        JsonNode row = row("bad_file_name");

        assertUnexpected(errorOf(row), row.get("fileName").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Order-0001\\x.xml", "Order-0001\u0000.xml", "Order-0001\u0001.xml",
        "Order-0001\n.xml", "Order-0001\u007f.xml", "a/b"})
    void aFileNameWithASeparatorOrControlCharacterIsUnexpectedResponse(String name) {
        ObjectNode row = row("two_errors");
        row.put("fileName", name);

        assertUnexpected(errorOf(row), name);
    }

    /** A row of {@code rowsCase} as INUBIT 8.1 lists it without queue file (T034-F1). */
    private static ObjectNode fileless(String rowsCase) {
        ObjectNode row = row(rowsCase);
        row.remove("fileName");
        row.put("fileSize", 0);
        return row;
    }

    private static ToolError errorOfRows(JsonNode... rows) {
        ToolErrorException failure = catchThrowableOfType(ToolErrorException.class,
            () -> QueueEntryRowParser.parseRows(ID, List.of(rows), rows.length));
        assertThat(failure).as("expected a ToolErrorException").isNotNull();
        return failure.error();
    }

    /**
     * T034-F1: INUBIT 8.1 lists ERROR rows without {@code fileName} ({@code fileSize} 0). Such a
     * row (field absent, null, empty or blank, with {@code fileSize} 0) is kept by its state
     * only and does not fail the lookup; the other rows are parsed as before.
     */
    @Test
    void rowsWithoutFileNameAreKeptByStateOnly() {
        ObjectNode missing = fileless("two_errors");
        ObjectNode nullName = fileless("two_errors");
        nullName.putNull("fileName");
        ObjectNode empty = fileless("two_errors");
        empty.put("fileName", "");
        ObjectNode blank = fileless("two_errors");
        blank.put("fileName", "   ");
        ObjectNode waiting = fileless("error_waiting");
        JsonNode withFile = row("two_errors");

        QueueEntries entries = QueueEntryRowParser.parseRows(ID,
            List.of(missing, nullName, empty, blank, waiting, withFile), 7);

        assertThat(entries.rows()).singleElement().satisfies(row -> assertThat(row.fileName())
            .startsWith("module-0002(1002)@100000001@"));
        assertThat(entries.withoutQueueFile()).containsExactly(ProcessState.ERROR,
            ProcessState.ERROR, ProcessState.ERROR, ProcessState.ERROR, ProcessState.WAITING);
        assertThat(entries.total()).isEqualTo(7);
        assertThat(entries.truncated()).isTrue();
    }

    /** Only {@code workflowId} and {@code fileSize} 0 are needed; a missing status is OTHER. */
    @Test
    void aRowWithoutFileNameNeedsOnlyItsWorkflowIdAndFileSizeZero() {
        ObjectNode row = fileless("two_errors");
        row.remove("workflowName");
        row.remove("startTime");
        ObjectNode noStatus = fileless("two_errors");
        noStatus.remove("status");

        assertThat(QueueEntryRowParser.parseRows(ID, List.of(row, noStatus), 2)
            .withoutQueueFile()).containsExactly(ProcessState.ERROR, ProcessState.OTHER);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 19_218})
    void aRowWithoutFileNameButWithAFileSizeFailsTheWholeLookup(long size) {
        ObjectNode row = fileless("two_errors");
        row.put("fileSize", size);

        assertUnexpected(errorOfRows(row("two_errors"), row), "Workflow-0002");
    }

    @Test
    void aRowWithoutFileNameAndWithoutANumericFileSizeFailsTheWholeLookup() {
        ObjectNode missing = fileless("two_errors");
        missing.remove("fileSize");
        ObjectNode nullSize = fileless("two_errors");
        nullSize.putNull("fileSize");
        ObjectNode text = fileless("two_errors");
        text.put("fileSize", "0");
        ObjectNode fraction = fileless("two_errors");
        fraction.put("fileSize", 0.5);

        for (ObjectNode row : List.of(missing, nullSize, text, fraction)) {
            assertUnexpected(errorOfRows(row("two_errors"), row), "Workflow-0002");
        }
    }

    @Test
    void aRowWithoutFileNameAndWithoutWorkflowIdFailsTheWholeLookup() {
        ObjectNode missing = fileless("two_errors");
        missing.remove("workflowId");
        ObjectNode blank = fileless("two_errors");
        blank.put("workflowId", " ");

        assertUnexpected(errorOfRows(row("two_errors"), missing), "100000001");
        assertUnexpected(errorOfRows(row("two_errors"), blank), "100000001");
    }

    @Test
    void theTotalIsAtLeastTheRowsRead() {
        QueueEntries entries = QueueEntryRowParser.parseRows(ID,
            List.of(fileless("two_errors"), row("two_errors")), 0);

        assertThat(entries.total()).isEqualTo(2);
        assertThat(entries.truncated()).isFalse();
    }

    @Test
    void aPresentButUnusableFileNameStillFailsTheWholeLookup() {
        ObjectNode slash = row("two_errors");
        slash.put("fileName", "Order-0001/x.xml");

        assertUnexpected(errorOfRows(fileless("two_errors"), slash), "Order-0001");
    }

    @Test
    void aFileNameNeverQuotesAPayloadValue() {
        ObjectNode row = row("two_errors");
        row.put("fileName", TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "/"
            + TestSecrets.CONFIGURED_PASSWORD);

        assertUnexpected(errorOf(row), TestSecrets.CREDENTIAL_PAYLOAD_VALUE);
    }

    @Test
    void theRecordedWorkflowWithTheObjectTypeSeparatorIsUnexpectedResponse() {
        assertUnexpected(errorOf(row("workflow_separator")), "Workflow-0001@@@error");
    }

    @Test
    void aWorkflowWithAControlCharacterOrWithoutNameIsUnexpectedResponse() {
        ObjectNode control = row("two_errors");
        control.put("workflowName", "Workflow-0001\u0002");
        ObjectNode missing = row("two_errors");
        missing.remove("workflowName");

        assertUnexpected(errorOf(control), "Workflow-0001");
        assertUnexpected(errorOf(missing), "Workflow-0002");
    }

    @Test
    void aWorkflowLongerThanTheFieldBoundIsKeptUncut() {
        ObjectNode row = row("two_errors");
        String workflow = "Workflow-0001-" + "x".repeat(ItemBounds.MAX_FIELD_CHARS * 2);
        row.put("workflowName", workflow);

        assertThat(QueueEntryRowParser.parse(ID, row).workflow()).isEqualTo(workflow);
    }

    @Test
    void aFileSizeAboveTheIntRangeIsALong() {
        ObjectNode row = row("two_errors");
        row.put("fileSize", 5_000_000_000L);

        assertThat(QueueEntryRowParser.parse(ID, row).fileSize()).isEqualTo(5_000_000_000L);
    }

    @Test
    void aMissingNegativeOrNonNumericFileSizeIsUnexpectedResponse() {
        ObjectNode missing = row("two_errors");
        missing.remove("fileSize");
        ObjectNode negative = row("two_errors");
        negative.put("fileSize", -1);
        ObjectNode text = row("two_errors");
        text.put("fileSize", "many");

        for (ObjectNode row : new ObjectNode[] {missing, negative, text}) {
            assertUnexpected(errorOf(row), "many");
        }
    }

    @Test
    void errorTextsAreCutAtTheMessageBoundAndBlankOnesAreAbsent() {
        ObjectNode row = row("two_errors");
        row.put("userDefined3", "e".repeat(ItemBounds.MAX_MESSAGE_CHARS + 1));
        row.put("userDefined4", "   ");
        row.put("userDefined5", "f".repeat(ItemBounds.MAX_MESSAGE_CHARS));

        Map<String, String> texts = QueueEntryRowParser.parse(ID, row).errorTexts();

        assertThat(texts).containsOnlyKeys("userDefined1", "userDefined2", "userDefined3",
            "userDefined5");
        assertThat(texts.get("userDefined3")).hasSize(ItemBounds.MAX_MESSAGE_CHARS)
            .endsWith(ItemBounds.TRUNCATION_MARKER);
        assertThat(texts.get("userDefined5")).isEqualTo("f".repeat(ItemBounds.MAX_MESSAGE_CHARS));
    }

    @Test
    void aRowWithoutProcessIdOrStartTimeIsUnexpectedResponse() {
        ObjectNode noId = row("two_errors");
        noId.remove("workflowId");
        ObjectNode noStart = row("two_errors");
        noStart.remove("startTime");

        assertUnexpected(errorOf(noId), "100000001");
        assertThat(errorOf(noId).message()).contains("workflowId");
        assertThat(errorOf(noStart).message()).contains("startTime");
    }
}
