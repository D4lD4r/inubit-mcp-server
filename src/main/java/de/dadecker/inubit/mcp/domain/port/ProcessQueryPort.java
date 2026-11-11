package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.time.Instant;

/**
 * Process instances of one INUBIT server (US2, research R-8; 8.1: the Queue Manager view
 * {@code queueLog}). Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id. The
 * methods are thread-safe.
 */
public interface ProcessQueryPort {

    /** Upper bound of the rows {@link #findByProcessId} returns. */
    int MAX_ROWS_PER_PROCESS = 100;

    /**
     * Checks the query without contacting the server (FR-028): paging bounds and values that
     * cannot be sent (e.g. characters XML does not allow). The error carries no server id.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code INVALID_INPUT}
     */
    void validate(ProcessQuery query);

    /**
     * One page of the matching instances, sorted by {@code since} descending, with the total
     * number of matches. {@code truncated} is set if text inside an item was cut.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code INVALID_INPUT} for {@code offset + limit > 10000}
     */
    Page<ProcessInstance> find(ProcessQuery query);

    /**
     * The rows of one process id (several for a process with sub-workflows), newest first; used
     * by the state check before restart/kill (US4). At most {@link #MAX_ROWS_PER_PROCESS} rows
     * are returned; {@link ProcessRows#truncated()} tells whether INUBIT has more, so that a
     * caller never decides on an incomplete set without knowing it.
     */
    ProcessRows findByProcessId(String processId, Instant now, Duration hangingThreshold);

    /**
     * The rows of one process id with file name, size and error texts, for reading an entry
     * (feature 020, D-9): the same request as {@link #findByProcessId}, newest first, at most
     * {@link #MAX_ROWS_PER_PROCESS} rows. The default is for adapter lines without it.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code UNSUPPORTED_VERSION} without contacting the server, unless the adapter
     *     implements it
     */
    default QueueEntries queueEntries(String processId) {
        throw new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "Reading Queue Manager entries is not available for this adapter line;"
                + " nothing was sent",
            "This adapter line does not read the Queue Manager rows of an entry",
            "Call list_nodes for what this node supports"));
    }
}
