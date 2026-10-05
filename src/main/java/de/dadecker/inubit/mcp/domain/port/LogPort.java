package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.Page;

/**
 * The INUBIT logs of one server (US2, research R-9). Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id. The
 * methods are thread-safe.
 */
public interface LogPort {

    /**
     * Checks the query against the filters this server's log types support, without contacting
     * the server (FR-028).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code INVALID_INPUT} naming the supported filters of the log type
     */
    void validate(LogQuery query);

    /**
     * One page of the matching entries, newest first, with the total number of matches.
     * {@code truncated} is set if text inside an item was cut.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code INVALID_INPUT} as {@link #validate}, or for {@code offset + limit > 10000}
     */
    Page<LogEntry> query(LogQuery query);
}
