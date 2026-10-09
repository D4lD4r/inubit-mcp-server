package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T016 (feature 004, FR-009, research D-2, D-25 L1): server-side confirmation — the preview
 * sends nothing and returns a code bound to inputs, workspace and server state; the confirmed
 * call repeats the conflict check right before the import.
 */
@Timeout(60)
class ImportConfirmationTest {

    @TempDir
    Path temp;

    private ImportHarness harness;
    private ImportService service;

    @BeforeEach
    void setUp() throws IOException {
        harness = ImportHarness.grpA(temp);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        service = harness.service();
    }

    private ImportPreview preview(String reason) {
        harness.exportGroup();
        ImportService.Response response = service.importArtifacts(harness.group(reason));
        assertThat(response).isInstanceOf(ImportService.Response.Challenge.class);
        return ((ImportService.Response.Challenge) response).preview();
    }

    private ToolError refusal(ImportService.ImportRequest request) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.importArtifacts(request));
        assertThat(exception).as("expected a refusal").isNotNull();
        assertThat(harness.inubit.imported).isEmpty();
        return exception.error();
    }

    @Test
    void theFirstCallOnlyPreviewsAndIssuesACode() {
        ImportPreview preview = preview("Move it");

        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).isEmpty();
        assertThat(preview.modify()).containsExactly("Workflow-0001");
        assertThat(preview.create()).isEmpty();
        assertThat(preview.scope()).isEqualTo("diagram group GRP-01");
        assertThat(preview.baseCommit()).matches("[0-9a-f]{40}");
        assertThat(preview.confirmationCode()).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(preview.message()).contains("confirmationCode");
        assertThat(harness.audit("import_artifacts")).singleElement().satisfies(record -> {
            assertThat(record.step()).isEqualTo(AuditRecord.Step.PREVIEW);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED);
            assertThat(record.inputs()).containsEntry(AuditRecord.ISSUED_CONFIRMATION_CODE,
                preview.confirmationCode());
        });
    }

    @Test
    void theConfirmedCallRepeatsTheConflictCheckAndImports() {
        ImportPreview preview = preview("Move it");
        harness.exportGroup().importApplied().exportGroup();

        ImportService.Response response = service.importArtifacts(ImportHarness.confirmed(
            harness.group("Move it"), preview.confirmationCode()));

        harness.cli.verifyComplete();
        assertThat(((ImportService.Response.Completed) response).outcome().outcome())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(harness.inubit.imported).hasSize(1);
    }

    @Test
    void aCodeForOtherInputsIsInvalidAndNothingIsSent() {
        ImportPreview preview = preview("Move it");

        ToolError error = refusal(ImportHarness.confirmed(harness.group("Another reason"),
            preview.confirmationCode()));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        harness.cli.verifyComplete();
    }

    @Test
    void aWorkspaceThatChangedAfterThePreviewNeedsANewPreview() {
        ImportPreview preview = preview("Move it");
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"140\"", "xPos=\"160\"");

        ToolError error = refusal(ImportHarness.confirmed(harness.group("Move it"),
            preview.confirmationCode()));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(error.message()).contains("preview again");
        harness.cli.verifyComplete();
    }

    @Test
    void aServerChangeAfterThePreviewIsAConflict() {
        ImportPreview preview = preview("Move it");
        harness.inubit.changeWorkflows(xml -> xml.replace("xPos=\"520\" yPos=\"130\"",
            "xPos=\"530\" yPos=\"130\""));
        harness.exportGroup();

        ToolError error = refusal(ImportHarness.confirmed(harness.group("Move it"),
            preview.confirmationCode()));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        harness.cli.verifyComplete();
    }

    @Test
    void connectionsSwappedOnTheServerAfterThePreviewAreNoChange() {
        // feature 007 (US1 scenario 4): INUBIT writes a module's connections in any order
        ImportPreview preview = preview("Move it");
        harness.inubit.changeWorkflows(ImportHarness::swapModule0002);
        harness.exportGroup().importApplied().exportGroup();

        ImportService.Response response = service.importArtifacts(ImportHarness.confirmed(
            harness.group("Move it"), preview.confirmationCode()));

        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        assertThat(((ImportService.Response.Completed) response).outcome().outcome())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(harness.inubit.imported).hasSize(1);
    }

    @Test
    void anotherConnectionOnTheServerAfterThePreviewIsAConflict() {
        ImportPreview preview = preview("Move it");
        harness.inubit.changeWorkflows(xml -> ImportHarness.swapModule0002(xml).replace(
            ImportHarness.connections(false, "3/6", "4/9"), ImportHarness.connections(false,
                "3/6", "4/10")));
        harness.exportGroup();

        ToolError error = refusal(ImportHarness.confirmed(harness.group("Move it"),
            preview.confirmationCode()));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        harness.cli.verifyComplete();
    }

    @Test
    void anExpiredCodeIsInvalid() {
        ImportPreview preview = preview("Move it");
        harness.clock.advance(Duration.ofMinutes(6));

        ToolError error = refusal(ImportHarness.confirmed(harness.group("Move it"),
            preview.confirmationCode()));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }
}
