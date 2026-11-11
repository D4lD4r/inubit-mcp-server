package de.dadecker.inubit.mcp.application;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Creates a target folder and its missing parents for the archive and the log file writer
 * (feature 015 R-11, feature 021 D-7).
 *
 * <p>The levels are created one at a time from the nearest existing ancestor, each
 * {@code rwx------} on POSIX; the permissions of existing folders are never changed. Existing
 * levels are detected following symbolic links; a level created concurrently is accepted only as
 * a real directory — a symbolic link created concurrently is refused. POSIX attributes are used
 * only when the default file system supports the {@code posix} view.
 */
final class OwnerOnlyFolders {

    /** Creates the single directory {@code directory}; the writers' file-operation seam. */
    @FunctionalInterface
    interface DirectoryCreator {

        void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException;
    }

    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");

    private OwnerOnlyFolders() {
    }

    /**
     * Creates {@code folder} and its missing parents with {@code creator}.
     *
     * @throws FileAlreadyExistsException if a level appeared concurrently as anything but a real
     *     directory, e.g. a symbolic link or a file
     */
    static void create(Path folder, DirectoryCreator creator) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        for (Path level = folder; level != null && !Files.exists(level);
            level = level.getParent()) {
            missing.push(level);
        }
        for (Path level : missing) {
            try {
                if (POSIX) {
                    creator.createDirectory(level,
                        PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
                } else {
                    creator.createDirectory(level);
                }
            } catch (FileAlreadyExistsException e) {
                if (Files.isDirectory(level, LinkOption.NOFOLLOW_LINKS)) {
                    continue; // created concurrently: accepted as it is
                }
                throw e;
            }
            if (POSIX) {
                Files.setPosixFilePermissions(level, DIRECTORY_PERMISSIONS);
            }
        }
    }
}
