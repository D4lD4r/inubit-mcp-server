package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePreview;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T020 (feature 004, US3, FR-020, research D-15, D-24, D-25): {@code set_active} sends only that
 * workflow, built from the fresh server export (never from the workspace), with
 * {@code --importWorkflowActive|Inactive}; it is refused if the workspace file has unimported
 * edits, on a conflict or in edit mode; the result is verified ({@code IsActive}) and committed,
 * and INUBIT's new version is noted.
 */
@Timeout(120)
class SetActiveTest {

    private static final String ACTIVE = "--importWorkflow --importWorkflowActive --importUser"
        + " 'jdoe' --returnProtocol";

    @TempDir
    Path temp;

    private static WriteOutcome completed(ImportService.Response response) {
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response).outcome();
    }

    private static ImportService.ActivationRequest request(String workflow, boolean active) {
        return new ImportService.ActivationRequest("dev/node1", Optional.empty(), "GRP-01",
            workflow, active, "Switch it", Optional.empty(), Optional.empty());
    }

    private static String element(String xml, String workflow) {
        Matcher matcher = Pattern.compile("(?s)<WorkflowName>" + workflow
            + "</WorkflowName>.*?</Workflow>").matcher(xml);
        assertThat(matcher.find()).as(workflow).isTrue();
        return matcher.group();
    }

    private static ToolError refusal(ImportHarness harness,
        ImportService.ActivationRequest request) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> harness.service().setActive(request));
        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).as("nothing was imported").isEmpty();
        assertThat(harness.audit("set_active")).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        return exception.error();
    }

    @Test
    void onlyThatWorkflowIsSentWithTheActiveFlagVerifiedAndCommitted() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.exportGroup().importApplied(ACTIVE).exportGroup();

        WriteOutcome outcome = completed(harness.service().setActive(request("Workflow-0001",
            true)));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001");
        assertThat(outcome.warnings()).anyMatch(w -> w.contains("new version"));
        Map<String, byte[]> sent = ArtifactFixtures.entries(harness.inubit.imported.get(0));
        assertThat(sent.keySet()).containsExactly("archive.properties", "workflow/workflow.xml",
            "module/module.xml");
        String workflow = new String(sent.get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        assertThat(workflow).doesNotContain("Workflow-0002").contains("###Switch it###");
        assertThat(element(workflow, "Workflow-0001")).contains("<IsActive>true</IsActive>");
        assertThat(new String(sent.get("module/module.xml"), StandardCharsets.UTF_8))
            .doesNotContain("<Module ");
        assertThat(element(harness.read(harness.workflow("Workflow-0001")), "Workflow-0001"))
            .contains("<IsActive>true</IsActive>");
        assertThat(harness.exports.log().get(0)).isEqualTo("activate dev/node1: workflow"
            + " Workflow-0001 of diagram group GRP-01 (1 artifacts) [" + outcome.auditId() + "]");
        assertThat(harness.exports.git("log", "-1",
            "--format=%(trailers:key=Server-State,valueonly)").strip()).isEqualTo("dev");
        assertThat(harness.audit("set_active")).extracting(r -> r.outcome())
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(harness.audit("set_active").get(1).inputs())
            .containsEntry("workflow", "Workflow-0001").containsEntry("active", "true")
            .containsEntry("scope", "diagram group GRP-01");
    }

    @Test
    void aDeactivationUsesTheInactiveFlagAndTheTargetsSecrets() throws IOException {
        ImportHarness harness = new ImportHarness(temp, ArtifactFixtures.bytes("grp-b.zip"),
            "OWNERS", "GRP-02");
        harness.exportGroup().importApplied("--importWorkflow --importWorkflowInactive"
            + " --importUser 'OWNERS' --returnProtocol").exportGroup();

        WriteOutcome outcome = completed(harness.service().setActive(
            new ImportService.ActivationRequest("dev/node1", Optional.empty(), "GRP-02",
                "Workflow-0006", false, "Off for tests", Optional.empty(), Optional.empty())));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        String workflow = new String(ArtifactFixtures.entries(harness.inubit.imported.get(0))
            .get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        assertThat(workflow).contains("<IsActive>false</IsActive>")
            .contains("synthetic-literal-0001").doesNotContain("${secret:");
    }

    @Test
    void unimportedEditsOfTheWorkflowFileAreRefused() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");

        ToolError error = refusal(harness, request("Workflow-0001", true));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("Workflow-0001", "unimported");
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void aWorkflowThatIsNotInTheWorkspaceIsRefused() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);

        ToolError error = refusal(harness, request("Workflow-0999", true));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.nextStep()).contains("export");
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void aChangeOnTheServerIsAConflict() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.inubit.changeWorkflows(xml -> xml.replace(element(xml, "Workflow-0001"),
            element(xml, "Workflow-0001").replace("xPos=\"120\"", "xPos=\"130\"")));
        harness.exportGroup();

        ToolError error = refusal(harness, request("Workflow-0001", true));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("Workflow-0001");
    }

    @Test
    void aWorkflowInEditModeIsAConflict() throws IOException {
        ImportHarness harness = new ImportHarness(temp, ArtifactFixtures.bytes("grp-a.zip"),
            "jdoe", "GRP-01");
        harness.exportGroup();

        ToolError error = refusal(harness, request("Workflow-0002", true));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("edit mode", "jdoe");
    }

    @Test
    void aChangeOfAnotherWorkflowOfTheGroupDoesNotBlock() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.inubit.changeWorkflows(xml -> xml.replace(element(xml, "Workflow-0002"),
            element(xml, "Workflow-0002").replace("xPos=\"120\"", "xPos=\"130\"")));
        harness.exportGroup().importApplied(ACTIVE).exportGroup();

        WriteOutcome outcome = completed(harness.service().setActive(request("Workflow-0001",
            true)));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(element(harness.read(harness.workflow("Workflow-0002")), "Workflow-0002"))
            .contains("xPos=\"120\"");
    }

    @Test
    void aWorkflowAlreadyInTheRequestedStateSendsNothing() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.exportGroup();

        WriteOutcome outcome = completed(harness.service().setActive(request("Workflow-0001",
            false)));

        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).isEmpty();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).isEmpty();
        assertThat(outcome.warnings()).anyMatch(w -> w.contains("already inactive"));
    }

    @Test
    void aFailingActivationIsRolledBackWithTheOriginalFlag() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.inubit.tamperNextImport = new String[] {"xPos=\"320\"", "xPos=\"555\""};
        harness.exportGroup().importApplied(ACTIVE).exportGroup()
            .importApplied("--importWorkflow --importWorkflowInactive --importUser 'jdoe'"
                + " --returnProtocol").exportGroup();

        WriteOutcome outcome = completed(harness.service().setActive(request("Workflow-0001",
            true)));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure().orElseThrow().code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
        assertThat(element(harness.inubit.workflowXml(), "Workflow-0001"))
            .contains("<IsActive>false</IsActive>").doesNotContain("xPos=\"555\"");
    }

    @Test
    void underServerConfirmationTheActivationIsPreviewedThenConfirmed() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        ImportService service = harness.service();
        harness.exportGroup();

        ImportService.Response first = service.setActive(request("Workflow-0001", true));

        WritePreview preview = ((ImportService.Response.WriteChallenge) first).preview();
        assertThat(preview.modify()).containsExactly("Workflow-0001");
        assertThat(preview.notes()).anyMatch(note -> note.contains("inactive")
            && note.contains("active"));
        assertThat(harness.inubit.imported).isEmpty();
        harness.exportGroup().importApplied(ACTIVE).exportGroup();

        WriteOutcome outcome = completed(service.setActive(new ImportService.ActivationRequest(
            "dev/node1", Optional.empty(), "GRP-01", "Workflow-0001", true, "Switch it",
            Optional.of(preview.confirmationCode()), Optional.empty())));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        harness.cli.verifyComplete();
    }

    // --- feature 007: INUBIT writes a module's connections in any order (US1) ----------------

    @Test
    void connectionsSwappedOnTheServerAfterThePreviewDoNotBlockTheActivation()
        throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        ImportService service = harness.service();
        harness.exportGroup();
        WritePreview preview = ((ImportService.Response.WriteChallenge) service.setActive(
            request("Workflow-0001", true))).preview();
        harness.inubit.changeWorkflows(ImportHarness::swapModule0002);
        harness.exportGroup().importApplied(ACTIVE).exportGroup();

        WriteOutcome outcome = completed(service.setActive(new ImportService.ActivationRequest(
            "dev/node1", Optional.empty(), "GRP-01", "Workflow-0001", true, "Switch it",
            Optional.of(preview.confirmationCode()), Optional.empty())));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(element(harness.inubit.workflowXml(), "Workflow-0001"))
            .contains("<IsActive>true</IsActive>");
    }

    @Test
    void anotherConnectionOnTheServerAfterThePreviewIsAConflict() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        ImportService service = harness.service();
        harness.exportGroup();
        WritePreview preview = ((ImportService.Response.WriteChallenge) service.setActive(
            request("Workflow-0001", true))).preview();
        harness.inubit.changeWorkflows(xml -> xml.replace(ImportHarness.connections(false,
            "4/9", "3/6"), ImportHarness.connections(false, "4/9", "3/7")));
        harness.exportGroup();

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.setActive(new ImportService.ActivationRequest("dev/node1",
                Optional.empty(), "GRP-01", "Workflow-0001", true, "Switch it",
                Optional.of(preview.confirmationCode()), Optional.empty())));

        assertThat(exception).as("expected a refusal").isNotNull();
        assertThat(exception.error().code()).isEqualTo(ErrorCode.CONFLICT);
        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).isEmpty();
    }

    /**
     * Re-exports GRP-01 with {@code change} applied on the server and in the export, then
     * activates Workflow-0001: the check for unimported edits compares the workspace file with
     * its last server state, which must hold whether the export kept or wrote the file.
     */
    private WriteOutcome activateAfterReExport(java.util.function.UnaryOperator<String> change)
        throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.inubit.changeWorkflows(change);
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>(ArtifactFixtures
            .entries(ImportHarness.withoutEditMode()));
        entries.put("workflow/workflow.xml", change.apply(new String(entries.get(
            "workflow/workflow.xml"), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        harness.exports.artifacts.exports.put("GRP-01", ArtifactFixtures.zip(entries));
        harness.exports.service().export(new WorkspaceService.ExportRequest(ImportHarness.DEV,
            "jdoe", List.of("GRP-01"), List.of()));
        harness.exportGroup().importApplied(ACTIVE).exportGroup();

        WriteOutcome outcome = completed(harness.service().setActive(request("Workflow-0001",
            true)));

        harness.cli.verifyComplete();
        return outcome;
    }

    @Test
    void anExportThatKeptTheFileForAnotherConnectionOrderDoesNotBlockTheActivation()
        throws IOException {
        WriteOutcome outcome = activateAfterReExport(ImportHarness::swapModule0002);

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
    }

    @Test
    void anExportThatWroteTheFileDoesNotBlockTheActivation() throws IOException {
        WriteOutcome outcome = activateAfterReExport(xml -> xml.replace(
            "xPos=\"520\" yPos=\"130\"", "xPos=\"530\" yPos=\"130\""));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
    }
}
