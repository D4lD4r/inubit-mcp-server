package de.dadecker.inubit.mcp.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * The Queue Manager rows of one process id for reading an entry (feature 020, D-9), newest first.
 *
 * @param rows             the rows with a queue file, sorted by {@code since} descending (rows
 *                         with the same time keep their order)
 * @param withoutQueueFile the states of the rows without a queue file ({@code fileName}
 *                         absent, null or blank, with {@code fileSize} 0; INUBIT 8.1 lists such
 *                         rows, T034-F1); they have no content to read
 * @param total            all rows of the process id that INUBIT reported, with and without
 *                         queue file
 */
public record QueueEntries(List<QueueEntryRow> rows, List<ProcessState> withoutQueueFile,
    long total) {

    /** The row cap, the same as {@code ProcessQueryPort.MAX_ROWS_PER_PROCESS}. */
    public static final int MAX_ROWS = 100;

    public QueueEntries {
        rows = List.copyOf(rows).stream()
            .sorted(Comparator.comparing(QueueEntryRow::since).reversed())
            .toList();
        withoutQueueFile = List.copyOf(withoutQueueFile);
        int read = rows.size() + withoutQueueFile.size();
        if (read > MAX_ROWS) {
            throw new IllegalArgumentException(read + " rows > " + MAX_ROWS);
        }
        if (total < read) {
            throw new IllegalArgumentException("total " + total + " < " + read + " rows");
        }
    }

    /** Rows that all have a queue file. */
    public QueueEntries(List<QueueEntryRow> rows, long total) {
        this(rows, List.of(), total);
    }

    /** The rows read, with and without queue file. */
    public int rowsRead() {
        return rows.size() + withoutQueueFile.size();
    }

    /** True if INUBIT reported more rows than were read (the row cap was hit). */
    public boolean truncated() {
        return total > rowsRead();
    }
}
