package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T030 (feature 004, US4 AS 5, FR-021, research D-26): {@code import_artifacts} with an optional
 * {@code tag} — only for a diagram-group import (a module import with a tag is refused before
 * anything is sent); the tag is set after the verified import and its write-back, in the same
 * call and audit record, and verified like {@code tag_artifacts}; a tag failure never undoes the
 * import (the result stays {@code EXECUTED} with {@code tag.applied: false}, the failure and a
 * warning to retry {@code tag_artifacts}); nothing is ever removed.
 */
@Timeout(120)
class ImportTagTest {

    @TempDir
    Path temp;

    private static ImportHarness edited(ImportHarness harness) {
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        return harness;
    }

    private static WriteOutcome completed(ImportService.Response response) {
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response).outcome();
    }

    private static void assertNeverRemoved(ImportHarness harness) {
        harness.cli.verifyComplete();
        assertThat(harness.cli.execCommands()).noneMatch(line -> line.contains("--tagDelete"));
    }

    @Test
    void theTagIsSetAfterTheVerifiedImportAndItsWriteBackInTheSameAuditedCall()
        throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        harness.exportGroup().importApplied().exportGroup().tagMoved("REL-1", false)
            .historyExport("REL-1");

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.tagged("Release", "REL-1")));

        assertNeverRemoved(harness);
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.commit()).isPresent();
        assertThat(outcome.tag()).hasValueSatisfying(tag -> {
            assertThat(tag.name()).isEqualTo("REL-1");
            assertThat(tag.applied()).isTrue();
            assertThat(tag.workflows()).isEqualTo(2);
            assertThat(tag.modules()).isEqualTo(1);
            assertThat(tag.failure()).isEmpty();
        });
        List<String> commands = harness.cli.execCommands();
        assertThat(commands.get(3)).startsWith("tag --tagMove 'REL-1' --tagWorkflowGroup"
            + " 'GRP-01'");
        assertThat(harness.read(harness.workflow("Workflow-0001"))).contains("xPos=\"140\"");
        List<AuditRecord> records = harness.audit("import_artifacts");
        assertThat(records).extracting(AuditRecord::outcome).containsExactly(
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(records).allSatisfy(record -> assertThat(record.inputs())
            .containsEntry("tag", "REL-1"));
        assertThat(records.get(1).inputs()).containsEntry("tagApplied", "true");
        assertThat(records.get(1).reason()).hasValueSatisfying(reason ->
            assertThat(reason).contains("REL-1"));
    }

    @Test
    void aFailingTagCommandKeepsTheImportAndAsksToRetryTagArtifacts() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        harness.exportGroup().importApplied().exportGroup().tagMoved("REL-1", true);

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.tagged("Release", "REL-1")));

        assertNeverRemoved(harness);
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.commit()).isPresent();
        assertThat(outcome.modified()).containsExactly("Workflow-0001");
        assertThat(outcome.tag()).hasValueSatisfying(tag -> {
            assertThat(tag.applied()).isFalse();
            assertThat(tag.failure()).hasValueSatisfying(failure -> {
                assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
                assertThat(failure.step()).isEqualTo("tag");
            });
        });
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("tag_artifacts")
            && warning.contains("REL-1"));
        assertThat(harness.audit("import_artifacts")).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.EXECUTED);
            assertThat(record.inputs()).containsEntry("tagApplied", "false");
        });
    }

    @Test
    void aCurrentVersionWithoutTheTagIsReportedAndNothingIsRemoved() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        harness.exportGroup().importApplied().exportGroup().tagMoved("REL-1", false)
            .historyExport("REL-1", "Module-0003");

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.tagged("Release", "REL-1")));

        assertNeverRemoved(harness);
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.tag()).hasValueSatisfying(tag -> {
            assertThat(tag.applied()).isFalse();
            assertThat(tag.workflows()).isEqualTo(2);
            assertThat(tag.modules()).isZero();
            assertThat(tag.failure()).hasValueSatisfying(failure -> {
                assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
                assertThat(failure.message()).contains("Module-0003");
            });
        });
        assertThat(outcome.reports()).anyMatch(report -> report.startsWith(".reports/tag-"));
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("tag_artifacts"));
    }

    @Test
    void aFailedImportSetsNoTag() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        harness.exportGroup().importRefused().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.tagged("Release", "REL-1")));

        assertNeverRemoved(harness);
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(harness.cli.execCommands()).noneMatch(line -> line.startsWith("tag "));
        assertThat(outcome.tag()).hasValueSatisfying(tag -> {
            assertThat(tag.applied()).isFalse();
            assertThat(tag.failure()).isEmpty();
        });
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("REL-1")
            && warning.contains("not set"));
    }

    @Test
    void anUnchangedWorkspaceSendsNothingAndSetsNoTag() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.tagged("Release", "REL-1")));

        assertThat(harness.cli.launches()).isEmpty();
        assertThat(outcome.tag()).hasValueSatisfying(tag -> assertThat(tag.applied()).isFalse());
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("tag_artifacts"));
    }

    @Test
    void aModuleImportWithTagOrAnUnquotableTagIsRefusedBeforeAnythingIsSent()
        throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        ImportService service = harness.service();
        for (ImportService.ImportRequest request : List.of(
            new ImportService.ImportRequest("dev/node1", Optional.empty(), Optional.empty(),
                List.of(new ImportScope.Module("Module-0003", Optional.empty())), "Module",
                Optional.empty(), Optional.empty(), Optional.of("REL-1")),
            harness.tagged("Release", "REL'1"), harness.tagged("Release", " "))) {
            ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
                () -> service.importArtifacts(request));
            assertThat(refused).as(request.toString()).isNotNull();
            ToolError error = refused.error();
            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThat(catchThrowableOfType(ToolErrorException.class, () -> service.importArtifacts(
            new ImportService.ImportRequest("dev/node1", Optional.empty(), Optional.empty(),
                List.of(new ImportScope.Module("Module-0003", Optional.empty())), "Module",
                Optional.empty(), Optional.empty(), Optional.of("REL-1")))).error().message())
            .contains("diagram group");
        assertThat(harness.cli.launches()).isEmpty();
        assertThat(harness.audit("import_artifacts")).isNotEmpty().allSatisfy(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
    }

    @Test
    void thePreviewShowsTheTagAndItsCodeIsBoundToIt() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        ImportService service = harness.service();
        harness.exportGroup();

        ImportPreview preview = ((ImportService.Response.Challenge) service.importArtifacts(
            harness.tagged("Release", "REL-1"))).preview();

        assertThat(preview.tag()).contains("REL-1");
        assertThat(preview.message()).contains("REL-1");
        ToolErrorException other = catchThrowableOfType(ToolErrorException.class,
            () -> service.importArtifacts(ImportHarness.confirmed(harness.tagged("Release",
                "REL-2"), preview.confirmationCode())));
        assertThat(other).isNotNull();
        assertThat(other.error().code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        harness.cli.verifyComplete();
    }
}
