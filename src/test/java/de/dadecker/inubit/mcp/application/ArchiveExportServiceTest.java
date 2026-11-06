package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81WorkflowArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkflowArchiveFixtures;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.MissingModule;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.WrittenArchive;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort.SlicedExport;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Feature 015 T010 (research R-2, R-13 steps 1, 4–8, FR-002, FR-003, FR-005, FR-012, FR-017):
 * the happy path of {@code export_archives} and the checks that depend on the export, with a
 * fake StartCLI, a fake audit log, an admitting version gate and the real
 * {@link V81WorkflowArchives}; T011 (research R-14, FR-012, Q25): the result bound; T017
 * (research R-13 steps 1–3, FR-007, FR-014, FR-017, Q9, Q10, Q13, Q15, Q23): the static input
 * checks with the real {@link TargetFolderRules} on server directories below the temporary
 * directory (outside every git tree) and a fake network probe; T020 (research R-12, R-13,
 * FR-011, FR-013, Q17, Q18, Q20): outcomes, partial results, audit precedence and the INFO line,
 * with failures injected through {@link HookedFileOps} and the fake audit log.
 */
class ArchiveExportServiceTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final NodeId DEV2 = NodeId.parse("dev/node2");
    private static final NodeId TEST1 = NodeId.parse("test/node1");
    /** Production, {@code write.enabled} and {@code write.productionOptIn}, no archives opt-in. */
    private static final NodeId PROD1 = NodeId.parse("prod/node1");
    /** Production with {@code archives.productionOptIn}, {@code write.enabled: false}. */
    private static final NodeId PROD2 = NodeId.parse("prod/node2");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000015");
    private static final String OWNER = WorkflowArchiveFixtures.OWNER;
    private static final String GRP_A = "GRP-01";
    private static final String GRP_B = "GRP-02";
    private static final String CLEAR_TEXT = "The archives contain the secrets of these workflows"
        + " (passwords, keys, certificates, saved test messages) in clear text. Do not share,"
        + " attach or commit them as they are; delete them when no longer needed.";
    private static final String SERVER_DIRECTORY = "is or lies inside one of the server's"
        + " directories";
    private static final String NOT_WRITABLE = "is not a writable directory";
    private static final String CLOUD = "the archives (with clear-text secrets) will be"
        + " synchronized to the cloud";
    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir(factory = GitFreeTempDir.class)
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
    private ResultLimiter limiter = ResultLimiter.withDefaults();
    private CapabilityMatrix matrix = CapabilityMatrix.production();
    private WorkflowArchivePort archivePort = new V81WorkflowArchives();
    private final HookedFileOps ops = new HookedFileOps();
    private final List<Logger> loggers = List.of(
        (Logger) LoggerFactory.getLogger(ArchiveExportService.class),
        (Logger) LoggerFactory.getLogger(ArchiveExportAudit.class));
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Map<NodeId, EffectiveNodeConfig> configs = Map.of(
        DEV1, TestNodeConfig.node().id("dev/node1").build(),
        DEV2, TestNodeConfig.node().id("dev/node2").build(),
        TEST1, TestNodeConfig.node().id("test/node1").build(),
        PROD1, TestNodeConfig.node().id("prod/node1").production(true)
            .write(true, true, ConfirmationMode.SERVER).build(),
        PROD2, TestNodeConfig.node().id("prod/node2").production(true)
            .write(false, false, ConfirmationMode.SERVER).archivesProductionOptIn(true).build());
    private final Map<String, String> environment = new HashMap<>();
    private boolean windows;

    /** The INFO and ERROR lines of the service and its audit (tests run at WARN otherwise). */
    @BeforeEach
    void captureLog() {
        logs.start();
        for (Logger logger : loggers) {
            logger.addAppender(logs);
            logger.setAdditive(false);
            logger.setLevel(Level.INFO);
        }
    }

    @AfterEach
    void releaseLog() {
        for (Logger logger : loggers) {
            logger.setLevel(null);
            logger.setAdditive(true);
            logger.detachAppender(logs);
        }
        logs.stop();
    }

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
        // within the 200 characters of a name, over 255 bytes as a file name (2 bytes each)
        String longName = "\u00C4".repeat(122);
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

    @Test
    void q25ListsAreFilledArchiveByArchiveUntilMaxItemsAndCountsStayComplete()
        throws IOException {
        limiter = new ResultLimiter(3, ResultLimiter.DEFAULT_MAX_CHARS);
        artifacts.export = WorkflowArchiveFixtures::grpB;
        Path folder = tmp.resolve("archives");
        List<String> selected = List.of("Workflow-0004", "Workflow-0005", "Workflow-0006");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, selected, folder));

        SlicedExport expected = new V81WorkflowArchives().slice(WorkflowArchiveFixtures.grpB(),
            new GroupId("dev"), OWNER, GRP_B);
        assertThat(result.truncated()).isTrue();
        assertThat(result.archives()).extracting(WrittenArchive::workflow)
            .containsExactlyElementsOf(selected);
        assertThat(files(folder)).containsExactly("Workflow-0004.diagram.zip",
            "Workflow-0005.diagram.zip", "Workflow-0006.diagram.zip");
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        WrittenArchive w4 = result.archives().get(0);
        WrittenArchive w5 = result.archives().get(1);
        WrittenArchive w6 = result.archives().get(2);
        assertThat(w4.modules()).isEqualTo(expected.plan("Workflow-0004").modules())
            .hasSize(3);
        assertThat(w5.modules()).isEmpty();
        assertThat(w6.modules()).isEmpty();
        assertThat(w6.repositoryPaths()).isEmpty();
        assertThat(result.archives()).extracting(WrittenArchive::moduleCount)
            .containsExactly(3, 2, 4);
        assertThat(result.archives()).extracting(WrittenArchive::repositoryFileCount)
            .containsExactly(0, 0, 2);
        assertThat(result.archives()).extracting(WrittenArchive::missingModuleCount)
            .containsExactly(0, 0, 0);
        assertThat(totalItems(result)).isEqualTo(3);
    }

    @Test
    void perArchiveModulesComeBeforeMissingModulesBeforeRepositoryPaths() {
        limiter = new ResultLimiter(8, ResultLimiter.DEFAULT_MAX_CHARS);
        artifacts.export = WorkflowArchiveFixtures::missingModule;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0004", "Workflow-0006"), tmp.resolve("archives")));

        SlicedExport expected = new V81WorkflowArchives().slice(
            WorkflowArchiveFixtures.missingModule(), new GroupId("dev"), OWNER, GRP_B);
        WrittenArchive w4 = result.archives().get(0);
        WrittenArchive w6 = result.archives().get(1);
        assertThat(w4.modules()).isEqualTo(expected.plan("Workflow-0004").modules())
            .hasSize(2);
        assertThat(w4.missingModules()).containsExactly(new MissingModule("Module-0023"));
        assertThat(w6.modules()).isEqualTo(expected.plan("Workflow-0006").modules())
            .hasSize(4);
        assertThat(w6.repositoryPaths()).containsExactly("/Root/OWNERS/xsd/core.xsd");
        assertThat(w6.repositoryFileCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
        assertThat(totalItems(result)).isEqualTo(8);
    }

    @Test
    void withinOneArchiveMissingModulesComeBeforeRepositoryPaths() {
        limiter = new ResultLimiter(4, ResultLimiter.DEFAULT_MAX_CHARS);
        artifacts.export = () -> WorkflowArchiveFixtures.withoutModule("Module-0026");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), tmp.resolve("archives")));

        WrittenArchive w6 = result.archives().get(0);
        assertThat(w6.moduleCount()).isEqualTo(3);
        assertThat(w6.modules()).hasSize(3);
        assertThat(w6.missingModules()).containsExactly(new MissingModule("Module-0026"));
        assertThat(w6.repositoryPaths()).isEmpty();
        assertThat(w6.repositoryFileCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void theBoundCutsMissingModulesButNoArchiveNoWarningAndNoCount() {
        limiter = new ResultLimiter(2, ResultLimiter.DEFAULT_MAX_CHARS);
        setting = VersionSetting.AUTO;
        detection.answer(DEV1, "9.0.7");
        artifacts.export = WorkflowArchiveFixtures::missingModule;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0004", "Workflow-0005", "Workflow-0006"),
            tmp.resolve("archives")));

        assertThat(result.truncated()).isTrue();
        assertThat(result.archives()).hasSize(3);
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.warnings()).containsExactly("INUBIT 9.0.7 on dev/node1 is not verified"
            + " for workspace export; results may be incomplete");
        WrittenArchive w4 = result.archives().get(0);
        assertThat(w4.modules()).hasSize(2);
        assertThat(w4.missingModules()).isEmpty();
        assertThat(w4.missingModuleCount()).isEqualTo(1);
        assertThat(w4.warnings()).singleElement().asString()
            .contains("imports only where these modules exist");
        assertThat(result.archives().subList(1, 3)).allSatisfy(archive -> {
            assertThat(archive.modules()).isEmpty();
            assertThat(archive.repositoryPaths()).isEmpty();
            assertThat(archive.moduleCount()).isPositive();
        });
        assertThat(result.archives().get(2).repositoryFileCount()).isEqualTo(2);
        assertThat(totalItems(result)).isEqualTo(2);
    }

    @Test
    void aSumExactlyAtMaxItemsIsNotTruncatedAndOneMoreItemIs() {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        List<String> selected = List.of("Workflow-0004", "Workflow-0005", "Workflow-0006");

        limiter = new ResultLimiter(11, ResultLimiter.DEFAULT_MAX_CHARS);
        ArchiveExportResult exact = service().export(request("dev/node1", Optional.empty(),
            GRP_B, selected, tmp.resolve("exact")));
        limiter = new ResultLimiter(10, ResultLimiter.DEFAULT_MAX_CHARS);
        ArchiveExportResult cut = service().export(request("dev/node1", Optional.empty(),
            GRP_B, selected, tmp.resolve("cut")));

        assertThat(totalItems(exact)).isEqualTo(11);
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.archives().get(2).repositoryPaths()).containsExactly(
            "/Root/OWNERS/xsd/core.xsd", "/Root/OWNERS/xsd/msg.xsd");
        assertThat(totalItems(cut)).isEqualTo(10);
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.archives().get(2).repositoryPaths()).containsExactly(
            "/Root/OWNERS/xsd/core.xsd");
    }

    @Test
    void theBoundCanCutTheModuleListPartway() {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        limiter = new ResultLimiter(2, ResultLimiter.DEFAULT_MAX_CHARS);

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0004"), tmp.resolve("archives")));

        SlicedExport expected = new V81WorkflowArchives().slice(
            WorkflowArchiveFixtures.grpB(), new GroupId("dev"), OWNER, GRP_B);
        List<WorkflowArchivePlan.ModuleEntry> planned = expected.plan("Workflow-0004").modules();
        assertThat(planned).hasSizeGreaterThan(2);
        assertThat(result.archives().get(0).modules()).containsExactlyElementsOf(
            planned.subList(0, 2));
        assertThat(result.archives().get(0).moduleCount()).isEqualTo(planned.size());
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aLargeMaxItemsGivesTheCompleteLists() {
        limiter = new ResultLimiter(10_000, ResultLimiter.DEFAULT_MAX_CHARS);
        artifacts.export = WorkflowArchiveFixtures::missingModule;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of(), tmp.resolve("archives")));

        SlicedExport expected = new V81WorkflowArchives().slice(
            WorkflowArchiveFixtures.missingModule(), new GroupId("dev"), OWNER, GRP_B);
        assertThat(result.truncated()).isFalse();
        assertThat(result.archives()).hasSize(expected.workflows().size());
        for (WrittenArchive archive : result.archives()) {
            WorkflowArchivePlan plan = expected.plan(archive.workflow());
            assertThat(archive.modules()).isEqualTo(plan.modules());
            assertThat(archive.missingModules()).extracting(MissingModule::name)
                .containsExactlyElementsOf(plan.missingModules());
            assertThat(archive.repositoryPaths()).isEqualTo(plan.repositoryPaths());
        }
    }

    @Test
    void q9WithoutTheAcknowledgementTheCallIsRefusedOnceBeforeTheExport() throws IOException {
        ToolErrorException refused = refusal(request("dev/node1", false, GRP_B, List.of(),
            Optional.of("handover"), tmp.resolve("archives").toString()));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused.error().message()).contains("acknowledgeClearTextSecrets",
            "secrets", "in clear text");
        assertThat(refused.error().node()).contains(DEV1);
        assertRefusedBeforeTheExport(ErrorCode.INVALID_INPUT);
        assertThat(audit.records.get(0).inputs())
            .containsEntry("acknowledgeClearTextSecrets", "false");
    }

    @Test
    void q23AProductionGroupWithoutTheOptInIsRefusedAlsoWithWriteEnabledAndItsOptIn()
        throws IOException {
        assertThat(configs.get(PROD1).write().enabled()).isTrue();
        assertThat(configs.get(PROD1).write().productionOptIn()).isTrue();

        ToolErrorException refused = refusal(request("prod/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(refused.error().message()).contains("prod/node1", "archives.productionOptIn");
        assertThat(refused.error().nextStep()).contains("archives.productionOptIn");
        assertThat(refused.error().node()).contains(PROD1);
        assertRefusedBeforeTheExport(ErrorCode.PRODUCTION_PROTECTED);
    }

    @Test
    void q23AProductionGroupWithTheOptInIsAllowedAlsoWithWriteDisabled() {
        assertThat(configs.get(PROD2).write().enabled()).isFalse();
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ArchiveExportResult result = service().export(request("prod/node2", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), tmp.resolve("archives")));

        assertThat(result.node()).isEqualTo("prod/node2");
        assertThat(result.archives()).hasSize(1);
        assertThat(artifacts.calls).containsExactly("prod/node2 OWNERS GRP-02");
    }

    @Test
    void q23ANonProductionGroupNeedsNoOptIn() {
        assertThat(configs.get(DEV1).production()).isFalse();
        assertThat(configs.get(DEV1).archivesProductionOptIn()).isFalse();
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), tmp.resolve("archives")));

        assertThat(result.archives()).hasSize(1);
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void q10q13EveryFolderRuleRefusesThroughTheServiceBeforeTheExport() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Files.createDirectories(tmp.resolve("repo").resolve(".git"));
        Path file = Files.writeString(tmp.resolve("file.txt"), "mine");
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("archives/out", "is not an absolute path");
        cases.put(tmp + "/a/../archives", "has a '.' or '..' element");
        cases.put(home().resolve(".inubit-mcp").resolve("x").toString(), SERVER_DIRECTORY);
        cases.put(workspace.resolve("x").toString(), SERVER_DIRECTORY);
        cases.put(tmp.resolve("audit").resolve("x").toString(), SERVER_DIRECTORY);
        cases.put(home().resolve(".config").resolve("inubit-mcp").resolve("x").toString(),
            SERVER_DIRECTORY);
        cases.put(tmp.resolve("elsewhere").resolve("sub").toString(), SERVER_DIRECTORY);
        if (POSIX) { // symbolic links need a privilege on Windows
            Path link = Files.createSymbolicLink(tmp.resolve("link"), workspace);
            cases.put(link.resolve("x").toString(), SERVER_DIRECTORY);
        }
        cases.put(tmp.resolve("repo").resolve("archives").toString(),
            "is or lies inside a git working tree");
        cases.put(file.toString(), NOT_WRITABLE);
        cases.put(file.resolve("below").toString(), NOT_WRITABLE);
        Path locked = Files.createDirectory(tmp.resolve("locked"));
        if (POSIX) {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
            if (!Files.isWritable(locked)) { // not with root privileges
                cases.put(locked.toString(), NOT_WRITABLE);
                cases.put(locked.resolve("new").toString(), NOT_WRITABLE);
            }
        }
        List<String> before = tree(tmp);

        try {
            for (Map.Entry<String, String> entry : cases.entrySet()) {
                ToolErrorException refused = refusal(request("dev/node1", true, GRP_B,
                    List.of(), Optional.empty(), entry.getKey()));
                assertThat(refused.error().code()).as(entry.getKey())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(refused.error().message()).as(entry.getKey())
                    .contains(entry.getValue());
                assertThat(refused.error().node()).as(entry.getKey()).contains(DEV1);
            }
        } finally {
            if (POSIX) {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
            }
        }

        assertRefusedOnlyBeforeTheExport(cases.size());
        assertThat(tree(tmp)).isEqualTo(before);
    }

    @Test
    void q10WithTheWindowsFlagUncAndRemoteDrivesAreNetworkPaths() throws IOException {
        windows = true;

        for (String folder : List.of("\\\\server\\share\\x", "\\\\?\\UNC\\server\\share",
            "Z:\\x")) {
            ToolErrorException refused = refusal(request("dev/node1", true, GRP_B, List.of(),
                Optional.empty(), folder));
            assertThat(refused.error().code()).as(folder).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(refused.error().message()).as(folder).contains("is a network path")
                .doesNotContain("absolute");
        }

        assertRefusedOnlyBeforeTheExport(3);
        assertThat(files(tmp)).isEmpty();
    }

    @Test
    void q15ACloudSyncFolderIsAcceptedWithACallLevelWarning() throws IOException {
        Path oneDrive = Files.createDirectories(tmp.resolve("OneDrive"));
        environment.put("OneDrive", oneDrive.toString());
        Path cloudStorage = Files.createDirectories(home().resolve("Library")
            .resolve("CloudStorage"));
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ArchiveExportResult synced = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), oneDrive.resolve("archives")));
        ArchiveExportResult stored = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), cloudStorage.resolve("archives")));

        assertThat(synced.warnings()).singleElement().asString().contains(CLOUD,
            oneDrive.toString());
        assertThat(synced.archives()).hasSize(1);
        assertThat(stored.warnings()).singleElement().asString().contains(CLOUD);
        assertThat(stored.archives()).hasSize(1);
    }

    @Test
    void theCloudWarningJoinsTheAdmissionWarnings() throws IOException {
        Path oneDrive = Files.createDirectories(tmp.resolve("OneDrive"));
        environment.put("OneDrive", oneDrive.toString());
        setting = VersionSetting.AUTO;
        detection.answer(DEV1, "9.0.7");
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), oneDrive.resolve("archives")));

        assertThat(result.warnings()).hasSize(2).anySatisfy(warning ->
            assertThat(warning).contains(CLOUD)).contains("INUBIT 9.0.7 on dev/node1 is not"
                + " verified for workspace export; results may be incomplete");
    }

    @Test
    void defenceInDepthReChecksTheSyntaxAndLimitsOfTheSchema() throws IOException {
        String folder = tmp.resolve("archives").toString();
        List<String> fiftyOne = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            fiftyOne.add("Workflow-%04d".formatted(i));
        }
        Map<ArchiveExportRequest, String> cases = new LinkedHashMap<>();
        cases.put(request("dev/node1", true, GRP_B, fiftyOne, Optional.empty(), folder),
            "workflows");
        cases.put(request("dev/node1", true, GRP_B, List.of("Workflow-0004", "Workflow-0004"),
            Optional.empty(), folder), "workflows");
        cases.put(request("dev/node1", true, GRP_B, List.of(""), Optional.empty(), folder),
            "workflows");
        cases.put(request("dev/node1", true, GRP_B, List.of("W".repeat(201)), Optional.empty(),
            folder), "workflows");
        cases.put(request("dev/node1", true, GRP_B, List.of("Workflow\u00010004"),
            Optional.empty(), folder), "workflows");
        cases.put(request("dev/node1", true, GRP_B, List.of("Workflow-0004\n"),
            Optional.empty(), folder), "workflows");
        cases.put(request("dev/node1", true, GRP_B, List.of("Workflow\u007F0004"),
            Optional.empty(), folder), "workflows");
        cases.put(request("dev/node1", true, "-GRP", List.of(), Optional.empty(), folder),
            "diagramGroup");
        cases.put(request("dev/node1", true, "GRP/02", List.of(), Optional.empty(), folder),
            "diagramGroup");
        cases.put(request("dev/node1", true, "", List.of(), Optional.empty(), folder),
            "diagramGroup");
        cases.put(request("dev/node1", true, "G".repeat(201), List.of(), Optional.empty(),
            folder), "diagramGroup");
        cases.put(request("dev/node1", true, GRP_B, List.of(), Optional.of("r".repeat(501)),
            folder), "reason");
        cases.put(request("dev/node1", true, GRP_B, List.of(), Optional.of("line one\nline two"),
            folder), "reason");
        cases.put(request("dev/node1", true, GRP_B, List.of(), Optional.of("line one\rline two"),
            folder), "reason");
        cases.put(request("dev/node1", true, GRP_B, List.of(), Optional.of(""), folder),
            "reason");

        for (Map.Entry<ArchiveExportRequest, String> entry : cases.entrySet()) {
            ToolErrorException refused = refusal(entry.getKey());
            assertThat(refused.error().code()).as(entry.getKey().toString())
                .isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(refused.error().message()).as(entry.getKey().toString())
                .contains(entry.getValue()).doesNotContain("\n", "\r", "\u0001", "\u007F");
        }

        assertRefusedOnlyBeforeTheExport(cases.size());
        assertThat(files(tmp)).isEmpty();
    }

    @Test
    void theLimitsOfTheReCheckAreInclusive() {
        artifacts.export = () -> WorkflowArchiveFixtures.renamed(WorkflowArchiveFixtures.grpB(),
            "Workflow-0006", "W".repeat(200));

        ArchiveExportResult result = service().export(request("dev/node1", true, GRP_B,
            List.of("W".repeat(200)),
            Optional.of("r".repeat(500)), tmp.resolve("archives").toString()));

        assertThat(result.archives()).extracting(WrittenArchive::workflow)
            .containsExactly("W".repeat(200));
        assertThat(service().export(request("dev/node1", true, "G".repeat(200), List.of(),
            Optional.empty(), tmp.resolve("other").toString())).archives()).isEmpty();
    }

    @Test
    void theFirstStaticFailureWinsInTheOrderOfResearchR13() {
        String badFolder = "archives/out";
        List<String> badNames = List.of("Workflow-0004", "Workflow-0004");

        assertThat(refusal(request("prod/node9", false, "-GRP", badNames, Optional.empty(),
            badFolder)).error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(refusal(request("test/node1", false, "-GRP", badNames, Optional.empty(),
            badFolder)).error().code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(refusal(request("prod/node1", false, "-GRP", badNames, Optional.empty(),
            badFolder)).error().code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(refusal(request("dev/node1", false, "-GRP", badNames, Optional.empty(),
            badFolder)).error().message()).contains("acknowledgeClearTextSecrets");
        assertThat(refusal(request("dev/node1", true, "-GRP", badNames, Optional.of(""),
            badFolder)).error().message()).contains("diagramGroup")
            .doesNotContain("workflows").doesNotContain("absolute");
        assertThat(refusal(request("dev/node1", true, GRP_B, badNames, Optional.of(""),
            badFolder)).error().message()).contains("workflows")
            .doesNotContain("reason").doesNotContain("absolute");
        assertThat(refusal(request("dev/node1", true, GRP_B, List.of(), Optional.of(""),
            badFolder)).error().message()).contains("reason").doesNotContain("absolute");
        assertThat(refusal(request("dev/node1", true, GRP_B, List.of(), Optional.empty(),
            badFolder)).error().message()).contains("absolute");

        assertThat(audit.records).hasSize(8).extracting(AuditRecord::outcome)
            .containsOnly(AuditOutcome.REFUSED);
        assertThat(artifacts.calls).isEmpty();
    }

    static Stream<ToolError> startCliFailures() {
        return Stream.of(
            ToolError.of(ErrorCode.CLI_UNAVAILABLE, "StartCLI could not be started on dev/node1",
                "cliHome does not point to an INUBIT client", "Check cliHome").withNode(DEV1),
            ToolError.of(ErrorCode.TIMEOUT, "StartCLI did not finish within 120 s",
                "The INUBIT server is slow or unreachable, or StartCLI waits for input",
                "Check get_health; raise cliExportTimeout if the INUBIT server is just slow")
                .withNode(DEV1),
            ToolError.of(ErrorCode.AUTH_FAILED, "StartCLI rejected the credentials of dev/node1",
                "The password is wrong or expired", "Check the credentials").withNode(DEV1),
            ToolError.of(ErrorCode.NOT_FOUND, "StartCLI found no diagram group 'GRP-02'",
                "The diagram group does not exist for the owner", "Check the name"));
    }

    @ParameterizedTest
    @MethodSource("startCliFailures")
    void q17AStartCliFailureIsPassedThroughUnchangedNothingIsWrittenAndItIsAuditedFailed(
        ToolError failure) throws IOException {
        artifacts.export = () -> {
            throw new ToolErrorException(failure);
        };

        ToolErrorException failed = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        ToolError expected = failure.node().isPresent() ? failure : failure.withNode(DEV1);
        assertThat(failed.error()).isEqualTo(expected);
        assertFailedAfterThePendingRecord(failure.code());
        assertThat(events).doesNotContain("slice");
    }

    @Test
    void q17TheSizeLimitOfTheExportIsPassedThroughUnchangedAndNothingIsSlicedOrWritten()
        throws IOException {
        ToolError tooLarge = ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, "The export of dev/node1"
                + " is too large (more than " + CliExportRunner.MAX_ARCHIVE_BYTES + " bytes)",
            "StartCLI or the export format behaved differently than recorded for INUBIT 8.1",
            "Check the INUBIT client version (cliHome) and retry; see the MCP server's log")
            .withNode(DEV1);
        artifacts.export = () -> {
            throw new ToolErrorException(tooLarge);
        };

        ToolErrorException failed = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(failed.error()).isEqualTo(tooLarge);
        assertThat(events).doesNotContain("slice");
        assertFailedAfterThePendingRecord(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void anUnsupportedVersionIsRefusedByTheGateAfterThePendingRecordBeforeTheExport()
        throws IOException {
        matrix = new CapabilityMatrix(List.of(), Map.of());
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
        assertThat(audit.records.get(1).reason()).hasValueSatisfying(
            reason -> assertThat(reason).startsWith("UNSUPPORTED_VERSION: "));
        assertThat(artifacts.calls).isEmpty();
        assertThat(files(tmp)).isEmpty();
    }

    @Test
    void q18AWriteFailureOnTheSecondOfThreeIsAPartialResultAuditedPartiallyCompleted()
        throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        ops.beforeWrite = (path, call) -> {
            if (call == 2) {
                throw new IOException("No space left on device");
            }
        };
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0004", "Workflow-0005", "Workflow-0006"), folder));

        assertThat(result.archives()).extracting(WrittenArchive::workflow)
            .containsExactly("Workflow-0004");
        assertThat(Files.readAllBytes(folder.resolve("Workflow-0004.diagram.zip")))
            .isEqualTo(expectedArchive("Workflow-0004"));
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0005");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0005.diagram.zip",
                "No space left on device");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0006");
        assertThat(result.clearTextSecrets()).isEqualTo(CLEAR_TEXT);
        assertThat(result.warnings()).isEmpty();
        assertThat(files(folder)).containsExactly("Workflow-0004.diagram.zip");
        assertPartiallyCompleted("1 written, 1 failed, 1 not written");
    }

    @Test
    void q18AFailureOnTheFirstArchiveIsAResultWithoutArchivesAuditedPartiallyCompleted()
        throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        ops.beforeWrite = (path, call) -> {
            throw new IOException("No space left on device");
        };
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0004", "Workflow-0005", "Workflow-0006"), folder));

        assertThat(result.archives()).isEmpty();
        assertThat(result.failed()).extracting(ArchiveExportResult.FailedArchive::workflow)
            .containsExactly("Workflow-0004");
        assertThat(result.notWritten()).containsExactly("Workflow-0005", "Workflow-0006");
        assertThat(folder).isDirectory();
        assertThat(files(folder)).isEmpty();
        assertPartiallyCompleted("0 written, 1 failed, 2 not written");
    }

    @Test
    void q18ATargetFolderThatCannotBeCreatedIsAResultWithoutArchivesAuditedPartiallyCompleted()
        throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        ops.beforeCreateDirectory = (path, call) -> {
            throw new AccessDeniedException(path.toString(), null, "Permission denied");
        };
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0004", "Workflow-0005"), folder));

        assertThat(result.archives()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0004");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("target folder", "Permission denied");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0005");
        assertThat(folder).doesNotExist();
        assertPartiallyCompleted("0 written, 1 failed, 1 not written");
    }

    @Test
    void q18ALeftoverPartFileIsACallLevelWarningAndTheArchiveCountsAsWrittenAndExecuted()
        throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        ops.beforeDelete = (path, call) -> {
            throw new AccessDeniedException(path.toString(), null, "Permission denied");
        };
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), folder));

        assertThat(result.archives()).extracting(WrittenArchive::workflow)
            .containsExactly("Workflow-0006");
        assertThat(result.archives().get(0).warnings()).isEmpty();
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.warnings()).singleElement().asString()
            .contains(".Workflow-0006.diagram.zip.", ".part", "could not be deleted");
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(audit.records.get(1).reason()).contains("1 written, 0 failed, 0 not written");
    }

    static Stream<RuntimeException> builderFailures() {
        return Stream.of(new IllegalStateException("broken model"),
            new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                "The archive of Workflow-0005 could not be built", "The export is unusual",
                "Check the export")));
    }

    /**
     * The archive of a workflow is built lazily while the writes run (T010-r1, T019-r1): a builder
     * that throws after an earlier archive was written ends the writes there, as a result.
     */
    @ParameterizedTest
    @MethodSource("builderFailures")
    void aBuilderFailureAfterAnEarlierArchiveIsAPartialResultAuditedPartiallyCompleted(
        RuntimeException failure) throws IOException {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        archivePort = failingArchive("Workflow-0005", failure);
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0004", "Workflow-0005", "Workflow-0006"), folder));

        assertThat(result.archives()).extracting(WrittenArchive::workflow)
            .containsExactly("Workflow-0004");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0005");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0005.diagram.zip",
                failure instanceof ToolErrorException e ? e.error().code().name()
                    : failure.getClass().getSimpleName());
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0006");
        assertThat(files(folder)).containsExactly("Workflow-0004.diagram.zip");
        assertPartiallyCompleted("1 written, 1 failed, 1 not written");
    }

    @Test
    void q20AnUnwritableAuditTurnsAStaticRefusalIntoInternalWithTheOriginalCodeAndMessage()
        throws IOException {
        audit.failing = record -> true;

        ToolErrorException refused = refusal(request("dev/node1", false, GRP_B, List.of(),
            Optional.empty(), tmp.resolve("archives").toString()));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(refused.error().message()).contains("INVALID_INPUT",
            "acknowledgeClearTextSecrets is not true", "nothing was exported or written");
        assertThat(events).containsExactly("audit failed REFUSED");
        assertThat(artifacts.calls).isEmpty();
        assertThat(files(tmp)).isEmpty();
    }

    @Test
    void q20AnUnwritableAuditRefusesAValidCallWithInternalBeforeTheGateAndTheExport()
        throws IOException {
        audit.failing = record -> true;
        artifacts.export = WorkflowArchiveFixtures::grpB;

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(refused.error().message()).contains("nothing was exported");
        assertThat(refused.error().nextStep()).contains("auditDirectory");
        assertThat(events).containsExactly("audit failed PENDING");
        assertThat(artifacts.calls).isEmpty();
        assertThat(files(tmp)).isEmpty();
    }

    @Test
    void aFailingOutcomeRecordReturnsTheResultWithAWarningNamingTheAuditId() throws IOException {
        audit.failing = record -> record.outcome() != AuditOutcome.PENDING;
        artifacts.export = WorkflowArchiveFixtures::grpB;
        Path folder = tmp.resolve("archives");

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), folder));

        assertThat(result.archives()).hasSize(1);
        assertThat(result.warnings()).singleElement().asString()
            .contains(ID.toString(), "auditDirectory");
        assertThat(events).contains("audit PENDING", "audit failed EXECUTED");
        assertThat(files(folder)).containsExactly("Workflow-0006.diagram.zip");
    }

    @Test
    void aFailingOutcomeRecordOfAPartialResultAlsoGivesTheWarning() {
        audit.failing = record -> record.outcome() != AuditOutcome.PENDING;
        artifacts.export = WorkflowArchiveFixtures::grpB;
        ops.beforeWrite = (path, call) -> {
            throw new IOException("No space left on device");
        };

        ArchiveExportResult result = service().export(request("dev/node1", Optional.empty(),
            GRP_B, List.of("Workflow-0006"), tmp.resolve("archives")));

        assertThat(result.failed()).hasSize(1);
        assertThat(result.warnings()).singleElement().asString().contains(ID.toString());
        assertThat(events).contains("audit failed PARTIALLY_COMPLETED");
    }

    @Test
    void aFailingOutcomeRecordOfAToolErrorKeepsTheErrorAndAddsASentenceNamingTheAuditId() {
        audit.failing = record -> record.outcome() != AuditOutcome.PENDING;
        ToolError failure = startCliFailures().findFirst().orElseThrow();
        artifacts.export = () -> {
            throw new ToolErrorException(failure);
        };

        ToolErrorException failed = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(failed.error().code()).isEqualTo(failure.code());
        assertThat(failed.error().message()).startsWith(failure.message())
            .contains(ID.toString());
        assertThat(failed.error().likelyCause()).isEqualTo(failure.likelyCause());
        assertThat(failed.error().nextStep()).isEqualTo(failure.nextStep());
        assertThat(failed.error().node()).contains(DEV1);
        assertThat(events).contains("audit PENDING", "audit failed FAILED");
    }

    @Test
    void anUnexpectedFailureIsAuditedFailedWithItsClassNameAndPropagatesUnchanged()
        throws IOException {
        IllegalStateException failure = new IllegalStateException("unexpected");
        artifacts.export = () -> {
            throw failure;
        };

        Throwable thrown = catchThrowable(() -> service().export(request("dev/node1",
            Optional.empty(), GRP_B, List.of(), tmp.resolve("archives"))));

        assertThat(thrown).isSameAs(failure);
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.records.get(1).reason()).contains("INTERNAL: IllegalStateException");
        assertThat(files(tmp)).isEmpty();
    }

    /**
     * T020 review: an unexpected failure after the writes (here: the plan of a written archive)
     * is audited once, with the counts instead of "nothing written", and ends in {@code INTERNAL}
     * naming the counts and the folder with its clear-text archives, never the failure's message.
     */
    @Test
    void anUnexpectedFailureAfterTheWritesIsAuditedOnceAndEndsInternalNamingTheFolder()
        throws IOException {
        IllegalStateException failure = new IllegalStateException("detail-0002");
        artifacts.export = WorkflowArchiveFixtures::grpB;
        archivePort = failingPlan(failure);
        Path folder = tmp.resolve("archives");

        ToolErrorException failed = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of("Workflow-0006"), folder));

        assertThat(failed.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(failed.error().message()).isEqualTo("The tool export_archives failed"
            + " unexpectedly (IllegalStateException) after the writes: 1 written, 0 failed, 0 not"
            + " written in " + folder + "; the archives written there contain secrets in clear"
            + " text and must not be shared or committed as they are");
        assertThat(failed.error().message()).doesNotContain("detail-0002");
        assertThat(failed.error().likelyCause())
            .isEqualTo("An internal error of the INUBIT MCP server");
        assertThat(failed.error().nextStep()).isEqualTo("Check " + folder + " for the archives"
            + " written and keep or delete them as their secrets require (existing files are"
            + " never overwritten, so move them before retrying into the same folder); report the"
            + " problem with the MCP server log on stderr");
        assertThat(failed.error().node()).contains(DEV1);
        assertThat(files(folder)).containsExactly("Workflow-0006.diagram.zip");
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.records.get(1).reason()).hasValue("INTERNAL: IllegalStateException"
            + " after the writes, 1 written, 0 failed, 0 not written");
        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " FAILED (diagram group GRP-02: INTERNAL IllegalStateException after the writes,"
            + " 1 written, 0 failed, 0 not written; audit id " + ID + ")");
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR)
            .singleElement()
            .satisfies(event -> assertThat(event.getThrowableProxy()).isNotNull());
        assertLogsHoldNoValues();
    }

    /** T020 review: with a failed outcome record the folder's error also names the audit id. */
    @Test
    void aFailingOutcomeRecordAfterTheWritesAddsTheWarningToTheFolderError() {
        audit.failing = record -> record.outcome() != AuditOutcome.PENDING;
        artifacts.export = WorkflowArchiveFixtures::grpB;
        archivePort = failingPlan(new IllegalStateException("detail-0002"));
        Path folder = tmp.resolve("archives");

        ToolErrorException failed = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of("Workflow-0006"), folder));

        assertThat(failed.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(failed.error().message()).startsWith("The tool export_archives failed"
            + " unexpectedly (IllegalStateException) after the writes: 1 written")
            .contains(folder.toString(), "clear text", ID.toString())
            .doesNotContain("detail-0002");
    }

    /**
     * T010-r1: the warning of a failed outcome record is not dropped for an unexpected failure;
     * the call ends with {@code INTERNAL} (as the tool layer would map it) carrying the warning.
     */
    @Test
    void aFailingOutcomeRecordOfAnUnexpectedFailureAddsTheWarningNamingTheAuditIdToTheError() {
        audit.failing = record -> record.outcome() != AuditOutcome.PENDING;
        artifacts.export = () -> {
            throw new IllegalStateException("detail-0001");
        };

        ToolErrorException failed = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(failed.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(failed.error().message()).contains("export_archives failed unexpectedly",
            "IllegalStateException", ID.toString()).doesNotContain("detail-0001");
        assertThat(failed.error().node()).contains(DEV1);
        assertThat(events).contains("audit PENDING", "audit failed FAILED");
    }

    @Test
    void q16TheAuditOfASuccessfulCallHoldsEveryNameTheFolderTheFilesAndTheReasonOnly() {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        Path folder = tmp.resolve("archives");

        service().export(request("dev/node1", Optional.empty(), GRP_B, List.of(), folder));

        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(audit.records.get(0).inputs()).containsExactly(
            Map.entry("owner", OWNER),
            Map.entry("diagramGroup", GRP_B),
            Map.entry("targetFolder", folder.toString()),
            Map.entry("acknowledgeClearTextSecrets", "true"),
            Map.entry("reason", "handover"));
        assertThat(audit.records.get(1).inputs()).containsExactly(
            Map.entry("owner", OWNER),
            Map.entry("diagramGroup", GRP_B),
            Map.entry("targetFolder", folder.toString()),
            Map.entry("workflow.1", "Workflow-0003"),
            Map.entry("workflow.2", "Workflow-0004"),
            Map.entry("workflow.3", "Workflow-0005"),
            Map.entry("workflow.4", "Workflow-0006"),
            Map.entry("file.1", "Workflow-0003.diagram.zip"),
            Map.entry("file.2", "Workflow-0004.diagram.zip"),
            Map.entry("file.3", "Workflow-0005.diagram.zip"),
            Map.entry("file.4", "Workflow-0006.diagram.zip"),
            Map.entry("acknowledgeClearTextSecrets", "true"),
            Map.entry("reason", "handover"));
        assertThat(audit.records.get(0).reason()).isEmpty();
        assertThat(audit.records.get(1).reason()).contains("4 written, 0 failed, 0 not written");
    }

    @Test
    void theInfoLineOfASuccessfulCallNamesNodeDiagramGroupCountsAndAuditIdOnly() {
        artifacts.export = WorkflowArchiveFixtures::grpB;

        service().export(request("dev/node1", Optional.empty(), GRP_B, List.of("Workflow-0006"),
            tmp.resolve("archives")));

        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " EXECUTED (diagram group GRP-02: 1 written, 0 failed, 0 not written; audit id "
            + ID + ")");
        assertLogsHoldNoValues();
    }

    @Test
    void theInfoLineOfAPartialResultNamesNodeDiagramGroupCountsAndAuditId() {
        artifacts.export = WorkflowArchiveFixtures::grpB;
        ops.beforeWrite = (path, call) -> {
            throw new IOException("No space left on device");
        };

        service().export(request("dev/node1", Optional.empty(), GRP_B, List.of("Workflow-0006"),
            tmp.resolve("archives")));

        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " PARTIALLY_COMPLETED (diagram group GRP-02: 0 written, 1 failed, 0 not written;"
            + " audit id " + ID + ")");
        assertLogsHoldNoValues();
    }

    @Test
    void theInfoLineOfAFailedCallNamesNodeDiagramGroupCodeAndAuditId() {
        ToolError failure = startCliFailures().findFirst().orElseThrow();
        artifacts.export = () -> {
            throw new ToolErrorException(failure);
        };

        refusal(request("dev/node1", Optional.empty(), GRP_B, List.of(),
            tmp.resolve("archives")));

        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " FAILED (diagram group GRP-02: CLI_UNAVAILABLE, nothing written; audit id " + ID
            + ")");
        assertLogsHoldNoValues();
    }

    @Test
    void theInfoLineOfARefusalAfterTheExportNamesNodeDiagramGroupCodeAndAuditId() {
        artifacts.export = WorkflowArchiveFixtures::grpB;

        refusal(request("dev/node1", Optional.empty(), GRP_B, List.of("Workflow-0099"),
            tmp.resolve("archives")));

        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " REFUSED (diagram group GRP-02: INVALID_INPUT, nothing written; audit id " + ID
            + ")");
        assertLogsHoldNoValues();
    }

    @Test
    void theInfoLineOfAnUnexpectedFailureNamesNodeDiagramGroupTheClassAndAuditId() {
        artifacts.export = () -> {
            throw new IllegalStateException("unexpected");
        };

        catchThrowable(() -> service().export(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives"))));

        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " FAILED (diagram group GRP-02: INTERNAL IllegalStateException, nothing written;"
            + " audit id " + ID + ")");
        assertLogsHoldNoValues();
    }

    @Test
    void theInfoLineOfAStaticRefusalNamesTargetDiagramGroupCodeAndAuditId() {
        refusal(request("dev/node1", false, GRP_B, List.of(), Optional.of("handover"),
            tmp.resolve("archives").toString()));

        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " REFUSED (diagram group GRP-02: INVALID_INPUT, nothing written; audit id " + ID
            + ")");
        assertLogsHoldNoValues();
    }

    /** T020 review: the line says what the call returns, and names no record that is missing. */
    @Test
    void theInfoLineOfAStaticRefusalWhoseRecordFailedSaysInternalAndNamesNoAuditId() {
        audit.failing = record -> true;

        ToolErrorException refused = refusal(request("dev/node1", false, GRP_B, List.of(),
            Optional.of("handover"), tmp.resolve("archives").toString()));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " INTERNAL (diagram group GRP-02: INVALID_INPUT, nothing written; audit record not"
            + " written)");
        assertLogsHoldNoValues();
    }

    @Test
    void aFailedPendingRecordAlsoGivesTheInfoLineWithoutAuditId() {
        audit.failing = record -> record.outcome() == AuditOutcome.PENDING;

        ToolErrorException refused = refusal(request("dev/node1", Optional.empty(), GRP_B,
            List.of(), tmp.resolve("archives")));

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(serviceLines(Level.INFO)).containsExactly("export_archives on dev/node1:"
            + " INTERNAL (diagram group GRP-02: nothing exported or written; audit record not"
            + " written)");
        assertLogsHoldNoValues();
    }

    @Test
    void anAuditFailureIsAnErrorLineWithoutValues() {
        audit.failing = record -> record.outcome() != AuditOutcome.PENDING;
        artifacts.export = WorkflowArchiveFixtures::grpB;

        service().export(request("dev/node1", Optional.empty(), GRP_B, List.of("Workflow-0006"),
            tmp.resolve("archives")));

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR)
            .singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains(ID.toString(), "EXECUTED", "UncheckedIOException"));
        assertLogsHoldNoValues();
    }

    /** No log line holds the folder, the owner, the reason, a workflow or file name. */
    private void assertLogsHoldNoValues() {
        assertThat(logs.list).isNotEmpty().allSatisfy(event -> assertThat(
            event.getFormattedMessage()).doesNotContain(tmp.toString(), OWNER, "handover",
                "Workflow-", ".diagram.zip", "No space left"));
    }

    private List<String> serviceLines(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
            .filter(event -> event.getLoggerName().equals(ArchiveExportService.class.getName()))
            .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertFailedAfterThePendingRecord(ErrorCode code) throws IOException {
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(audit.records.get(1).reason()).hasValueSatisfying(
            reason -> assertThat(reason).startsWith(code + ": "));
        assertThat(artifacts.calls).hasSize(1);
        assertThat(files(tmp)).isEmpty();
    }

    private void assertPartiallyCompleted(String counts) throws IOException {
        assertThat(audit.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.PARTIALLY_COMPLETED);
        assertThat(audit.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(audit.records.get(1).reason()).contains(counts);
        assertThat(audit.records.get(1).inputs()).containsKeys("workflow.1", "file.1");
        try (Stream<Path> all = Files.walk(tmp)) {
            assertThat(all.map(path -> path.getFileName().toString()))
                .noneMatch(name -> name.endsWith(".part"));
        }
    }

    private static byte[] expectedArchive(String workflow) {
        return new V81WorkflowArchives().slice(WorkflowArchiveFixtures.grpB(),
            new GroupId("dev"), OWNER, GRP_B).archive(workflow);
    }

    /** The real slicing, except that building the archive of {@code workflow} throws. */
    private static WorkflowArchivePort failingArchive(String workflow, RuntimeException failure) {
        WorkflowArchivePort real = new V81WorkflowArchives();
        return (export, group, owner, diagramGroup) -> {
            SlicedExport sliced = real.slice(export, group, owner, diagramGroup);
            return new SlicedExport() {
                @Override
                public List<String> workflows() {
                    return sliced.workflows();
                }

                @Override
                public WorkflowArchivePlan plan(String name) {
                    return sliced.plan(name);
                }

                @Override
                public byte[] archive(String name) {
                    if (name.equals(workflow)) {
                        throw failure;
                    }
                    return sliced.archive(name);
                }
            };
        };
    }

    /** The real slicing, except that the plan of every workflow (read after the writes) throws. */
    private static WorkflowArchivePort failingPlan(RuntimeException failure) {
        WorkflowArchivePort real = new V81WorkflowArchives();
        return (export, group, owner, diagramGroup) -> {
            SlicedExport sliced = real.slice(export, group, owner, diagramGroup);
            return new SlicedExport() {
                @Override
                public List<String> workflows() {
                    return sliced.workflows();
                }

                @Override
                public WorkflowArchivePlan plan(String name) {
                    throw failure;
                }

                @Override
                public byte[] archive(String name) {
                    return sliced.archive(name);
                }
            };
        };
    }

    /** {@code count} refusals, each one {@code REFUSED} record; no gate and no export. */
    private void assertRefusedOnlyBeforeTheExport(int count) {
        assertThat(audit.records).hasSize(count).extracting(AuditRecord::outcome)
            .containsOnly(AuditOutcome.REFUSED);
        assertThat(audit.records).allSatisfy(record -> assertThat(record.reason())
            .hasValueSatisfying(reason -> assertThat(reason).startsWith("INVALID_INPUT: ")));
        assertThat(artifacts.calls).isEmpty();
        assertThat(events).noneMatch(event -> event.startsWith("gate"));
    }

    private static int totalItems(ArchiveExportResult result) {
        return result.archives().stream().mapToInt(archive -> archive.modules().size()
            + archive.missingModules().size() + archive.repositoryPaths().size()).sum();
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
        TargetResolver targets = new TargetResolver(List.of(DEV1, DEV2, TEST1, PROD1, PROD2));
        Map<NodeId, Optional<String>> owners = Map.of(DEV1, Optional.of(OWNER), DEV2,
            Optional.of(OWNER), TEST1, Optional.empty(), PROD1, Optional.of(OWNER), PROD2,
            Optional.of(OWNER));
        List<UUID> ids = new CopyOnWriteArrayList<>(List.of(ID));
        // as Wiring: the production flag and archives.productionOptIn of the node's group
        return new ArchiveExportService(targets, owners::get, node -> Optional.of("jdoe"),
            node -> configs.get(node).production(),
            node -> configs.get(node).archivesProductionOptIn(), folderRules(),
            node -> artifacts.forNode(node), (export, group, owner, diagramGroup) -> {
                events.add("slice");
                return archivePort.slice(export, group, owner, diagramGroup);
            },
            gateWithoutClients(versions, detection, matrix), new ArchiveFolderWriter(ops),
            limiter, new ArchiveExportAudit.Dependencies(audit,
                new MutableClock(Instant.parse("2026-11-06T10:00:00Z")), "default",
                () -> ids.remove(0)));
    }

    private Path home() {
        return tmp.resolve("home");
    }

    /**
     * The folder rules with the server's directories below the temporary directory (none of them
     * created unless a test does), the fake environment and a probe reporting {@code Z:} remote.
     */
    private TargetFolderRules folderRules() {
        return new TargetFolderRules(home(), tmp.resolve("workspace"), tmp.resolve("audit"),
            List.of(home().resolve(".config").resolve("inubit-mcp"), tmp.resolve("elsewhere")),
            environment, letter -> letter == 'Z', windows);
    }

    private static ArchiveExportRequest request(String target, Optional<String> owner,
        String diagramGroup, List<String> workflows, Path folder) {
        return new ArchiveExportRequest(target, owner, diagramGroup, workflows,
            folder.toString(), true, Optional.of("handover"), Optional.of("claude-code/2.0"));
    }

    private static ArchiveExportRequest request(String target, boolean acknowledged,
        String diagramGroup, List<String> workflows, Optional<String> reason, String folder) {
        return new ArchiveExportRequest(target, Optional.empty(), diagramGroup, workflows,
            folder, acknowledged, reason, Optional.empty());
    }

    private static List<String> tree(Path root) throws IOException {
        try (Stream<Path> all = Files.walk(root)) {
            return all.map(root::relativize).map(Path::toString).sorted().toList();
        }
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
        /** Which records fail to be written (the audit directory is full). */
        Predicate<AuditRecord> failing = record -> false;

        @Override
        public void append(AuditRecord record) {
            if (failing.test(record)) {
                events.add("audit failed " + record.outcome());
                throw new UncheckedIOException(new IOException("No space left on device"));
            }
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
