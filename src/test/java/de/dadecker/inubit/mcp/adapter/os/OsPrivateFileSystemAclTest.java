package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 013 (T011, FR-007, research R-8), Windows only: the access list
 * {@link OsPrivateFileSystem} sets, read back independently of the code under test with
 * PowerShell's {@code Get-Acl} (SIDs, inheritance and the protection flag) — owner the token user,
 * protected, exactly the token user and {@code SYSTEM} with full control, inheritable on
 * directories — and inherited by a file another process writes into such a directory.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(60)
class OsPrivateFileSystemAclTest {

    private static final String SYSTEM = "S-1-5-18";
    private static final String USERS = "S-1-5-32-545";
    private static final String DIRECTORY_INHERITANCE = "ContainerInherit, ObjectInherit";

    /**
     * Prints the token user, then owner, protection and every access rule of $env:ACL_PATH.
     * {@code Get-Acl} comes explicitly from Windows PowerShell's own module directory, so that a
     * PowerShell 7 module (e.g. when {@code mvn} runs under {@code pwsh}) is never loaded.
     */
    private static final String READ_BACK = String.join("\n",
        "$ErrorActionPreference = 'Stop'",
        "Import-Module (Join-Path $PSHOME 'Modules\\Microsoft.PowerShell.Security')",
        "$sid = [System.Security.Principal.SecurityIdentifier]",
        "$acl = Get-Acl -LiteralPath $env:ACL_PATH",
        "'me=' + [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value",
        "'owner=' + $acl.GetOwner($sid).Value",
        "'protected=' + $acl.AreAccessRulesProtected",
        "foreach ($rule in $acl.GetAccessRules($true, $true, $sid)) {",
        "  'entry=' + $rule.IdentityReference.Value + '|' + $rule.AccessControlType + '|'"
            + " + $rule.IsInherited + '|' + $rule.InheritanceFlags + '|'"
            + " + $rule.FileSystemRights",
        "}");

    private final OsPrivateFileSystem files = new OsPrivateFileSystem(true);

    @TempDir
    Path root;

    record Entry(String sid, String type, boolean inherited, String inheritance, String rights) {
    }

    record ReadBack(String me, String owner, boolean isProtected, List<Entry> entries) {
    }

    private static Path system32(String executable) {
        return Path.of(System.getenv("SystemRoot"), "System32", executable);
    }

    /**
     * The standard output of a finished tool; its error output goes to a file, so that progress
     * records PowerShell may write there never mix with the lines parsed here.
     */
    private static String run(ProcessBuilder builder) throws IOException, InterruptedException {
        Path errors = Files.createTempFile("acl-test-", ".err");
        try {
            Process process = builder.redirectError(errors.toFile()).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.ISO_8859_1);
            assertThat(process.waitFor())
                .as("%s%n%s", output, Files.readString(errors, StandardCharsets.ISO_8859_1))
                .isZero();
            return output;
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    private static ReadBack readBack(Path path) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
            system32("WindowsPowerShell").resolve("v1.0").resolve("powershell.exe").toString(),
            "-NoProfile", "-NonInteractive", "-EncodedCommand",
            Base64.getEncoder().encodeToString(READ_BACK.getBytes(StandardCharsets.UTF_16LE)));
        // PowerShell 7 puts its module directories first; Windows PowerShell rebuilds its own
        builder.environment().remove("PSModulePath");
        builder.environment().put("ACL_PATH", path.toString());
        String output = run(builder);
        String me = null;
        String owner = null;
        Boolean isProtected = null;
        List<Entry> entries = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.startsWith("me=")) {
                me = line.substring(3);
            } else if (line.startsWith("owner=")) {
                owner = line.substring(6);
            } else if (line.startsWith("protected=")) {
                isProtected = Boolean.parseBoolean(line.substring(10));
            } else if (line.startsWith("entry=")) {
                String[] field = line.substring(6).split("\\|", -1);
                assertThat(field).as(line).hasSize(5);
                entries.add(new Entry(field[0], field[1], Boolean.parseBoolean(field[2]),
                    field[3], field[4]));
            }
        }
        assertThat(me).as(output).startsWith("S-1-5-");
        assertThat(owner).as(output).isNotNull();
        assertThat(isProtected).as(output).isNotNull();
        return new ReadBack(me, owner, isProtected, entries);
    }

    /** Owned by the token user, protected, exactly the two explicit full-control entries. */
    private static void assertPrivate(Path path, String inheritance)
        throws IOException, InterruptedException {
        ReadBack acl = readBack(path);

        assertThat(acl.owner()).as("owner of %s", path).isEqualTo(acl.me());
        assertThat(acl.isProtected()).as("protected: %s", path).isTrue();
        assertThat(acl.entries()).as("entries of %s", path).containsExactlyInAnyOrder(
            new Entry(acl.me(), "Allow", false, inheritance, "FullControl"),
            new Entry(SYSTEM, "Allow", false, inheritance, "FullControl"));
    }

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
        ReadBack acl = readBack(child);
        assertThat(acl.entries()).containsExactlyInAnyOrder(
            new Entry(acl.me(), "Allow", true, "None", "FullControl"),
            new Entry(SYSTEM, "Allow", true, "None", "FullControl"));
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
