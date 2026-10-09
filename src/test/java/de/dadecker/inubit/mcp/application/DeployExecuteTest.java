package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.State;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T021, T023 (feature 005, US1, FR-014–FR-020, research D-7, D-9, D-12): {@code deploy_release}
 * with the code deploys node by node — re-check, backup, {@code PENDING} audit, imports in the
 * D-7 order with the node's own secrets, verification, group-scoped tag, ledger — and commits
 * the verified state once every node is deployed or unchanged.
 */
class DeployExecuteTest extends DeployExecution {

    @Test
    void everyNodeIsBackedUpImportedVerifiedTaggedAndTheStateIsCommitted() {
        harness(false);
        DeploymentPreview preview = preview();
        harness.audit.clear();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.nodes()).extracting(DeploymentResult.NodeOutcome::state)
            .containsExactly(State.DEPLOYED, State.DEPLOYED, State.DEPLOYED);
        assertThat(result.nodes()).allSatisfy(node -> {
            assertThat(node.imported()).containsExactlyInAnyOrder("Module-0005",
                DeployHarness.RELEASE_XSL);
            assertThat(node.created()).containsExactly(DeployHarness.RELEASE_XSL);
            assertThat(node.tag()).get().satisfies(tag -> assertThat(tag.applied()).isTrue());
            BackupStore.Manifest backup = harness.backups.find(node.backupRef().orElseThrow())
                .orElseThrow();
            assertThat(backup.kind()).isEqualTo(BackupStore.Manifest.Kind.DEPLOYMENT);
            assertThat(backup.groups()).containsExactly("GRP-01");
            assertThat(backup.tag()).contains(TAG);
            assertThat(backup.source()).contains("dev");
            assertThat(backup.repositoryPaths()).containsExactly(DeployHarness.RELEASE_XSL);
            assertThat(backup.outcome()).isEqualTo("EXECUTED");
        });
        DeployHarness.TARGETS.forEach(node -> {
            FakeServer target = harness.servers.get(node);
            assertThat(target.repositoryContent(DeployHarness.RELEASE_XSL))
                .contains(DeployHarness.RELEASE_XSL_V1);
            assertThat(target.importFlags).containsExactly((Boolean) null);
            assertThat(harness.ledger.entries(node)).containsKeys(
                "module:XSLT Converter/Module-0005", "repository:" + DeployHarness.RELEASE_XSL);
        });
        assertThat(result.commit()).isPresent();
        assertThat(harness.workspace.log().get(0)).isEqualTo("deploy int ← dev: " + TAG + " ["
            + result.auditId() + "]");
        assertThat(harness.workspace.git("log", "-1", "--format=%(trailers)"))
            .contains("Server-State: int");
        assertThat(harness.workspace.snapshot()).containsKey(
            "int/jdoe/workflows/GRP-01/Workflow-0001.xml");
        assertThat(audit()).extracting(AuditRecord::node, AuditRecord::outcome).containsExactly(
            org.assertj.core.groups.Tuple.tuple("int", AuditOutcome.PENDING),
            org.assertj.core.groups.Tuple.tuple("int/node1", AuditOutcome.PENDING),
            org.assertj.core.groups.Tuple.tuple("int/node1", AuditOutcome.EXECUTED),
            org.assertj.core.groups.Tuple.tuple("int/node2", AuditOutcome.PENDING),
            org.assertj.core.groups.Tuple.tuple("int/node2", AuditOutcome.EXECUTED),
            org.assertj.core.groups.Tuple.tuple("int/node3", AuditOutcome.PENDING),
            org.assertj.core.groups.Tuple.tuple("int/node3", AuditOutcome.EXECUTED),
            org.assertj.core.groups.Tuple.tuple("int", AuditOutcome.EXECUTED));
        assertThat(audit()).allMatch(r -> r.auditId().equals(result.auditId()));
        harness.verifyComplete();
    }

    @Test
    void theCheckinCommentNamesTheTagAndTheSource() {
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        execute(preview);

        String index = new String(de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures
            .entries(harness.servers.get(DeployHarness.INT1).exportModule("XSLT Converter",
                "Module-0005").orElseThrow()).get("module/module.xml"),
            java.nio.charset.StandardCharsets.UTF_8);
        assertThat(index).contains("DefaultCommitCommentImport###deploy " + TAG
            + " from dev###");
    }

    @Test
    void redeployingAnUnchangedReleaseImportsNothing() {
        // SC-003: every node UNCHANGED, only the tag
        harness(false);
        DeploymentPreview first = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);
        execute(first);
        List<Integer> imports = DeployHarness.TARGETS.stream()
            .map(node -> harness.servers.get(node).imported.size()).toList();

        DeploymentPreview second = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(node -> {
            nodeExports(node);
            harness.tagVerified(node, "GRP-01", TAG);
        });
        DeploymentResult result = execute(second);

        assertThat(second.plans()).allSatisfy(plan -> assertThat(plan.warnings()).isEmpty());
        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.nodes()).allSatisfy(node -> {
            assertThat(node.state()).isEqualTo(State.UNCHANGED);
            assertThat(node.imported()).isEmpty();
            assertThat(node.backupRef()).isEmpty();
        });
        assertThat(DeployHarness.TARGETS.stream().map(node -> harness.servers.get(node)
            .imported.size()).toList()).isEqualTo(imports);
        harness.verifyComplete();
    }

    @Test
    void aVerifiedRenderingWithSwappedConnectionsDoesNotRewriteTheWorkspaceFile()
        throws java.io.IOException {
        // feature 007 (US3, contract P-6): the commit keeps a workflow file that differs only in
        // the order of a module's connections
        harness(false);
        DeploymentPreview first = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);
        execute(first);
        String file = "int/jdoe/workflows/GRP-01/Workflow-0001.xml";
        byte[] before = java.nio.file.Files.readAllBytes(harness.root.resolve(file));
        String commits = harness.workspace.git("log", "--format=%H", "--", file);
        // the node whose verified state is committed presents the connections swapped
        harness.servers.get(DeployHarness.INT3).publishWorkflow("Workflow-0001",
            DeployHarness::swapModule0002);

        DeploymentPreview second = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(node -> {
            nodeExports(node);
            harness.tagVerified(node, "GRP-01", TAG);
        });
        DeploymentResult result = execute(second);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(java.nio.file.Files.readAllBytes(harness.root.resolve(file)))
            .isEqualTo(before);
        assertThat(harness.workspace.git("log", "--format=%H", "--", file)).isEqualTo(commits);
        harness.verifyComplete();
    }

    @Test
    void aVerifiedRenderingWithARealChangeIsWrittenAndCommitted() throws java.io.IOException {
        // every other case writes the verified rendering exactly (contract P-6)
        harness(false);
        DeploymentPreview first = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);
        execute(first);
        String file = "int/jdoe/workflows/GRP-01/Workflow-0001.xml";
        byte[] before = java.nio.file.Files.readAllBytes(harness.root.resolve(file));
        Consumer<FakeServer> change = server -> server.publishWorkflow("Workflow-0001", xml -> xml
            .replace("<ConnectionId>5</ConnectionId>", "<ConnectionId>7</ConnectionId>"));
        source(change, true);
        DeployHarness.TARGETS.forEach(this::nodeExports);
        DeploymentPreview second = harness.deployService(List.of()).deploy(request(
            Optional.empty())).preview().orElseThrow();
        executeStart();
        DeployHarness.TARGETS.forEach(node -> {
            nodeExports(node);
            harness.importApplied(node, "--importWorkflow --importWorkflowInactive --importUser"
                + " 'jdoe' --returnProtocol");
            nodeExports(node);
            harness.tagVerified(node, "GRP-01", TAG);
        });

        DeploymentResult result = execute(second);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        byte[] verified = new de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec().prepare(
            DeployHarness.INT3.group(), "jdoe", List.of(harness.servers.get(DeployHarness.INT3)
                .exportWorkflowGroup("GRP-01").orElseThrow())).files().get(file);
        byte[] now = java.nio.file.Files.readAllBytes(harness.root.resolve(file));
        assertThat(now).isNotEqualTo(before).isEqualTo(verified);
        assertThat(new String(now, java.nio.charset.StandardCharsets.UTF_8))
            .contains("<ConnectionId>7</ConnectionId>");
        assertThat(harness.workspace.git("show", "--name-only", "--format=", "HEAD").lines())
            .contains(file);
        harness.verifyComplete();
    }

    @Test
    void newWorkflowsTakeTheReleasesFlagInOneArchivePerFlag() {
        harness(true);
        source(server -> server.publishWorkflow("Workflow-0001", xml -> xml.replace(
            "<IsActive>false</IsActive>", "<IsActive>true</IsActive>")), true);
        List<String[]> modules = List.of(new String[] {"Assign", "Module-0003"},
            new String[] {"Assign", "Module-0004"}, new String[] {"Assign", "Module-0007"},
            new String[] {"Assign", "Module-0008"}, new String[] {"Assign", "Module-0009"},
            new String[] {"Demultiplexer", "Module-0002"},
            new String[] {"Demultiplexer", "Module-0006"},
            new String[] {"XSLT Converter", "Module-0001"},
            new String[] {"XSLT Converter", "Module-0005"});
        Consumer<NodeId> emptyExports = node -> {
            harness.exportGroup(node);
            modules.forEach(module -> harness.exportModule(node, module[0], module[1]));
            harness.exportRepository(node, DeployHarness.RELEASE_XSL);
        };
        DeployHarness.TARGETS.forEach(emptyExports);
        DeploymentPreview preview = harness.deployService(List.of())
            .deploy(request(Optional.empty())).preview().orElseThrow();
        source(server -> { }, false);
        DeployHarness.TARGETS.forEach(emptyExports);
        DeployHarness.TARGETS.forEach(node -> {
            emptyExports.accept(node); // re-check
            harness.importRepositoryApplied(node)
                .importApplied(node, "--importWorkflow --importWorkflowInactive --importUser"
                    + " 'jdoe' --returnProtocol")
                .importApplied(node, "--importWorkflow --importWorkflowActive --importUser"
                    + " 'jdoe' --returnProtocol");
            nodeExports(node); // verification: every module is in the group export now
            harness.tagVerified(node, "GRP-01", TAG);
        });

        DeploymentResult result = execute(preview);

        assertThat(result.nodes()).allSatisfy(node -> {
            assertThat(node.state()).isEqualTo(State.DEPLOYED);
            assertThat(node.created()).hasSize(12);
        });
        FakeServer target = harness.servers.get(DeployHarness.INT1);
        assertThat(target.active("Workflow-0001")).contains(true);
        assertThat(target.active("Workflow-0002")).contains(false);
        assertThat(target.importFlags).containsExactly(false, true);
        harness.verifyComplete();
    }

    @Test
    void aTargetWhoseConnectionsSwappedSinceThePreviewIsStillDeployed() {
        // feature 007 (US2 scenario 4): INUBIT writes a module's connections in any order
        harness(false);
        DeploymentPreview preview = preview();
        harness.servers.get(DeployHarness.INT2).publishWorkflow("Workflow-0001",
            DeployHarness::swapModule0002);
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);

        DeploymentResult result = execute(preview);

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        assertThat(result.nodes()).extracting(DeploymentResult.NodeOutcome::state)
            .containsExactly(State.DEPLOYED, State.DEPLOYED, State.DEPLOYED);
        harness.verifyComplete();
    }

    @Test
    void aTargetWithAnotherConnectionSinceThePreviewIsAConflict() {
        harness(false);
        DeploymentPreview preview = preview();
        harness.servers.get(DeployHarness.INT2).publishWorkflow("Workflow-0001", xml -> xml
            .replace(DeployHarness.connections("4/9", "3/6"), DeployHarness.connections("3/6",
                "4/10")));
        executeStart();

        ToolErrorException e = failure(preview);

        assertThat(e.error().code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(harness.launches()).noneMatch(line -> line.contains(" import ")
            || line.contains(" tag "));
        harness.verifyComplete();
    }

    @Test
    void aReleaseThatChangedSinceThePreviewIsAConflictBeforeTheFirstNode() {
        harness(false);
        DeploymentPreview preview = preview();
        // the tag moved on the source
        DeployHarness.SOURCES.forEach(node -> harness.servers.get(node).publishModule(
            "Module-0003", text -> text.replace("IsModuleTemplate", "IsModuleTemplateX")));
        harness.tagged("GRP-01", TAG);
        executeStart();

        ToolErrorException e = failure(preview);

        assertThat(e.error().code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(harness.launches()).noneMatch(line -> line.contains(" import ")
            || line.contains(" tag "));
        assertThat(audit().get(audit().size() - 1).outcome()).isEqualTo(AuditOutcome.REFUSED);
        harness.verifyComplete();
    }

    @Test
    void aWrongOrUsedCodeIsRefusedAndNothingIsSent() {
        harness(false);
        DeploymentPreview preview = preview();
        executeStart();
        DeployHarness.TARGETS.forEach(this::deployed);
        execute(preview);

        ToolErrorException e = failure(preview);

        assertThat(e.error().code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        harness.verifyComplete();
    }
}
