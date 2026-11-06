package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.V81WorkflowArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkflowArchiveFixtures;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.FailedArchive;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult.WrittenArchive;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort.SlicedExport;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 015 T020 (research R-11, R-13 step 6, FR-009, Q22, SC-003): two concurrent {@code
 * export_archives} calls into the same folder with overlapping workflows never replace a file.
 *
 * <p>The race is forced, not timed: each call's {@link HookedFileOps} holds its first placement
 * (the hard link that gives an archive its name) on a shared {@code CountDownLatch(2)}, so both
 * calls have passed the collision check against the still-empty folder before either places a
 * file. A latch in the fake {@code ArtifactPort} would only align the exports, not the checks.
 */
class ArchiveExportConcurrencyTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final String OWNER = WorkflowArchiveFixtures.OWNER;
    private static final String GRP_B = "GRP-02";
    private static final String SHARED = "Workflow-0005";
    private static final long TIMEOUT_SECONDS = 30;

    @TempDir(factory = GitFreeTempDir.class)
    Path tmp;

    @Test
    void q22TwoConcurrentCallsIntoOneFolderNeverReplaceAFileAndTheLoserNamesIt()
        throws Exception {
        Path folder = Files.createDirectory(tmp.resolve("archives"));
        CountDownLatch placing = new CountDownLatch(2);
        Call first = new Call(UUID.fromString("00000000-0000-4000-8000-000000000001"), placing);
        Call second = new Call(UUID.fromString("00000000-0000-4000-8000-000000000002"), placing);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ArchiveExportResult> a = pool.submit(() -> first.export(folder,
                List.of(SHARED, "Workflow-0004")));
            Future<ArchiveExportResult> b = pool.submit(() -> second.export(folder,
                List.of(SHARED, "Workflow-0006")));
            first.result = a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            second.result = b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(first.held).as("first call held at its first placement").isTrue();
        assertThat(second.held).as("second call held at its first placement").isTrue();
        List<Call> losers = Stream.of(first, second)
            .filter(call -> !call.result.failed().isEmpty()).toList();
        List<Call> winners = Stream.of(first, second)
            .filter(call -> call.result.failed().isEmpty()).toList();
        assertThat(losers).as("exactly one call loses the race").hasSize(1);
        assertThat(winners).hasSize(1);
        Call loser = losers.get(0);
        Call winner = winners.get(0);

        FailedArchive failed = loser.result.failed().get(0);
        assertThat(failed.workflow()).isEqualTo(SHARED);
        assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(failed.message()).contains("Workflow-0005.diagram.zip", "appeared",
            "left unchanged");
        assertThat(loser.result.archives()).isEmpty();
        assertThat(loser.result.notWritten()).hasSize(1);
        assertThat(loser.outcomes()).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.PARTIALLY_COMPLETED);

        assertThat(winner.result.archives()).extracting(WrittenArchive::workflow).hasSize(2)
            .startsWith(SHARED);
        assertThat(winner.result.notWritten()).isEmpty();
        assertThat(winner.outcomes()).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.EXECUTED);

        List<String> names = files(folder);
        assertThat(names).doesNotHaveDuplicates().hasSize(2)
            .contains("Workflow-0005.diagram.zip")
            .allMatch(name -> name.endsWith(".diagram.zip"));
        SlicedExport expected = new V81WorkflowArchives().slice(WorkflowArchiveFixtures.grpB(),
            new GroupId("dev"), OWNER, GRP_B);
        for (WrittenArchive archive : winner.result.archives()) {
            assertThat(Files.readAllBytes(folder.resolve(archive.file())))
                .as(archive.file()).isEqualTo(expected.archive(archive.workflow()));
        }
    }

    private static List<String> files(Path folder) throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** One call: its own service, audit log, file operations and audit id. */
    private final class Call {

        final List<AuditRecord> records = new CopyOnWriteArrayList<>();
        final HookedFileOps ops = new HookedFileOps();
        final ArchiveExportService service;
        volatile boolean held;
        ArchiveExportResult result;

        Call(UUID auditId, CountDownLatch placing) {
            AtomicBoolean first = new AtomicBoolean(true);
            ops.beforeLink = (path, call) -> {
                if (first.getAndSet(false)) {
                    placing.countDown();
                    try {
                        held = placing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
            };
            NodeVersions versions = new NodeVersions(
                new MutableClock(Instant.parse("2026-11-06T08:00:00Z")),
                node -> VersionSetting.V8_1, node -> Duration.ofMinutes(10));
            List<UUID> ids = new CopyOnWriteArrayList<>(List.of(auditId));
            service = new ArchiveExportService(new TargetResolver(List.of(DEV1)),
                node -> Optional.of(OWNER), node -> Optional.of("jdoe"), node -> false,
                node -> false, new TargetFolderRules(tmp.resolve("home"),
                    tmp.resolve("workspace"), tmp.resolve("audit"), List.of(), Map.of(),
                    letter -> false, false),
                node -> artifacts(), new V81WorkflowArchives(),
                gateWithoutClients(versions, new FakeVersionDetection(versions),
                    CapabilityMatrix.production()),
                new ArchiveFolderWriter(ops), ResultLimiter.withDefaults(),
                new ArchiveExportAudit.Dependencies(records::add,
                    new MutableClock(Instant.parse("2026-11-06T10:00:00Z")), "default",
                    () -> ids.remove(0)));
        }

        ArchiveExportResult export(Path folder, List<String> workflows) {
            return service.export(new ArchiveExportRequest("dev/node1", Optional.empty(), GRP_B,
                workflows, folder.toString(), true, Optional.of("handover"), Optional.empty()));
        }

        List<AuditOutcome> outcomes() {
            return records.stream().map(AuditRecord::outcome).toList();
        }
    }

    /** StartCLI: the recorded export of {@code GRP-02}. */
    private static ArtifactPort artifacts() {
        return new ArtifactPort() {
            @Override
            public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
                return WorkflowArchiveFixtures.grpB();
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
