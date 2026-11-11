package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * The Queue Manager entry whose content is read (feature 020); built from a
 * {@link QueueEntryRow} with {@link #of}. The row rules for workflow, file name and size apply.
 */
public record QueueEntryRef(NodeId node, String workflow, String fileName, long fileSize) {

    public QueueEntryRef {
        Objects.requireNonNull(node, "node");
        QueueEntryRow.checkEntry(workflow, fileName, fileSize);
    }

    public static QueueEntryRef of(NodeId node, QueueEntryRow row) {
        return new QueueEntryRef(node, row.workflow(), row.fileName(), row.fileSize());
    }
}
