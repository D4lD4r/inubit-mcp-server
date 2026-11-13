package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T025 (feature 004): docs/tools.md describes the five development tools as the code announces
 * them, with the confirmation, the failure model and the rollback semantics, and lists every
 * error code; docs/setup.md documents the development settings, owners, end-to-end tests and
 * backups. T028 (research D-26): no owner kind any more — every owner, user or user group, is
 * imported with {@code --importUser}. Feature 005 (T003, research D-15): the deployment codes
 * {@code CHAIN_VIOLATION}, {@code SOURCE_INCONSISTENT} and {@code DEPLOY_LOCKED} are in the
 * catalogue as well (described in full with {@code deploy_release}, T029). Feature 018 (T032,
 * FR-022): {@code find_partners} is the eighteenth tool, quoted as announced, with its masking,
 * the limits of the equality statement, the name collisions of the REST export and its audit
 * records, and {@code get_inventory_item} documents its {@code partnerUsage}. Feature 022 (T027,
 * FR-027): {@code get_server_settings} is the twenty-third tool, quoted as announced, with its
 * parts, fields and errors, the role it needs and its state per version as the production matrix
 * declares it.
 */
class DevelopmentToolsReferenceTest {

    private static final Path TOOLS = Path.of("docs/tools.md");
    private static final Path SETUP = Path.of("docs/setup.md");

    /** The text with quote markers and line breaks folded into single spaces. */
    private static String folded(Path file) throws IOException {
        return Files.readString(file).replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
    }

