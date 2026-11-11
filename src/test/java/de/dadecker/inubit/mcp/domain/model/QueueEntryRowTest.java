package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Feature 020 (T005, data-model "Domain"): one Queue Manager row with file name and texts. */
class QueueEntryRowTest {

    private static final Instant SINCE = Instant.parse("2026-11-10T08:00:00Z");

    private static QueueEntryRow row(String workflow, String fileName, long fileSize,
        Map<String, String> errorTexts) {
        return new QueueEntryRow("4711", Optional.of("4711"), ProcessState.ERROR, "Error", SINCE,
            workflow, Optional.of("Module-0001"), Optional.of("Script Connector"), fileName,
            fileSize, new TreeMap<>(errorTexts));
    }

    private static QueueEntryRow row(String workflow, String fileName) {
        return row(workflow, fileName, 12, Map.of());
    }

    @Test
    void holdsAllFields() {
        QueueEntryRow row = row("Workflow-0001", "Order-0001.xml", 1_234,
            Map.of("userDefined1", "Connection refused", "userDefined3", "Retry later"));

        assertThat(row.processId()).isEqualTo("4711");
        assertThat(row.globalProcessId()).contains("4711");
        assertThat(row.state()).isEqualTo(ProcessState.ERROR);
        assertThat(row.rawState()).isEqualTo("Error");
        assertThat(row.since()).isEqualTo(SINCE);
        assertThat(row.workflow()).isEqualTo("Workflow-0001");
        assertThat(row.module()).contains("Module-0001");
        assertThat(row.moduleType()).contains("Script Connector");
        assertThat(row.fileName()).isEqualTo("Order-0001.xml");
        assertThat(row.fileSize()).isEqualTo(1_234L);
        assertThat(row.errorTexts()).containsExactly(
            Map.entry("userDefined1", "Connection refused"),
            Map.entry("userDefined3", "Retry later"));
    }

    @Test
    void absentOptionalsBecomeEmpty() {
        QueueEntryRow row = new QueueEntryRow("4711", null, ProcessState.ERROR, "Error", SINCE,
            "Workflow-0001", null, null, "Order-0001.xml", 0, new TreeMap<>());

        assertThat(row.globalProcessId()).isEmpty();
        assertThat(row.module()).isEmpty();
        assertThat(row.moduleType()).isEmpty();
        assertThat(row.errorTexts()).isEmpty();
    }

    @Test
    void errorTextsAreAnImmutableCopy() {
        SortedMap<String, String> source = new TreeMap<>(Map.of("userDefined1", "Failed"));
        QueueEntryRow row = new QueueEntryRow("4711", Optional.empty(), ProcessState.ERROR,
            "Error", SINCE, "Workflow-0001", Optional.empty(), Optional.empty(),
            "Order-0001.xml", 0, source);

        source.put("userDefined2", "Later");

        assertThat(row.errorTexts()).containsOnlyKeys("userDefined1");
        assertThatThrownBy(() -> row.errorTexts().put("userDefined2", "x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsAFileNameOfExactly255Characters() {
        String name = "a".repeat(251) + ".xml";

        assertThat(row("Workflow-0001", name).fileName()).hasSize(255);
    }

    @Test
    void rejectsAnEmptyOrTooLongFileName() {
        assertThatIllegalArgumentException().isThrownBy(() -> row("Workflow-0001", ""));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> row("Workflow-0001", "a".repeat(256)));
    }

    @Test
    void rejectsAFileNameWithSeparatorsNulOrControlCharacters() {
        for (String name : new String[] {"in/Order-0001.xml", "in\\Order-0001.xml",
            "Order\u0000.xml", "Order\n.xml", "Order\t.xml", "Order\u007f.xml",
            "Order\u0085.xml"}) {
            assertThatIllegalArgumentException().as(name)
                .isThrownBy(() -> row("Workflow-0001", name));
            assertThat(QueueEntryRow.isValidFileName(name)).as(name).isFalse();
        }
        assertThat(QueueEntryRow.isValidFileName("Order-0001 (copy).xml")).isTrue();
    }

    @Test
    void rejectsAWorkflowWithTheObjectTypeSeparatorOrControlCharacters() {
        for (String workflow : new String[] {"Workflow@@@0001", "Workflow\n0001",
            "Workflow\u00000001", ""}) {
            assertThatIllegalArgumentException().as(workflow)
                .isThrownBy(() -> row(workflow, "Order-0001.xml"));
            assertThat(QueueEntryRow.isValidWorkflow(workflow)).as(workflow).isFalse();
        }
        assertThat(QueueEntryRow.isValidWorkflow("Workflow-0001 @ acme")).isTrue();
    }

    @Test
    void rejectsANegativeFileSize() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> row("Workflow-0001", "Order-0001.xml", -1, Map.of()));
    }

    @Test
    void errorTextsAreOnlyTheFiveUserDefinedFields() {
        for (int i = 1; i <= 5; i++) {
            assertThat(row("Workflow-0001", "Order-0001.xml", 0,
                Map.of("userDefined" + i, "x")).errorTexts()).hasSize(1);
        }
        for (String key : new String[] {"userDefined0", "userDefined6", "message", ""}) {
            assertThatIllegalArgumentException().as(key).isThrownBy(
                () -> row("Workflow-0001", "Order-0001.xml", 0, Map.of(key, "x")));
        }
    }

    @Test
    void errorTextsAreNonEmptyAndAtMost2000Characters() {
        assertThat(row("Workflow-0001", "Order-0001.xml", 0,
            Map.of("userDefined1", "x".repeat(2_000))).errorTexts().get("userDefined1"))
            .hasSize(2_000);
        assertThatIllegalArgumentException().isThrownBy(() -> row("Workflow-0001",
            "Order-0001.xml", 0, Map.of("userDefined1", "x".repeat(2_001))));
        assertThatIllegalArgumentException().isThrownBy(() -> row("Workflow-0001",
            "Order-0001.xml", 0, Map.of("userDefined1", "")));
    }

    @Test
    void rejectionMessagesDoNotEchoTheValues() {
        String secret = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        assertThatIllegalArgumentException()
            .isThrownBy(() -> row("Workflow-0001", secret + "/x.xml"))
            .satisfies(e -> TestSecrets.assertNoLeak(e.getMessage()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> row(secret + "@@@x", "Order-0001.xml"))
            .satisfies(e -> TestSecrets.assertNoLeak(e.getMessage()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> row("Workflow-0001", "Order-0001.xml", 0,
                Map.of("userDefined1", secret.repeat(100))))
            .satisfies(e -> TestSecrets.assertNoLeak(e.getMessage()));
    }

    @Test
    void aReferenceIsBuiltFromARow() {
        NodeId node = NodeId.parse("dev/node1");
        QueueEntryRef ref = QueueEntryRef.of(node,
            row("Workflow-0001", "Order-0001.xml", 1_234, Map.of()));

        assertThat(ref).isEqualTo(new QueueEntryRef(node, "Workflow-0001", "Order-0001.xml",
            1_234));
    }

    @Test
    void aReferenceAppliesTheRowRules() {
        NodeId node = NodeId.parse("dev/node1");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> new QueueEntryRef(node, "Workflow@@@0001", "Order-0001.xml", 0));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new QueueEntryRef(node, "Workflow-0001", "in/Order-0001.xml", 0));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new QueueEntryRef(node, "Workflow-0001", "Order-0001.xml", -1));
    }
}
