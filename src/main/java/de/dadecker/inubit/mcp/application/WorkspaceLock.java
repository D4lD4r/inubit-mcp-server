package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * The lock that lets only one export or check work on a workspace at a time (research D-9,
 * FR-020): an exclusive {@link FileChannel#tryLock() file lock} on {@code <workspace>/.lock}.
 * It works across the server processes of a profile (e.g. the CLI and the desktop instance) and
 * within one process; a second acquisition is refused at once, without waiting, with
 * {@code PRECONDITION_FAILED}. The lock is released by {@link #close()} (use
 * try-with-resources, so that it is released on every exit path) and by the operating system
 * when the process ends. The file itself stays; git ignores it.
 */
public final class WorkspaceLock implements AutoCloseable {

    /** The lock file, relative to the workspace root. */
    public static final String FILE = ".lock";

    private final FileChannel channel;
    private final FileLock lock;

    private WorkspaceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Locks the workspace {@code root}.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if another export or check holds
     *     the lock, or if the lock file cannot be opened
     */
    public static WorkspaceLock acquire(Path root) {
        Objects.requireNonNull(root, "root");
        FileChannel channel;
        try {
            channel = FileChannel.open(root.resolve(FILE), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The workspace " + root + " cannot be locked (" + e.getClass().getSimpleName()
                    + ")",
                "The workspace directory does not exist or is not writable",
                "Check the workspace setting and the directory's permissions"));
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException | IOException e) {
            lock = null;
        }
        if (lock == null) {
            closeQuietly(channel);
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The workspace " + root + " is busy: another export or check is running",
                "An export or check of this profile (in this or another MCP server process) is"
                    + " still working on the workspace",
                "Wait until it has finished, then retry"));
        }
        return new WorkspaceLock(channel, lock);
    }

    /** Releases the lock; closing twice is harmless. */
    @Override
    public void close() {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            // closing the channel below releases it as well
        } finally {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // nothing left to release
        }
    }
}
