package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T023 (feature 015, FR-014, FR-016): docs/setup.md documents {@code archives.productionOptIn}
 * (group level, default {@code false}, only {@code true}/{@code false}, independent of
 * {@code write.*}, the no-effect warning, the {@code --check-config} group line) and lists
 * {@code export_archives} and {@code PARTIALLY_COMPLETED} in the audit log; SECURITY.md names the
 * tool, the clear-text archives, the acknowledgement, the client confirmation, the folder rules in
 * the wording of contracts/mcp-tools-delta.md, the limits of the network rule, the cloud-sync risk
 * and the inherited Windows ACLs.
 */
class ArchiveSetupSecurityDocsTest {

    private static final Path SETUP = Path.of("docs/setup.md");
    private static final Path SECURITY = Path.of("SECURITY.md");

    /** The text with quote markers and line breaks folded into single spaces. */
    private static String folded(Path file) throws IOException {
        return Files.readString(file).replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
    }

    /** The folded part of {@code file} from {@code heading} up to the next level-2/3 heading. */
    private static String section(Path file, String heading) throws IOException {
        String text = folded(file);
        assertThat(text).contains(heading);
        String rest = text.substring(text.indexOf(heading) + heading.length());
        int end = rest.indexOf(" ##");
        return end < 0 ? rest : rest.substring(0, end);
    }

    @Test
    void setupDocumentsTheArchivesOptInAfterTheWriteSettings() throws IOException {
        String setup = folded(SETUP);
        assertThat(setup.indexOf("### Write settings")).isPositive()
            .isLessThan(setup.indexOf("### Workbench archives"));
        assertThat(setup.indexOf("### Workbench archives"))
            .isLessThan(setup.indexOf("### Audit log"));

        String archives = section(SETUP, "### Workbench archives");
        assertThat(archives).contains("`export_archives`", "`archives.productionOptIn`",
            "| `archives.productionOptIn` | group | `false` |", "only `true` or `false`",
            "independent of `write.enabled` and `write.productionOptIn`",
            "PRODUCTION_PROTECTED",
            "archives.productionOptIn has no effect on group <name> (not production)",
            "`archives` on a node or in `defaults`", "unknown key");
        assertThat(archives).contains("archives: allowed",
            "archives: refused (no archives.productionOptIn)",
            "archives: allowed (archives.productionOptIn)");
    }

    @Test
    void setupListsTheToolAndTheNewOutcomeInTheAuditLog() throws IOException {
        String audit = section(SETUP, "### Audit log");
        assertThat(audit).contains("`export_archives`", "`PARTIALLY_COMPLETED`");
    }

    @Test
    void setupExplainsTheArchiveStateOfTheCheckConfigGroupLine() throws IOException {
        String check = section(SETUP, "## 6. Check the configuration");
        assertThat(check).contains("the archive state of the group",
            "Umgebung test: archives: allowed",
            "Umgebung prod (production): archives: refused (no archives.productionOptIn)");
    }

    @Test
    void securityNamesTheToolTheClearTextArchivesAndTheConfirmations() throws IOException {
        String security = folded(SECURITY);
        assertThat(security).contains("`export_archives`", "in clear text",
            "`acknowledgeClearTextSecrets: true`", "client confirmation",
            "`archives.productionOptIn`", "inherit the target folder's ACLs");
    }

    @Test
    void securityDescribesTheFolderRulesInTheWordingOfTheContract() throws IOException {
        assertThat(folded(SECURITY)).contains(
            "is not an absolute path, or has a `.` or `..` element",
            "is a network path: a UNC path (`\\\\server\\share`, `\\\\?\\UNC\\…`) or a mapped"
                + " network drive (Windows)",
            "exists and is not a writable directory, or (missing) its nearest existing ancestor"
                + " is not a writable directory",
            "is or lies inside one of the server's directories: `~/.inubit-mcp`, the configured"
                + " `workspace`, the configured `auditDirectory`, the **configuration directory**"
                + " (`~/.config/inubit-mcp`, on Windows `%APPDATA%\\inubit-mcp`) or the **folder"
                + " of the configuration file in use**",
            "is or lies inside a git working tree (a `.git` entry in the folder or an ancestor)",
            "on the path as given and with symbolic links resolved",
            "a symbolic link or junction to a network share",
            "a local folder resolving onto a mapped drive");
    }

    @Test
    void securityNamesTheCloudSyncRiskAndItsLimits() throws IOException {
        assertThat(folded(SECURITY)).contains("`~/Library/CloudStorage`",
            "`~/Library/Mobile Documents`", "`OneDrive`", "`OneDriveCommercial`",
            "`OneDriveConsumer`", "only the listed sync roots are detected",
            "iCloud \"Desktop & Documents\"", "sync folders outside those roots");
    }
}