    /** The folded part of {@code text} from {@code heading} up to the next level-2 heading. */
    private static String section(String text, String heading) {
        int start = text.indexOf(heading);
        assertThat(start).as(heading).isNotNegative();
        int end = text.indexOf(" ## ", start + heading.length());
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    @Test
    void theDescriptionsAreQuotedAsRenderedAndListed() throws IOException {
        String reference = folded(TOOLS);

        for (String description : new String[] {ImportArtifactsTool.DESCRIPTION,
            RestoreBackupTool.DESCRIPTION, SetActiveTool.DESCRIPTION,
            TagArtifactsTool.DESCRIPTION, RunE2eTestTool.DESCRIPTION,
            DeployReleaseTool.DESCRIPTION}) {
            assertThat(reference).contains("[acme] " + Terminology.DEFAULT.render(description));
        }
        assertThat(reference).contains("| [`import_artifacts`](#import_artifacts) |",
            "| [`restore_backup`](#restore_backup) |", "| [`set_active`](#set_active) |",
            "| [`tag_artifacts`](#tag_artifacts) |", "| [`run_e2e_test`](#run_e2e_test) |",
            "| [`deploy_release`](#deploy_release) |",
            "All twenty-three tools");
    }

    @Test
    void findPartnersIsQuotedAsRenderedAndDescribed() throws IOException {
        String reference = folded(TOOLS);

        assertThat(reference).contains(
            "[acme] " + Terminology.DEFAULT.render(FindPartnersTool.DESCRIPTION),
            "| [`find_partners`](#find_partners) |", "## `find_partners`",
            "../specs/018-partner-management-read/contracts/find_partners.md",
            "| `find_partners`, the partner usage of `get_inventory_item` (feature 018) |"
                + " `partner_management` |");
        // masking, the equality statement and its limits, workflows by name, name collisions
        assertThat(reference).contains("**`fieldType`** is not `String`",
            "the same secret stored in different forms",
            "It is made **on INUBIT 8.1 only**", "\"equality\": \"not verified on 9.0.3\"",
            "match technical workflows **by name only**",
            "The REST model export selects a diagram **by name only**");
        // the carry-overs of the reviews
        assertThat(reference).contains("**share one `cliExportTimeout`**",
            "A field **masked on any compared node is not searched**",
            "`onlyOn` means \"compared alone\", **not \"absent on the other nodes\"**",
            "**next to the clouds of the system diagrams that were read**",
            "**An error of a Partner Management export carries no `excerpt`**");
        // get_inventory_item.partnerUsage
        assertThat(reference).contains("| `partnerUsage` |", "`partnerManagementNode`",
            "`3 × timeout + cliExportTimeout`", "**the read goes on and fills the cache**",
            "The partner usage is **not audited**");
    }

    @Test
    void getServerSettingsIsQuotedAsRenderedAndDescribed() throws IOException {
        String reference = folded(TOOLS);

        assertThat(reference).contains(
            "[acme] " + Terminology.DEFAULT.render(GetServerSettingsTool.DESCRIPTION),
            "| [`get_server_settings`](#get_server_settings) |",
            "`get_server_settings` of feature 022 [its delta](../specs/022-full-interface-coverage/"
                + "contracts/mcp-tools-delta.md)",
            "| `get_server_settings` | `server_settings` |", "`reading server settings`",
            "`get_server_settings` is **offered only if**");
        String section = section(reference, "## `get_server_settings`");
        // every input and output field
        assertThat(section).contains("`target`", "`parts`", "`configurationSection`", "`node`",
            "`license`", "`expiryDate`", "`licensedThreads`", "`licensee`", "`workflowThreads`",
            "`privilegedWorkflows`", "`percentage`", "`workflows`", "`truncated`",
            "`dbConnectionTolerance`", "`faultThreshold`", "`dropRatePercent`",
            "`dropIntervalSeconds`", "`blockingQueueSize`", "`version`", "`memoryUsedPercent`",
            "`maintenanceMode`", "`serverTime`", "`configuration`", "`sections`", "`section`",
            "`values`", "`path`", "`value`", "`masked`", "`maskedCount`", "`sources`",
            "`unavailable`", "`warnings`", "`error`", "`serverUpdate`");
        // the role, the coverage page and the errors the tool uses
        assertThat(section).contains("\"System Administrator\"", "\"CLI login access\"",
            "[interface-coverage.md](interface-coverage.md)", "`readOnlyHint: true`",
            "| `INVALID_INPUT` |", "| `TARGET_UNKNOWN` |", "| `UNSUPPORTED_VERSION` |",
            "| `CLI_UNAVAILABLE` |", "| `FORBIDDEN` |", "| `NOT_FOUND` |",
            "| `MAINTENANCE_MODE` |", "| `UNEXPECTED_RESPONSE` |",
            "| `AUTH_FAILED`, `TLS_ERROR`, `UNREACHABLE`, `TIMEOUT` |", "**Example prompts**");
        // the state per version follows the production matrix (flipped in T025 / T026)
        CapabilityMatrix matrix = CapabilityMatrix.production();
        for (String line : List.of("8.1", "9.0")) {
            boolean verified = matrix.state(InubitVersion.parse(line + ".0"),
                Capability.SERVER_SETTINGS) == SupportState.SUPPORTED;
            assertThat(section).contains("- INUBIT " + line + ": "
                + (verified ? "supported" : "not yet verified"));
        }

        assertThat(folded(SETUP)).contains("`get_server_settings` reads the license and the"
            + " configuration only with the INUBIT role \"System Administrator\"");
    }

    @Test
    void theFailureModelRollbackAndConfirmationAreDescribed() throws IOException {
        String reference = folded(TOOLS);

        assertThat(reference).contains("created artifacts are not removed",
            "`failure`", "`rollback`", "`createdNotRemoved`", "`backupRef`",
            "`confirmationCode`", "`development.confirmation`", "`--importUser`",
            "user group", "`TIME_WINDOW_UNCERTAIN`", "`X-Inubit-Mcp-Test-Id`");
        assertThat(reference).doesNotContain("`ownerKind`", "not yet supported",
            "`--importUserGroup '");
        // T029 (research D-26): tags per whole diagram group, reused, never removed
        assertThat(reference).contains("an existing tag name is reused",
            "the same tag in other diagram groups stays", "nothing is removed")
            .doesNotContain("`removedAgain`", "--tagDelete '", "never moving an existing tag");
        // T030: import_artifacts with an optional tag for a diagram group
        assertThat(reference).contains("a module import with `tag` is refused",
            "`tag` `{name, applied, workflows, modules, failure}`", "`applied: false`",
            "the import is not undone");
    }

    @Test
    void everyErrorCodeIsDocumented() throws IOException {
        String reference = folded(TOOLS);

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(reference).as(code.name()).contains("`" + code.name() + "`");
        }
        assertThat(reference).contains("| `CHAIN_VIOLATION` |", "| `SOURCE_INCONSISTENT` |",
            "| `DEPLOY_LOCKED` |");
    }

    @Test
    void theSetupDocumentsTheDevelopmentSettings() throws IOException {
        String setup = folded(SETUP);

        assertThat(setup).contains("### Development settings", "`development.enabled`",
            "`development.confirmation`", "`e2eTests`", "`e2e.soap.baseUrl`", "`--importUser`",
            "_E2E_USERNAME", "_E2E_PASSWORD", "~/.inubit-mcp/<profile>/backups", "30 days",
            "development: on (confirmation SERVER)");
        assertThat(setup).doesNotContain("`owners.<name>`", "Owner kinds", "not yet supported");
    }

    @Test
    void theSetupDocumentsTheFindPartnersAuditRecordsAndBudget() throws IOException {
        String setup = folded(SETUP);

        assertThat(setup).contains("`find_partners` (step `EXECUTE`, no `PENDING`) records these"
                + " cases:", "**one record per targeted or compared node**",
            "`callId` (shared by all records of one call)",
            "`details` (`fromCache`, `systemDiagramsRead`, `partnersReturned`, `valuesMasked`)",
            "\"capability\":\"find_partners\"", "**share this one budget**");
    }
}
