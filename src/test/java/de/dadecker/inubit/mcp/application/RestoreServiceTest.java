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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T019 (feature 004, US2, FR-019, research D-14, D-25 H7): {@code restore_backup} re-imports the
 * backup of an earlier call, limited to the artifacts that call changed and that existed before
 * it — conflict check against the state the referenced call left (its manifest, also for a
 * failed call), its own backup and rollback, the target's current secrets, verification and the
 * commit; created artifacts stay and are reported; an unknown, removed or foreign reference is
 * {@code NOT_FOUND} before anything is read from INUBIT.
 */
@Timeout(120)
class RestoreServiceTest {

    @TempDir
    Path temp;

    private static WriteOutcome completed(ImportService.Response response) {
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response).outcome();
    }

    private static ImportService.RestoreRequest restore(String ref) {
        return new ImportService.RestoreRequest("dev/node1", ref, "Undo the move",
            Optional.empty(), Optional.empty());
    }

    /** An executed import of Workflow-0001 moved from xPos 120 to 140; its backup ref. */
    private static String imported(ImportHarness harness) {
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();
        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Move it")));
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        return outcome.backupRef().orElseThrow();
    }

    private static String workflow0001(String xml) {
        Matcher matcher = Pattern.compile(
            "(?s)<WorkflowName>Workflow-0001</WorkflowName>.*?</Workflow>").matcher(xml);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    private static ToolError refusal(ImportHarness harness, ImportService service,
        ImportService.RestoreRequest request, int importsBefore) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.restore(request));
        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).as("nothing was imported").hasSize(importsBefore);
        assertThat(harness.audit("restore_backup")).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        return exception.error();
    }

    @Test
    void theArtifactsOfTheReferencedCallAreRestoredVerifiedAndCommitted() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = imported(harness);
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(harness.service().restore(restore(ref)));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001");
        assertThat(outcome.created()).isEmpty();
        assertThat(workflow0001(harness.inubit.workflowXml())).contains("xPos=\"120\"")
            .doesNotContain("xPos=\"140\"");
        String sent = new String(ArtifactFixtures.entries(harness.inubit.imported.get(1))
            .get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        assertThat(sent).contains("<WorkflowName>Workflow-0001</WorkflowName>")
            .doesNotContain("Workflow-0002").contains("###Undo the move###");
        assertThat(harness.read(harness.workflow("Workflow-0001"))).contains("xPos=\"120\"");
        assertThat(harness.exports.log().get(0)).isEqualTo("restore dev/node1: diagram group"
            + " GRP-01 (1 artifacts) [" + outcome.auditId() + "]");
        assertThat(harness.exports.git("log", "-1",
            "--format=%(trailers:key=Server-State,valueonly)").strip()).isEqualTo("dev");
        assertThat(outcome.backupRef()).contains(outcome.auditId().toString())
            .isNotEqualTo(Optional.of(ref));
        assertThat(harness.backups.find(outcome.auditId().toString())).isPresent();
        assertThat(harness.audit("restore_backup")).extracting(r -> r.outcome())
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(harness.audit("restore_backup").get(1).inputs())
            .containsEntry("backupRef", ref).containsEntry("reason", "Undo the move")
            .containsEntry("changeSet", "Workflow-0001");
    }

    @Test
    void aChangeOnTheServerAfterTheReferencedCallIsAConflict() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = imported(harness);
        harness.inubit.changeWorkflows(xml -> xml.replace(workflow0001(xml),
            workflow0001(xml).replace("xPos=\"140\"", "xPos=\"150\"")));
        harness.exportGroup();

        ToolError error = refusal(harness, harness.service(), restore(ref), 1);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("Workflow-0001", ".reports/conflict-");
    }

    @Test
    void anUnknownRemovedOrForeignBackupIsNotFoundWithoutContactingInubit() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String foreign = UUID.randomUUID().toString();
        harness.backups.write(new BackupStore.Manifest(foreign, ImportHarness.TEST, "jdoe",
            "diagram group GRP-01", List.of("Workflow-0001"), List.of(), Map.of(), "EXECUTED",
            harness.clock.instant(), List.of()), List.of(harness.inubit.exportWorkflowGroup()));
        String removed = UUID.randomUUID().toString();
        harness.backups.write(new BackupStore.Manifest(removed, ImportHarness.DEV, "jdoe",
            "diagram group GRP-01", List.of("Workflow-0001"), List.of(), Map.of(), "EXECUTED",
            harness.clock.instant().minus(Duration.ofDays(40)), List.of()),
            List.of(harness.inubit.exportWorkflowGroup()));
        harness.backups.write(new BackupStore.Manifest(UUID.randomUUID().toString(),
            ImportHarness.DEV, "jdoe", "diagram group GRP-01", List.of("Workflow-0001"),
            List.of(), Map.of(), "EXECUTED", harness.clock.instant().minus(Duration.ofDays(1)),
            List.of()), List.of(harness.inubit.exportWorkflowGroup()));

        for (String ref : List.of(UUID.randomUUID().toString(), foreign, removed)) {
            ToolError error = refusal(harness, harness.service(), restore(ref), 0);
            assertThat(error.code()).as(ref).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(error.message()).contains(ref);
        }
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void aMalformedBackupRefIsInvalid() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);

        ToolError error = refusal(harness, harness.service(), restore("../../etc"), 0);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void createdArtifactsOfTheReferencedCallStayAndAreReported() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.write(harness.workflow("Workflow-0100"), harness.read(harness.workflow(
            "Workflow-0001")).replace("Workflow-0001", "Workflow-0100"));
        String ref = imported(harness);
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(harness.service().restore(restore(ref)));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001");
        assertThat(outcome.createdNotRemoved()).containsExactly("Workflow-0100");
        assertThat(outcome.warnings()).anyMatch(w -> w.contains("Workflow-0100")
            && w.contains("Workbench"));
        String sent = new String(ArtifactFixtures.entries(harness.inubit.imported.get(1))
            .get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        assertThat(sent).doesNotContain("Workflow-0100");
        assertThat(harness.inubit.workflowXml()).contains("Workflow-0100");
    }

    @Test
    void aFailedCallIsRestoredAgainstTheStateItLeft() throws IOException {
        // research D-25 H7: the manifest records what the server showed at the end of the call
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.inubit.tamperNextImport = new String[] {"xPos=\"140\"", "xPos=\"999\""};
        harness.exportGroup().importApplied().exportGroup().importRefused().exportGroup();
        WriteOutcome failed = completed(harness.service().importArtifacts(
            harness.group("Risky")));
        assertThat(failed.rollback()).contains(WriteOutcome.Rollback.FAILED);
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(harness.service().restore(restore(
            failed.backupRef().orElseThrow())));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(workflow0001(harness.inubit.workflowXml())).contains("xPos=\"120\"")
            .doesNotContain("xPos=\"999\"");
    }

    @Test
    void aFailingRestoreIsRolledBackFromItsOwnBackup() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = imported(harness);
        harness.inubit.tamperNextImport = new String[] {"xPos=\"120\"", "xPos=\"777\""};
        harness.exportGroup().importRestored().exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(harness.service().restore(restore(ref)));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure().orElseThrow().code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
        assertThat(workflow0001(harness.inubit.workflowXml())).contains("xPos=\"140\"")
            .doesNotContain("xPos=\"777\"");
        assertThat(outcome.backupRef()).contains(outcome.auditId().toString());
        assertThat(harness.backups.find(outcome.auditId().toString())).isPresent();
    }

    @Test
    void aSetActiveCallIsRestoredWithTheBackedUpFlag() throws IOException {
        // review m-b: the restore re-imports the flag of the backup, not a plain --importWorkflow
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.exportGroup().importApplied("--importWorkflow --importWorkflowActive"
            + " --importUser 'jdoe' --returnProtocol").exportGroup();
        WriteOutcome activated = completed(harness.service().setActive(
            new ImportService.ActivationRequest("dev/node1", Optional.empty(), "GRP-01",
                "Workflow-0001", true, "On", Optional.empty(), Optional.empty())));
        assertThat(workflow0001(harness.inubit.workflowXml())).contains(
            "<IsActive>true</IsActive>");
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(harness.service().restore(restore(
            activated.backupRef().orElseThrow())));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(workflow0001(harness.inubit.workflowXml())).contains(
            "<IsActive>false</IsActive>");
    }

    @Test
    void aReferencedCallWithoutARecordedStateIsRefused() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = UUID.randomUUID().toString();
        harness.backups.write(new BackupStore.Manifest(ref, ImportHarness.DEV, "jdoe",
            "diagram group GRP-01", List.of("Workflow-0001"), List.of(), Map.of(), "PENDING",
            harness.clock.instant(), List.of()), List.of(harness.inubit.exportWorkflowGroup()));
        harness.exportGroup();

        ToolError error = refusal(harness, harness.service(), restore(ref), 0);

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("Workflow-0001", ref);
    }

    @Test
    void underServerConfirmationTheRestoreIsPreviewedThenConfirmed() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = imported(harness);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        ImportService service = harness.service();
        harness.exportGroup();

        ImportService.Response first = service.restore(restore(ref));

        assertThat(first).isInstanceOf(ImportService.Response.WriteChallenge.class);
        WritePreview preview = ((ImportService.Response.WriteChallenge) first).preview();
        assertThat(preview.modify()).containsExactly("Workflow-0001");
        assertThat(preview.scope()).isEqualTo("diagram group GRP-01");
        assertThat(preview.notes()).anyMatch(note -> note.contains(ref));
        assertThat(harness.inubit.imported).hasSize(1);
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(service.restore(new ImportService.RestoreRequest(
            "dev/node1", ref, "Undo the move", Optional.of(preview.confirmationCode()),
            Optional.empty())));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(harness.audit("restore_backup")).extracting(r -> r.outcome())
            .containsExactly(AuditOutcome.CHALLENGE_ISSUED, AuditOutcome.PENDING,
                AuditOutcome.EXECUTED);
    }

    // --- feature 007: INUBIT writes a module's connections in any order (US1, FR-009) --------

    /**
     * Replaces the recorded state of {@code ref} with the fingerprint 0.5.0 computed: over the
     * rendering the call left (the workspace file it wrote back), connections in file order.
     */
    private static String recordLegacyState(ImportHarness harness, String ref) {
        String file = harness.workflow("Workflow-0001");
        // Module-0002 lists 4/9 before 3/6: the ordered form differs from this rendering
        assertThat(harness.read(file)).containsSubsequence("moduleOutId=\"4\"",
            "moduleOutId=\"3\"");
        String legacy = ConflictDetector.fingerprint(new java.util.TreeMap<>(Map.of(file,
            harness.read(file).getBytes(StandardCharsets.UTF_8))));
        BackupStore.Manifest m = harness.backups.find(ref).orElseThrow();
        harness.backups.update(new BackupStore.Manifest(m.auditId(), m.node(), m.owner(),
            m.scope(), m.changeSet(), m.created(), Map.of("Workflow-0001", legacy), m.outcome(),
            m.takenAt(), m.zips(), m.kind(), m.groups(), m.repositoryPaths(), m.tag(),
            m.source()));
        return legacy;
    }

    @Test
    void connectionsSwappedOnTheServerAfterTheCallAndThePreviewAreNoChange() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = imported(harness);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        ImportService service = harness.service();
        harness.exportGroup();
        WritePreview preview = ((ImportService.Response.WriteChallenge) service.restore(
            restore(ref))).preview();
        harness.inubit.changeWorkflows(ImportHarness::swapModule0002);
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(service.restore(new ImportService.RestoreRequest(
            "dev/node1", ref, "Undo the move", Optional.of(preview.confirmationCode()),
            Optional.empty())));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(workflow0001(harness.inubit.workflowXml())).contains("xPos=\"120\"");
    }

    @Test
    void aStateRecordedBeforeTheUpgradeIsStillRecognised() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = imported(harness);
        recordLegacyState(harness, ref);
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(harness.service().restore(restore(ref)));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(workflow0001(harness.inubit.workflowXml())).contains("xPos=\"120\"");
    }

    @Test
    void aRealChangeSinceACallRecordedBeforeTheUpgradeIsAConflict() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = imported(harness);
        recordLegacyState(harness, ref);
        harness.inubit.changeWorkflows(xml -> xml.replace(ImportHarness.connections(true,
            "4/9", "3/6"), ImportHarness.connections(true, "4/9", "3/7")));
        harness.exportGroup();

        ToolError error = refusal(harness, harness.service(), restore(ref), 1);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("Workflow-0001", ".reports/conflict-");
    }
}
