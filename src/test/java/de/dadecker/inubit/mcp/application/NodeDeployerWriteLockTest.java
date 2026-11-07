package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.NodeOutcome;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.State;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 017 T046 (FR-018, research D-24, contracts "Every write tool"): multi-step writes when
 * INUBIT changes from 8.1.17 to 9.0.7 while a call runs. The harnesses hand every StartCLI write
 * off under the real lock of their node versions; the change is made right before a chosen
 * hand-off takes the lock, i.e. after the call was admitted.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodeDeployerWriteLockTest extends DeployExecution {

    private static final InubitVersion V9_0_7 = InubitVersion.parse("9.0.7").orElseThrow();
    private static final String REFUSAL = "UNSUPPORTED_VERSION: INUBIT on %s changed from 8.1.17"
        + " to 9.0.7 during this call; the write was admitted for INUBIT 8.1; nothing was sent";

    @TempDir
    Path importTemp;

    /** The targets run AUTO and are detected as 8.1.17 when the call is admitted. */
    private void autoTargets() {
        DeployHarness.TARGETS.forEach(node -> {
            harness.versionLines.put(node, VersionSetting.AUTO);
            harness.detection.answer(node, "8.1.17");
        });
    }

    /** A parallel call finds 9.0.7 on {@code node} right before its {@code nth} write. */
    private void changesBefore(NodeId node, int nth) {
        changesBefore(node, nth, () -> harness.versions.detected(node, V9_0_7));
    }

    private void changesBefore(NodeId node, int nth, Runnable change) {
        int[] seen = {0};
        harness.writeLock.changeBefore(permit -> permit.node().equals(node) && ++seen[0] == nth,
            change);
    }

    /**
     * A deployment whose module import on INT1 is refused after its repository import replaced
     * the existing file; the re-import of the old file is refused as well.
     */
    private DeploymentResult refusedRollback(Runnable change) {
        harness(false);
        autoTargets();
        String old = DeployHarness.RELEASE_XSL_V1.replace("'v1'", "'v0'");
        DeployHarness.TARGETS.forEach(node -> harness.servers.get(node)
            .putRepositoryFile(DeployHarness.RELEASE_XSL, old));
        DeploymentPreview preview = preview();
        executeStart();
        nodeExports(DeployHarness.INT1); // re-check
        harness.importRepositoryApplied(DeployHarness.INT1);
        nodeExports(DeployHarness.INT1); // the state after the refusal: the file changed
        changesBefore(DeployHarness.INT1, 2, change);
        return execute(preview);
    }

    private List<AuditRecord> nodeAudit(NodeId node) {
        return harness.audit.stream().filter(r -> r.node().equals(node.toString())).toList();
    }

    @Test
    void aVersionChangeBetweenTwoNodesFailsTheSecondNodesImportWithTheRefusal() {
        harness(false);
        autoTargets();
        DeploymentPreview preview = preview();
        executeStart();
        deployed(DeployHarness.INT1);
        nodeExports(DeployHarness.INT2); // re-check
        nodeExports(DeployHarness.INT2); // the state after the refusal; nothing to re-import
        changesBefore(DeployHarness.INT2, 1);

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.FAILED);
        assertThat(result.nodes()).extracting(NodeOutcome::state).containsExactly(
            State.DEPLOYED, State.ROLLED_BACK, State.NOT_STARTED);
        NodeOutcome second = result.nodes().get(1);
        assertThat(second.failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("import");
            assertThat(failure.message()).contains(REFUSAL.formatted("int/node2"));
        });
        assertThat(second.backupRef()).isPresent();
        assertThat(harness.launches()).noneMatch(line -> line.startsWith("int/node2 import")
            || line.startsWith("int/node3 import") || line.startsWith("int/node3 tag"));
        assertThat(harness.servers.get(DeployHarness.INT2).repositoryImports).isEmpty();
        assertThat(nodeAudit(DeployHarness.INT2)).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(result.warnings()).noneMatch(w -> w.contains("rollback of int/node2"));
        assertThat(harness.writeLock.permits()).allSatisfy(permit -> {
            assertThat(permit.capability()).isEqualTo(Capability.DEPLOYMENT);
            assertThat(permit.admitted().raw()).isEqualTo("8.1.17");
        });
        harness.verifyComplete();
    }

    @Test
    void aRefusedRollbackIsRollbackFailedWithAWarningNamingBothVersionsAndTheBackupIsKept() {
        DeploymentResult result = refusedRollback(() -> harness.versions.detected(
            DeployHarness.INT1, V9_0_7));

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.FAILED);
        NodeOutcome first = result.nodes().get(0);
        assertThat(first.state()).isEqualTo(State.ROLLBACK_FAILED);
        assertThat(first.failure()).get().satisfies(failure ->
            assertThat(failure.message()).contains(REFUSAL.formatted("int/node1")));
        String backupRef = first.backupRef().orElseThrow();
        assertThat(harness.backups.find(backupRef)).get().satisfies(backup ->
            assertThat(backup.outcome()).isEqualTo("ROLLBACK_FAILED"));
        assertThat(result.warnings()).contains("The rollback of int/node1 was not sent because"
            + " INUBIT changed from 8.1.17 to 9.0.7 during this call; its state before the"
            + " deployment is in the backup " + backupRef + ", taken on INUBIT 8.1; restore it"
            + " with restore_backup only on INUBIT 8.1");
        assertThat(result.warnings()).noneMatch(w -> w.endsWith("(restore_backup)"));
        assertThat(harness.servers.get(DeployHarness.INT1).repositoryImports).hasSize(1);
        assertThat(harness.servers.get(DeployHarness.INT1).repositoryContent(
            DeployHarness.RELEASE_XSL)).contains(DeployHarness.RELEASE_XSL_V1);
        assertThat(result.nodes().get(1).state()).isEqualTo(State.NOT_STARTED);
        // the import that was sent, the refused module import and the refused rollback
        assertThat(harness.writeLock.permits()).hasSize(3).allSatisfy(permit -> {
            assertThat(permit.node()).isEqualTo(DeployHarness.INT1);
            assertThat(permit.capability()).isEqualTo(Capability.DEPLOYMENT);
        });
        harness.verifyComplete();
    }

    @Test
    void aRollbackRefusedOnAStaleVersionSaysINUBITMayRunAnotherVersion() {
        DeploymentResult result = refusedRollback(() -> harness.versions.stale(
            DeployHarness.INT1));

        NodeOutcome first = result.nodes().get(0);
        assertThat(first.state()).isEqualTo(State.ROLLBACK_FAILED);
        assertThat(first.failure()).get().satisfies(failure -> assertThat(failure.message())
            .contains("UNSUPPORTED_VERSION: INUBIT on int/node1 may run another version than"
                + " 8.1.17 since this call was admitted; nothing was sent"));
        assertThat(result.warnings()).contains("The rollback of int/node1 was not sent because"
            + " INUBIT may run another version than 8.1.17 since this call was admitted; its"
            + " state before the deployment is in the backup " + first.backupRef().orElseThrow()
            + ", taken on INUBIT 8.1; restore it with restore_backup only on INUBIT 8.1");
        harness.verifyComplete();
    }

    @Test
    void aTagRefusedAfterAVersionChangeKeepsTheImportAndAsksToRetryTagArtifacts()
        throws IOException {
        ImportHarness imports = ImportHarness.grpA(importTemp);
        imports.versionLines.put(ImportHarness.DEV, VersionSetting.AUTO);
        imports.detection.answer(ImportHarness.DEV, "8.1.17");
        imports.edit(imports.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        imports.exportGroup().importApplied().exportGroup();
        imports.writeLock.changeBefore(2, () -> imports.versions.detected(ImportHarness.DEV,
            V9_0_7));

        WriteOutcome outcome = ((ImportService.Response.Completed) imports.service()
            .importArtifacts(imports.tagged("Release", "REL-1"))).outcome();

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.commit()).isPresent();
        assertThat(outcome.tag()).hasValueSatisfying(tag -> {
            assertThat(tag.applied()).isFalse();
            assertThat(tag.failure()).hasValueSatisfying(failure -> {
                assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
                assertThat(failure.step()).isEqualTo("tag");
                assertThat(failure.message()).contains(REFUSAL.formatted("dev/node1")
                    .substring("UNSUPPORTED_VERSION: ".length()));
            });
        });
        assertThat(outcome.warnings()).anyMatch(w -> w.contains("tag_artifacts")
            && w.contains("REL-1"));
        assertThat(imports.cli.execCommands()).noneMatch(command -> command.startsWith("tag "));
        assertThat(imports.writeLock.permits()).hasSize(2).allSatisfy(permit -> {
            assertThat(permit.node()).isEqualTo(ImportHarness.DEV);
            assertThat(permit.capability()).isEqualTo(Capability.DEVELOPMENT_WRITE);
        });
        imports.cli.verifyComplete();
    }
}
