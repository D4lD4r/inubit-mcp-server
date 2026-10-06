package de.dadecker.inubit.mcp.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Prepares the workspace directory at startup (feature 003, FR-002,
 * contracts/configuration-delta.md): a missing workspace is created for the current user only
 * ({@code rwx------}), and so is every parent directory created on the way; an existing
 * directory is used as it is (its permissions are not changed). It must be a readable and
 * writable directory. Messages name the path.
 */
public final class WorkspaceDirectory {

    private static final Set<PosixFilePermission> OWNER_ONLY =
        PosixFilePermissions.fromString("rwx------");

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
     * Creates {@code workspace} (an absolute path) if missing and checks that it is usable.
     */
    public static Result prepare(Path workspace) {
        Path absolute = workspace.toAbsolutePath().normalize();
        boolean created = false;
        if (!Files.exists(absolute)) {
            try {
                created = create(absolute);
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
    private static boolean create(Path directory) throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(directory);
            return true;
        }
        List<Path> missing = new ArrayList<>();
        for (Path path = directory; path != null && !Files.exists(path);
            path = path.getParent()) {
            missing.addFirst(path);
        }
        boolean created = false;
        for (Path path : missing) {
            try {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (FileAlreadyExistsException e) {
                continue; // created concurrently: not ours, not modified
            }
            Files.setPosixFilePermissions(path, OWNER_ONLY); // independent of the umask
            created = path.equals(directory) || created;
        }
        return created;
    }
}
