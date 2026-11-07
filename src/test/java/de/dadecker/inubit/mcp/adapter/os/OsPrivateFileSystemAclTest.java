package de.dadecker.inubit.mcp.adapter.os;

import static de.dadecker.inubit.mcp.testing.WindowsAcl.DIRECTORY_INHERITANCE;
import static de.dadecker.inubit.mcp.testing.WindowsAcl.USERS;
import static de.dadecker.inubit.mcp.testing.WindowsAcl.assertInheritedPrivate;
import static de.dadecker.inubit.mcp.testing.WindowsAcl.assertPrivate;
import static de.dadecker.inubit.mcp.testing.WindowsAcl.readBack;
import static de.dadecker.inubit.mcp.testing.WindowsAcl.run;
import static de.dadecker.inubit.mcp.testing.WindowsAcl.system32;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.WindowsAcl.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 013 (T011, FR-007, research R-8), Windows only: the access list
 * {@link OsPrivateFileSystem} sets, read back independently of the code under test with
 * PowerShell's {@code Get-Acl} ({@code WindowsAcl}) — owner the token user,
 * protected, exactly the token user and {@code SYSTEM} with full control, inheritable on
 * directories — and inherited by a file another process writes into such a directory.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(60)
class OsPrivateFileSystemAclTest {

    private final OsPrivateFileSystem files = new OsPrivateFileSystem(true);

    @TempDir
    Path root;

    @Test
    void aDirectoryHasTheProtectedInheritableListOfTheTokenUserAndSystem() throws Exception {
        assertPrivate(files.createDirectory(root.resolve("d")), DIRECTORY_INHERITANCE);
    }

    @Test
    void aTemporaryDirectoryHasTheProtectedInheritableList() throws Exception {
        assertPrivate(files.createTempDirectory(root, "inubit-mcp-export-"),
            DIRECTORY_INHERITANCE);
    }

    @Test
    void everyDirectoryCreatedOnTheWayHasTheProtectedInheritableList() throws Exception {
        Path a = root.resolve("a");

        assertThat(files.createDirectories(a.resolve("b"))).isTrue();

        assertPrivate(a, DIRECTORY_INHERITANCE);
        assertPrivate(a.resolve("b"), DIRECTORY_INHERITANCE);
    }

    @Test
    void aFileHasTheProtectedListWithoutInheritance() throws Exception {
        assertPrivate(files.createFile(root.resolve("f")), "None");
    }

    @Test
    void aFileAnotherProcessWritesIntoTheDirectoryInheritsExactlyTheTwoEntries()
        throws Exception {
        Path directory = files.createTempDirectory(root, "inubit-mcp-export-");

        run(new ProcessBuilder(system32("cmd.exe").toString(), "/d", "/c", "echo",
            "written>child.txt").directory(directory.toFile()));

        Path child = directory.resolve("child.txt");
        assertThat(child).isRegularFile();
        assertInheritedPrivate(child);
    }

    @Test
    void checkReportsAPrincipalGrantedAccessAndNothingForThePrivateList() throws Exception {
        Path directory = files.createDirectory(root.resolve("private"));
        Path open = Files.createDirectory(directory.resolve("open")); // inherits the two entries

        assertThat(files.check(directory)).isEmpty();
        assertThat(files.check(open)).isEmpty();

        run(new ProcessBuilder(system32("icacls.exe").toString(), open.toString(), "/grant",
            "*" + USERS + ":(RX)"));

        assertThat(readBack(open).entries()).extracting(Entry::sid).contains(USERS);
        assertThat(files.check(open)).hasSize(1);
    }

    @Test
    void checkIgnoresAnInheritOnlyEntryThatGrantsNothingOnTheObjectItself() throws Exception {
        Path directory = files.createDirectory(root.resolve("private"));
        Path open = Files.createDirectory(directory.resolve("open"));

        run(new ProcessBuilder(system32("icacls.exe").toString(), open.toString(), "/grant",
            "*" + USERS + ":(OI)(CI)(IO)(RX)"));

        assertThat(readBack(open).entries()).extracting(Entry::sid).contains(USERS);
        assertThat(files.check(open)).isEmpty();
    }

    @Test
    void theOwnerIsComparedWithTheTokenUser() throws Exception {
        assertThat(files.ownedByCurrentUser(files.createDirectory(root.resolve("d")))).isTrue();
        assertThat(files.ownedByCurrentUser(Path.of(System.getenv("SystemRoot"))))
            .as("owned by TrustedInstaller").isFalse();
    }
}
