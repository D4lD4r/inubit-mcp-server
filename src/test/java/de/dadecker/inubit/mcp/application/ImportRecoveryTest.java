package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 0.4.2: the import of a diagram group with a new XSLT module, as it went wrong on a development
 * server — the stylesheet's final line break failed the verification, the rollback left the new
 * module on the server, and from then on every import of the group was a conflict.
 *
 * <ul>
 *   <li>An embedded stylesheet is sent and compared as INUBIT stores it (no final line break).
 *   <li>A module that is new in the workspace but already on the server is identical (not sent)
 *       or updated as a new version, shown as such in the preview; it is no conflict.
 *   <li>The check-in comment of a rollback is no conflict; a change of content still is.
 *   <li>A module that the diagram group's export lacks (no workflow uses it) is looked up on its
 *       own, so its base and its server state have the same extent.
 * </ul>
 */
@Timeout(60)
class ImportRecoveryTest {

    private static final String XSLT = "XSLT Converter";
    private static final String MODULE = "Module-0100";

    @TempDir
    Path temp;

    private ImportHarness harness;
    private String module;

    @BeforeEach
    void setUp() throws IOException {
        harness = ImportHarness.grpA(temp);
        harness.inubit.onlyUsedModules = true;
        String source = harness.moduleDirectory("Module-0001");
        module = source.replace("Module-0001", MODULE);
        harness.write(module + "/module.xml", harness.read(source + "/module.xml"));
        harness.write(module + "/index.xml", harness.read(source + "/index.xml")
            .replace("Module-0001", MODULE));
        // written by hand in an editor: the stylesheet ends with a line break
        harness.write(module + "/xslt.stylesheet.xsl", harness.read(source
            + "/xslt.stylesheet.xsl") + "\n");
        harness.edit(harness.workflow("Workflow-0001"), "Module-0001", MODULE);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
    }

