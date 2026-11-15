package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.application.OwnerOnlyFileLock;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * One write at a time per knowledge directory (feature 019, research D-12): the
 * {@link OwnerOnlyFileLock} on {@code <knowledge>/.lock} with the texts of the local knowledge.
 */
public final class KnowledgeLock {

    /** The file name of the lock file in the knowledge directory. */
    public static final String FILE = ".lock";

    private final Path root;
    private final OwnerOnlyFileLock lock;

    /**
     * @param root  the knowledge directory; it exists when {@link #acquire} is called
     * @param files creates the lock file owner-only and opens it without following a link
     */
    public KnowledgeLock(Path root, PrivateFileSystem files) {
        this.root = Objects.requireNonNull(root, "root");
        this.lock = new OwnerOnlyFileLock(Objects.requireNonNull(files, "files"),
            "the local knowledge", "the knowledge directory");
    }

    /**
     * Takes the lock of the knowledge directory.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} when another write holds it after
     *     {@code wait}, or when the lock file cannot be used (a link, a directory, an I/O error)
     */
    public Held acquire(Duration wait) {
        return new Held(lock.acquire(root.resolve(FILE), wait, KnowledgeLock::busy));
    }

    /** Whether the in-process lock of {@code root} is held (for tests). */
    static boolean heldInProcess(Path root) {
        return OwnerOnlyFileLock.heldInProcess(root.resolve(FILE));
    }

    private static ToolErrorException busy() {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "Another write to the local knowledge is in progress; nothing was written",
            "Another record_knowledge call (in this or another MCP process of the profile) has not"
                + " finished within " + KnowledgeLimits.LOCK_WAIT.toSeconds() + " s",
            "Try again in a moment"));
    }

    /** The held lock; {@link #close()} releases the file lock, then the in-process lock. */
    public static final class Held implements AutoCloseable {

        private final OwnerOnlyFileLock.Held held;

        private Held(OwnerOnlyFileLock.Held held) {
            this.held = held;
        }

        /** Releases the lock; closing twice is harmless. */
        @Override
        public void close() {
            held.close();
        }
    }
}
