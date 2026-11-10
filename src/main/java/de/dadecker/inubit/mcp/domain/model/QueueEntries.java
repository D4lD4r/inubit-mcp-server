package de.dadecker.inubit.mcp.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * The Queue Manager rows of one process id for reading an entry (feature 020, D-9), newest first.
 *
 * @param rows  at most {@value #MAX_ROWS} rows, sorted by {@code since} descending (rows with the
 *              same time keep their order)
 * @param total all rows of the process id that INUBIT reported
 */
public record QueueEntries(List<QueueEntryRow> rows, long total) {

    /** The row cap, the same as {@code ProcessQueryPort.MAX_ROWS_PER_PROCESS}. */
    public static final int MAX_ROWS = 100;

    public QueueEntries {
        rows = List.copyOf(rows).stream()
            .sorted(Comparator.comparing(QueueEntryRow::since).reversed())
            .toList();
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException(rows.size() + " rows > " + MAX_ROWS);
        }
        if (total < rows.size()) {
            throw new IllegalArgumentException("total " + total + " < " + rows.size() + " rows");
        }
    }

    /** True if INUBIT reported more rows than {@link #rows()} holds (the row cap was hit). */
    public boolean truncated() {
        return total > rows.size();
    }
}
