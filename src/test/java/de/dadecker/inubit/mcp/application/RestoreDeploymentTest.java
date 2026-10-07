package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePreview;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T026 (feature 005, US3, FR-019, research D-13): {@code restore_backup} of a deployment backup
 * on a node of a target group — admitted although the node is no development stage, always with
 * a preview and a server code; it re-imports the workflows, modules and repository files the
 * deployment changed on that node (from the backup, with the node's current secrets), verifies
 * them, and lists what the deployment created (nothing is deleted).
 */
class RestoreDeploymentTest extends DeployExecution {

    private static final String SOURCE_MODULE = "XSLT Converter";
    private static final String WORKFLOW_INACTIVE = "--importWorkflow --importWorkflowInactive"
        + " --importUser 'jdoe' --returnProtocol";
    private static final String WORKFLOW = "--importWorkflow --importUser 'jdoe'"
        + " --returnProtocol";
    private static final String OLD_XSL = DeployHarness.RELEASE_XSL_V1.replace("'v1'", "'v0'");

    /** The connection ids of the workflows of {@code GRP-01} on {@code node}, in order. */
    private List<String> connections(NodeId node) {
        String xml = harness.servers.get(node).exportWorkflowGroup("GRP-01").map(zip ->
            ArtifactFixtures.entries(zip).values().stream().map(bytes -> new String(bytes,
                StandardCharsets.UTF_8)).reduce("", String::concat)).orElseThrow();
        return Pattern.compile("<ConnectionId>\\d+<").matcher(xml).results()
            .map(MatchResult::group).toList();
    }

    private String module(NodeId node) {
        return new String(ArtifactFixtures.entries(harness.servers.get(node).exportModule(
            SOURCE_MODULE, "Module-0005").orElseThrow()).get("module/module-0005.xml"),
            StandardCharsets.UTF_8);
    }

    /**
     * Deploys a release that changes Workflow-0001 and Module-0005 and — with
     * {@code changedFile} — the repository file the targets hold in an older version (else it
     * is new there).
     */
    private DeploymentResult deploy(boolean changedFile) {
        if (changedFile) {
            DeployHarness.TARGETS.forEach(node -> harness.servers.get(node)
                .putRepositoryFile(DeployHarness.RELEASE_XSL, OLD_XSL));
        }
        Consumer<FakeServer> change = server -> server.publishWorkflow("Workflow-0001", xml ->
            xml.replace("<ConnectionId>5</ConnectionId>", "<ConnectionId>7</ConnectionId>"));
        source(change, true);
        DeployHarness.TARGETS.forEach(this::nodeExports);
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request(
            Optional.empty())).preview().orElseThrow();
        executeStart();
        DeployHarness.TARGETS.forEach(node -> {
            nodeExports(node);
            harness.importRepositoryApplied(node).importApplied(node, MODULE_IMPORT)
                .importApplied(node, WORKFLOW_INACTIVE);
            nodeExports(node);
            harness.tagVerified(node, "GRP-01", TAG);
        });
        DeploymentResult result = execute(preview);
        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        return result;
    }

    private static ImportService.RestoreRequest restore(String backupRef,
        Optional<String> code) {
        return new ImportService.RestoreRequest("int/node1", backupRef, "Undo " + TAG, code,
            Optional.of("client/1.0"));
    }

    private List<AuditRecord> restoreAudit() {
        return harness.audit.stream().filter(r -> r.capability().equals("restore_backup"))
            .toList();
    }

    @Test
    void aDeploymentIsRestoredOnItsNodeAfterAPreviewWithAServerCode() {
        harness(false);
        List<String> before = connections(DeployHarness.INT1);
        String moduleBefore = module(DeployHarness.INT1);
        DeploymentResult deployed = deploy(true);
        String backupRef = deployed.nodes().get(0).backupRef().orElseThrow();
        assertThat(connections(DeployHarness.INT1)).contains("<ConnectionId>7<");
        long imports = harness.launches().stream().filter(line -> line.startsWith(
            "int/node1 import")).count();
        nodeExports(DeployHarness.INT1); // the restore plan of the preview

        ImportService.Response first = harness.importService().restore(restore(backupRef,
            Optional.empty()));

        assertThat(first).isInstanceOf(ImportService.Response.WriteChallenge.class);
        WritePreview preview = ((ImportService.Response.WriteChallenge) first).preview();
        assertThat(preview.node()).isEqualTo(DeployHarness.INT1);
        assertThat(preview.modify()).containsExactlyInAnyOrder("Workflow-0001", "Module-0005",
            DeployHarness.RELEASE_XSL);
        assertThat(harness.launches()).filteredOn(line -> line.startsWith("int/node1 import"))
            .hasSize((int) imports); // the preview sent nothing
        nodeExports(DeployHarness.INT1); // the plan again
        harness.importRepositoryApplied(DeployHarness.INT1)
            .importApplied(DeployHarness.INT1, MODULE_IMPORT)
            .importApplied(DeployHarness.INT1, WORKFLOW);
        nodeExports(DeployHarness.INT1); // verification

        ImportService.Response second = harness.importService().restore(restore(backupRef,
            Optional.of(preview.confirmationCode())));

        WriteOutcome outcome = ((ImportService.Response.Completed) second).outcome();
        assertThat(outcome.outcome()).as(outcome.toString())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactlyInAnyOrder("Workflow-0001",
            "Module-0005", DeployHarness.RELEASE_XSL);
        assertThat(outcome.backupRef()).isPresent().get().isNotEqualTo(backupRef);
        assertThat(connections(DeployHarness.INT1)).isEqualTo(before);
        assertThat(harness.servers.get(DeployHarness.INT1).active("Workflow-0001"))
            .contains(false);
        assertThat(harness.servers.get(DeployHarness.INT1).repositoryContent(
            DeployHarness.RELEASE_XSL)).contains(OLD_XSL);
        assertThat(module(DeployHarness.INT1)).doesNotContain("inubitrepository:");
        assertThat(moduleBefore).doesNotContain("inubitrepository:");
        assertThat(connections(DeployHarness.INT2)).contains("<ConnectionId>7<");
        assertThat(restoreAudit()).extracting(AuditRecord::step, AuditRecord::outcome)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(AuditRecord.Step.PREVIEW,
                    AuditOutcome.CHALLENGE_ISSUED),
                org.assertj.core.groups.Tuple.tuple(AuditRecord.Step.EXECUTE,
                    AuditOutcome.PENDING),
                org.assertj.core.groups.Tuple.tuple(AuditRecord.Step.EXECUTE,
                    AuditOutcome.EXECUTED));
        assertThat(restoreAudit()).allMatch(r -> r.node().equals("int/node1"));
        assertThat(harness.backups.find(outcome.backupRef().get())).get().satisfies(own ->
            assertThat(own.outcome()).isEqualTo("EXECUTED"));
        harness.verifyComplete();
    }

    @Test
    void whatTheDeploymentCreatedStaysAndIsListed() {
        harness(false);
        DeploymentResult deployed = deploy(false);
        String backupRef = deployed.nodes().get(0).backupRef().orElseThrow();
        // the backup has no repository file: the new one is not read
        harness.exportGroup(DeployHarness.INT1);
        WritePreview preview = ((ImportService.Response.WriteChallenge) harness.importService()
            .restore(restore(backupRef, Optional.empty()))).preview();
        assertThat(preview.notes()).anyMatch(note -> note.contains(DeployHarness.RELEASE_XSL));
        harness.exportGroup(DeployHarness.INT1);
        harness.importApplied(DeployHarness.INT1, MODULE_IMPORT)
            .importApplied(DeployHarness.INT1, WORKFLOW);
        harness.exportGroup(DeployHarness.INT1);

        WriteOutcome outcome = ((ImportService.Response.Completed) harness.importService()
            .restore(restore(backupRef, Optional.of(preview.confirmationCode())))).outcome();

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactlyInAnyOrder("Workflow-0001",
            "Module-0005");
        assertThat(outcome.createdNotRemoved()).containsExactly(DeployHarness.RELEASE_XSL);
        assertThat(harness.servers.get(DeployHarness.INT1).repositoryContent(
            DeployHarness.RELEASE_XSL)).contains(DeployHarness.RELEASE_XSL_V1);
        harness.verifyComplete();
    }

    @Test
    void aNodeChangedSinceTheDeploymentIsAConflictAndNothingIsSent() {
        harness(false);
        DeploymentResult deployed = deploy(false);
        harness.servers.get(DeployHarness.INT1).publishModule("Module-0005", text ->
            text.replace("xslt_mode", "xslt_mode_colleague"));
        harness.exportGroup(DeployHarness.INT1);

        ToolErrorException e = org.assertj.core.api.Assertions.catchThrowableOfType(
            ToolErrorException.class, () -> harness.importService().restore(restore(
                deployed.nodes().get(0).backupRef().orElseThrow(), Optional.empty())));

        assertThat(e).isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(e.error().message()).contains("Module-0005");
        assertThat(restoreAudit()).singleElement().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        assertThat(harness.launches()).filteredOn(line -> line.startsWith("int/node1 import"))
            .hasSize(3); // the deployment's only
        harness.verifyComplete();
    }

    @Test
    void aTargetNodeIsNotAdmittedForAnythingButADeploymentBackupOfItsOwn() {
        harness(false);
        DeploymentResult deployed = deploy(false);

        ToolErrorException unknown = org.assertj.core.api.Assertions.catchThrowableOfType(
            ToolErrorException.class, () -> harness.importService().restore(restore(
                UUID.randomUUID().toString(), Optional.empty())));
        ToolErrorException foreign = org.assertj.core.api.Assertions.catchThrowableOfType(
            ToolErrorException.class, () -> harness.importService().restore(restore(
                deployed.nodes().get(1).backupRef().orElseThrow(), Optional.empty())));

        assertThat(unknown.error().code()).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(foreign.error().code()).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        harness.verifyComplete();
    }

    @Test
    void aDeploymentBackupOnADevelopmentNodeIsStillPreviewedWithTheDeployTtl() {
        // final review n1: CLIENT confirmation and a 5-minute TTL do not apply
        harness(false);
        DeploymentResult deployed = deploy(false);
        harness.exportGroup(DeployHarness.INT1);

        ImportService.Response first = harness.importService(true).restore(restore(
            deployed.nodes().get(0).backupRef().orElseThrow(), Optional.empty()));

        assertThat(first).isInstanceOf(ImportService.Response.WriteChallenge.class);
        assertThat(((ImportService.Response.WriteChallenge) first).preview().expiresAt())
            .isEqualTo(harness.clock.instant().plus(java.time.Duration.ofMinutes(30)));
        harness.verifyComplete();
    }

    @Test
    void aRestoreAdmittedAsDeploymentRestoreHasTheDeployTtl() {
        harness(false);
        DeploymentResult deployed = deploy(false);
        harness.exportGroup(DeployHarness.INT1);

        ImportService.Response first = harness.importService().restore(restore(
            deployed.nodes().get(0).backupRef().orElseThrow(), Optional.empty()));

        assertThat(((ImportService.Response.WriteChallenge) first).preview().expiresAt())
            .isEqualTo(harness.clock.instant().plus(java.time.Duration.ofMinutes(30)));
        harness.verifyComplete();
    }

    @Test
    void theNextPreviewDoesNotReportThisServersOwnRestoreAsOutsideTheChain() {
        // final review m2: the ledger records the restored state
        harness(false);
        DeploymentResult deployed = deploy(false);
        String backupRef = deployed.nodes().get(0).backupRef().orElseThrow();
        harness.exportGroup(DeployHarness.INT1);
        WritePreview preview = ((ImportService.Response.WriteChallenge) harness.importService()
            .restore(restore(backupRef, Optional.empty()))).preview();
        harness.exportGroup(DeployHarness.INT1);
        harness.importApplied(DeployHarness.INT1, MODULE_IMPORT)
            .importApplied(DeployHarness.INT1, WORKFLOW);
        harness.exportGroup(DeployHarness.INT1);
        WriteOutcome restored = ((ImportService.Response.Completed) harness.importService()
            .restore(restore(backupRef, Optional.of(preview.confirmationCode())))).outcome();
        assertThat(restored.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        source(server -> { }, false);
        DeployHarness.TARGETS.forEach(this::nodeExports);

        DeploymentPreview again = harness.deployService(List.of()).deploy(request(
            Optional.empty())).preview().orElseThrow();

        assertThat(again.plans().get(0).node()).isEqualTo(DeployHarness.INT1);
        assertThat(again.plans().get(0).counts())
            .containsEntry(de.dadecker.inubit.mcp.domain.model.ArtifactClass.CHANGED, 2);
        assertThat(again.plans().get(0).warnings()).noneMatch(warning -> warning.kind()
            == de.dadecker.inubit.mcp.domain.model.NodePlan.WarningKind.OUTSIDE_CHAIN);
        assertThat(harness.ledger.entries(DeployHarness.INT1).get(
            "module:XSLT Converter/Module-0005").auditId())
            .isEqualTo(restored.auditId().toString());
        harness.verifyComplete();
    }
}
