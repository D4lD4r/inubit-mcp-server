package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.NodeOutcome;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.State;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Stage 3 review M1, M2, m1, m2 (feature 005, FR-015–FR-019): {@code deploy_release} always
 * answers with a result naming every node's state and always writes its final group record;
 * what fails after a verified import (backup manifest, ledger, tag, workspace commit) is a
 * warning and the deployment stays; what fails before a node is written leaves that node and the
 * following ones {@code NOT_STARTED}.
 */
class DeployRobustnessTest extends DeployExecution {

    /** The deploy service whose audit fails for the records {@code failing} matches. */
    private DeploymentResult execute(DeploymentPreview preview, Predicate<AuditRecord> failing) {
        AuditPort audit = record -> {
            if (failing.test(record)) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
            harness.audit.add(record);
        };
        return harness.deployService(List.of(), audit).deploy(request(
            preview.confirmationCode())).result().orElseThrow();
    }

    private void assertStates(DeploymentResult result, State... states) {
        assertThat(result.nodes()).extracting(NodeOutcome::node)
            .containsExactlyElementsOf(DeployHarness.TARGETS);
        assertThat(result.nodes()).extracting(NodeOutcome::state).containsExactly(states);
    }

    private AuditRecord groupRecord() {
        return audit().get(audit().size() - 1);
    }

    @Test
    void anUnwritableLedgerAfterAVerifiedImportIsAWarningAndTheNodeStaysDeployed()
        throws IOException {
        // M1: no rollback once the verification passed
        harness(false);
        DeploymentPreview preview = preview();
        Files.createDirectories(harness.deployments().resolve("int"
            + DeploymentLedger.SUFFIX).resolve("blocked"));
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertStates(result, State.DEPLOYED, State.DEPLOYED, State.DEPLOYED);
        assertThat(result.nodes()).allSatisfy(node -> assertThat(node.tag()).get()
            .satisfies(tag -> assertThat(tag.applied()).isTrue()));
        assertThat(result.warnings()).filteredOn(w -> w.contains("ledger")).hasSize(3);
        assertThat(result.commit()).isPresent();
        assertThat(audit()).filteredOn(r -> r.node().startsWith("int/"))
            .extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
                AuditOutcome.EXECUTED, AuditOutcome.PENDING, AuditOutcome.EXECUTED,
                AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(groupRecord().outcome()).isEqualTo(AuditOutcome.EXECUTED);
        harness.verifyComplete(); // no rollback import was sent
    }

