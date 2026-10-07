package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodePlan;
import de.dadecker.inubit.mcp.domain.model.NodePlan.PlannedArtifact;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T020 (feature 005, US1, US2, FR-007–FR-013, research D-6): {@code deploy_release} without a
 * code is a preview — the chain, the locks, the release, one plan per target node, the ledger
 * warnings, and a code only if every plan is executable — and nothing is ever written to
 * INUBIT.
 */
class DeployServiceTest {

    private static final String TAG = "TAG-01";

    @TempDir
    Path temp;

    private DeployHarness harness;

    private DeployHarness harness(boolean emptyTargets) {
        try {
            harness = new DeployHarness(temp, emptyTargets);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return harness;
    }

    private static DeployGuard.Request request(String target, String tag) {
        return new DeployGuard.Request(target, tag, Optional.empty(), Optional.empty(),
            Optional.of("client/1.0"));
    }

    /** The source nodes tag GRP-01 after {@code change} and answer the release exports. */
    private void source(Consumer<FakeServer> change) {
        DeployHarness.SOURCES.forEach(node -> change.accept(harness.servers.get(node)));
        harness.tagged("GRP-01", TAG);
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.SOURCE);
    }

    /** The target nodes answer the exports of a planner run. */
    private void targets() {
        DeployHarness.TARGETS.forEach(node -> harness.exportGroup(node)
            .exportRepository(node, DeployHarness.RELEASE_XSL));
    }

    private DeploymentPreview preview() {
        return harness.deployService(List.of()).preview(request("int", TAG));
    }

    private ToolErrorException failure(DeployGuard.Request request) {
        try {
            harness.deployService(List.of()).preview(request);
        } catch (ToolErrorException e) {
            return e;
        }
        throw new AssertionError("no failure");
    }

    private static PlannedArtifact artifact(NodePlan plan, String name) {
        return plan.artifacts().stream().filter(a -> a.name().equals(name)).findFirst()
            .orElseThrow();
    }

    private List<AuditRecord> audit() {
        return harness.audit.stream().filter(r -> r.capability().equals("deploy_release"))
            .toList();
    }

    /** Only exports ran: no import, no tag on any node (SC-001). */
    private void nothingWritten() {
        assertThat(harness.launches()).allMatch(line -> line.matches("^[a-z0-9/-]+ export .*"));
        harness.verifyComplete();
    }

    // --- US1 ------------------------------------------------------------------------------------

