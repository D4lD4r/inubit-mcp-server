package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T016 (feature 004, FR-025, research D-18): every call of the development tools is audited —
 * preview, pending and final execution with one audit id, failures with the rollback state,
 * refusals — with sanitized inputs (reason, scope, owner, change set, backup) and
 * never content or secrets. (Further tools add their records to this test.)
 */
@Timeout(60)
class DevelopmentAuditTest {

    @TempDir
    Path temp;

    private ImportHarness edited() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        return harness;
    }

    @Test
    void aPreviewAndItsConfirmedExecutionAreAudited() throws IOException {
        ImportHarness harness = edited();
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        ImportService service = harness.service();
        harness.exportGroup();
        ImportPreview preview = ((ImportService.Response.Challenge) service.importArtifacts(
            harness.group("Audited change"))).preview();
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = ((ImportService.Response.Completed) service.importArtifacts(
            ImportHarness.confirmed(harness.group("Audited change"),
                preview.confirmationCode()))).outcome();

        List<AuditRecord> records = harness.audit("import_artifacts");
        assertThat(records).extracting(AuditRecord::step, AuditRecord::outcome).containsExactly(
            org.assertj.core.groups.Tuple.tuple(AuditRecord.Step.PREVIEW,
                AuditOutcome.CHALLENGE_ISSUED),
            org.assertj.core.groups.Tuple.tuple(AuditRecord.Step.EXECUTE, AuditOutcome.PENDING),
            org.assertj.core.groups.Tuple.tuple(AuditRecord.Step.EXECUTE,
                AuditOutcome.EXECUTED));
        assertThat(records.get(1).auditId()).isEqualTo(outcome.auditId())
            .isEqualTo(records.get(2).auditId());
        assertThat(records).allSatisfy(record -> {
            assertThat(record.profile()).isEqualTo("acme");
            assertThat(record.node()).isEqualTo("dev/node1");
            assertThat(record.group()).contains("dev");
            assertThat(record.account()).contains("jdoe");
            assertThat(record.inputs()).containsEntry("reason", "Audited change")
                .containsEntry("scope", "diagram group GRP-01").containsEntry("owner", "jdoe")
                .doesNotContainKey("ownerKind").containsEntry("changeSet", "Workflow-0001");
        });
        assertThat(records.get(2).inputs()).containsEntry(AuditRecord.CONFIRMATION_CODE,
            preview.confirmationCode()).containsEntry("backupRef",
            outcome.auditId().toString());
        assertThat(records.toString()).doesNotContain("xPos");
    }

    @Test
    void aFailedExecutionNamesTheRollback() throws IOException {
        ImportHarness harness = edited();
        harness.exportGroup().importRefused().exportGroup();

        harness.service().importArtifacts(harness.group("Failing change"));

        List<AuditRecord> records = harness.audit("import_artifacts");
        assertThat(records).extracting(AuditRecord::outcome).containsExactly(
            AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(records.get(1).inputs()).containsEntry("rollback", "NOT_NEEDED");
        assertThat(records.get(1).reason()).hasValueSatisfying(reason ->
            assertThat(reason).contains("IMPORT_FAILED"));
    }

    @Test
    void aRefusalIsAuditedWithItsCode() throws IOException {
        ImportHarness harness = edited();
        harness.edit(harness.workflow("Workflow-0001"), "moduleOutId=\"2\"",
            "moduleOutId=\"77\"");

        assertThatThrownBy(() -> harness.service().importArtifacts(harness.group("Refused")))
            .isInstanceOf(ToolErrorException.class);

        assertThat(harness.audit("import_artifacts")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("PRECONDITION_FAILED"));
        });
    }

    @Test
    void aRestoreIsAuditedWithTheBackupItRestoresAndItsOwnBackup() throws IOException {
        ImportHarness harness = edited();
        harness.exportGroup().importApplied().exportGroup();
        String ref = ((ImportService.Response.Completed) harness.service().importArtifacts(
            harness.group("Audited change"))).outcome().backupRef().orElseThrow();
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = ((ImportService.Response.Completed) harness.service().restore(
            new ImportService.RestoreRequest("dev/node1", ref, "Audited undo",
                java.util.Optional.empty(), java.util.Optional.empty()))).outcome();

        List<AuditRecord> records = harness.audit("restore_backup");
        assertThat(records).extracting(AuditRecord::outcome).containsExactly(
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(records).allSatisfy(record -> assertThat(record.inputs())
            .containsEntry("reason", "Audited undo").containsEntry("backupRef", ref)
            .containsEntry("scope", "diagram group GRP-01").containsEntry("owner", "jdoe")
            .doesNotContainKey("ownerKind").containsEntry("changeSet", "Workflow-0001")
            .containsEntry("newBackupRef", outcome.auditId().toString()));
        assertThat(records.toString()).doesNotContain("xPos");
    }

    @Test
    void aRefusedRestoreIsAuditedWithItsReference() throws IOException {
        ImportHarness harness = edited();
        String ref = java.util.UUID.randomUUID().toString();

        assertThatThrownBy(() -> harness.service().restore(new ImportService.RestoreRequest(
            "dev/node1", ref, "Unknown", java.util.Optional.empty(),
            java.util.Optional.empty()))).isInstanceOf(ToolErrorException.class);

        assertThat(harness.audit("restore_backup")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.inputs()).containsEntry("backupRef", ref);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("NOT_FOUND"));
        });
    }

    @Test
    void aLongChangeSetIsCutInTheAudit() throws IOException {
        ImportHarness harness = new ImportHarness(temp, ExportHarness.large(25, 100), "jdoe",
            "GRP-01");
        for (int i = 0; i < 25; i++) {
            harness.edit(harness.workflow("Workflow-%04d".formatted(2000 + i)), "xPos=\"",
                "xPos=\"1");
        }
        harness.exportGroup().importApplied().exportGroup();

        harness.service().importArtifacts(harness.group("Many"));

        String changeSet = harness.audit("import_artifacts").get(0).inputs().get("changeSet");
        assertThat(changeSet.split(", ")).hasSize(21);
        assertThat(changeSet).endsWith("…+5");
    }
}
