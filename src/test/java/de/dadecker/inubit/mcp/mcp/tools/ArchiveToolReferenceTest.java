package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T022 (feature 015, FR-016): docs/tools.md describes {@code export_archives} as the code announces
 * it — every parameter and result field of its schemas, the clear-text statement, the client
 * confirmation, the five folder rules in the wording of contracts/mcp-tools-delta.md, the
 * inherited Windows ACLs and the audit outcomes — and README.md lists the tool.
 */
class ArchiveToolReferenceTest {

    private static final Path REFERENCE = Path.of("docs/tools.md");
    private static final Path SCHEMAS = Path.of("src/main/resources/schemas");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The text with quote markers and line breaks folded into single spaces. */
    private static String folded(Path file) throws IOException {
        return Files.readString(file).replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
    }

    /** The folded {@code export_archives} section, up to the next level-2 heading. */
    private static String section() throws IOException {
        String reference = folded(REFERENCE);
        String heading = "## `export_archives`";
        assertThat(reference).contains(heading);
        String rest = reference.substring(reference.indexOf(heading) + heading.length());
        int end = rest.indexOf(" ## ");
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static List<String> properties(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.get("properties").propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void theDescriptionIsQuotedAsRenderedAndTheToolIsListed() throws IOException {
        assertThat(folded(REFERENCE)).contains(
            "[acme] " + Terminology.DEFAULT.render(ExportArchivesTool.DESCRIPTION),
            "| [`export_archives`](#export_archives) |", "All twenty-two tools");
        assertThat(folded(Path.of("README.md"))).contains("| `export_archives` |");
    }

    @Test
    void everyParameterAndResultFieldIsNamed() throws IOException {
        JsonNode input = JSON.readTree(SCHEMAS.resolve("export_archives.input.json").toFile());
        JsonNode output = JSON.readTree(SCHEMAS.resolve("export_archives.output.json").toFile());
        List<String> names = new ArrayList<>(properties(input));
        names.addAll(properties(output));
        names.addAll(properties(output.get("properties").get("archives").get("items")));
        assertThat(names).as("the scan finds the fields").contains("acknowledgeClearTextSecrets",
            "clearTextSecrets", "failed", "notWritten", "truncated", "missingModules");

        String section = section();
        assertThat(names).allSatisfy(name -> assertThat(section).as(name)
            .contains("`" + name + "`"));
        assertThat(section).contains(ArchiveExportResult.CLEAR_TEXT_SECRETS);
    }

    @Test
    void theClientConfirmationAndBothCheckMomentsAreDescribed() throws IOException {
        assertThat(section()).contains("`readOnlyHint: false`", "`destructiveHint: false`",
            "`idempotentHint: false`", "`openWorldHint: true`", "clients ask before calling it",
            "static input checks", "after the export, before the first write",
            "| `PRODUCTION_PROTECTED` |", "`archives.productionOptIn`", "`UNSUPPORTED_VERSION`",
            "existing files are never replaced", "**Example prompt**", "export_archives(target:");
    }

    @Test
    void theFiveFolderRulesAreDescribedInTheWordingOfTheContract() throws IOException {
        assertThat(section()).contains(
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
            "inherit the target folder's ACLs");
    }

    @Test
    void theAuditOutcomesArePartiallyCompletedForEveryWriteFailure() throws IOException {
        assertThat(section()).contains("`capability: export_archives`", "`REFUSED`", "`PENDING`",
            "`EXECUTED`", "`FAILED`", "`PARTIALLY_COMPLETED` for every write failure",
            "also when no archive was written");
    }
}
