package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81ImportArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.adapter.xslt.SaxonXsltRunner;
import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodePlan;
import de.dadecker.inubit.mcp.domain.model.NodePlan.PlannedArtifact;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T017 (feature 005, FR-010, FR-012, FR-012a, FR-015a, FR-016, research D-5): per target node,
 * the release artifacts are classified against the node's exports, the resulting active flags
 * are shown, and the errors that make a plan not executable are found; difference and summary
 * files hold placeholders only.
 */
class ReleasePlannerTest {

    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-00000000d017");
    private static final String TAG = "TAG-01";
    private static final String PASSWORD_VALUE = "AES-U1lOVEgtQUVTLTAwMDAwMDk5";

    @TempDir
    Path temp;

    private DeployHarness harness;

    private DeployHarness harness(boolean emptyTargets) throws IOException {
        harness = new DeployHarness(temp, emptyTargets);
        return harness;
    }

    private ReleasePlanner planner() {
        WorkspaceInspector inspector = new WorkspaceInspector();
        return new ReleasePlanner(new ReleasePlanner.Dependencies(harness::artifacts,
            harness::inventory, new ArchiveCodec(), new V81ReleaseArchives(),
            new V81ImportArchives(), (root, paths) -> new ArtifactCheckService(root, inspector,
                new SaxonXsltRunner(root), group -> Optional.empty(), node -> {
                    throw new AssertionError("no server lookups");
                }, node -> Optional.empty(), ResultLimiter.withDefaults(), harness.clock)
                .checkPaths(paths, false),
            node -> new ImportService.Account("jdoe", "inubit-int.example.test"), harness.root,
            harness.clock));
    }

    private static DeployGuard.Admitted admitted(List<StageChain.Exclusion> exclude) {
        return new DeployGuard.Admitted(new GroupId("int"), new GroupId("dev"),
            DeployMode.EXECUTE, exclude, DeployHarness.TARGETS, DeployHarness.SOURCES, "jdoe",
            TAG);
    }