    @Test
    void aPreviewNamesTheGroupsPlansEveryNodeAndIssuesACode() throws IOException {
        harness(false);
        source(server -> { });
        targets();

        DeploymentPreview preview = preview();

        assertThat(preview.target()).isEqualTo(new GroupId("int"));
        assertThat(preview.source()).isEqualTo(new GroupId("dev"));
        assertThat(preview.diagramGroups()).containsExactly("GRP-01");
        assertThat(preview.plans()).extracting(NodePlan::node)
            .containsExactlyElementsOf(DeployHarness.TARGETS);
        assertThat(preview.plans()).allSatisfy(plan -> {
            assertThat(plan.counts()).containsEntry(ArtifactClass.CHANGED, 1)
                .containsEntry(ArtifactClass.NEW, 1).containsEntry(ArtifactClass.UNCHANGED, 10);
            assertThat(harness.root.resolve(plan.diffFile())).isRegularFile();
        });
        assertThat(preview.executable()).isTrue();
        assertThat(preview.confirmationCode()).get().asString().hasSize(22);
        assertThat(preview.expiresAt()).contains(harness.clock.instant()
            .plus(Duration.ofMinutes(30)));
        assertThat(preview.previewState()).startsWith("release=sha256:")
            .contains(";groups=GRP-01;", "int/node1=sha256:", "int/node3=sha256:");
        assertThat(preview.notes()).anyMatch(note -> note.contains("system diagrams"));
        assertThat(preview.instruction()).contains("deploy_release", "confirmationCode");
        assertThat(preview).asString().doesNotContain(preview.confirmationCode().get());
        assertThat(audit()).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED);
            assertThat(record.step()).isEqualTo(AuditRecord.Step.PREVIEW);
            assertThat(record.node()).isEqualTo("int");
            assertThat(record.auditId()).isEqualTo(preview.auditId());
            assertThat(record.inputs()).containsEntry("tag", TAG).containsEntry("source", "dev")
                .containsEntry("owner", "jdoe").containsEntry("diagramGroups", "GRP-01")
                .containsKey(AuditRecord.ISSUED_CONFIRMATION_CODE);
        });
        assertThat(harness.challenges.redeem(preview.confirmationCode().get(),
            WriteChallengeRegistry.DEPLOY_RELEASE, new GroupId("int"),
            DeployService.inputFingerprint(new GroupId("int"), TAG, "jdoe")))
            .isEqualTo(preview.previewState());
        nothingWritten();
    }

    @Test
    void layoutOnlyAndActiveFlagsAreShownPerNode() {
        harness(false);
        source(server -> server.publishWorkflow("Workflow-0002", xml -> xml.replace(
            "<IsActive>false</IsActive>", "<IsActive>true</IsActive>")));
        harness.servers.get(DeployHarness.INT2).publishWorkflow("Workflow-0001",
            xml -> xml.replace("xPos=\"120\"", "xPos=\"140\""));
        targets();

        DeploymentPreview preview = preview();

        NodePlan second = preview.plans().get(1);
        assertThat(artifact(second, "Workflow-0001").artifactClass())
            .isEqualTo(ArtifactClass.LAYOUT_ONLY);
        assertThat(artifact(preview.plans().get(0), "Workflow-0001").artifactClass())
            .isEqualTo(ArtifactClass.UNCHANGED);
        // existing workflows keep the node's flag (US1 AS4)
        assertThat(artifact(second, "Workflow-0002").active()).contains(false);
        assertThat(artifact(second, "Workflow-0002").kept()).isTrue();
        nothingWritten();
    }

    @Test
    void theFirstDeploymentWarnsThatNoEarlierDeploymentIsKnown() {
        harness(false);
        source(server -> { });
        targets();

        DeploymentPreview preview = preview();

        assertThat(preview.plans()).allSatisfy(plan -> assertThat(plan.warnings())
            .singleElement().satisfies(warning -> {
                assertThat(warning.kind()).isEqualTo(NodePlan.WarningKind.OUTSIDE_CHAIN);
                assertThat(warning.artifact()).isEqualTo("Module-0005");
                assertThat(warning.detail()).contains("changed on the target outside the"
                    + " chain", "no earlier deployment by this server");
            }));
        nothingWritten();
    }

    @Test
    void aTargetStateThisServerWroteIsNoOutsideChange() {
        harness(false);
        source(server -> { });
        targets();
        DeploymentPreview first = preview();
        harness.audit.clear();
        // record what each node holds now as written by an earlier deployment of this server
        first.plans().forEach(plan -> {
            Map<String, DeploymentLedger.Entry> entries = new java.util.TreeMap<>();
            plan.artifactStates().forEach((key, fingerprint) -> entries.put(key,
                new DeploymentLedger.Entry(fingerprint, first.auditId().toString(), TAG,
                    Instant.parse("2026-10-06T10:00:00Z"))));
            harness.ledger.record(plan.node(), entries);
        });
        // node 3 was changed in the Workbench since (a hotfix): US1 AS5
        harness.servers.get(DeployHarness.INT3).publishModule("Module-0005",
            text -> text.replace("IsModuleTemplate", "IsModuleTemplateX"));
        source(server -> { });
        targets();

        DeploymentPreview preview = preview();

        assertThat(preview.plans().get(0).warnings()).isEmpty();
        assertThat(preview.plans().get(2).warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.kind()).isEqualTo(NodePlan.WarningKind.OUTSIDE_CHAIN);
            assertThat(warning.detail()).doesNotContain("no earlier deployment");
        });
        assertThat(preview.executable()).isTrue();
        nothingWritten();
    }

    @Test
    void aReleaseEqualOnEveryNodeIsStillPreviewed() {
        // US1 AS6: nothing would be imported; the nodes would only get the tag
        harness(false);
        source(server -> { });
        DeployHarness.TARGETS.forEach(node -> {
            FakeServer target = harness.servers.get(node);
            target.publishModule("Module-0005", text -> new String(harness.servers.get(
                DeployHarness.SOURCE).exportModule("XSLT Converter", "Module-0005").map(zip ->
                    de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.entries(zip)
                        .get("module/module-0005.xml")).orElseThrow(),
                java.nio.charset.StandardCharsets.UTF_8));
            target.putRepositoryFile(DeployHarness.RELEASE_XSL, DeployHarness.RELEASE_XSL_V1);
        });
        targets();

        DeploymentPreview preview = preview();

        assertThat(preview.plans()).allSatisfy(plan -> assertThat(plan.counts())
            .containsEntry(ArtifactClass.UNCHANGED, 12));
        assertThat(preview.executable()).isTrue();
        nothingWritten();
    }

    @Test
    void anUnresolvedSecretMakesThePreviewNotExecutableWithoutACode() {
        // US1 AS7
        harness(false);
        source(server -> server.publishModule("Module-0001", text -> text.replace(
            "<Property name=\"IsModuleTemplate\"", "<Property name=\"Password\""
                + " type=\"Password\" encrypted=\"true\">AES-U1lOVEgtQUVTLTAwMDAwMDk5"
                + "</Property>\n\t<Property name=\"IsModuleTemplate\"")));
        targets();

        DeploymentPreview preview = preview();

        assertThat(preview.executable()).isFalse();
        assertThat(preview.confirmationCode()).isEmpty();
        assertThat(preview.expiresAt()).isEmpty();
        assertThat(preview.plans()).allSatisfy(plan -> assertThat(plan.errors())
            .anySatisfy(error -> assertThat(error.code())
                .isEqualTo(ErrorCode.SECRET_UNRESOLVED)));
        assertThat(preview.instruction()).contains("not executable");
        assertThat(audit()).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).get().asString().contains("not executable");
        });
        assertThat(harness.challenges.pending()).isZero();
        nothingWritten();
    }

    @Test
    void errorFindingsOfTheChecksOnTheReleaseAreErrorsOfEveryPlan() {
        harness(false);
        source(server -> server.publishWorkflow("Workflow-0001", xml -> xml.replace(
            "<Connection moduleOutId=\"4\">", "<Connection moduleOutId=\"99\">")));
        targets();

        DeploymentPreview preview = preview();

        assertThat(preview.executable()).isFalse();
        assertThat(preview.plans()).allSatisfy(plan -> assertThat(plan.errors())
            .anySatisfy(error -> assertThat(error.artifact()).contains("Workflow-0001")));
        nothingWritten();
    }

    // --- US2 and failures -----------------------------------------------------------------------

    @Test
    void theChainCannotBeBypassedAndNothingIsLaunched() {
        harness(false);

        assertThat(failure(request("dev", TAG)).error().code())
            .isEqualTo(ErrorCode.CHAIN_VIOLATION);
        assertThat(failure(request("int/node1", TAG)).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(failure(request("int", "REL*")).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(audit()).hasSize(3).allMatch(r -> r.outcome() == AuditOutcome.REFUSED);
        assertThat(harness.launches()).isEmpty();
        harness.verifyComplete();
    }

    @Test
    void aTagOnNoDiagramGroupFailsThePreview() {
        harness(false);
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, "TAG-99"));

        ToolErrorException e = failure(request("int", "TAG-99"));

        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(audit()).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).get().asString().startsWith("NOT_FOUND");
        });
        nothingWritten();
    }

    @Test
    void inconsistentSourceNodesFailThePreview() {
        harness(false);
        harness.servers.get(DeployHarness.SOURCE2).publishWorkflow("Workflow-0001",
            xml -> xml.replace("<ConnectionId>5</ConnectionId>", "<ConnectionId>7</ConnectionId>"));
        harness.tagged("GRP-01", TAG);
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));

        assertThat(failure(request("int", TAG)).error().code())
            .isEqualTo(ErrorCode.SOURCE_INCONSISTENT);
        nothingWritten();
    }

    @Test
    void aTargetNodeThatCannotBeReadFailsTheWholePreview() {
        harness(false);
        source(server -> { });
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);
        harness.cli.get(DeployHarness.INT2).expect("export ").replying("unreachable");

        ToolErrorException e = failure(request("int", TAG));

        assertThat(e.error().code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(e.error().node()).contains(DeployHarness.INT2);
        assertThat(harness.challenges.pending()).isZero();
        assertThat(audit()).singleElement().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        nothingWritten();
    }

    @Test
    void aRunningDeploymentIntoTheSameGroupIsLocked() {
        harness(false);
        try (DeployLock busy = DeployLock.acquire(harness.deployments(), new GroupId("int"),
            Files.createDirectories(temp.resolve("other-workspace")))) {

            assertThat(failure(request("int", TAG)).error().code())
                .isEqualTo(ErrorCode.DEPLOY_LOCKED);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertThat(audit()).singleElement().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        nothingWritten();
    }

    @Test
    void exclusionsOfTheChainApply() {
        harness(false);
        source(server -> { });
        DeployHarness.TARGETS.forEach(node -> harness.exportGroup(node));

        DeploymentPreview preview = harness.deployService(List.of(new StageChain.Exclusion(
            StageChain.Exclusion.Kind.REPOSITORY_PATH, "/Root/jdoe/**"))).preview(
                request("int", TAG));

        assertThat(preview.plans()).allSatisfy(plan -> assertThat(artifact(plan,
            DeployHarness.RELEASE_XSL).artifactClass()).isEqualTo(ArtifactClass.EXCLUDED));
        nothingWritten();
    }

    static NodeId node(String id) {
        return NodeId.parse(id);
    }

    @Test
    void aFailingAuditIsNotAuditedTwiceAndIssuesNoCode() {
        // stage 2 review n2
        harness(false);
        source(server -> { });
        targets();
        java.util.concurrent.atomic.AtomicInteger attempts =
            new java.util.concurrent.atomic.AtomicInteger();

        assertThatThrownBy(() -> harness.deployService(List.of(), record -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("disk full");
        }).preview(request("int", TAG))).isInstanceOfSatisfying(ToolErrorException.class,
            e -> assertThat(e.error().code()).isEqualTo(ErrorCode.INTERNAL));
        assertThat(attempts).hasValue(1);
        assertThat(harness.challenges.pending()).isZero();
        nothingWritten();
    }
}