    @Test
    void anUnwritableBackupLeavesTheFirstNodeAndTheOthersNotStarted() throws IOException {
        // M2: step backup
        harness(false);
        DeploymentPreview preview = preview();
        Path backups = harness.backups.root();
        assertThat(backups).doesNotExist();
        Files.writeString(backups, "not a directory");
        executeStart();
        nodeExports(DeployHarness.INT1); // re-check

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.FAILED);
        assertStates(result, State.NOT_STARTED, State.NOT_STARTED, State.NOT_STARTED);
        assertThat(result.nodes().get(0).failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failure.step()).isEqualTo("backup");
            assertThat(failure.message()).contains("nothing was sent");
        });
        assertThat(result.nodes().get(1).failure()).isEmpty();
        assertThat(harness.launches()).noneMatch(line -> line.contains(" import ")
            || line.contains(" tag "));
        assertThat(groupRecord().node()).isEqualTo("int");
        assertThat(groupRecord().outcome()).isEqualTo(AuditOutcome.FAILED);
        harness.verifyComplete();
    }

    @Test
    void aPendingRecordThatCannotBeWrittenFailsClosedOnThatNode() {
        // M2 step pending, m2: nothing is sent to node 2
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        deployed(DeployHarness.INT1);
        nodeExports(DeployHarness.INT2); // re-check

        DeploymentResult result = execute(preview, record -> record.node().equals("int/node2")
            && record.outcome() == AuditOutcome.PENDING);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.FAILED);
        assertStates(result, State.DEPLOYED, State.NOT_STARTED, State.NOT_STARTED);
        assertThat(result.nodes().get(1).failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failure.step()).isEqualTo("pending");
        });
        assertThat(harness.launches()).noneMatch(line -> line.startsWith("int/node2 import")
            || line.startsWith("int/node2 tag"));
        assertThat(harness.servers.get(DeployHarness.INT2).imported).isEmpty();
        assertThat(harness.servers.get(DeployHarness.INT2).repositoryImports).isEmpty();
        assertThat(groupRecord().outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(groupRecord().inputs()).containsEntry("int/node2", "NOT_STARTED");
        harness.verifyComplete();
    }

    @Test
    void aNodeThatCannotBeReadAtItsReCheckIsNotStarted() {
        // M2 step recheck
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        harness.exportFails(DeployHarness.INT1);

        DeploymentResult result = execute(preview);

        assertStates(result, State.NOT_STARTED, State.NOT_STARTED, State.NOT_STARTED);
        assertThat(result.nodes().get(0).failure()).get().satisfies(failure ->
            assertThat(failure.step()).isEqualTo("recheck"));
        assertThat(groupRecord().outcome()).isEqualTo(AuditOutcome.FAILED);
        harness.verifyComplete();
    }

    @Test
    void aFailingWorkspaceCommitIsAWarningAndTheDeploymentStaysExecuted() throws IOException {
        // M2: the commit write fails
        harness(false);
        DeploymentPreview preview = preview();
        Files.writeString(harness.root.resolve("int"), "a file where a directory belongs");
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertStates(result, State.DEPLOYED, State.DEPLOYED, State.DEPLOYED);
        assertThat(result.commit()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("could not be committed"));
        assertThat(groupRecord().outcome()).isEqualTo(AuditOutcome.EXECUTED);
        harness.verifyComplete();
    }

    @Test
    void aFinalGroupRecordThatCannotBeWrittenStillAnswersWithTheResult() {
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        DeploymentResult result = execute(preview, record -> record.node().equals("int")
            && record.outcome() == AuditOutcome.EXECUTED);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("audit record"));
        harness.verifyComplete();
    }

    @Test
    void aFailingTagKeepsTheDeploymentWithARetryHint() {
        // m2
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        nodeExports(DeployHarness.INT1);
        harness.importRepositoryApplied(DeployHarness.INT1).importApplied(DeployHarness.INT1,
            MODULE_IMPORT);
        nodeExports(DeployHarness.INT1);
        harness.cli.get(DeployHarness.INT1).expect("tag --tagMove '" + TAG + "'")
            .replying("unreachable");
        deployed(DeployHarness.INT2);
        deployed(DeployHarness.INT3);

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertStates(result, State.DEPLOYED, State.DEPLOYED, State.DEPLOYED);
        assertThat(result.nodes().get(0).tag()).get().satisfies(tag -> {
            assertThat(tag.applied()).isFalse();
            assertThat(tag.failure()).get().satisfies(failure ->
                assertThat(failure.step()).isEqualTo("tag"));
        });
        assertThat(result.warnings()).anyMatch(w -> w.contains("int/node1")
            && w.contains("tag_artifacts") && w.contains(TAG));
        assertThat(harness.backups.find(result.nodes().get(0).backupRef().orElseThrow()))
            .get().satisfies(backup -> assertThat(backup.outcome()).isEqualTo("EXECUTED"));
        assertThat(audit()).filteredOn(r -> r.node().equals("int/node1")).last()
            .satisfies(record -> {
                assertThat(record.outcome()).isEqualTo(AuditOutcome.EXECUTED);
                assertThat(record.inputs()).containsEntry("tagApplied", "false");
            });
        harness.verifyComplete();
    }

    @Test
    void everyUnchangedNodeHasItsOwnAuditRecord() {
        // m1
        harness(false);
        DeploymentPreview first = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);
        execute(first);
        DeploymentPreview second = preview();
        harness.audit.clear();
        executeStart();
        DeployHarness.TARGETS.forEach(node -> {
            nodeExports(node);
            harness.tagVerified(node, "GRP-01", TAG);
        });

        DeploymentResult result = execute(second);

        assertStates(result, State.UNCHANGED, State.UNCHANGED, State.UNCHANGED);
        assertThat(audit()).extracting(AuditRecord::node).containsExactly("int", "int/node1",
            "int/node2", "int/node3", "int");
        assertThat(audit()).filteredOn(r -> r.node().startsWith("int/")).allSatisfy(record -> {
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.EXECUTED);
            assertThat(record.inputs()).containsEntry("state", "UNCHANGED")
                .containsEntry("tagApplied", "true");
            assertThat(record.auditId()).isEqualTo(result.auditId());
        });
        harness.verifyComplete();
    }
}