    private WriteOutcome run(String reason) {
        ImportService.Response response = harness.service().importArtifacts(
            harness.group(reason));
        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response).outcome();
    }

    /** The first import fails (the server changes the layout) and is rolled back. */
    private WriteOutcome failedFirstImport() {
        harness.inubit.tamperNextImport = new String[] {"xPos=\"140\"", "xPos=\"999\""};
        harness.exportGroup().importApplied().exportGroup().importApplied().exportGroup();
        WriteOutcome outcome = run("New query module");
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure().orElseThrow().code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
        assertThat(outcome.createdNotRemoved()).containsExactly(MODULE);
        assertThat(harness.inubit.hasModule(MODULE)).isTrue();
        assertThat(harness.inubit.workflowXml()).doesNotContain(MODULE);
        harness.targetModules.add(MODULE);
        return outcome;
    }

    private String lastArchiveModuleFile() {
        byte[] last = harness.inubit.imported.get(harness.inubit.imported.size() - 1);
        byte[] file = ArtifactFixtures.entries(last).get("module/module-0100.xml");
        return file == null ? null : new String(file, StandardCharsets.UTF_8);
    }

    @Test
    void aNewStylesheetEndingWithALineBreakIsImportedAndVerified() {
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run("New query module");

        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.created()).containsExactly(MODULE);
        assertThat(lastArchiveModuleFile()).contains("&lt;/xsl:stylesheet></Property>");
        assertThat(harness.read(module + "/xslt.stylesheet.xsl")).endsWith("</xsl:stylesheet>");
        assertThat(harness.exports.history.status()).isEmpty();
    }

    @Test
    void anIdenticalModuleLeftByARollbackIsNotSentAgain() {
        failedFirstImport();
        harness.exportGroup().exportModule(XSLT, MODULE).importApplied().exportGroup();

        WriteOutcome outcome = run("New query module");

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.created()).isEmpty();
        assertThat(outcome.modified()).containsExactly("Workflow-0001");
        assertThat(outcome.warnings()).anyMatch(w -> w.contains("identical") && w.contains(
            MODULE));
        assertThat(lastArchiveModuleFile()).isNull();
        assertThat(harness.inubit.workflowXml()).contains(MODULE, "xPos=\"140\"");
        // the module's server state is recorded: the next import does not look at it again
        assertThat(harness.exports.history.status()).isEmpty();
        assertThat(harness.read(module + "/xslt.stylesheet.xsl")).endsWith("</xsl:stylesheet>");
        assertThat(run("Nothing").warnings()).anyMatch(w -> w.contains("Nothing changed"));
    }

    @Test
    void aChangedModuleLeftByARollbackIsPreviewedAsExistingAndUpdated() {
        failedFirstImport();
        harness.edit(module + "/xslt.stylesheet.xsl", "match=\"/\"", "match=\"/*\"");
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        harness.exportGroup().exportModule(XSLT, MODULE);
        ImportService service = harness.service();

        ImportService.Response response = service.importArtifacts(
            harness.group("New query module"));

        assertThat(response).isInstanceOf(ImportService.Response.Challenge.class);
        ImportPreview preview = ((ImportService.Response.Challenge) response).preview();
        assertThat(preview.create()).isEmpty();
        assertThat(preview.modify()).containsExactly("Workflow-0001", MODULE);
        assertThat(preview.existing()).containsExactly(MODULE);
        assertThat(preview.identical()).isEmpty();
        assertThat(preview.message()).contains("exist on dev/node1 already", MODULE);

        harness.exportGroup().exportModule(XSLT, MODULE).importApplied().exportGroup();
        ImportService.Response confirmed = service.importArtifacts(
            ImportHarness.confirmed(harness.group("New query module"),
                preview.confirmationCode()));
        harness.cli.verifyComplete();

        WriteOutcome outcome = ((ImportService.Response.Completed) confirmed).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.created()).isEmpty();
        assertThat(outcome.modified()).containsExactly("Workflow-0001", MODULE);
        assertThat(lastArchiveModuleFile()).contains("match=\"/*\"")
            .doesNotContain("match=\"/\"");
        // updated, not created: the archive carries the UID the server has
        String index = new String(ArtifactFixtures.entries(harness.inubit.imported.get(
            harness.inubit.imported.size() - 1)).get("module/module.xml"),
            StandardCharsets.UTF_8);
        assertThat(index).containsPattern("(?s)<ModuleName>" + MODULE + "</ModuleName>"
            + "((?!</Module>).)*<ModuleUId>-fake:");
        assertThat(harness.exports.history.status()).isEmpty();
    }

    @Test
    void theCheckinCommentOfARollbackIsNoConflictForTheNextImport() {
        failedFirstImport();
        assertThat(harness.inubit.workflowXml()).contains("Rollback of import");
        harness.exportGroup().exportModule(XSLT, MODULE).importApplied().exportGroup();

        WriteOutcome outcome = run("New query module");

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
    }

    @Test
    void aColleaguesChangeAfterTheRollbackIsStillAConflict() {
        failedFirstImport();
        harness.inubit.changeWorkflows(xml -> xml.replace("xPos=\"520\" yPos=\"130\"",
            "xPos=\"530\" yPos=\"130\""));
        harness.exportGroup().exportModule(XSLT, MODULE);

        assertThatThrownBy(() -> harness.service().importArtifacts(harness.group("Again")))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(e.error().message()).contains("changed on dev/node1 since the"
                    + " export: Workflow-0001").doesNotContain(MODULE);
            });
    }

    @Test
    void aNameTheOwnerUsesForAModuleOfAnotherPluginTypeIsStillAConflict() {
        // the owner's module list names it, but StartCLI finds no XSLT module of that name
        harness.targetModules.add(MODULE);
        harness.exportGroup().exportModule(XSLT, MODULE);

        assertThatThrownBy(() -> harness.service().importArtifacts(harness.group("Again")))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(e.error().message()).contains("exists on dev/node1 already: "
                    + MODULE);
            });
    }

    @Test
    void aModuleExportedOnItsOwnAfterTheRollbackIsNoConflict() {
        failedFirstImport();
        // what the user did: export the group and the unused module again, redo the change
        harness.exports.artifacts.exports.put("GRP-01", harness.inubit.exportWorkflowGroup());
        harness.exports.service().export(new WorkspaceService.ExportRequest(ImportHarness.DEV,
            "jdoe", List.of("GRP-01"), List.of()));
        harness.exports.artifacts.exports.put(MODULE, harness.inubit.exportModule(XSLT,
            MODULE));
        harness.exports.service().export(new WorkspaceService.ExportRequest(ImportHarness.DEV,
            "jdoe", List.of(), List.of(new WorkspaceService.ModuleRef(MODULE,
                Optional.of(XSLT)))));
        harness.edit(harness.workflow("Workflow-0001"), "Module-0001", MODULE);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run("New query module");

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001");
        assertThat(lastArchiveModuleFile()).isNull();
    }

    @Test
    void aChangedModuleThatTheGroupExportLacksIsComparedWithItsOwnExport() {
        failedFirstImport();
        harness.exports.artifacts.exports.put("GRP-01", harness.inubit.exportWorkflowGroup());
        harness.exports.service().export(new WorkspaceService.ExportRequest(ImportHarness.DEV,
            "jdoe", List.of("GRP-01"), List.of()));
        harness.exports.artifacts.exports.put(MODULE, harness.inubit.exportModule(XSLT,
            MODULE));
        harness.exports.service().export(new WorkspaceService.ExportRequest(ImportHarness.DEV,
            "jdoe", List.of(), List.of(new WorkspaceService.ModuleRef(MODULE,
                Optional.of(XSLT)))));
        harness.edit(harness.workflow("Workflow-0001"), "Module-0001", MODULE);
        harness.edit(module + "/xslt.stylesheet.xsl", "match=\"/\"", "match=\"/*\"");
        harness.exportGroup().exportModule(XSLT, MODULE).importApplied().exportGroup();

        WriteOutcome outcome = run("New query module");

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001", MODULE);
        assertThat(lastArchiveModuleFile()).contains("match=\"/*\"");
    }
}
