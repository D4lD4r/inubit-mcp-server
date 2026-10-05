package de.dadecker.inubit.mcp.domain.model;

import java.util.List;

/**
 * The rows of one process id (several for a process with sub-workflows), newest first.
 *
 * @param rows  at most {@code ProcessQueryPort.MAX_ROWS_PER_PROCESS} rows
 * @param total all rows of the process id that INUBIT reported
 */
public record ProcessRows(List<ProcessInstance> rows, long total) {

    public ProcessRows {
        rows = List.copyOf(rows);
        if (total < rows.size()) {
            throw new IllegalArgumentException("total " + total + " < " + rows.size() + " rows");
        }
    }

    /** True if INUBIT reported more rows than {@link #rows()} holds (the row cap was hit). */
    public boolean truncated() {
        return total > rows.size();
    }
}
