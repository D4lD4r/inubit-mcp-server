package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.NodeOutcome;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.State;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 017, review of stage 5 (FR-009, FR-018, research D-24): a write whose own response is
 * unexpected marks its AUTO node stale with its permit. The same call's rollback is still handed
 * off on the version it was admitted on, as in 0.8.0; a foreign stale mark (a parallel read) or
 * a version change still refuses it. The ports report through the real stale wrapper to the
 * real node versions; every change is made right before a chosen hand-off takes the lock.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OwnStaleMarkTest extends DeployExecution {

    private static final NodeId DEV = ImportHarness.DEV;
    private static final NodeId INT1 = DeployHarness.INT1;
    private static final InubitVersion V9_0_7 = InubitVersion.parse("9.0.7").orElseThrow();
    private static final String MAY_RUN_ANOTHER = "may run another version than 8.1.17 since"
        + " this call was admitted";
    private static final String WORKFLOW_INACTIVE = "--importWorkflow --importWorkflowInactive"
        + " --importUser 'jdoe' --returnProtocol";
    private static final String WORKFLOW = "--importWorkflow --importUser 'jdoe'"
        + " --returnProtocol";

    @TempDir
    Path importTemp;

    /** StartCLI applied the import but printed its protocol without the Total line. */
    private static String withoutTotal(String stdout) {
        assertThat(stdout).contains("Total: ");
        return stdout.replaceAll("(?m)^Total: \\d+\\r?$", "");
    }

    // --- import_artifacts ----------------------------------------------------------------------

    /** {@link ImportHarness#DEV} runs AUTO, is detected as 8.1.17 and gets a moved workflow. */
    private ImportHarness imports() throws IOException {
        ImportHarness imports = ImportHarness.grpA(importTemp);
        imports.staleReporting = true;
        imports.versionLines.put(DEV, VersionSetting.AUTO);
        imports.detection.answer(DEV, "8.1.17");
        imports.edit(imports.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        return imports;
    }

    /** The next StartCLI call is an import the server applies, answered without Total. */
    private static void importAppliedWithoutTotal(ImportHarness imports) {
        imports.cli.expect("import ").capturingImportFile().replyingWith(spec -> withoutTotal(
            imports.inubit.importArchive(ImportHarness.read(
                ScriptedProcessLauncher.importFile(spec)))));
    }

    private static long cliImports(ImportHarness imports) {
        return imports.cli.execCommands().stream().filter(c -> c.startsWith("import ")).count();
    }

    private static WriteOutcome importArtifacts(ImportHarness imports) {
        return ((ImportService.Response.Completed) imports.service().importArtifacts(
            imports.group("Move it"))).outcome();
    }

    @Test
    void anImportsOwnUnexpectedResponseStillSendsItsRollbackUnderThePermit() throws IOException {
        ImportHarness imports = imports();
        imports.exportGroup();
        importAppliedWithoutTotal(imports);
        imports.exportGroup() // the state after the failure
            .importApplied() // the rollback
            .exportGroup(); // its verification
        List<NodeVersionState> atRollback = new ArrayList<>();
        imports.writeLock.changeBefore(2, () -> atRollback.add(imports.versions.state(DEV)));

        WriteOutcome outcome = importArtifacts(imports);

        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.message()).contains("The import protocol has no Total line");
        });
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
        assertThat(cliImports(imports)).isEqualTo(2);
        List<WritePermit> permits = imports.writeLock.permits();
        assertThat(permits).hasSize(2);
        assertThat(permits.get(1)).as("the rollback's permit is the import's")
            .isSameAs(permits.get(0));
        assertThat(atRollback).singleElement().as("the rollback is handed off on the own mark")
            .satisfies(state -> assertThat(state.staleOnlyBy(permits.get(0))).isTrue());
        assertThat(imports.versions.state(DEV).stale()).as("the next write re-detects").isTrue();
        imports.cli.verifyComplete();
    }

    @Test
    void aForeignStaleMarkAfterTheOwnOneRefusesTheRollback() throws IOException {
        ImportHarness imports = imports();
        imports.exportGroup();
        importAppliedWithoutTotal(imports);
        imports.exportGroup() // the state after the failure
            .exportGroup(); // the state after the refused rollback
        imports.writeLock.changeBefore(2, () -> imports.versions.stale(DEV));

        WriteOutcome outcome = importArtifacts(imports);

        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.FAILED);
        assertThat(cliImports(imports)).as("the rollback was not launched").isEqualTo(1);
        imports.cli.verifyComplete();
    }

    @Test
    void aForeignStaleMarkAfterTheAdmissionRefusesTheFirstWrite() throws IOException {
        ImportHarness imports = imports();
        imports.exportGroup()
            .exportGroup(); // the state after the refusal: nothing to roll back
        imports.writeLock.changeBefore(1, () -> imports.versions.stale(DEV));

        WriteOutcome outcome = importArtifacts(imports);

        assertThat(outcome.failure()).hasValueSatisfying(failure -> assertThat(
            failure.message()).contains("INUBIT on dev/node1 " + MAY_RUN_ANOTHER
                + "; nothing was sent"));
        assertThat(cliImports(imports)).isZero();
        imports.cli.verifyComplete();
    }

    // --- deploy_release ------------------------------------------------------------------------

    /**
     * A deployment of a changed workflow and a changed repository file whose workflow import on
     * INT1 (its third write) is applied but answered without Total; the targets run AUTO and are
     * detected as 8.1.17.
     */
    private DeploymentPreview unexpectedWorkflowImport() {
        harness(false);
        harness.staleReporting = true;
        DeployHarness.TARGETS.forEach(node -> {
            harness.versionLines.put(node, VersionSetting.AUTO);
            harness.detection.answer(node, "8.1.17");
        });
        String old = DeployHarness.RELEASE_XSL_V1.replace("'v1'", "'v0'");
        DeployHarness.TARGETS.forEach(node -> harness.servers.get(node)
            .putRepositoryFile(DeployHarness.RELEASE_XSL, old));
        source(server -> server.publishWorkflow("Workflow-0001", xml -> xml.replace(
            "<ConnectionId>5</ConnectionId>", "<ConnectionId>7</ConnectionId>")), true);
        DeployHarness.TARGETS.forEach(this::nodeExports);
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request(
            Optional.empty())).preview().orElseThrow();
        executeStart();
        nodeExports(INT1); // re-check
        harness.importRepositoryApplied(INT1).importApplied(INT1, MODULE_IMPORT);
        FakeServer target = harness.servers.get(INT1);
        harness.cli.get(INT1).expect(Pattern.compile("^import --importFile '[^']+' "
                + Pattern.quote(WORKFLOW_INACTIVE) + "$"))
            .capturingImportFile()
            .replyingWith(spec -> withoutTotal(target.importArchive(ImportHarness.read(
                ScriptedProcessLauncher.importFile(spec)), Boolean.FALSE)));
        nodeExports(INT1); // the state after the failure
        return preview;
    }

    /** A parallel call does {@code change} right before the {@code nth} hand-off on INT1. */
    private void changesBefore(int nth, Runnable change) {
        int[] seen = {0};
        harness.writeLock.changeBefore(permit -> permit.node().equals(INT1) && ++seen[0] == nth,
            change);
    }

    private List<WritePermit> permitsOf(NodeId node) {
        return harness.writeLock.permits().stream().filter(p -> p.node().equals(node)).toList();
    }

    @Test
    void aDeploymentsOwnUnexpectedResponseStillRollsTheNodeBack() {
        DeploymentPreview preview = unexpectedWorkflowImport();
        harness.importRepositoryApplied(INT1) // rollback: the old file
            .importApplied(INT1, MODULE_IMPORT)
            .importApplied(INT1, WORKFLOW);
        nodeExports(INT1); // verification of the rollback
        List<NodeVersionState> atRollback = new ArrayList<>();
        changesBefore(4, () -> atRollback.add(harness.versions.state(INT1)));

        DeploymentResult result = execute(preview);

        NodeOutcome first = result.nodes().get(0);
        assertThat(first.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.message()).contains("The import protocol has no Total line")
                .doesNotContain("UNSUPPORTED_VERSION");
        });
        assertThat(first.state()).isEqualTo(State.ROLLED_BACK);
        assertThat(result.warnings()).noneMatch(w -> w.contains("rollback of int/node1"));
        assertThat(harness.servers.get(INT1).repositoryContent(DeployHarness.RELEASE_XSL))
            .hasValueSatisfying(content -> assertThat(content).contains("'v0'"));
        List<WritePermit> permits = permitsOf(INT1);
        assertThat(permits).as("three imports and three re-imports").hasSize(6)
            .allSatisfy(permit -> assertThat(permit).isSameAs(permits.get(0)));
        assertThat(atRollback).singleElement().as("the rollback is handed off on the own mark")
            .satisfies(state -> assertThat(state.staleOnlyBy(permits.get(0))).isTrue());
        assertThat(harness.versions.state(INT1).stale()).as("the next write re-detects")
            .isTrue();
        assertThat(result.nodes().get(1).state()).isEqualTo(State.NOT_STARTED);
        harness.verifyComplete();
    }

    @Test
    void aForeignStaleMarkBeforeTheRollbackRefusesItAndNothingOfItWasSent() {
        DeploymentPreview preview = unexpectedWorkflowImport();
        changesBefore(4, () -> harness.versions.stale(INT1));

        DeploymentResult result = execute(preview);

        NodeOutcome first = result.nodes().get(0);
        assertThat(first.state()).isEqualTo(State.ROLLBACK_FAILED);
        assertThat(result.warnings()).contains("The rollback of int/node1 was not sent because"
            + " INUBIT " + MAY_RUN_ANOTHER + "; its state before the deployment is in the"
            + " backup " + first.backupRef().orElseThrow() + ", taken on INUBIT 8.1; restore"
            + " it with restore_backup only on INUBIT 8.1");
        assertThat(harness.servers.get(INT1).repositoryImports).as("the deployment's only")
            .hasSize(1);
        harness.verifyComplete();
    }

    @Test
    void aRollbackRefusedPartwayNamesWhatItReImported() {
        DeploymentPreview preview = unexpectedWorkflowImport();
        harness.importRepositoryApplied(INT1); // rollback: the old file, then the refusal
        changesBefore(5, () -> harness.versions.detected(INT1, V9_0_7));

        DeploymentResult result = execute(preview);

        NodeOutcome first = result.nodes().get(0);
        assertThat(first.state()).isEqualTo(State.ROLLBACK_FAILED);
        assertThat(result.warnings()).contains("The rollback of int/node1 was not completed"
            + " because INUBIT changed from 8.1.17 to 9.0.7 during this call; it re-imported "
            + DeployHarness.RELEASE_XSL + " before; its state before the deployment is in the"
            + " backup " + first.backupRef().orElseThrow() + ", taken on INUBIT 8.1; restore"
            + " it with restore_backup only on INUBIT 8.1");
        assertThat(result.warnings()).noneMatch(w -> w.contains("was not sent"));
        assertThat(harness.servers.get(INT1).repositoryContent(DeployHarness.RELEASE_XSL))
            .hasValueSatisfying(content -> assertThat(content).contains("'v0'"));
        harness.verifyComplete();
    }
}
