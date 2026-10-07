package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T025 (feature 005, US4, FR-023, research D-10): the package of a package-only node — the
 * import archives of D-7 in their order, {@code diff.txt}, {@code warnings.txt} and a
 * {@code README.md} with the StartCLI commands in that order, readable only by the current user;
 * packages follow the backup retention (30 days, the newest per target kept).
 */
class PackageWriterTest {

    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final byte[] ZIP = {'P', 'K', 3, 4};

    @TempDir
    Path temp;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));

    private PackageWriter writer() {
        return new PackageWriter(temp.resolve("packages"), clock);
    }

    private static PackageWriter.Content content(UUID auditId, NodeId node) {
        return new PackageWriter.Content(auditId, node, "TAG-01", new GroupId("int"), "jdoe",
            List.of(new PackageWriter.Archive(Optional.empty(), Optional.empty(), ZIP),
                new PackageWriter.Archive(Optional.of(ImportPort.Mode.MODULE), Optional.empty(),
                    ZIP),
                new PackageWriter.Archive(Optional.of(ImportPort.Mode.WORKFLOW_INACTIVE),
                    Optional.of("GRP 01"), ZIP),
                new PackageWriter.Archive(Optional.of(ImportPort.Mode.WORKFLOW_ACTIVE),
                    Optional.of("GRP 01"), ZIP)),
            "--- Module-0003\n+++ Module-0003\n", List.of("SHARED_MODULE Module-0003: used by"
                + " Workflow-0009"), List.of("Workflow-0004 (excluded)"));
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void aPackageHoldsTheArchivesInImportOrderAndTheReadmeCommands() throws IOException {
        UUID auditId = UUID.randomUUID();

        Path dir = writer().write(content(auditId, PROD));

        assertThat(dir).isEqualTo(temp.resolve("packages").resolve(auditId.toString())
            .resolve("prod-node1"));
        List<String> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(files).containsExactly("1-repository.zip", "2-modules.zip",
            "3-GRP_01-inactive.zip", "4-GRP_01-active.zip", "README.md", "diff.txt",
            "warnings.txt");
        assertThat(Files.readAllBytes(dir.resolve("2-modules.zip"))).isEqualTo(ZIP);
        String readme = Files.readString(dir.resolve("README.md"), StandardCharsets.UTF_8);
        String repository = "import --importFile '" + dir.resolve("1-repository.zip")
            + "' --importRepositoryPath '/Root/jdoe'";
        String modules = "import --importFile '" + dir.resolve("2-modules.zip")
            + "' --importModule --importUser 'jdoe' --returnProtocol";
        String inactive = "import --importFile '" + dir.resolve("3-GRP_01-inactive.zip")
            + "' --importWorkflow --importWorkflowInactive --importUser 'jdoe' --returnProtocol";
        String active = "import --importFile '" + dir.resolve("4-GRP_01-active.zip")
            + "' --importWorkflow --importWorkflowActive --importUser 'jdoe' --returnProtocol";
        assertThat(readme).contains("TAG-01", "int", "prod/node1", auditId.toString(),
            "--execCommand \"" + repository + "\"", modules, inactive, active,
            "the node's own secret values", "diff.txt", "warnings.txt");
        assertThat(readme.indexOf(repository)).isLessThan(readme.indexOf(modules));
        assertThat(readme.indexOf(modules)).isLessThan(readme.indexOf(inactive));
        assertThat(readme.indexOf(inactive)).isLessThan(readme.indexOf(active));
        assertThat(Files.readString(dir.resolve("diff.txt"))).contains("Module-0003");
        assertThat(Files.readString(dir.resolve("warnings.txt"))).contains("SHARED_MODULE",
            "Workflow-0004 (excluded)");
        assertThat(permissions(temp.resolve("packages"))).isEqualTo("rwx------");
        assertThat(permissions(dir.getParent())).isEqualTo("rwx------");
        assertThat(permissions(dir)).isEqualTo("rwx------");
        for (String file : files) {
            assertThat(permissions(dir.resolve(file))).as(file).isEqualTo("rw-------");
        }
    }

    @Test
    void aNodeOfTheSameCallGoesIntoTheSameAuditDirectory() throws IOException {
        UUID auditId = UUID.randomUUID();
        PackageWriter writer = writer();

        Path first = writer.write(content(auditId, PROD));
        Path second = writer.write(content(auditId, NodeId.parse("prod/node2")));

        assertThat(second.getParent()).isEqualTo(first.getParent());
        assertThat(second.getFileName()).hasToString("prod-node2");
    }

    @Test
    void packagesOlderThanTheRetentionAreRemovedExceptTheNewestPerTarget() throws IOException {
        PackageWriter writer = writer();
        UUID oldest = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        writer.write(content(oldest, PROD));
        clock.advance(Duration.ofDays(1));
        writer.write(content(older, PROD));
        Path foreign = Files.createDirectories(temp.resolve("packages").resolve("notes"));
        clock.advance(PackageWriter.RETENTION.plusDays(1));

        List<UUID> removed = writer.sweep();

        assertThat(removed).containsExactly(oldest);
        assertThat(temp.resolve("packages").resolve(oldest.toString())).doesNotExist();
        assertThat(temp.resolve("packages").resolve(older.toString())).isDirectory();
        assertThat(foreign).isDirectory();

        UUID newest = UUID.randomUUID();
        writer.write(content(newest, PROD)); // sweeps first: older is no longer the newest

        assertThat(temp.resolve("packages").resolve(older.toString())).doesNotExist();
        assertThat(temp.resolve("packages").resolve(newest.toString())).isDirectory();
    }

    @Test
    void aPackageThatCannotBeWrittenCompletelyIsRemovedWithItsSecrets() {
        // final review m1: the archives hold the node's secret values
        UUID auditId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger written =
            new java.util.concurrent.atomic.AtomicInteger();
        PackageWriter writer = new PackageWriter(temp.resolve("packages"), clock,
            (file, content) -> {
                if (written.incrementAndGet() == 3) { // the marker, then the first archive
                    throw new IOException("disk full");
                }
                PackageWriter.writeOwnerOnly(file, content);
            });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.write(content(auditId,
            PROD))).isInstanceOf(java.io.UncheckedIOException.class);

        assertThat(temp.resolve("packages").resolve(auditId.toString()).resolve("prod-node1"))
            .doesNotExist();
    }

    @Test
    void theReadmeAsksForTheNodesOwnStartCliOptions() throws IOException {
        // final review n2
        Path dir = writer().write(content(UUID.randomUUID(), PROD));

        assertThat(Files.readString(dir.resolve("README.md"))).contains(
            "as configured for this node", "trust store", "host name verification");
    }
}
