package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Prepares the workspace directory at startup (feature 003, FR-002,
 * contracts/configuration-delta.md): a missing workspace is created for the current user only,
 * and so is every parent directory created on the way, through the {@link PrivateFileSystem}
 * (feature 013, FR-007: {@code rwx------} on POSIX, on Windows a protected access list for the
 * current user and {@code SYSTEM}); without restricted access only on a file system that supports
 * neither. An existing directory is used as it is (its access is neither changed nor checked). It
 * must be a readable and writable directory. Messages name the path.
 */
public final class WorkspaceDirectory {

    /** The outcome of {@link #prepare}. */
    public sealed interface Result permits Usable, Unusable {
    }

    /** The workspace can be used; {@code created} if it was created just now. */
    public record Usable(boolean created) implements Result {
    }

    /** The workspace cannot be used; {@code problem} is a sentence naming the path. */
    public record Unusable(String problem) implements Result {

        public Unusable {
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** {@link #prepare}, injectable for tests that must not touch the file system. */
    @FunctionalInterface
    public interface Preparer {

        Result prepare(Path workspace);
    }

    private WorkspaceDirectory() {
    }

    /**
     * {@link #prepare(Path, PrivateFileSystem)} with the private files of the host.
     */
    public static Result prepare(Path workspace) {
        return prepare(workspace, OsPrivateFileSystem.forHost());
    }

    /**
     * Creates {@code workspace} (an absolute path) if missing and checks that it is usable.
     */
    public static Result prepare(Path workspace, PrivateFileSystem files) {
        Path absolute = workspace.toAbsolutePath().normalize();
        boolean created = false;
        if (!Files.exists(absolute)) {
            try {
                created = create(absolute, files);
            } catch (IOException | RuntimeException e) {
                return new Unusable("The workspace " + absolute + " cannot be created ("
                    + e.getClass().getSimpleName() + ")");
            }
        }
        if (!Files.isDirectory(absolute)) {
            return new Unusable("The workspace " + absolute + " is not a directory");
        }
        if (!Files.isReadable(absolute) || !Files.isWritable(absolute)
            || !Files.isExecutable(absolute)) {
            return new Unusable("The workspace " + absolute + " must be readable and writable");
        }
        return new Usable(created);
    }

    /** True if this call created the directory (not a concurrent process). */
    private static boolean create(Path directory, PrivateFileSystem files) throws IOException {
        try {
            return files.createDirectories(directory);
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(directory); // neither POSIX permissions nor access lists
            return true;
        }
    }
}
