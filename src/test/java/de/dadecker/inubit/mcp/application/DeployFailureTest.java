package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.NodeOutcome;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.State;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T022 (feature 005, US3, FR-015, FR-017, SC-002): a failure on node 2 of 3 rolls node 2 back
 * from its backup and verifies the rollback, keeps node 1 deployed and leaves node 3 untouched;
 * the result names every node's state and backup; nothing is committed.
 */
class DeployFailureTest extends DeployExecution {

    private static final String SOURCE_MODULE = "XSLT Converter";

    private NodeOutcome node(DeploymentResult result, int index) {
        return result.nodes().get(index);
    }

    private void assertNode1DeployedNode3Untouched(DeploymentResult result) {
        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.FAILED);
        assertThat(node(result, 0).state()).isEqualTo(State.DEPLOYED);
        assertThat(node(result, 0).backupRef()).isPresent();
        assertThat(node(result, 2).state()).isEqualTo(State.NOT_STARTED);
        assertThat(harness.launches()).noneMatch(line -> line.startsWith("int/node3 import")
            || line.startsWith("int/node3 tag"));
        assertThat(harness.servers.get(DeployHarness.INT3).imported).isEmpty();
        assertThat(harness.servers.get(DeployHarness.INT3).repositoryImports).isEmpty();
        assertThat(result.commit()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("nothing was committed"));
        assertThat(harness.workspace.log()).noneMatch(subject -> subject.startsWith("deploy "));
    }

    private String module(int node) {
        return new String(de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.entries(
            harness.servers.get(DeployHarness.TARGETS.get(node)).exportModule(SOURCE_MODULE,
                "Module-0005").orElseThrow()).get("module/module-0005.xml"),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void aRefusedImportOnNode2RollsNode2BackAndStops() {
        harness(false);
        DeploymentPreview preview = preview();
        String before = module(1);
        executeStart();
        deployed(DeployHarness.INT1);
        nodeExports(DeployHarness.INT2); // re-check
        harness.importRepositoryApplied(DeployHarness.INT2).importRefused(DeployHarness.INT2);
        nodeExports(DeployHarness.INT2); // the state after the failure; nothing to re-import

        DeploymentResult result = execute(preview);

        assertNode1DeployedNode3Untouched(result);
        NodeOutcome second = node(result, 1);
        assertThat(second.state()).isEqualTo(State.ROLLED_BACK);
        assertThat(second.failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("import");
        });
        assertThat(second.backupRef()).isPresent();
        // the new repository file stays (nothing is deleted) and is listed as created
        assertThat(second.created()).containsExactly(DeployHarness.RELEASE_XSL);
        assertThat(module(1)).isEqualTo(before);
        assertThat(harness.audit.stream().filter(r -> r.node().equals("int/node2"))
            .map(AuditRecord::outcome)).containsExactly(AuditOutcome.PENDING,
                AuditOutcome.FAILED);
        assertThat(harness.audit.get(harness.audit.size() - 1).outcome())
            .isEqualTo(AuditOutcome.FAILED);
        harness.verifyComplete();
    }

    @Test
    void aVerificationMismatchOnNode2IsRolledBackAndVerified() {
        harness(false);
        DeploymentPreview preview = preview();
        String before = module(1);
        executeStart();
        deployed(DeployHarness.INT1);
        nodeExports(DeployHarness.INT2);
        harness.importRepositoryApplied(DeployHarness.INT2).importApplied(DeployHarness.INT2,
            MODULE_IMPORT);
        harness.cli.get(DeployHarness.INT2).then(spec -> harness.servers.get(
            DeployHarness.INT2).tamperNextImport = new String[] {"xslt_mode", "xslt_mode_X"});
        nodeExports(DeployHarness.INT2); // verification sees the tampered module
        harness.importApplied(DeployHarness.INT2, MODULE_IMPORT); // rollback from the backup
        nodeExports(DeployHarness.INT2); // verification of the rollback

        DeploymentResult result = execute(preview);

        assertNode1DeployedNode3Untouched(result);
        assertThat(node(result, 1).state()).isEqualTo(State.ROLLED_BACK);
        assertThat(node(result, 1).failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
        });
        // the backup's content is back (re-imported, so normalized like any import)
        assertThat(module(1)).doesNotContain("xslt_mode_X", "inubitrepository:")
            .contains("xslt_mode");
        assertThat(before).doesNotContain("inubitrepository:");
        harness.verifyComplete();
    }

    @Test
    void aReExportWithSwappedConnectionsIsVerifiedWithoutRollback() {
        // feature 007 (US2 scenario 1): INUBIT writes a module's connections in any order
        harness(false);
        source(server -> server.publishWorkflow("Workflow-0001", xml -> xml.replace(
            "<ConnectionId>5</ConnectionId>", "<ConnectionId>7</ConnectionId>")), true);
        DeployHarness.TARGETS.forEach(this::nodeExports);
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request(
            java.util.Optional.empty())).preview().orElseThrow();
        String workflow = "--importWorkflow --importWorkflowInactive --importUser 'jdoe'"
            + " --returnProtocol";
        executeStart();
        for (var node : DeployHarness.TARGETS) {
            nodeExports(node); // re-check
            harness.importRepositoryApplied(node).importApplied(node, MODULE_IMPORT)
                .importApplied(node, workflow);
            if (node.equals(DeployHarness.INT1)) {
                harness.cli.get(node).then(spec -> harness.servers.get(DeployHarness.INT1)
                    .tamperNextImport = new String[] {DeployHarness.connections("4/9", "3/6"),
                        DeployHarness.connections("3/6", "4/9")});
            }
            nodeExports(node); // verification
            harness.tagVerified(node, "GRP-01", TAG);
        }

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.nodes()).extracting(NodeOutcome::state).containsExactly(
            State.DEPLOYED, State.DEPLOYED, State.DEPLOYED);
        assertThat(node(result, 0).imported()).contains("Workflow-0001");
        assertThat(harness.servers.get(DeployHarness.INT1).imported).hasSize(2);
        assertThat(String.join("", connections(DeployHarness.INT1)))
            .isNotEqualTo(String.join("", connections(DeployHarness.INT2)));
        harness.verifyComplete();
    }

    @Test
    void aFailingRollbackIsReportedAndTheBackupKept() {
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        deployed(DeployHarness.INT1);
        nodeExports(DeployHarness.INT2);
        harness.importRepositoryApplied(DeployHarness.INT2).importApplied(DeployHarness.INT2,
            MODULE_IMPORT);
        harness.cli.get(DeployHarness.INT2).then(spec -> harness.servers.get(
            DeployHarness.INT2).tamperNextImport = new String[] {"xslt_mode", "xslt_mode_X"});
        nodeExports(DeployHarness.INT2);
        harness.importRefused(DeployHarness.INT2); // the rollback import fails

        DeploymentResult result = execute(preview);

        assertNode1DeployedNode3Untouched(result);
        NodeOutcome second = node(result, 1);
        assertThat(second.state()).isEqualTo(State.ROLLBACK_FAILED);
        assertThat(harness.backups.find(second.backupRef().orElseThrow())).get()
            .satisfies(backup -> assertThat(backup.outcome()).isEqualTo("ROLLBACK_FAILED"));
        assertThat(result.warnings()).anyMatch(w -> w.contains(second.backupRef().get()));
        harness.verifyComplete();
    }

    @Test
    void aNodeThatChangedSinceThePreviewIsAConflictAtItsReCheck() {
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        deployed(DeployHarness.INT1);
        // a colleague publishes on node 2 while node 1 is deployed
        harness.cli.get(DeployHarness.INT1).then(spec -> harness.servers.get(DeployHarness.INT2)
            .publishModule("Module-0003", text -> text.replace("IsModuleTemplate",
                "IsModuleTemplateX")));
        nodeExports(DeployHarness.INT2); // the re-check sees the change

        DeploymentResult result = execute(preview);

        assertNode1DeployedNode3Untouched(result);
        NodeOutcome second = node(result, 1);
        assertThat(second.state()).isEqualTo(State.NOT_STARTED);
        assertThat(second.backupRef()).isEmpty();
        assertThat(second.failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(failure.step()).isEqualTo("recheck");
        });
        assertThat(harness.servers.get(DeployHarness.INT2).imported).isEmpty();
        harness.verifyComplete();
    }

    @Test
    void aFailingReExportIsAVerificationFailureFollowedByARollback() {
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        deployed(DeployHarness.INT1);
        nodeExports(DeployHarness.INT2);
        harness.importRepositoryApplied(DeployHarness.INT2).importApplied(DeployHarness.INT2,
            MODULE_IMPORT);
        harness.exportFails(DeployHarness.INT2); // the verification cannot read the node
        nodeExports(DeployHarness.INT2); // the rollback reads it again
        harness.importApplied(DeployHarness.INT2, MODULE_IMPORT); // Module-0005 back
        nodeExports(DeployHarness.INT2);

        DeploymentResult result = execute(preview);

        assertThat(node(result, 1).state()).isEqualTo(State.ROLLED_BACK);
        assertThat(node(result, 1).failure()).get().satisfies(failure ->
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH));
        assertNode1DeployedNode3Untouched(result);
        harness.verifyComplete();
    }

    /** The connection ids of the workflows of {@code GRP-01} on {@code node}, in order. */
    private List<String> connections(de.dadecker.inubit.mcp.domain.model.NodeId node) {
        String xml = harness.servers.get(node).exportWorkflowGroup("GRP-01").map(zip ->
            de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.entries(zip).values()
                .stream().map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .reduce("", String::concat)).orElseThrow();
        return java.util.regex.Pattern.compile("<ConnectionId>\\d+<").matcher(xml).results()
            .map(java.util.regex.MatchResult::group).toList();
    }

    @Test
    void aChangedWorkflowAndAChangedRepositoryFileAreRolledBackAndVerified()
        throws java.io.IOException {
        // stage 3 review m2, m3: the rollback path of workflows and repository files
        harness(false);
        String old = DeployHarness.RELEASE_XSL_V1.replace("'v1'", "'v0'");
        DeployHarness.TARGETS.forEach(node -> harness.servers.get(node)
            .putRepositoryFile(DeployHarness.RELEASE_XSL, old));
        java.util.function.Consumer<FakeServer> change = server -> server.publishWorkflow(
            "Workflow-0001", xml -> xml.replace("<ConnectionId>5</ConnectionId>",
                "<ConnectionId>7</ConnectionId>"));
        source(change, true);
        DeployHarness.TARGETS.forEach(this::nodeExports);
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request(
            java.util.Optional.empty())).preview().orElseThrow();
        List<String> before = connections(DeployHarness.INT1);
        String workflow = "--importWorkflow --importWorkflowInactive --importUser 'jdoe'"
            + " --returnProtocol";
        executeStart();
        nodeExports(DeployHarness.INT1); // re-check
        harness.importRepositoryApplied(DeployHarness.INT1)
            .importApplied(DeployHarness.INT1, MODULE_IMPORT)
            .importApplied(DeployHarness.INT1, workflow);
        harness.cli.get(DeployHarness.INT1).then(spec -> harness.servers.get(
            DeployHarness.INT1).tamperNextImport = new String[] {"<ConnectionId>7<",
                "<ConnectionId>9<"});
        nodeExports(DeployHarness.INT1); // verification sees the tampered workflow
        harness.importRepositoryApplied(DeployHarness.INT1) // rollback: the old file
            .importApplied(DeployHarness.INT1, MODULE_IMPORT)
            .importApplied(DeployHarness.INT1, "--importWorkflow --importUser 'jdoe'"
                + " --returnProtocol");
        nodeExports(DeployHarness.INT1); // verification of the rollback

        DeploymentResult result = execute(preview);

        NodeOutcome first = node(result, 0);
        assertThat(first.state()).isEqualTo(State.ROLLED_BACK);
        assertThat(first.imported()).contains("Workflow-0001", DeployHarness.RELEASE_XSL);
        FakeServer target = harness.servers.get(DeployHarness.INT1);
        assertThat(target.repositoryContent(DeployHarness.RELEASE_XSL)).contains(old);
        assertThat(target.repositoryImports).hasSize(2);
        assertThat(connections(DeployHarness.INT1)).isEqualTo(before)
            .doesNotContain("<ConnectionId>7<");
        assertThat(target.active("Workflow-0001")).contains(false);
        assertThat(result.reports()).anySatisfy(report -> assertThat(java.nio.file.Files
            .readString(harness.root.resolve(report))).contains("Workflow-0001",
                DeployHarness.RELEASE_XSL, "IsActive", "protocol"));
        assertThat(node(result, 1).state()).isEqualTo(State.NOT_STARTED);
        harness.verifyComplete();
    }

    @Test
    void aRollbackWhoseReExportHasSwappedConnectionsIsRolledBack() throws java.io.IOException {
        // feature 007: the rollback's check compares the backup and the node without the order
        // of a module's connections
        harness(false);
        String old = DeployHarness.RELEASE_XSL_V1.replace("'v1'", "'v0'");
        DeployHarness.TARGETS.forEach(node -> harness.servers.get(node)
            .putRepositoryFile(DeployHarness.RELEASE_XSL, old));
        source(server -> server.publishWorkflow("Workflow-0001", xml -> xml.replace(
            "<ConnectionId>5</ConnectionId>", "<ConnectionId>7</ConnectionId>")), true);
        DeployHarness.TARGETS.forEach(this::nodeExports);
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request(
            java.util.Optional.empty())).preview().orElseThrow();
        executeStart();
        nodeExports(DeployHarness.INT1); // re-check
        harness.importRepositoryApplied(DeployHarness.INT1)
            .importApplied(DeployHarness.INT1, MODULE_IMPORT)
            .importApplied(DeployHarness.INT1, "--importWorkflow --importWorkflowInactive"
                + " --importUser 'jdoe' --returnProtocol");
        harness.cli.get(DeployHarness.INT1).then(spec -> harness.servers.get(
            DeployHarness.INT1).tamperNextImport = new String[] {"<ConnectionId>7<",
                "<ConnectionId>9<"}); // a real difference: verification fails
        nodeExports(DeployHarness.INT1);
        harness.importRepositoryApplied(DeployHarness.INT1)
            .importApplied(DeployHarness.INT1, MODULE_IMPORT)
            .importApplied(DeployHarness.INT1, "--importWorkflow --importUser 'jdoe'"
                + " --returnProtocol");
        harness.cli.get(DeployHarness.INT1).then(spec -> harness.servers.get(
            DeployHarness.INT1).tamperNextImport = new String[] {DeployHarness.connections(
                "4/9", "3/6"), DeployHarness.connections("3/6", "4/9")}); // the rollback's order
        nodeExports(DeployHarness.INT1); // verification of the rollback

        DeploymentResult result = execute(preview);

        NodeOutcome first = node(result, 0);
        assertThat(first.failure()).get().satisfies(failure -> assertThat(failure.code())
            .isEqualTo(ErrorCode.VERIFY_MISMATCH));
        assertThat(first.state()).isEqualTo(State.ROLLED_BACK);
        // Workflow-0001 is back (5), with Module-0002's connections 3/6, 4/9 swapped
        assertThat(connections(DeployHarness.INT1)).startsWith("<ConnectionId>5<",
            "<ConnectionId>6<", "<ConnectionId>9<").doesNotContain("<ConnectionId>7<");
        for (String report : result.reports()) {
            assertThat(java.nio.file.Files.readString(harness.root.resolve(report)))
                .doesNotContain("not back to its state before");
        }
        harness.verifyComplete();
    }

    @Test
    void theFailureResultNamesEveryNodesState() {
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        deployed(DeployHarness.INT1);
        nodeExports(DeployHarness.INT2);
        harness.importRepositoryApplied(DeployHarness.INT2).importRefused(DeployHarness.INT2);
        nodeExports(DeployHarness.INT2);

        DeploymentResult result = execute(preview);

        assertThat(result.nodes()).extracting(NodeOutcome::node)
            .containsExactlyElementsOf(DeployHarness.TARGETS);
        assertThat(result.nodes()).extracting(NodeOutcome::state).containsExactly(
            State.DEPLOYED, State.ROLLED_BACK, State.NOT_STARTED);
        assertThat(List.of(result.nodes().get(0).backupRef(), result.nodes().get(1)
            .backupRef())).allMatch(java.util.Optional::isPresent);
    }
}
