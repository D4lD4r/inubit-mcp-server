package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T024 (feature 015, FR-016): {@code CHANGELOG.md} {@code [0.7.0]} (released 2026-11-06) has an
 * {@code ### Added} entry for {@code export_archives} naming the clear-text secrets, the
 * acknowledgement, the client confirmation, the folder rules (including the configuration
 * directory), the inherited Windows ACLs and {@code archives.productionOptIn}; the 0.7.0 text of
 * feature 011 stays.
 */
class ArchiveChangelogTest {

    private static final Path CHANGELOG = Path.of("CHANGELOG.md");

    /** The {@code [0.7.0]} section with line breaks folded into single spaces. */
    private static String release070() throws IOException {
        String text = Files.readString(CHANGELOG);
        int start = text.indexOf("## [0.7.0]");
        assertThat(start).isNotNegative();
        int end = text.indexOf("\n## [", start + 1);
        return text.substring(start, end < 0 ? text.length() : end).replaceAll("\\s+", " ");
    }

    /** The part of {@code section} from {@code heading} up to the next level-3 heading. */
    private static String subsection(String section, String heading) {
        assertThat(section).contains(heading);
        String rest = section.substring(section.indexOf(heading) + heading.length());
        int end = rest.indexOf(" ### ");
        return end < 0 ? rest : rest.substring(0, end);
    }

    @Test
    void theReleaseSection070KeepsFeature011() throws IOException {
        String section = release070();
        assertThat(section).contains("Feature 011: credentials only from the credential store",
            "**Environment variables are no longer read for INUBIT credentials.**",
            "Credential store adapters:");
    }

    @Test
    void addedHasAnEntryForExportArchivesWithItsSafeguards() throws IOException {
        String added = subsection(release070(), "### Added");
        assertThat(added).contains("Feature 015", "`export_archives`", "Workbench",
            "in clear text", "`acknowledgeClearTextSecrets: true`", "client confirmation",
            "the configuration directory", "network share", "cloud-synchronized",
            "inherit the target folder's ACLs", "`archives.productionOptIn`");
    }

    @Test
    void theEntryStatesAuditingAndThePartialOutcomePrecisely() throws IOException {
        String added = subsection(release070(), "### Added");
        assertThat(added).contains("No archive is written without",
            "Every call that passes the input schema is audited",
            "A failure while writing the archives (after all checks passed) ends as"
                + " `PARTIALLY_COMPLETED`, also when none was written",
            "an existing file or another refusal before writing is `REFUSED`");
        assertThat(added).doesNotContain("Nothing is written without", "Every call is audited");
    }
}
