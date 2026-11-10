package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One write at a time per knowledge directory (feature 019, research D-12): first a lock per
 * directory within the process, then a {@link FileChannel#tryLock() file lock} on
 * {@code <knowledge>/.lock}, polled every 50 ms until the wait - which covers both steps - has run
 * out. The JVM refuses a second file lock on one file from one process, so the in-process lock is
 * taken first. The lock file is created owner-only, never deleted and never opened through a link.
 */
public final class KnowledgeLock {

    /** The file name of the lock file in the knowledge directory. */
    public static final String FILE = ".lock";

    private static final long POLL_MILLIS = 50;
    private static final ConcurrentMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private final Path root;
    private final PrivateFileSystem files;

    /**
     * @param root  the knowledge directory; it exists when {@link #acquire} is called
     * @param files creates the lock file owner-only and opens it without following a link
     */
    public KnowledgeLock(Path root, PrivateFileSystem files) {
        this.root = Objects.requireNonNull(root, "root");
        this.files = Objects.requireNonNull(files, "files");
    }

    /**
     * Takes the lock of the knowledge directory.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} when another write holds it after
     *     {@code wait}, or when the lock file cannot be used (a link, a directory, an I/O error)
     */
    public Held acquire(Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        ReentrantLock local = IN_PROCESS.computeIfAbsent(key(root), path -> new ReentrantLock());
        try {
            if (!local.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                throw busy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        try {
            FileChannel channel = open();
            try {
                return new Held(channel, fileLock(channel, deadline), local);
            } catch (RuntimeException e) {
                closeQuietly(channel);
                throw e;
            }
        } catch (RuntimeException e) {
            local.unlock();
            throw e;
        }
    }

    /** Whether the in-process lock of {@code root} is held (for tests). */
    static boolean heldInProcess(Path root) {
        ReentrantLock local = IN_PROCESS.get(key(root));
        return local != null && local.isLocked();
    }

    private static Path key(Path root) {
        return root.toAbsolutePath().normalize();
    }

    private FileChannel open() {
        Path file = root.resolve(FILE);
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw unusable(file, Files.isSymbolicLink(file)
                        ? "it is a symbolic link" : "it is not a regular file");
                }
            } else {
                try {
                    files.createFile(file);
                } catch (FileAlreadyExistsException e) {
                    // created meanwhile by another process, or a link that appeared: checked below
                }
            }
            return files.openLockFileNoFollow(file);
        } catch (FileSystemException e) {
            throw unusable(file, Files.isSymbolicLink(file) ? "it is a symbolic link"
                : e.getClass().getSimpleName() + (e.getReason() == null ? ""
                    : ": " + e.getReason()));
        } catch (IOException | UnsupportedOperationException e) {
            throw unusable(file, Files.isSymbolicLink(file) ? "it is a symbolic link"
                : e.getClass().getSimpleName());
        }
    }

    private static FileLock fileLock(FileChannel channel, long deadline) {
        while (true) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // held through another channel of this process
            } catch (IOException e) {
                throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                    "The lock file of the local knowledge cannot be locked ("
                        + e.getClass().getSimpleName() + "); nothing was written",
                    "The file system does not support file locks or reported an I/O error",
                    "Keep the knowledge directory on a local file system and retry"));
            }
            if (lock != null) {
                return lock;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw busy();
            }
            try {
                Thread.sleep(Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(left) + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw busy();
            }
        }
    }

    private static ToolErrorException busy() {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "Another write to the local knowledge is in progress; nothing was written",
            "Another record_knowledge call (in this or another MCP process of the profile) has not"
                + " finished within " + KnowledgeLimits.LOCK_WAIT.toSeconds() + " s",
            "Try again in a moment"));
    }

    private static ToolErrorException unusable(Path file, String why) {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "The lock file " + file + " of the local knowledge cannot be used (" + why
                + "); nothing was written",
            "This file serializes the writes to the local knowledge; a link there is never"
                + " followed",
            "Remove " + file + " by hand (it is created again on the next write), then retry"));
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // nothing left to release
        }
    }

    /** The held lock; {@link #close()} releases the file lock, then the in-process lock. */
    public static final class Held implements AutoCloseable {

        private final FileChannel channel;
        private final FileLock lock;
        private final ReentrantLock local;
        private boolean closed;

        private Held(FileChannel channel, FileLock lock, ReentrantLock local) {
            this.channel = channel;
            this.lock = lock;
            this.local = local;
        }

        /** Releases the lock; closing twice is harmless. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException e) {
                // closing the channel releases it as well
            } finally {
                closeQuietly(channel);
                local.unlock();
            }
        }
    }
}
