package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Feature 020 (T005, data-model "Domain"): the rows of one process id, newest first. */
class QueueEntriesTest {

    private static QueueEntryRow row(String fileName, String since) {
        return new QueueEntryRow("4711", Optional.empty(), ProcessState.ERROR, "Error",
            Instant.parse(since), "Workflow-0001", Optional.empty(), Optional.empty(), fileName,
            0, new TreeMap<>());
    }

    private static List<QueueEntryRow> rows(int count) {
        List<QueueEntryRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row("Order-" + i + ".xml", "2026-11-10T08:00:00Z"));
        }
        return rows;
    }

    @Test
    void theRowCapIsThatOfTheProcessQuery() {
        assertThat(QueueEntries.MAX_ROWS).isEqualTo(ProcessQueryPort.MAX_ROWS_PER_PROCESS);
    }

    @Test
    void rowsAreNewestFirst() {
        QueueEntryRow older = row("Order-0001.xml", "2026-11-10T08:00:00Z");
        QueueEntryRow newer = row("Order-0002.xml", "2026-11-10T09:00:00Z");
        QueueEntryRow sameAsOlder = row("Order-0003.xml", "2026-11-10T08:00:00Z");

        QueueEntries entries = new QueueEntries(List.of(older, newer, sameAsOlder), 3);

        assertThat(entries.rows()).containsExactly(newer, older, sameAsOlder);
    }

    @Test
    void rowsAreAnImmutableCopy() {
        List<QueueEntryRow> source = rows(1);
        QueueEntries entries = new QueueEntries(source, 1);

        source.clear();

        assertThat(entries.rows()).hasSize(1);
        assertThatThrownBy(() -> entries.rows().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void truncatedWhenInubitReportedMoreRows() {
        assertThat(new QueueEntries(rows(100), 100).truncated()).isFalse();
        assertThat(new QueueEntries(rows(100), 101).truncated()).isTrue();
        assertThat(new QueueEntries(List.of(), 0).truncated()).isFalse();
    }

    @Test
    void rejectsMoreThan100RowsOrATotalBelowTheRows() {
        assertThatIllegalArgumentException().isThrownBy(() -> new QueueEntries(rows(101), 101));
        assertThatIllegalArgumentException().isThrownBy(() -> new QueueEntries(rows(2), 1));
        assertThatThrownBy(() -> new QueueEntries(Collections.singletonList(null), 1))
            .isInstanceOf(NullPointerException.class);
    }

    /** T034-F1: rows without queue file are kept by state only; the two-argument form has none. */
    @Test
    void rowsWithoutQueueFileAreKeptByStateAndCountAsRead() {
        List<ProcessState> states = new ArrayList<>(List.of(ProcessState.ERROR,
            ProcessState.WAITING));
        QueueEntries entries = new QueueEntries(rows(1), states, 3);

        states.clear();

        assertThat(entries.withoutQueueFile()).containsExactly(ProcessState.ERROR,
            ProcessState.WAITING);
        assertThat(entries.rowsRead()).isEqualTo(3);
        assertThat(entries.truncated()).isFalse();
        assertThat(new QueueEntries(rows(1), List.of(ProcessState.ERROR), 3).truncated())
            .isTrue();
        assertThat(new QueueEntries(rows(1), 1).withoutQueueFile()).isEmpty();
        assertThatThrownBy(() -> entries.withoutQueueFile().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rowsWithoutQueueFileCountTowardsTheCapAndTheTotal() {
        List<ProcessState> one = List.of(ProcessState.ERROR);

        assertThat(new QueueEntries(rows(99), one, 100).rowsRead()).isEqualTo(100);
        assertThatIllegalArgumentException().isThrownBy(() -> new QueueEntries(rows(100), one,
            101));
        assertThatIllegalArgumentException().isThrownBy(() -> new QueueEntries(rows(1), one,
            1));
        assertThatThrownBy(() -> new QueueEntries(rows(1),
            Collections.singletonList(null), 2)).isInstanceOf(NullPointerException.class);
    }
}
