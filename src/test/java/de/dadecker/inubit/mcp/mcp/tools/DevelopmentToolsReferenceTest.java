package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T025 (feature 004): docs/tools.md describes the five development tools as the code announces
 * them, with the confirmation, the failure model and the rollback semantics, and lists every
 * error code; docs/setup.md documents the development settings, owners, end-to-end tests and
 * backups.
 */
class DevelopmentToolsReferenceTest {

    private static final Path TOOLS = Path.of("docs/tools.md");
    private static final Path SETUP = Path.of("docs/setup.md");

    /** The text with quote markers and line breaks folded into single spaces. */
    private static String folded(Path file) throws IOException {
        return Files.readString(file).replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
    }

    @Test
    void theDescriptionsAreQuotedAsRenderedAndListed() throws IOException {
        String reference = folded(TOOLS);

        for (String description : new String[] {ImportArtifactsTool.DESCRIPTION,
            RestoreBackupTool.DESCRIPTION, SetActiveTool.DESCRIPTION,
            TagArtifactsTool.DESCRIPTION, RunE2eTestTool.DESCRIPTION}) {
            assertThat(reference).contains("[acme] " + Terminology.DEFAULT.render(description));
        }
        assertThat(reference).contains("| [`import_artifacts`](#import_artifacts) |",
            "| [`restore_backup`](#restore_backup) |", "| [`set_active`](#set_active) |",
            "| [`tag_artifacts`](#tag_artifacts) |", "| [`run_e2e_test`](#run_e2e_test) |",
            "All fifteen tools");
    }

    @Test
    void theFailureModelRollbackAndConfirmationAreDescribed() throws IOException {
        String reference = folded(TOOLS);

        assertThat(reference).contains("created artifacts are not removed",
            "`failure`", "`rollback`", "`createdNotRemoved`", "`backupRef`", "`removedAgain`",
            "`confirmationCode`", "`development.confirmation`", "user-group owners",
            "not yet supported", "`TIME_WINDOW_UNCERTAIN`", "`X-Inubit-Mcp-Test-Id`");
    }

    @Test
    void everyErrorCodeIsDocumented() throws IOException {
        String reference = folded(TOOLS);

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(reference).as(code.name()).contains("`" + code.name() + "`");
        }
    }

    @Test
    void theSetupDocumentsTheDevelopmentSettings() throws IOException {
        String setup = folded(SETUP);

        assertThat(setup).contains("### Development settings", "`development.enabled`",
            "`development.confirmation`", "`e2eTests`", "`e2e.soap.baseUrl`", "`owners`",
            "_E2E_USERNAME", "_E2E_PASSWORD", "~/.inubit-mcp/<profile>/backups", "30 days",
            "development: on (confirmation SERVER)");
    }
}
