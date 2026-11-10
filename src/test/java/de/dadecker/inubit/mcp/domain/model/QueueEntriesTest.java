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
}