    /** Changes the source nodes, tags GRP-01 there and discovers the release. */
    private ReleaseDiscovery.Release release(Consumer<FakeServer> sourceChange) {
        DeployHarness.SOURCES.forEach(node -> sourceChange.accept(harness.servers.get(node)));
        harness.tagged("GRP-01", TAG);
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.SOURCE);
        return new ReleaseDiscovery(harness::artifacts, new ArchiveCodec(),
            new V81ReleaseArchives(), harness.root).discover(admitted(List.of()), AUDIT_ID);
    }

    private ReleaseDiscovery.Release release() {
        return release(server -> { });
    }

    private NodePlan plan(ReleaseDiscovery.Release release, List<StageChain.Exclusion> exclude) {
        return planner().plan(admitted(exclude), release, DeployHarness.INT1, AUDIT_ID);
    }

    private static Map<String, ArtifactClass> classes(NodePlan plan) {
        Map<String, ArtifactClass> classes = new LinkedHashMap<>();
        plan.artifacts().forEach(a -> classes.put(a.name(), a.artifactClass()));
        return classes;
    }

    private static PlannedArtifact artifact(NodePlan plan, String name) {
        return plan.artifacts().stream().filter(a -> a.name().equals(name)).findFirst()
            .orElseThrow();
    }

    private String read(String relative) throws IOException {
        return Files.readString(harness.root.resolve(relative));
    }

    private static UnaryOperator<String> module(String from, String to) {
        return text -> text.replace(from, to);
    }

    @Test
    void aHeadTargetGetsTheChangedModuleAndTheNewRepositoryFile() throws IOException {
        harness(false);
        ReleaseDiscovery.Release release = release();
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(classes(plan)).containsEntry("Workflow-0001", ArtifactClass.UNCHANGED)
            .containsEntry("Workflow-0002", ArtifactClass.UNCHANGED)
            .containsEntry("Module-0005", ArtifactClass.CHANGED)
            .containsEntry("Module-0001", ArtifactClass.UNCHANGED)
            .containsEntry(DeployHarness.RELEASE_XSL, ArtifactClass.NEW).hasSize(12);
        assertThat(artifact(plan, "Workflow-0001").active()).contains(false);
        assertThat(artifact(plan, "Workflow-0001").kept()).isTrue();
        assertThat(artifact(plan, "Module-0005").group()).contains("XSLT Converter");
        assertThat(plan.executable()).isTrue();
        assertThat(plan.node()).isEqualTo(DeployHarness.INT1);
        assertThat(plan.targetFingerprint()).startsWith("sha256:");
        assertThat(plan.diffFile()).isEqualTo(".reports/deploy-" + AUDIT_ID + "/int-node1.diff");
        assertThat(read(plan.diffFile())).contains("Module-0005", "release.xsl")
            .doesNotContain("Workflow-0001.xml");
        assertThat(read(plan.summaryFile())).contains("int/node1", "CHANGED", "Module-0005");
        assertThat(harness.restCalls).containsExactly("int/node1 diagrams jdoe");
        harness.verifyComplete();
    }

    @Test
    void aMovedNodeIsLayoutOnlyAndAnotherEdgeIsChanged() {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release();
        FakeServer target = harness.servers.get(DeployHarness.INT1);
        target.publishWorkflow("Workflow-0001", xml -> xml.replace("xPos=\"120\"",
            "xPos=\"140\""));
        target.publishWorkflow("Workflow-0002", xml -> xml.replace(
            "<ConnectionId>5</ConnectionId>", "<ConnectionId>8</ConnectionId>"));
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(classes(plan)).containsEntry("Workflow-0001", ArtifactClass.LAYOUT_ONLY)
            .containsEntry("Workflow-0002", ArtifactClass.CHANGED);
        harness.verifyComplete();
    }

    @Test
    void onAnEmptyTargetEverythingIsNewWithTheReleasesFlags() {
        harness = harnessQuietly(true);
        ReleaseDiscovery.Release release = release(server -> server.publishWorkflow(
            "Workflow-0001", xml -> xml.replace("<IsActive>false</IsActive>",
                "<IsActive>true</IsActive>")));
        harness.exportGroup(DeployHarness.INT1);
        for (String[] module : new String[][] {{"Assign", "Module-0003"},
            {"Assign", "Module-0004"}, {"Assign", "Module-0007"}, {"Assign", "Module-0008"},
            {"Assign", "Module-0009"}, {"Demultiplexer", "Module-0002"},
            {"Demultiplexer", "Module-0006"}, {"XSLT Converter", "Module-0001"},
            {"XSLT Converter", "Module-0005"}}) {
            harness.exportModule(DeployHarness.INT1, module[0], module[1]);
        }
        harness.exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(plan.counts()).containsEntry(ArtifactClass.NEW, 12);
        assertThat(artifact(plan, "Workflow-0001").active()).contains(true);
        assertThat(artifact(plan, "Workflow-0001").kept()).isFalse();
        assertThat(artifact(plan, "Workflow-0002").active()).contains(false);
        assertThat(plan.executable()).as(plan.errors().toString()).isTrue();
        assertThat(harness.restCalls).contains("int/node1 modules jdoe");
        harness.verifyComplete();
    }

    @Test
    void workflowsOnlyOnTheTargetAreListedAndNeverTouched() {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release();
        harness.servers.get(DeployHarness.INT1).load(ExportHarness.rewrite("grp-a.zip",
            "workflow/workflow.xml", xml -> xml.replace("Workflow-0001", "Workflow-0099")
                .replace("<CheckoutUser>jdoe</CheckoutUser>", "")));
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(classes(plan)).containsEntry("Workflow-0099", ArtifactClass.ONLY_ON_TARGET);
        harness.verifyComplete();
    }

    @Test
    void exclusionsByDiagramGroupNameAndRepositoryPath() {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release();
        harness.exportGroup(DeployHarness.INT1); // the excluded path is not even read

        NodePlan plan = plan(release, List.of(
            new StageChain.Exclusion(StageChain.Exclusion.Kind.NAME, "Module-0005"),
            new StageChain.Exclusion(StageChain.Exclusion.Kind.REPOSITORY_PATH,
                "/Root/*/xsd/**")));

        assertThat(classes(plan)).containsEntry("Module-0005", ArtifactClass.EXCLUDED)
            .containsEntry(DeployHarness.RELEASE_XSL, ArtifactClass.EXCLUDED)
            .containsEntry("Workflow-0002", ArtifactClass.UNCHANGED);
        assertThat(plan.executable()).isTrue();
        assertThat(artifact(plan, "Module-0005").active()).isEmpty();
        harness.verifyComplete();
    }

    @Test
    void anExcludedDiagramGroupTakesItsOwnModulesAlong() {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release();
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of(new StageChain.Exclusion(
            StageChain.Exclusion.Kind.DIAGRAM_GROUP, "GRP-01")));

        // repository files are excluded by their own rules only (stage 2 ruling)
        assertThat(plan.counts()).containsEntry(ArtifactClass.EXCLUDED, 11)
            .containsEntry(ArtifactClass.NEW, 1);
        harness.verifyComplete();
    }

    @Test
    void anExcludedModuleThatIsMissingOnTheTargetIsAnError() {
        harness = harnessQuietly(true);
        ReleaseDiscovery.Release release = release();
        harness.exportGroup(DeployHarness.INT1);
        for (String[] module : new String[][] {{"Assign", "Module-0003"},
            {"Assign", "Module-0004"}, {"Assign", "Module-0007"}, {"Assign", "Module-0008"},
            {"Assign", "Module-0009"}, {"Demultiplexer", "Module-0002"},
            {"Demultiplexer", "Module-0006"}, {"XSLT Converter", "Module-0001"}}) {
            harness.exportModule(DeployHarness.INT1, module[0], module[1]);
        }
        harness.exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of(new StageChain.Exclusion(
            StageChain.Exclusion.Kind.NAME, "Module-0005")));

        assertThat(plan.executable()).isFalse();
        assertThat(plan.errors()).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(error.artifact()).isEqualTo("Workflow-0002");
            assertThat(error.message()).contains("Module-0005", "excluded", "int/node1");
        });
        harness.verifyComplete();
    }

    @Test
    void aWorkflowNameInAnotherDiagramGroupOfTheTargetIsAnError() {
        harness = harnessQuietly(true);
        ReleaseDiscovery.Release release = release();
        harness.servers.get(DeployHarness.INT1).load(ExportHarness.rewrite("grp-a.zip",
            "workflow/workflow.xml", xml -> xml.replace("GRP-01", "GRP-02")
                .replace("<CheckoutUser>jdoe</CheckoutUser>", "")));
        harness.exportGroup(DeployHarness.INT1);
        for (String[] module : new String[][] {{"Assign", "Module-0003"},
            {"Assign", "Module-0004"}, {"Assign", "Module-0007"}, {"Assign", "Module-0008"},
            {"Assign", "Module-0009"}, {"Demultiplexer", "Module-0002"},
            {"Demultiplexer", "Module-0006"}, {"XSLT Converter", "Module-0001"},
            {"XSLT Converter", "Module-0005"}}) {
            harness.exportModule(DeployHarness.INT1, module[0], module[1]);
        }
        harness.exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(plan.errors()).anySatisfy(error -> {
            assertThat(error.artifact()).isEqualTo("Workflow-0001");
            assertThat(error.message()).contains("GRP-02");
        });
        harness.verifyComplete();
    }

    @Test
    void aTargetWorkflowInEditModeIsAConflict() {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release();
        FakeServer target = harness.servers.get(DeployHarness.INT1);
        target.publishModule("Module-0003", module("IsModuleTemplate", "IsModuleTemplateX"));
        target.publishWorkflow("Workflow-0001", xml -> xml.replace("xPos=\"120\"",
            "xPos=\"140\""));
        target.editMode("Workflow-0001", "jdoe");
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(plan.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(error.artifact()).isEqualTo("Workflow-0001");
            assertThat(error.message()).contains("edit mode", "jdoe");
        });
        assertThat(classes(plan)).containsEntry("Module-0003", ArtifactClass.CHANGED);
        harness.verifyComplete();
    }

    @Test
    void aSecretWithoutValueOnTheTargetIsUnresolvedWithoutTheValue() throws IOException {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release(server -> server.publishModule(
            "Module-0001", module("<Property name=\"IsModuleTemplate\"",
                "<Property name=\"Password\" type=\"Password\" encrypted=\"true\">"
                    + PASSWORD_VALUE + "</Property>\n\t<Property name=\"IsModuleTemplate\"")));
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(plan.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.SECRET_UNRESOLVED);
            assertThat(error.message()).doesNotContain(PASSWORD_VALUE);
        });
        assertThat(read(plan.diffFile())).doesNotContain(PASSWORD_VALUE);
        harness.verifyComplete();
    }

    @Test
    void keyMaterialIsNeverDeployedAndMustExistOnTheTarget() {
        harness = harnessQuietly(false);
        String cer = "/Root/jdoe/keys/partner.cer";
        ReleaseDiscovery.Release release = release(server -> {
            server.putRepositoryFile(cer, "-----BEGIN CERTIFICATE-----\nAAAA\n"
                + "-----END CERTIFICATE-----\n");
            server.publishModule("Module-0001", module("<Property name=\"IsModuleTemplate\"",
                "<Property name=\"Note\">href=\"inubitrepository:" + cer + "\"</Property>\n\t"
                    + "<Property name=\"IsModuleTemplate\""));
        });
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, cer)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL)
            .exportGroup(DeployHarness.INT2)
            .exportRepository(DeployHarness.INT2, cer)
            .exportRepository(DeployHarness.INT2, DeployHarness.RELEASE_XSL);
        harness.servers.get(DeployHarness.INT2).putRepositoryFile(cer, "own certificate");

        NodePlan missing = plan(release, List.of());
        NodePlan present = planner().plan(admitted(List.of()), release, DeployHarness.INT2,
            AUDIT_ID);

        assertThat(classes(missing)).containsEntry(cer, ArtifactClass.EXCLUDED);
        assertThat(missing.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.SECRET_UNRESOLVED);
            assertThat(error.artifact()).isEqualTo(cer);
        });
        assertThat(classes(present)).containsEntry(cer, ArtifactClass.EXCLUDED);
        assertThat(present.executable()).isTrue();
        harness.verifyComplete();
    }

    @Test
    void repositoryFilesOutsideTheOwnersAreaAreExcludedAndMustExist() {
        // stage 2 ruling (review #3)
        harness = harnessQuietly(false);
        String shared = "/Root/OWNERS/xsd/shared.xsd";
        ReleaseDiscovery.Release release = release(server -> {
            server.putRepositoryFile(shared, "<xs:schema/>");
            server.publishModule("Module-0001", module("<Property name=\"IsModuleTemplate\"",
                "<Property name=\"Note\">href=\"inubitrepository:" + shared + "\"</Property>\n"
                    + "\t<Property name=\"IsModuleTemplate\""));
        });
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, shared)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(classes(plan)).containsEntry(shared, ArtifactClass.EXCLUDED);
        assertThat(plan.warnings()).anySatisfy(warning -> {
            assertThat(warning.kind()).isEqualTo(NodePlan.WarningKind.OUTSIDE_OWNER_REPOSITORY);
            assertThat(warning.artifact()).isEqualTo(shared);
        });
        assertThat(plan.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(error.message()).contains(shared, "int/node1");
        });
        harness.verifyComplete();
    }

    @Test
    void aModuleNameOfAnotherPluginTypeOnTheTargetIsAnError() {
        harness = harnessQuietly(true);
        ReleaseDiscovery.Release release = release();
        byte[] mapper = ExportHarness.rewrite("grp-a.zip", "module/module.xml", xml -> xml
            .replace("<ModuleGroupName>XSLT Converter</ModuleGroupName>",
                "<ModuleGroupName>Mapper</ModuleGroupName>"));
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries(mapper));
        entries.remove("workflow/workflow.xml");
        harness.servers.get(DeployHarness.INT1).load(ArtifactFixtures.zip(entries));
        harness.exportGroup(DeployHarness.INT1)
            .exportModule(DeployHarness.INT1, "Assign", "Module-0003")
            .exportModule(DeployHarness.INT1, "Assign", "Module-0004")
            .exportModule(DeployHarness.INT1, "Assign", "Module-0007")
            .exportModule(DeployHarness.INT1, "Assign", "Module-0008")
            .exportModule(DeployHarness.INT1, "Assign", "Module-0009")
            .exportModule(DeployHarness.INT1, "Demultiplexer", "Module-0002")
            .exportModule(DeployHarness.INT1, "Demultiplexer", "Module-0006")
            .exportModule(DeployHarness.INT1, "XSLT Converter", "Module-0001")
            .exportModule(DeployHarness.INT1, "XSLT Converter", "Module-0005")
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(plan.errors()).extracting(NodePlan.PlanError::artifact)
            .containsExactly("Module-0001", "Module-0005");
        assertThat(plan.errors().get(0).message()).contains("Mapper", "XSLT Converter");
        harness.verifyComplete();
    }

    @Test
    void aTargetNodeThatCannotBeReadFailsThePlan() {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release();
        harness.cli.get(DeployHarness.INT1).expect("export ").replying("unreachable");

        assertThatThrownBy(() -> plan(release, List.of()))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNREACHABLE));
        harness.verifyComplete();
    }

    @Test
    void errorFindingsOfTheFeature003ChecksOnTheReleaseAreErrors() throws IOException {
        harness = harnessQuietly(false);
        assertThat(planner().checkRelease(release(), UUID.randomUUID())).isEmpty();
        ReleaseDiscovery.Release release = release(server -> server.publishWorkflow(
            "Workflow-0001", xml -> xml.replace("<Connection moduleOutId=\"4\">",
                "<Connection moduleOutId=\"99\">")));

        List<NodePlan.PlanError> errors = planner().checkRelease(release, AUDIT_ID);

        assertThat(errors).isNotEmpty().allSatisfy(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(error.artifact()).contains("Workflow-0001");
        });
        assertThat(harness.root.resolve(".reports/deploy-" + AUDIT_ID + "/release"))
            .isDirectory();
    }

    private DeployHarness harnessQuietly(boolean empty) {
        try {
            return harness(empty);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static NodeId node(String id) {
        return NodeId.parse(id);
    }

    // --- T018: warnings (US5) ---------------------------------------------------------------

    @Test
    void aChangedStageSpecificPropertyWarnsWithItsNameOnly() throws IOException {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release(server -> server.publishModule("Module-0001",
            module("<Property name=\"IsModuleTemplate\"", "<Property name=\"EndpointUrl\">"
                + "https://dev-host.example.test/x</Property>\n\t<Property name=\"Note\">"
                + "dev note</Property>\n\t<Property name=\"IsModuleTemplate\"")));
        harness.servers.get(DeployHarness.INT1).publishModule("Module-0001",
            module("<Property name=\"IsModuleTemplate\"", "<Property name=\"EndpointUrl\">"
                + "https://int-host.example.test/x</Property>\n\t<Property name=\"Note\">"
                + "int note</Property>\n\t<Property name=\"IsModuleTemplate\""));
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(plan.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.kind()).isEqualTo(NodePlan.WarningKind.STAGE_SPECIFIC_VALUE);
            assertThat(warning.artifact()).isEqualTo("Module-0001");
            assertThat(warning.detail()).contains("EndpointUrl").doesNotContain("example.test");
        });
        assertThat(plan.executable()).isTrue();
        assertThat(read(plan.diffFile())).contains("int-host.example.test",
            "dev-host.example.test");
        assertThat(read(plan.summaryFile())).contains("EndpointUrl")
            .doesNotContain("host.example.test");
        harness.verifyComplete();
    }

    @Test
    void aChangedModuleUsedByTargetWorkflowsOutsideTheReleaseIsShared() {
        harness = harnessQuietly(false);
        ReleaseDiscovery.Release release = release();
        harness.servers.get(DeployHarness.INT1).copyWorkflow("Workflow-0002", "Workflow-0099",
            "GRP-09");
        harness.exportGroup(DeployHarness.INT1)
            .exportRepository(DeployHarness.INT1, DeployHarness.RELEASE_XSL);

        NodePlan plan = plan(release, List.of());

        assertThat(plan.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.kind()).isEqualTo(NodePlan.WarningKind.SHARED_MODULE);
            assertThat(warning.artifact()).isEqualTo("Module-0005");
            assertThat(warning.detail()).contains("Workflow-0099");
        });
        assertThat(harness.restCalls).contains("int/node1 diagram Workflow-0099")
            .doesNotContain("int/node1 diagram Workflow-0001");
        harness.verifyComplete();
    }
}

