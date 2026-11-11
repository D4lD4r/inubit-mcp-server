package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * Maps a {@code queueLog} row to a {@link QueueEntryRow} for reading an entry (feature 020,
 * research D-9). Unlike {@link QueueLogRowParser} it keeps the uncut {@code fileName} and
 * {@code workflowName}, {@code fileSize} as {@code long} and the non-blank
 * {@code userDefined1..5}, each cut at {@value ItemBounds#MAX_MESSAGE_CHARS} characters. A row
 * whose file name or workflow name cannot be used (see {@link QueueEntryRow}) or that lacks
 * {@code workflowId}, {@code startTime} or a non-negative {@code fileSize} is
 * {@code UNEXPECTED_RESPONSE}; the error never quotes a value of the row. A row without queue
 * file ({@code fileName} absent, null or blank, with {@code workflowId} and {@code fileSize} 0;
 * INUBIT 8.1 lists such rows, T034-F1) has no content to read: {@link #parseRows} keeps only
 * its state. A row without {@code fileName} that lacks {@code workflowId} or has no
 * {@code fileSize} of 0 is {@code UNEXPECTED_RESPONSE}.
 */
final class QueueEntryRowParser {

    private static final List<String> ERROR_TEXTS = List.of("userDefined1", "userDefined2",
        "userDefined3", "userDefined4", "userDefined5");

    private QueueEntryRowParser() {
    }

    /**
     * The rows of one process id: rows with a file name parsed by {@link #parse}, rows without
     * queue file by their state only (see the class comment); {@code total} raised to the rows
     * read if INUBIT reported fewer.
     */
    static QueueEntries parseRows(NodeId server, Iterable<JsonNode> rows, long total) {
        List<QueueEntryRow> withFile = new ArrayList<>();
        List<ProcessState> withoutFile = new ArrayList<>();
        for (JsonNode row : rows) {
            if (RowValues.text(row, "fileName").isPresent()) {
                withFile.add(parse(server, row));
            } else {
                withoutFile.add(withoutQueueFile(server, row));
            }
        }
        return new QueueEntries(withFile, withoutFile,
            Math.max(total, withFile.size() + withoutFile.size()));
    }

    /** The state of a row without {@code fileName}; see the class comment for what fails. */
    private static ProcessState withoutQueueFile(NodeId server, JsonNode row) {
        if (RowValues.text(row, LogFilterTable.WORKFLOW_ID).isEmpty()) {
            throw unexpected(server, "has no " + LogFilterTable.WORKFLOW_ID);
        }
        JsonNode size = row.get("fileSize");
        if (size == null || !size.isIntegralNumber() || !size.canConvertToLong()
            || size.asLong() != 0) {
            throw unexpected(server, "has no file name and no fileSize of 0");
        }
        return ProcessState.fromRaw(RowValues.text(row, "status").orElse(""));
    }

    /** One row with a file name; see the class comment for what fails. */
    static QueueEntryRow parse(NodeId server, JsonNode row) {
        String processId = RowValues.text(row, LogFilterTable.WORKFLOW_ID)
            .orElseThrow(() -> unexpected(server, "has no " + LogFilterTable.WORKFLOW_ID));
        Instant since = RowValues.instant(row, "startTime")
            .orElseThrow(() -> unexpected(server, "has no startTime"));
        String rawState = RowValues.text(row, "status").orElse("");
        String workflow = RowValues.text(row, LogFilterTable.WORKFLOW_NAME).orElse("");
        if (!QueueEntryRow.isValidWorkflow(workflow)) {
            throw unexpected(server, "has a workflow name that cannot be used (empty, with"
                + " '@@@' or with control characters)");
        }
        String fileName = RowValues.text(row, "fileName").orElse("");
        if (!QueueEntryRow.isValidFileName(fileName)) {
            throw unexpected(server, "has a file name that cannot be used (expected 1 to "
                + QueueEntryRow.MAX_FILE_NAME_CHARS
                + " characters without '/', '\\' or control characters)");
        }
        JsonNode size = row.get("fileSize");
        if (size == null || !size.isIntegralNumber() || !size.canConvertToLong()
            || size.asLong() < 0) {
            throw unexpected(server, "has no fileSize as a number of bytes");
        }
        SortedMap<String, String> errorTexts = new TreeMap<>();
        for (String field : ERROR_TEXTS) {
            RowValues.text(row, field).ifPresent(text -> errorTexts.put(field,
                RowValues.cut(text, ItemBounds.MAX_MESSAGE_CHARS)));
        }
        return new QueueEntryRow(processId,
            RowValues.text(row, LogFilterTable.GLOBAL_PID),
            ProcessState.fromRaw(rawState),
            rawState,
            since,
            workflow,
            RowValues.text(row, LogFilterTable.MODULE_NAME),
            RowValues.text(row, "moduleType"),
            fileName,
            size.asLong(),
            errorTexts);
    }

    private static ToolErrorException unexpected(NodeId server, String problem) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "A queueLog row of " + server + " " + problem,
            "This INUBIT version lists the Queue Manager entries in another format",
            "Check the INUBIT version with get_health").withNode(server));
    }
}
