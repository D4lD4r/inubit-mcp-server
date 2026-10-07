package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T028 (feature 004, FR-014, research D-26): the owner kind is gone. INUBIT imports for a
 * user-group owner only with {@code --importUser '<owner>'} ({@code --importUserGroup} answers
 * "Missing user or group!"), and the same works for users — so every import, restore,
 * activation and rollback names the owner with {@code --importUser}, whatever it is, without a
 * lookup and without a refusal. The fixture's shared user-group owner {@code OWNERS} is in no
 * user list and in no profile setting.
 */
@Timeout(120)
class UserGroupOwnerTest {

    private static final String WORKFLOW = "--importWorkflow --importUser 'OWNERS'"
        + " --returnProtocol";

    @TempDir
    Path temp;

    private static ImportHarness owners(Path temp) throws IOException {
        return new ImportHarness(temp, ArtifactFixtures.bytes("grp-b.zip"), "OWNERS", "GRP-02");
    }

    /** The outcome of {@code call}; a refusal fails the test on an assertion. */
    private static WriteOutcome completed(Supplier<ImportService.Response> call) {
        AtomicReference<ImportService.Response> response = new AtomicReference<>();
        Throwable thrown = catchThrowable(() -> response.set(call.get()));
        assertThat(thrown).as("a user-group owner is not refused").isNull();
        assertThat(response.get()).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response.get()).outcome();
    }

    private static void assertImportUserOnly(ImportHarness harness) {
        harness.cli.verifyComplete();
        assertThat(harness.cli.execCommands())
            .filteredOn(command -> command.startsWith("import ")).isNotEmpty().allMatch(command -> command.contains("--importUser 'OWNERS'"))
            .noneMatch(command -> command.contains("--importUserGroup"));
        assertThat(harness.audit).extracting(AuditRecord::inputs)
            .allSatisfy(inputs -> assertThat(inputs).doesNotContainKey("ownerKind"));
    }

    @Test
    void anImportForAUserGroupOwnerUsesImportUser() throws IOException {
        ImportHarness harness = owners(temp);
        harness.edit(harness.workflow("Workflow-0006"), "xPos=\"", "xPos=\"1");
        harness.exportGroup().importApplied(WORKFLOW).exportGroup();

        WriteOutcome outcome = completed(() -> harness.service().importArtifacts(
            harness.group("Group owner change")));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0006");
        assertImportUserOnly(harness);
    }

    @Test
    void aRestoreAndItsRollbackForAUserGroupOwnerUseImportUser() throws IOException {
        ImportHarness harness = owners(temp);
        harness.edit(harness.workflow("Workflow-0006"), "xPos=\"", "xPos=\"1");
        harness.exportGroup().importApplied(WORKFLOW).exportGroup();
        String ref = completed(() -> harness.service().importArtifacts(harness.group(
            "Group owner change"))).backupRef().orElseThrow();
        String active = "--importWorkflow --importWorkflowActive --importUser 'OWNERS'"
            + " --returnProtocol";
        harness.inubit.tamperNextImport = new String[] {"xPos=\"", "xPos=\"9"};
        harness.exportGroup().importApplied(active).exportGroup().importApplied(active)
            .exportGroup();

        WriteOutcome outcome = completed(() -> harness.service().restore(
            new ImportService.RestoreRequest("dev/node1", ref, "Undo it", Optional.empty(),
                Optional.empty())));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.rollback()).isPresent();
        assertImportUserOnly(harness);
    }

    @Test
    void anActivationForAUserGroupOwnerUsesImportUser() throws IOException {
        ImportHarness harness = owners(temp);
        harness.exportGroup().importApplied("--importWorkflow --importWorkflowInactive"
            + " --importUser 'OWNERS' --returnProtocol").exportGroup();

        WriteOutcome outcome = completed(() -> harness.service().setActive(
            new ImportService.ActivationRequest("dev/node1", Optional.empty(), "GRP-02",
                "Workflow-0006", false, "Off for tests", Optional.empty(), Optional.empty())));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertImportUserOnly(harness);
    }
}
