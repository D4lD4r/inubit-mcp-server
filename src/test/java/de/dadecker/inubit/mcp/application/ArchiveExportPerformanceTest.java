package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.V81WorkflowArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkflowArchiveFixtures;
import de.dadecker.inubit.mcp.domain.model.ArchiveExportResult;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 015 T010 (SC-002, Q27): ten workflows are sliced, built and written in at most ten
 * seconds when StartCLI answers at once.
 */
class ArchiveExportPerformanceTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final Duration BUDGET = Duration.ofSeconds(10);

    @TempDir
    Path tmp;

    @Test
    void tenWorkflowsAreBuiltAndWrittenWithinTenSeconds() throws Exception {
        byte[] export = WorkflowArchiveFixtures.tenWorkflows();
        ArtifactPort artifacts = new ArtifactPort() {
            @Override
            public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
                return export;
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
        ArchiveExportService service = new ArchiveExportService(
            new TargetResolver(List.of(DEV1)), node -> Optional.of(WorkflowArchiveFixtures.OWNER),
            node -> Optional.of("jdoe"), node -> artifacts, new V81WorkflowArchives(),
            FakeVersionDetection.pinned81(), new ArchiveFolderWriter(),
            ResultLimiter.withDefaults(),
            new ArchiveExportAudit.Dependencies(record -> { },
                new MutableClock(Instant.parse("2026-11-06T10:00:00Z")), "default",
                UUID::randomUUID));
        Path folder = tmp.resolve("archives");

        long start = System.nanoTime();
        ArchiveExportResult result = service.export(new ArchiveExportRequest("dev/node1",
            Optional.empty(), "GRP-02", List.of(), folder.toString(), true, Optional.empty(),
            Optional.empty()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.archives()).hasSize(10);
        assertThat(result.failed()).isEmpty();
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.filter(file -> file.toString().endsWith(".diagram.zip")))
                .hasSize(10);
        }
        assertThat(elapsed).isLessThanOrEqualTo(BUDGET);
    }
}
