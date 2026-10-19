package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T009 (feature 009, US2, FR-005, FR-008, FR-011, FR-015): {@code import_artifacts},
 * {@code restore_backup} of a development backup and {@code set_active} pass the version gate
 * after the configuration checks and before the StartCLI check. A node on INUBIT 9.0, pinned to
 * {@code V9_X} or with a failed detection is refused with {@code UNSUPPORTED_VERSION} naming the
 * tool, audited {@code REFUSED}, and nothing but the detection is requested. A StartCLI client
 * whose patch differs from the server's admits the call with the mismatch in {@code warnings}.
 *
 * <p>T021 (US4, FR-001, FR-012): a node pinned to {@code 8.1.17} imports without any detection
 * request; a pin contradicted by {@code get_health} refuses the import until a matching
 * observation.
 */
@Timeout(120)
class ImportServiceVersionTest {

    private static final NodeId DEV = ImportHarness.DEV;

    @TempDir
    Path temp;

    /** The three calls of {@link ImportService} that the guard admits for development writes. */
    enum Call {
        IMPORT("import_artifacts", h -> h.service().importArtifacts(h.group("Move it"))),
        RESTORE("restore_backup", h -> h.service().restore(new ImportService.RestoreRequest(
            DEV.value(), UUID.randomUUID().toString(), "Undo it", Optional.empty(),
            Optional.empty()))),
        SET_ACTIVE("set_active", h -> h.service().setActive(new ImportService.ActivationRequest(
            DEV.value(), Optional.empty(), "GRP-01", "Workflow-0001", true, "Switch it",
            Optional.empty(), Optional.empty())));

        final String tool;
        final Function<ImportHarness, ImportService.Response> run;

        Call(String tool, Function<ImportHarness, ImportService.Response> run) {
            this.tool = tool;
            this.run = run;
        }
    }

    private ToolError refusal(ImportHarness harness, Call call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> call.run.apply(harness));
        assertThat(exception).as("expected a refusal").isNotNull();
        assertThat(harness.cli.launches()).as("no StartCLI call").isEmpty();
        assertThat(harness.inubit.imported).as("nothing was imported").isEmpty();
        assertThat(harness.audit(call.tool)).singleElement().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        ToolError error = exception.error();
        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).startsWith(call.tool + ": ").containsIgnoringCase("development writes")
            .contains("nothing was sent");
        return error;
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    void aNodeDetectedOnInubit90IsRefusedAfterOnlyTheDetection(Call call) throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.versionLines.put(DEV, VersionSetting.AUTO);
        harness.detection.answer(DEV, "9.0.7");

        ToolError error = refusal(harness, call);

        assertThat(error.message()).contains("INUBIT 9.0.7 on dev/node1");
        assertThat(harness.detection.requests()).containsExactly(DEV);
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    void aNodePinnedToV9xIsRefusedWithoutAnyRequest(Call call) throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.versionLines.put(DEV, VersionSetting.V9_X);

        ToolError error = refusal(harness, call);

        assertThat(error.message()).contains("INUBIT 9.x (versionLine V9_X)");
        assertThat(harness.detection.requests()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    void aFailedDetectionRefusesTheWrite(Call call) throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.versionLines.put(DEV, VersionSetting.AUTO);
        harness.detection.fail(DEV, ErrorCode.AUTH_FAILED);

        ToolError error = refusal(harness, call);

        assertThat(error.message()).contains("the INUBIT version of dev/node1 is unknown"
            + " (AUTH_FAILED at ");
        assertThat(harness.detection.requests()).containsExactly(DEV);
    }

    @Test
    void anImportWithAClientPatchMismatchRunsWithTheWarning() throws IOException {
        ImportHarness harness = mismatched();
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Move it")));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.warnings()).contains(MISMATCH);
        assertThat(harness.detection.requests()).containsExactly(DEV);
    }

    @Test
    void aRestoreWithAClientPatchMismatchRunsWithTheWarning() throws IOException {
        ImportHarness harness = mismatched();
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();
        String ref = completed(harness.service().importArtifacts(harness.group("Move it")))
            .backupRef().orElseThrow();
        harness.exportGroup().importRestored().exportGroup();

        WriteOutcome outcome = completed(harness.service().restore(
            new ImportService.RestoreRequest(DEV.value(), ref, "Undo it", Optional.empty(),
                Optional.empty())));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.warnings()).contains(MISMATCH);
    }

    @Test
    void anActivationWithAClientPatchMismatchRunsWithTheWarning() throws IOException {
        ImportHarness harness = mismatched();
        harness.exportGroup().importApplied("--importWorkflow --importWorkflowActive"
            + " --importUser 'jdoe' --returnProtocol").exportGroup();

        WriteOutcome outcome = completed(Call.SET_ACTIVE.run.apply(harness));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.warnings()).contains(MISMATCH);
    }

    @Test
    void aPinned81NodeWithoutAClientResultRunsWithoutVersionWarnings() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Move it")));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.warnings()).noneMatch(warning -> warning.contains("StartCLI client"));
        assertThat(harness.detection.requests()).isEmpty();
    }

    @Test
    void aNodePinnedTo8117ImportsWithoutAnyDetectionRequest() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.versionLines.put(DEV, pin("8.1.17"));
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Move it")));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(harness.detection.requests()).isEmpty();
    }

    @Test
    void aPinContradictedByGetHealthRefusesTheImportUntilAMatchingObservation()
        throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.versionLines.put(DEV, pin("8.1.17"));
        // what get_health records for the version it read (HealthService, T016)
        harness.versions.observed(DEV, version("9.0.7"));

        ToolError error = refusal(harness, Call.IMPORT);

        assertThat(error.message()).contains("versionLine 8.1.17 of dev/node1 contradicts"
            + " INUBIT 9.0.7 observed by get_health at 2026-10-07T10:00:00Z");

        harness.versions.observed(DEV, version("8.1.17"));
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Move it")));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(harness.detection.requests()).isEmpty();
    }

    private static VersionSetting pin(String text) {
        return VersionSetting.parse(text).orElseThrow();
    }

    private static final String MISMATCH = "StartCLI client 8.1.17 and INUBIT 8.1.18 on"
        + " dev/node1 differ; StartCLI results may be unreliable";

    /** An {@code AUTO} node detected as 8.1.18 with a seeded 8.1.17 client. */
    private ImportHarness mismatched() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.versionLines.put(DEV, VersionSetting.AUTO);
        harness.detection.answer(DEV, "8.1.18");
        harness.versions.clientProbed(DEV, new ClientVersion(version("8.1.17"),
            Optional.of(version("4.0.1")), Optional.of(version("8.1.17"))));
        return harness;
    }

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static WriteOutcome completed(ImportService.Response response) {
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response).outcome();
    }
}
