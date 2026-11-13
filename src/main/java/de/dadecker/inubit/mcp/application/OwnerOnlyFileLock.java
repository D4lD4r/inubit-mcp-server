package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One holder at a time per lock file, within the process and across processes (feature 019, made
 * generic in feature 024 research D-12): first a lock per lock-file path within the process, then
 * a {@link FileChannel#tryLock() file lock} on the lock file, polled every 50 ms until the wait -
 * which covers both steps - has run out. The JVM refuses a second file lock on one file from one
 * process, so the in-process lock is taken first. The lock file is created owner-only, never
 * deleted and never opened through a link; its directory exists when {@link #acquire} is called.
 */
public final class OwnerOnlyFileLock {

    private static final long POLL_MILLIS = 50;
    private static final ConcurrentMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private final PrivateFileSystem files;
    private final String subject;
    private final String directory;

    /**
     * @param files     creates the lock file owner-only and opens it without following a link
     * @param subject   what the lock serializes the writes to, for the error texts (e.g. "the
     *                  local knowledge")
     * @param directory the directory of the lock file, for the error texts, without its path (e.g.
     *                  "the knowledge directory")
     */
    public OwnerOnlyFileLock(PrivateFileSystem files, String subject, String directory) {
        this.files = Objects.requireNonNull(files, "files");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * Takes the lock of {@code lockFile}.
     *
     * @param busy the caller's error when another holder keeps the lock beyond {@code wait}
     * @throws ToolErrorException {@code busy} when the wait runs out; {@code PRECONDITION_FAILED}
     *     when the lock file cannot be used (a link, a directory, an I/O error)
     */
    public Held acquire(Path lockFile, Duration wait, Supplier<ToolErrorException> busy) {
        long deadline = System.nanoTime() + wait.toNanos();
        ReentrantLock local = IN_PROCESS.computeIfAbsent(key(lockFile), path -> new ReentrantLock());
        try {
            if (!local.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                throw busy.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy.get();
        }
        try {
            FileChannel channel = open(lockFile);
            try {
                return new Held(channel, fileLock(channel, deadline, busy), local);
            } catch (RuntimeException e) {
                closeQuietly(channel);
                throw e;
            }
        } catch (RuntimeException e) {
            local.unlock();
            throw e;
        }
    }

    /** Whether the in-process lock of {@code lockFile} is held (for tests). */
    public static boolean heldInProcess(Path lockFile) {
        ReentrantLock local = IN_PROCESS.get(key(lockFile));
        return local != null && local.isLocked();
    }

    private static Path key(Path lockFile) {
        return lockFile.toAbsolutePath().normalize();
    }

    private FileChannel open(Path file) {
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
        } catch (IOException | UnsupportedOperationException e) {
            throw unusable(file, Files.isSymbolicLink(file) ? "it is a symbolic link"
                : e.getClass().getSimpleName());
        }
    }

    private FileLock fileLock(FileChannel channel, long deadline,
        Supplier<ToolErrorException> busy) {
        while (true) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // held through another channel of this process
            } catch (IOException e) {
                throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                    "The lock file of " + subject + " cannot be locked ("
                        + e.getClass().getSimpleName() + "); nothing was written",
                    "The file system does not support file locks or reported an I/O error",
                    "Keep " + directory + " on a local file system and retry"));
            }
            if (lock != null) {
                return lock;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw busy.get();
            }
            try {
                Thread.sleep(Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(left) + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw busy.get();
            }
        }
    }

    /** Names the file only by its name within {@link #directory} (feature 019 research D-14). */
    private ToolErrorException unusable(Path file, String why) {
        String name = file.getFileName().toString();
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "The lock file " + name + " in " + directory + " cannot be used (" + why
                + "); nothing was written",
            "This file serializes the writes to " + subject + "; a link there is never followed",
            "Remove " + name + " from " + directory + " by hand (it is created again on the next"
                + " write), then retry"));
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
