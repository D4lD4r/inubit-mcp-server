package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 021 T006 (research D-7): creating a target folder and its missing parents owner-only,
 * shared by the archive and the log file writer.
 */
class OwnerOnlyFoldersTest {

    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir(factory = GitFreeTempDir.class)
    Path tmp;

    private final List<Path> created = new ArrayList<>();

    @Test
    void createsTheFolderAndItsMissingParentsOwnerOnlyFromTheNearestExistingAncestor()
        throws IOException {
        Path folder = tmp.resolve("a").resolve("b").resolve("c");

        OwnerOnlyFolders.create(folder, (directory, attributes) -> {
            created.add(directory);
            Files.createDirectory(directory, attributes);
        });

        assertThat(created).containsExactly(tmp.resolve("a"), tmp.resolve("a/b"), folder);
        for (Path level : created) {
            assertThat(level).isDirectory();
            if (POSIX) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(level)))
                    .as(level.toString()).isEqualTo("rwx------");
            }
        }
    }

    @Test
    void neverChangesThePermissionsOfAnExistingFolderOrParent() throws IOException {
        Path parent = Files.createDirectory(tmp.resolve("parent"));
        Path existing = Files.createDirectory(parent.resolve("existing"));
        if (POSIX) {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxr-x---"));
        }

        OwnerOnlyFolders.create(existing, (directory, attributes) -> {
            created.add(directory);
            Files.createDirectory(directory, attributes);
        });
        Path folder = parent.resolve("new");
        OwnerOnlyFolders.create(folder, (directory, attributes) -> {
            created.add(directory);
            Files.createDirectory(directory, attributes);
        });

        assertThat(created).containsExactly(folder);
        if (POSIX) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(parent)))
                .isEqualTo("rwxr-xr-x");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(existing)))
                .isEqualTo("rwxr-x---");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)))
                .isEqualTo("rwx------");
        }
    }

    @Test
    void refusesASymbolicLinkCreatedBetweenTheCheckAndTheCreation() throws IOException {
        Path elsewhere = Files.createDirectory(tmp.resolve("elsewhere"));
        Path middle = tmp.resolve("a");
        Path folder = middle.resolve("out");

        assertThatThrownBy(() -> OwnerOnlyFolders.create(folder, (directory, attributes) -> {
            if (directory.equals(middle)) {
                Files.createSymbolicLink(middle, elsewhere);
            }
            Files.createDirectory(directory, attributes);
        })).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.isSymbolicLink(middle)).isTrue();
        assertThat(Files.exists(elsewhere.resolve("out"), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }
}
