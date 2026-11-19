package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.BackupStore.AdministrationScope;
import de.dadecker.inubit.mcp.application.BackupStore.Manifest;
import de.dadecker.inubit.mcp.application.BackupStore.Manifest.ObjectKind;
import de.dadecker.inubit.mcp.application.BackupStore.Removed;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T053 (feature 022, research D-15, FR-018): the {@code ADMINISTRATION} backups taken before an
 * administration change — an export, scoped by node, object kind and name, private like every
 * backup and kept by the unchanged sweep (30 days, the newest per scope always). The tag list
 * backup is gone with tag removal, which is excluded after T055 (research D-18).
 */
class BackupStoreAdministrationTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");
    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final PrivateFileSystem FILES = PrivateFiles.forHost();
    private static final AdministrationScope ORDER =
        new AdministrationScope(DEV, ObjectKind.WORKFLOW, "Order");

    @TempDir
    Path home;

    private final MutableClock clock = new MutableClock(NOW);

    private Path root() {
        return BackupStore.defaultRoot(home, "acme");
    }

    private BackupStore store() {
        return new BackupStore(root(), clock, FILES);
    }

    private static byte[] export(String text) {
        return ("PK-fake-zip " + text).getBytes(StandardCharsets.UTF_8);
    }

    // --- manifest round trip --------------------------------------------------------------------

    @Test
    void anExportBackupOfAWorkflowIsReadBackWithItsScope() throws IOException {
        String id = UUID.randomUUID().toString();
        AdministrationScope scope = new AdministrationScope(DEV, ObjectKind.WORKFLOW, "Order");

        Manifest written = store().write(Manifest.administration(id, scope, NOW),
            List.of(export("history")));

        assertThat(written.zips()).containsExactly(id + "-1.zip");
        Manifest read = store().find(id).orElseThrow();
        assertThat(read).isEqualTo(written);
        assertThat(read.kind()).isEqualTo(Manifest.Kind.ADMINISTRATION);
        assertThat(read.auditId()).isEqualTo(id);
        assertThat(read.node()).isEqualTo(DEV);
        assertThat(read.objectKind()).contains(ObjectKind.WORKFLOW);
        assertThat(read.scope()).isEqualTo("Order");
        assertThat(read.outcome()).isEqualTo("PENDING");
        assertThat(store().exports(id)).containsExactly(export("history"));
        assertThat(Files.readString(root().resolve(id + ".json")))
            .contains("\"kind\": \"ADMINISTRATION\"", "\"objectKind\": \"WORKFLOW\"");
    }

    @Test
    void everyObjectKindSurvivesTheRoundTrip() {
        for (ObjectKind kind : ObjectKind.values()) {
            String id = UUID.randomUUID().toString();
            Manifest written = store().write(Manifest.administration(id,
                new AdministrationScope(DEV, kind, "name " + kind), NOW), List.of(export("x")));

            assertThat(store().find(id)).as(kind.name()).contains(written);
            assertThat(written.objectKind()).contains(kind);
        }
    }

    @Test
    void anUpdateKeepsTheObjectKind() {
        String id = UUID.randomUUID().toString();
        Manifest written = store().write(Manifest.administration(id, ORDER, NOW),
            List.of(export("history")));

        store().update(written.with("EXECUTED", Map.of()));

        Manifest read = store().find(id).orElseThrow();
        assertThat(read.outcome()).isEqualTo("EXECUTED");
        assertThat(read.objectKind()).contains(ObjectKind.WORKFLOW);
        assertThat(read.zips()).isEqualTo(written.zips());
    }

    @Test
    void anAdministrationManifestNeedsItsObjectKindAndOnlyIt() {
        String id = UUID.randomUUID().toString();

        assertThatIllegalArgumentException().isThrownBy(() -> new Manifest(id, DEV, "", "x",
            List.of(), List.of(), Map.of(), "PENDING", NOW, List.of(),
            Manifest.Kind.ADMINISTRATION, List.of(), List.of(), Optional.empty(),
            Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Manifest(id, DEV, "jdoe", "x",
            List.of(), List.of(), Map.of(), "PENDING", NOW, List.of(), Manifest.Kind.IMPORT,
            List.of(), List.of(), Optional.empty(), Optional.empty(),
            Optional.of(ObjectKind.MODULE)));
    }

    @Test
    void aManifestNamingAFileOtherThanAZipIsNotTrusted() throws IOException {
        String id = UUID.randomUUID().toString();
        store().write(Manifest.administration(id,
            new AdministrationScope(DEV, ObjectKind.MODULE, "Mapper"), NOW), List.of(export("m")));
        Path manifest = root().resolve(id + ".json");
        Files.writeString(manifest, Files.readString(manifest)
            .replace(id + "-1.zip", id + "-1.json"));

        assertThat(store().find(id)).isEmpty();
    }

    @Test
    void anImportManifestHasNoObjectKind() {
        String id = UUID.randomUUID().toString();
        store().write(new Manifest(id, DEV, "jdoe", "g", List.of(), List.of(), Map.of(),
            "PENDING", NOW, List.of()), List.of(export("i")));

        assertThat(store().find(id).orElseThrow().objectKind()).isEmpty();
    }

    // --- private files --------------------------------------------------------------------------

    @Test
    void theExportAndItsManifestArePrivate() throws IOException {
        String id = UUID.randomUUID().toString();

        store().write(Manifest.administration(id, ORDER, NOW), List.of(export("history")));

        try (Stream<Path> files = Files.list(root())) {
            assertThat(files.map(file -> file.getFileName().toString()))
                .containsExactlyInAnyOrder(id + "-1.zip", id + ".json");
        }
        if (POSIX) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root())))
                .isEqualTo("rwx------");
            try (Stream<Path> files = Files.list(root())) {
                assertThat(files.toList()).allSatisfy(file -> assertThat(
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .as(file.getFileName().toString()).isEqualTo("rw-------"));
            }
        }
    }

    @Test
    void anExportThatCannotBeMadePrivateIsNotWritten() throws IOException {
        BackupStore store = new BackupStore(root(), clock, PrivateFiles.failingFiles());
        String id = UUID.randomUUID().toString();

        assertThatThrownBy(() -> store.write(Manifest.administration(id, ORDER, NOW),
            List.of(export("history"))))
            .isInstanceOf(UncheckedIOException.class)
            .hasMessage("The backup cannot be written to " + root());
        try (Stream<Path> files = Files.list(root())) {
            assertThat(files).isEmpty();
        }
        assertThat(store().find(id)).isEmpty();
    }

    // --- retention ------------------------------------------------------------------------------

    @Test
    void retentionKeepsTheNewestPerNodeObjectKindAndName() {
        BackupStore store = store();
        String oldOrder = backup(store, DEV, ObjectKind.WORKFLOW, "Order", 40);
        String olderOrder = backup(store, DEV, ObjectKind.WORKFLOW, "Order", 45);
        String newestOrder = backup(store, DEV, ObjectKind.WORKFLOW, "Order", 35);
        String moduleOrder = backup(store, DEV, ObjectKind.MODULE, "Order", 50);
        String otherNode = backup(store, NodeId.parse("dev2/node1"), ObjectKind.WORKFLOW,
            "Order", 60);

        BackupStore.Sweep sweep = store.sweep();

        assertThat(sweep.skipped()).isZero();
        assertThat(sweep.removed()).as("oldest first").extracting(Removed::auditId)
            .containsExactly(olderOrder, oldOrder);
        for (String kept : List.of(newestOrder, moduleOrder, otherNode)) {
            assertThat(store.find(kept)).as(kept).isPresent();
        }
        assertThat(root().resolve(oldOrder + "-1.zip")).doesNotExist();
        assertThat(root().resolve(newestOrder + "-1.zip")).exists();
    }

    private String backup(BackupStore store, NodeId node, ObjectKind kind, String name,
        int daysAgo) {
        String id = UUID.randomUUID().toString();
        store.write(Manifest.administration(id, new AdministrationScope(node, kind, name),
            NOW.minus(Duration.ofDays(daysAgo))), List.of(export(id)));
        return id;
    }
}
