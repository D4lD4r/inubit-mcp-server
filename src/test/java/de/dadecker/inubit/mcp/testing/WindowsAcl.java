package de.dadecker.inubit.mcp.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Feature 013 (T011, T019, FR-007, research R-8), Windows only: reads the access list of a path
 * back independently of the code under test, with PowerShell's {@code Get-Acl} (SIDs,
 * inheritance and the protection flag).
 */
public final class WindowsAcl {

    public static final String SYSTEM = "S-1-5-18";
    public static final String USERS = "S-1-5-32-545";
    /** The inheritance flags of a private directory's entries. */
    public static final String DIRECTORY_INHERITANCE = "ContainerInherit, ObjectInherit";

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

    public record Entry(String sid, String type, boolean inherited, String inheritance, String rights) {
    }

    public record ReadBack(String me, String owner, boolean isProtected, List<Entry> entries) {
    }

    public static Path system32(String executable) {
        return Path.of(System.getenv("SystemRoot"), "System32", executable);
    }

    /**
     * The standard output of a finished tool; its error output goes to a file, so that progress
     * records PowerShell may write there never mix with the lines parsed here.
     */
    public static String run(ProcessBuilder builder) throws IOException, InterruptedException {
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

    /** The token user, owner, protection and access rules of {@code path}. */
    public static ReadBack readBack(Path path) throws IOException, InterruptedException {
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
    public static void assertPrivate(Path path, String inheritance)
        throws IOException, InterruptedException {
        assertPrivate(readBack(path), path.toString(), inheritance);
    }

    /** As {@link #assertPrivate(Path, String)}, for an access list read back before. */
    public static void assertPrivate(ReadBack acl, String what, String inheritance) {
        assertThat(acl.owner()).as("owner of %s", what).isEqualTo(acl.me());
        assertThat(acl.isProtected()).as("protected: %s", what).isTrue();
        assertThat(acl.entries()).as("entries of %s", what).containsExactlyInAnyOrder(
            new Entry(acl.me(), "Allow", false, inheritance, "FullControl"),
            new Entry(SYSTEM, "Allow", false, inheritance, "FullControl"));
    }

    /**
     * A file created below a private directory (by StartCLI, git or the server without a
     * security descriptor of its own): exactly the two entries, inherited.
     */
    public static void assertInheritedPrivate(Path path)
        throws IOException, InterruptedException {
        assertInheritedPrivate(readBack(path), path.toString());
    }

    /** As {@link #assertInheritedPrivate(Path)}, for an access list read back before. */
    public static void assertInheritedPrivate(ReadBack acl, String what) {
        assertThat(acl.entries()).as("entries of %s", what).containsExactlyInAnyOrder(
            new Entry(acl.me(), "Allow", true, "None", "FullControl"),
            new Entry(SYSTEM, "Allow", true, "None", "FullControl"));
    }

    private WindowsAcl() {
    }
}
