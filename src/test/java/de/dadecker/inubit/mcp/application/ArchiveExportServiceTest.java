package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.archive.v81.V81WorkflowArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkflowArchiveFixtures;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.MissingModule;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.WrittenArchive;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort.SlicedExport;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 015 T010 (research R-2, R-13 steps 1, 4–8, FR-002, FR-003, FR-005, FR-012, FR-017):
 * the happy path of {@code export_archives} and the checks that depend on the export, with a
 * fake StartCLI, a fake audit log, an admitting version gate and the real
 * {@link V81WorkflowArchives}.
 */
class ArchiveExportServiceTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final NodeId DEV2 = NodeId.parse("dev/node2");
    private static final NodeId TEST1 = NodeId.parse("test/node1");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000015");
    private static final String OWNER = WorkflowArchiveFixtures.OWNER;
    private static final String GRP_A = "GRP-01";
    private static final String GRP_B = "GRP-02";
    private static final String CLEAR_TEXT = "The archives contain the secrets of these workflows"
        + " (passwords, keys, certificates, saved test messages) in clear text. Do not share,"
        + " attach or commit them as they are; delete them when no longer needed.";

    @TempDir
    Path tmp;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private VersionSetting setting = VersionSetting.V8_1;
    private final RecordingAudit audit = new RecordingAudit();
    private final FakeArtifacts artifacts = new FakeArtifacts();
    private final NodeVersions versions = new NodeVersions(
        new MutableClock(Instant.parse("2026-11-06T08:00:00Z")), node -> {
            events.add("gate " + node);
            return setting;
        }, node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions);

    @Test
    void q1ThreeWorkflowsGiveThreeArchivesInAnEmptyFolder() throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        Path folder = tmp.resolve("archives");
        List<String> selected = List.of("Workflow-0004", "Workflow-0005", "Workflow-0006");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, selected, folder));

        assertThat(result.node()).isEqualTo("dev/node1");
        assertThat(result.owner()).isEqualTo(OWNER);
        assertThat(result.diagramGroup()).isEqualTo(GRP_B);
        assertThat(result.targetFolder()).isEqualTo(folder.toString());
        assertThat(result.auditId()).isEqualTo(ID);
        assertThat(result.clearTextSecrets()).isEqualTo(CLEAR_TEXT);
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.archives()).extracting(WrittenArchive::workflow)
            .containsExactlyElementsOf(selected);
        SlicedExport expected = new V81WorkflowArchives().slice(WorkflowArchiveFixtures.grpB(),
            new GroupId("dev"), OWNER, GRP_B);
        for (WrittenArchive archive : result.archives()) {
            String file = archive.workflow() + ".diagram.zip";
            Path path = folder.resolve(file);
            WorkflowArchivePlan plan = expected.plan(archive.workflow());
            assertThat(archive.file()).isEqualTo(file);
            assertThat(archive.path()).isEqualTo(path.toAbsolutePath().toString());
            assertThat(Path.of(archive.path()).isAbsolute()).isTrue();
            assertThat(archive.size()).isEqualTo(Files.size(path));
            assertThat(Files.readAllBytes(path)).isEqualTo(expected.archive(archive.workflow()));
            assertThat(archive.moduleCount()).isEqualTo(plan.modules().size());
            assertThat(archive.modules()).isEqualTo(plan.modules());
            assertThat(archive.missingModuleCount()).isZero();
            assertThat(archive.missingModules()).isEmpty();
            assertThat(archive.repositoryFileCount()).isEqualTo(plan.repositoryPaths().size());
            assertThat(archive.repositoryPaths()).isEqualTo(plan.repositoryPaths());
            assertThat(archive.warnings()).isEmpty();
        }
        assertThat(result.archives().get(2).repositoryPaths()).containsExactly(
            "/Root/OWNERS/xsd/core.xsd", "/Root/OWNERS/xsd/msg.xsd");
        assertThat(result.archives().get(2).modules()).isNotEmpty();
        assertThat(files(folder)).containsExactly("Workflow-0004.diagram.zip",
            "Workflow-0005.diagram.zip", "Workflow-0006.diagram.zip");
        assertThat(artifacts.calls).containsExactly("dev/node1 OWNERS GRP-02");

        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(audit.records).extracting(AuditRecord::auditId).containsOnly(ID);
        AuditRecord executed = audit.records.get(1);
        assertThat(executed.capability()).isEqualTo("export_archives");
        assertThat(executed.node()).isEqualTo("dev/node1");
        assertThat(executed.account()).contains("jdoe");
        assertThat(executed.mcpClient()).contains("claude-code/2.0");
        assertThat(executed.inputs()).containsExactly(
            Map.entry("owner", OWNER),
            Map.entry("diagramGroup", GRP_B),
            Map.entry("targetFolder", folder.toString()),
            Map.entry("workflow.1", "Workflow-0004"),
            Map.entry("workflow.2", "Workflow-0005"),
            Map.entry("workflow.3", "Workflow-0006"),
            Map.entry("file.1", "Workflow-0004.diagram.zip"),
            Map.entry("file.2", "Workflow-0005.diagram.zip"),
            Map.entry("file.3", "Workflow-0006.diagram.zip"),
            Map.entry("acknowledgeClearTextSecrets", "true"),
            Map.entry("reason", "handover"));
    }

    @Test
    void q5OneNamedWorkflowGivesOnlyItsArchive() throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpA;
        Path folder = Files.createDirectory(tmp.resolve("archives"));

        ArchiveExportResult result = service().export(request("dev/node1",
            Optional.of(WorkflowArchiveFixtures.USER), GRP_A, List.of("Workflow-0002"), folder));

        assertThat(result.owner()).isEqualTo("jdoe");
        assertThat(result.archives()).extracting(WrittenArchive::file)
            .containsExactly("Workflow-0002.diagram.zip");
        assertThat(files(folder)).containsExactly("Workflow-0002.diagram.zip");
        assertThat(artifacts.calls).containsExactly("dev/node1 jdoe GRP-01");
    }

    @Test
    void withoutWorkflowsEveryTechnicalWorkflowOfTheDiagramGroupIsWrittenInExportOrder() {
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of(), tmp.resolve("archives")));

        assertThat(result.archives()).extracting(WrittenArchive::workflow).containsExactly(
            "Workflow-0003", "Workflow-0004", "Workflow-0005", "Workflow-0006");
        assertThat(audit.records.get(1).inputs()).containsKeys("workflow.4", "file.4");
    }

    @Test
    void namedWorkflowsAreAuditedWrittenAndListedInTheRequestOrder() throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006", "Workflow-0004"), folder));

        assertThat(result.archives()).extracting(WrittenArchive::workflow)
            .containsExactly("Workflow-0006", "Workflow-0004");
        assertThat(result.archives()).extracting(WrittenArchive::file)
            .containsExactly("Workflow-0006.diagram.zip", "Workflow-0004.diagram.zip");
        assertThat(files(folder)).containsExactly("Workflow-0004.diagram.zip",
            "Workflow-0006.diagram.zip");
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(audit.records.get(0).inputs()).containsEntry("workflow.1", "Workflow-0006")
            .containsEntry("workflow.2", "Workflow-0004").doesNotContainKey("workflow.3");
        assertThat(audit.records.get(1).inputs()).containsEntry("workflow.1", "Workflow-0006")
            .containsEntry("file.1", "Workflow-0006.diagram.zip")
            .containsEntry("workflow.2", "Workflow-0004")
            .containsEntry("file.2", "Workflow-0004.diagram.zip")
            .doesNotContainKeys("workflow.3", "file.3");
    }

    @Test
    void aGroupTargetUsesItsFirstNode() {
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ArchiveExportResult result = service().export(request("dev", Optional.empty(), GRP_B,
            List.of("Workflow-0006"), tmp.resolve("archives")));

        assertThat(result.node()).isEqualTo("dev/node1");
        assertThat(artifacts.calls).containsExactly("dev/node1 OWNERS GRP-02");
        assertThat(audit.records).extracting(AuditRecord::node).containsOnly("dev/node1");
    }

    @Test
    void anOwnerInTheCallWinsOverInventoryOwner() {
        artifacts.export = WorkflowArchiveFixtures::grpA;

        service().export(request("dev/node2", Optional.of("jdoe"), GRP_A, List.of(),
            tmp.resolve("archives")));

        assertThat(artifacts.calls).containsExactly("dev/node2 jdoe GRP-01");
    }

    @Test
    void withoutOwnerAndInventoryOwnerTheCallIsRefusedOnceBeforeTheExport() throws IOException {
        Path folder = tmp.resolve("archives");

        ToolErrorException refused = refusal(request("test/node1", Optional.empty(), GRP_B,
            List.of(), folder));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(refused.error().message()).contains("test/node1", "inventory.owner");
        assertThat(refused.error().node()).contains(TEST1);
        assertRefusedBeforeTheExport(ErrorCode.NOT_CONFIGURED);
    }

    @Test
    void anUnknownTargetIsRefusedOnceBeforeTheExport() throws IOException {
        ToolErrorException refused = refusal(request("prod/node9", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(audit.records.get(0).node()).isEqualTo("prod/node9");
        assertRefusedBeforeTheExport(ErrorCode.TARGET_UNKNOWN);
    }

    @Test
    void aRelativeTargetFolderIsRefusedOnceBeforeTheExport() throws IOException {
        ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
            () -> service().export(new ArchiveExportRequest("dev/node1", Optional.empty(), GRP_B,
                List.of(), "archives/out", true, Optional.empty(), Optional.empty())));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused.error().message()).contains("archives/out", "absolute");
        assertRefusedBeforeTheExport(ErrorCode.INVALID_INPUT);
    }

    @Test
    void unknownWorkflowNamesAreRefusedAfterTheExportNamingThemCaseSensitively()
        throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of("Workflow-0004", "workflow-0005", "Workflow-0099"), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused.error().message()).contains("'workflow-0005'", "'Workflow-0099'",
            GRP_B).doesNotContain("'Workflow-0004'");
        assertThat(refused.error().node()).contains(DEV1);
        assertRefusedAfterTheExport(ErrorCode.INVALID_INPUT);
    }

    @Test
    void moreThanFiftyWorkflowsWithoutWorkflowsAreRefusedNamingTheCount() throws IOException {
        artifacts.export = () -> WorkflowArchiveFixtures.workflows(51);

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused.error().message()).contains("51", "50");
        assertThat(refused.error().nextStep()).contains("workflows");
        assertRefusedAfterTheExport(ErrorCode.INVALID_INPUT);
    }

    @Test
    void fiftyNamedWorkflowsOfALargerGroupAreNoCountRefusal() {
        artifacts.export = () -> WorkflowArchiveFixtures.workflows(51);

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0053"), tmp.resolve("archives")));

        assertThat(result.archives()).extracting(WrittenArchive::workflow)
            .containsExactly("Workflow-0053");
    }

    @Test
    void aFileNameOver255BytesIsRefusedNamingTheWorkflow() throws IOException {
        String longName = "W".repeat(244);
        artifacts.export = () -> WorkflowArchiveFixtures.renamed(WorkflowArchiveFixtures.grpB(),
            "Workflow-0005", longName);

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused.error().message()).contains(longName, "255");
        assertThat(refused.error().node()).contains(DEV1);
        assertRefusedAfterTheExport(ErrorCode.INVALID_INPUT);
    }

    @Test
    void twoWorkflowsWithTheSameFileNameKeyAreRefusedNamingBoth() throws IOException {
        artifacts.export = () -> WorkflowArchiveFixtures.renamed(WorkflowArchiveFixtures.renamed(
            WorkflowArchiveFixtures.grpB(), "Workflow-0004", "STRASSE"), "Workflow-0005",
            "Straße");

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused.error().message()).contains("'STRASSE'", "'Straße'",
            "STRASSE.diagram.zip", "Straße.diagram.zip");
        assertRefusedAfterTheExport(ErrorCode.INVALID_INPUT);
        assertThat(audit.records.get(1).inputs()).containsEntry("file.2", "STRASSE.diagram.zip");
    }

    @Test
    void anExistingFileWithTheSameKeyIsRefusedNamingBothAndStaysUnchanged() throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        Path folder = Files.createDirectory(tmp.resolve("archives"));
        Path existing = Files.writeString(folder.resolve("WORKFLOW-0005.DIAGRAM.ZIP"), "mine");

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of("Workflow-0004", "Workflow-0005"), folder));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused.error().message()).contains("Workflow-0005.diagram.zip",
            "WORKFLOW-0005.DIAGRAM.ZIP").doesNotContain("Workflow-0004.diagram.zip");
        assertThat(files(folder)).containsExactly("WORKFLOW-0005.DIAGRAM.ZIP");
        assertThat(Files.readString(existing)).isEqualTo("mine");
        assertRefusedAfterTheExportIntoAnExistingFolder(ErrorCode.INVALID_INPUT);
    }

    @Test
    void anUnknownNameWinsOverTheCountOverAFileNameOverACollision() {
        String longName = "W".repeat(244);
        artifacts.export = () -> WorkflowArchiveFixtures.renamed(WorkflowArchiveFixtures.renamed(
            WorkflowArchiveFixtures.workflows(51), "Workflow-0005", longName), "Workflow-0006",
            "workflow-0004");

        assertThat(refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of("Workflow-0099", longName, "Workflow-0004", "workflow-0004"),
            tmp.resolve("a"))).error().message()).contains("'Workflow-0099'")
            .doesNotContain("255");
        assertThat(refusal(request("dev/node1", Optional.empty(), GRP_B, List.of(),
            tmp.resolve("b"))).error().message()).contains("51").doesNotContain("255");
        assertThat(refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of("Workflow-0004", "workflow-0004", longName), tmp.resolve("c"))).error()
            .message()).contains("255").doesNotContain("'workflow-0004'");
    }

    @Test
    void aCollisionBetweenWorkflowsWinsOverOneWithTheFolder() throws IOException {
        artifacts.export = () -> WorkflowArchiveFixtures.renamed(WorkflowArchiveFixtures.grpB(),
            "Workflow-0006", "workflow-0004");
        Path folder = Files.createDirectory(tmp.resolve("archives"));
        Files.writeString(folder.resolve("Workflow-0005.diagram.zip"), "mine");

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), folder));

        assertThat(refused.error().message()).contains("'Workflow-0004'", "'workflow-0004'")
            .doesNotContain("Workflow-0005");
    }

    @Test
    void aDiagramGroupWithoutTechnicalWorkflowsSucceedsWithEmptyListsAndCreatesNoFolder() {
        artifacts.export = WorkflowArchiveFixtures::noTechnicalWorkflows;
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of(), folder));

        assertThat(result.archives()).isEmpty();
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.clearTextSecrets()).isEqualTo(CLEAR_TEXT);
        assertThat(folder).doesNotExist();
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void aDiagramGroupMissingFromTheExportIsTreatedAsOneWithoutTechnicalWorkflows() {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_A, List.of(), folder));

        assertThat(result.archives()).isEmpty();
        assertThat(result.failed()).isEmpty();
        assertThat(folder).doesNotExist();
        assertThat(audit.records.get(1).outcome()).isEqualTo(AuditOutcome.EXECUTED);
    }

    @Test
    void thePendingRecordPrecedesTheGateAndTheExportAndTheOutcomeFollowsThem() {
        artifacts.export = WorkflowArchiveFixtures::grpB;

        service().export(request("dev/node1", Optional.empty(), GRP_B,
            List.of("Workflow-0006"), tmp.resolve("archives")));

        int pending = events.indexOf("audit PENDING");
        int gate = events.indexOf("gate dev/node1");
        int export = events.indexOf("export dev/node1");
        int executed = events.indexOf("audit EXECUTED");
        assertThat(pending).isNotNegative();
        assertThat(gate).isGreaterThan(pending);
        assertThat(export).isGreaterThan(gate);
        assertThat(executed).isGreaterThan(export);
    }

    @Test
    void admissionWarningsAreCallLevelWarningsOfTheResult() {
        setting = VersionSetting.AUTO;
        detection.answer(DEV1, "9.0.7");
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), tmp.resolve("archives")));

        assertThat(result.warnings()).containsExactly("INUBIT 9.0.7 on dev/node1 is not verified"
            + " for workspace export; results may be incomplete");
        assertThat(result.archives()).hasSize(1);
    }

    @Test
    void missingModulesAreListedWithAWarningAndTheArchiveIsWritten() {
        artifacts.export = WorkflowArchiveFixtures::missingModule;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0004", "Workflow-0006"), tmp.resolve("archives")));

        WrittenArchive w4 = result.archives().get(0);
        assertThat(w4.missingModuleCount()).isEqualTo(1);
        assertThat(w4.missingModules()).containsExactly(new MissingModule("Module-0023"));
        assertThat(w4.warnings()).singleElement().asString()
            .contains("imports only where these modules exist");
        assertThat(result.archives().get(1).warnings()).isEmpty();
        assertThat(result.archives().get(1).missingModules()).isEmpty();
        assertThat(audit.records.get(1).outcome()).isEqualTo(AuditOutcome.EXECUTED);
    }

    @Test
    void aWorkflowConnectorNodeGivesAWarningThatTheCalledWorkflowIsNotIncluded() {
        artifacts.export = WorkflowArchiveFixtures::workflowConnector;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0003", "Workflow-0004"), tmp.resolve("archives")));

        assertThat(result.archives().get(0).warnings()).singleElement().asString()
            .contains("the called workflow is not included");
        assertThat(result.archives().get(1).warnings()).isEmpty();
    }

    @Test
    void theWorkspaceIsUntouchedAndTheRawExportReachesNoCodec() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Files.writeString(workspace.resolve("README.md"), "workspace");
        artifacts.export = WorkflowArchiveFixtures::grpB;

        service().export(request("dev/node1", Optional.empty(), GRP_B, List.of(),
            tmp.resolve("archives")));

        try (Stream<Path> all = Files.walk(workspace)) {
            assertThat(all.map(workspace::relativize).map(Path::toString))
                .containsExactlyInAnyOrder("", "README.md");
        }
        assertThat(files(tmp)).containsExactlyInAnyOrder("archives", "workspace");
        for (Constructor<?> constructor : ArchiveExportService.class.getConstructors()) {
            assertThat(Arrays.asList(constructor.getParameterTypes()))
                .doesNotContain(ArchiveCodecPort.class, ImportArchivePort.class);
        }
    }

    private void assertRefusedBeforeTheExport(ErrorCode code) throws IOException {
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.auditId()).isEqualTo(ID);
            assertThat(record.reason()).hasValueSatisfying(
                reason -> assertThat(reason).startsWith(code + ": "));
        });
        assertThat(artifacts.calls).isEmpty();
        assertThat(events).noneMatch(event -> event.startsWith("gate"));
        assertThat(files(tmp)).isEmpty();
    }

    /** The refusal created no target folder ({@code tmp/archives} did not exist before). */
    private void assertRefusedAfterTheExport(ErrorCode code) throws IOException {
        assertRefusedAfterTheExportIntoAnExistingFolder(code);
        assertThat(tmp.resolve("archives")).doesNotExist();
    }

    private void assertRefusedAfterTheExportIntoAnExistingFolder(ErrorCode code)
        throws IOException {
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
        assertThat(audit.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(audit.records.get(1).reason()).hasValueSatisfying(
            reason -> assertThat(reason).startsWith(code + ": "));
        assertThat(artifacts.calls).hasSize(1);
        try (Stream<Path> all = Files.walk(tmp)) {
            assertThat(all.filter(Files::isRegularFile).map(Path::getFileName)
                .map(Path::toString)).allMatch(name -> !name.endsWith(".part")
                    && !name.endsWith(".diagram.zip"));
        }
    }

    private ToolErrorException refusal(ArchiveExportRequest request) {
        ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
            () -> service().export(request));
        assertThat(refused).as("refusal").isNotNull();
        return refused;
    }

    private ArchiveExportService service() {
        TargetResolver targets = new TargetResolver(List.of(DEV1, DEV2, TEST1));
        Map<NodeId, Optional<String>> owners = Map.of(DEV1, Optional.of(OWNER), DEV2,
            Optional.of(OWNER), TEST1, Optional.empty());
        List<UUID> ids = new CopyOnWriteArrayList<>(List.of(ID));
        return new ArchiveExportService(targets, owners::get, node -> Optional.of("jdoe"),
            node -> artifacts.forNode(node), new V81WorkflowArchives(),
            gateWithoutClients(versions, detection, CapabilityMatrix.production()),
            new ArchiveFolderWriter(), new ArchiveExportAudit.Dependencies(audit,
                new MutableClock(Instant.parse("2026-11-06T10:00:00Z")), "default",
                () -> ids.remove(0)));
    }

    private static ArchiveExportRequest request(String target, Optional<String> owner,
        String diagramGroup, List<String> workflows, Path folder) {
        return new ArchiveExportRequest(target, owner, diagramGroup, workflows,
            folder.toString(), true, Optional.of("handover"), Optional.of("claude-code/2.0"));
    }

    private static List<String> files(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** The audit log, recording the order of its records among the other events. */
    private final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void append(AuditRecord record) {
            events.add("audit " + record.outcome());
            records.add(record);
        }
    }

    /** StartCLI: returns the fixture bytes of {@link #export}; counts and records each call. */
    private final class FakeArtifacts {

        final List<String> calls = new CopyOnWriteArrayList<>();
        Supplier<byte[]> export = () -> {
            throw new AssertionError("no export expected");
        };

        ArtifactPort forNode(NodeId node) {
            return new ArtifactPort() {
                @Override
                public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
                    events.add("export " + node);
                    calls.add(node + " " + owner + " " + diagramGroup);
                    return export.get();
                }

                @Override
                public byte[] exportModule(String owner, String pluginType, String name) {
                    throw new AssertionError("not used");
                }

                @Override
                public byte[] exportRelease(String owner, String tag) {
                    throw new AssertionError("not used");
                }

                @Override
                public byte[] exportRepository(String path) {
                    throw new AssertionError("not used");
                }
            };
        }
    }
}
