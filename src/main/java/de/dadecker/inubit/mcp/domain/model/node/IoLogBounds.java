package de.dadecker.inubit.mcp.domain.model.node;

/**
 * The bounds of reading the I/O log of a process run (research D-8); the content limits of a
 * message are those of {@code QueueEntryBounds}.
 */
public final class IoLogBounds {

    /** Entries returned in one listing; the rest are only counted. */
    public static final int MAX_LISTED = 500;
    /** The largest listing file read (16 MiB). */
    public static final long MAX_LIST_BYTES = 16_777_216;
    /** Characters of the {@code workflow} input. */
    public static final int MAX_WORKFLOW_CHARS = 200;
    /** Characters of the {@code module} input. */
    public static final int MAX_MODULE_CHARS = 200;

    private IoLogBounds() {
    }
}
