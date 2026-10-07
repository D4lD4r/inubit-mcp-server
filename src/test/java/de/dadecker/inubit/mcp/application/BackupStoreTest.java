package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import de.dadecker.inubit.mcp.application.BackupStore.Manifest;
import de.dadecker.inubit.mcp.application.BackupStore.Removed;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * T008 (feature 004, research D-13, D-25, FR-010): backups outside the workspace history — one
 * raw ZIP per scope export plus a manifest without secrets, owner-only, found by audit id, and
 * removed after 30 days unless they are the newest of their node, owner and scope.
 */
class BackupStoreTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final String SECRET = "S3cr3t-pw-in-backup";

    @TempDir
    Path home;

    private final MutableClock clock = new MutableClock(NOW);

    private Path root() {
        return BackupStore.defaultRoot(home, "acme");
    }

    private BackupStore store() {
        return new BackupStore(root(), clock);
    }

    private static Manifest manifest(String auditId, NodeId node, String owner, String scope,
        Instant takenAt) {
        return new Manifest(auditId, node, owner, scope,
            List.of("Workflow-0001", "Module-0001"), List.of("Module-0009"),
            Map.of("Workflow-0001", "sha256:aa", "Module-0001", "sha256:bb"), "PENDING",
            takenAt, List.of());
    }

    private static byte[] export(String text) {
        return ("PK-fake-zip " + text).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theDefaultRootIsBelowTheProfileDirectory() {
        assertThat(BackupStore.defaultRoot(home, "acme"))
            .isEqualTo(home.resolve(".inubit-mcp/acme/backups"));
    }

    @Test
    void aBackupIsOneZipPerExportPlusTheManifestOwnerOnly() throws IOException {
        String id = UUID.randomUUID().toString();

        Manifest written = store().write(manifest(id, DEV, "jdoe", "diagram group GRP-01", NOW),
            List.of(export("first " + SECRET), export("second")));

        assertThat(written.zips()).containsExactly(id + "-1.zip", id + "-2.zip");
        assertThat(Files.readAllBytes(root().resolve(id + "-1.zip")))
            .isEqualTo(export("first " + SECRET));
        assertThat(Files.readAllBytes(root().resolve(id + "-2.zip"))).isEqualTo(export("second"));
        assertThat(root().resolve(id + ".json")).isRegularFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root())))
            .isEqualTo("rwx------");
        try (Stream<Path> files = Files.list(root())) {
            assertThat(files.toList()).hasSize(3).allSatisfy(file -> assertThat(
                PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .as(file.getFileName().toString()).isEqualTo("rw-------"));
        }
    }

    @Test
    void anExistingDirectoryWithWiderPermissionsIsTightened() throws IOException {
        Files.createDirectories(root(), PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwxr-xr-x")));

        store().write(manifest(UUID.randomUUID().toString(), DEV, "jdoe", "g", NOW),
            List.of(export("x")));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root())))
            .isEqualTo("rwx------");
    }

    @Test
    void theManifestHoldsNamesAndHashesButNoSecret() throws IOException {
        String id = UUID.randomUUID().toString();

        store().write(manifest(id, DEV, "jdoe", "diagram group GRP-01", NOW),
            List.of(export(SECRET)));

        String json = Files.readString(root().resolve(id + ".json"));
        assertThat(json).doesNotContain(SECRET).doesNotContain("PK-fake-zip")
            .contains("\"auditId\"", id, "\"node\"", "dev/node1", "\"owner\"", "jdoe",
                "\"scope\"", "diagram group GRP-01", "\"changeSet\"", "Workflow-0001",
                "\"created\"", "Module-0009", "\"intendedState\"", "sha256:aa", "\"outcome\"",
                "PENDING", "\"takenAt\"", "2026-10-06T08:00:00Z", "\"zips\"", id + "-1.zip");
    }

    @Test
    void findReadsTheManifestBack() {
        String id = UUID.randomUUID().toString();
        Manifest odd = new Manifest(id, DEV, "jdoe", "module \"Größe\\1\"\n/x",
            List.of("A \"quoted\" name", "Tab\there"), List.of(),
            Map.of("A \"quoted\" name", "sha256:01"), "PENDING", NOW, List.of());

        Manifest written = store().write(odd, List.of(export("x")));

        assertThat(store().find(id)).contains(written);
        assertThat(written.zips()).containsExactly(id + "-1.zip");
    }

    @Test
    void anUnknownOrMalformedReferenceIsNotFound() {
        assertThat(store().find(UUID.randomUUID().toString())).isEmpty();
        for (String ref : List.of("../../etc/passwd", "", "x.json", "ABC")) {
            assertThat(store().find(ref)).as(ref).isEmpty();
        }
        assertThat(root()).as("nothing is created by a lookup").doesNotExist();
    }

    @Test
    void theExportsAreReadBackInOrder() {
        String id = UUID.randomUUID().toString();
        store().write(manifest(id, DEV, "jdoe", "g", NOW), List.of(export("1"), export("2")));

        assertThat(store().exports(id)).containsExactly(export("1"), export("2"));
    }

    @Test
    void updateRewritesTheManifestAndKeepsTheZips() {
        String id = UUID.randomUUID().toString();
        Manifest written = store().write(manifest(id, DEV, "jdoe", "g", NOW),
            List.of(export("1")));
        Manifest executed = new Manifest(id, DEV, "jdoe", "g", written.changeSet(),
            List.of("Module-0009", "Module-0010"), Map.of("Workflow-0001", "sha256:cc"),
            "EXECUTED", NOW, written.zips());

        store().update(executed);

        assertThat(store().find(id)).contains(executed);
        assertThat(store().exports(id)).containsExactly(export("1"));
    }

    @Test
    void anAuditIdIsWrittenOnlyOnceAndMustBeAUuid() {
        String id = UUID.randomUUID().toString();
        store().write(manifest(id, DEV, "jdoe", "g", NOW), List.of(export("1")));

        assertThatIllegalStateException().isThrownBy(() -> store().write(
            manifest(id, DEV, "jdoe", "g", NOW), List.of(export("2"))));
        assertThatIllegalArgumentException().isThrownBy(() -> store().write(
            manifest("../x", DEV, "jdoe", "g", NOW), List.of(export("2"))));
        assertThat(store().exports(id)).containsExactly(export("1"));
    }

    @Test
    void retentionRemovesBackupsOlderThan30DaysUnlessNewestOfTheirScope() {
        BackupStore store = store();
        String a = backup(store, "jdoe", "G1", 40);
        String b = backup(store, "jdoe", "G1", 35);
        String c = backup(store, "jdoe", "G1", 1);
        String d = backup(store, "jdoe", "G2", 40);
        String e = backup(store, "OWNERS", "G1", 31);
        String f = backup(store, "OWNERS", "G1", 32);
        String g = backup(store, "jdoe", "G3", 29);
        String h = backup(store, "jdoe", "G3", 30);

        BackupStore.Sweep sweep = store.sweep();
        List<Removed> removed = sweep.removed();

        assertThat(sweep.skipped()).isZero();
        assertThat(removed).as("oldest first").extracting(Removed::auditId)
            .containsExactly(a, b, f);
        assertThat(removed).filteredOn(r -> r.auditId().equals(a)).singleElement()
            .satisfies(r -> {
                assertThat(r.node()).isEqualTo(DEV);
                assertThat(r.owner()).isEqualTo("jdoe");
                assertThat(r.scope()).isEqualTo("G1");
                assertThat(r.takenAt()).isEqualTo(NOW.minus(Duration.ofDays(40)));
            });
        for (String kept : List.of(c, d, e, g, h)) {
            assertThat(store.find(kept)).as(kept).isPresent();
        }
        for (String gone : List.of(a, b, f)) {
            assertThat(store.find(gone)).as(gone).isEmpty();
            assertThat(root().resolve(gone + "-1.zip")).doesNotExist();
        }
        assertThat(store.sweep().removed()).isEmpty();
    }

    @Test
    void retentionKeysIncludeTheNode() {
        BackupStore store = store();
        String old = backup(store, DEV, "jdoe", "G1", 40);
        String otherNode = backup(store, NodeId.parse("dev2/node1"), "jdoe", "G1", 1);

        assertThat(store.sweep().removed()).isEmpty();
        assertThat(store.find(old)).isPresent();
        assertThat(store.find(otherNode)).isPresent();
    }

    @Test
    void aManifestNamingFilesOutsideItsBackupIsNeverTrusted() throws IOException {
        // review I2: a tampered manifest must not make find, exports or the sweep touch other
        // files
        BackupStore store = store();
        String valid = backup(store, "jdoe", "G1", 1);
        Path victim = home.resolve("victim.zip");
        Files.writeString(victim, "keep me");
        String tampered = UUID.randomUUID().toString();
        String json = Files.readString(root().resolve(valid + ".json"))
            .replace(valid + "-1.zip", "../../victim.zip").replace(valid, tampered)
            .replace("2026-10-05T08:00:00Z", "2026-08-01T08:00:00Z");
        Files.writeString(root().resolve(tampered + ".json"), json);

        BackupStore.Sweep sweep = store.sweep();

        assertThat(store.find(tampered)).isEmpty();
        assertThat(sweep.removed()).isEmpty();
        assertThat(sweep.skipped()).isEqualTo(1);
        assertThat(victim).hasContent("keep me");
        assertThat(store.find(valid)).isPresent();
    }

    @Test
    void aManifestWhoseIdDiffersFromItsFileNameIsSkipped() throws IOException {
        BackupStore store = store();
        String old = backup(store, "jdoe", "G1", 40);
        backup(store, "jdoe", "G1", 1);
        String other = UUID.randomUUID().toString();
        Files.move(root().resolve(old + ".json"), root().resolve(other + ".json"));

        BackupStore.Sweep sweep = store.sweep();

        assertThat(sweep.removed()).isEmpty();
        assertThat(sweep.skipped()).isEqualTo(1);
        assertThat(store.find(other)).isEmpty();
        assertThat(root().resolve(old + "-1.zip")).exists();
    }

    @Test
    void aWriteThatFailsPartWayLeavesNoZipBehind() throws IOException {
        // review M3
        String id = UUID.randomUUID().toString();
        Files.createDirectories(root());
        Files.writeString(root().resolve(id + "-2.zip"), "in the way");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store().write(
            manifest(id, DEV, "jdoe", "g", NOW), List.of(export("1"), export("2"))))
            .isInstanceOf(IllegalStateException.class);

        assertThat(root().resolve(id + "-1.zip")).doesNotExist();
        assertThat(root().resolve(id + ".json")).doesNotExist();
        assertThat(store().find(id)).isEmpty();
    }

    @Test
    void retentionWithoutBackupDirectoryRemovesNothing() {
        assertThat(store().sweep().removed()).isEmpty();
        assertThat(root()).doesNotExist();
    }

    private String backup(BackupStore store, String owner, String scope, int daysAgo) {
        return backup(store, DEV, owner, scope, daysAgo);
    }

    private String backup(BackupStore store, NodeId node, String owner, String scope,
        int daysAgo) {
        String id = UUID.randomUUID().toString();
        store.write(manifest(id, node, owner, scope, NOW.minus(Duration.ofDays(daysAgo))),
            List.of(export(id)));
        return id;
    }

    // --- feature 005 (T021, research D-7, D-13): backups of deployments ----------------------

    @Test
    void aDeploymentBackupNamesItsKindGroupsRepositoryPathsTagAndSource() {
        String id = "00000000-0000-0000-0000-00000000d021";
        Manifest deployment = new Manifest(id, NodeId.parse("int/node1"), "jdoe", "deploy TAG-01",
            List.of("Module-0005"), List.of(), Map.of(), "PENDING", NOW, List.of(),
            Manifest.Kind.DEPLOYMENT, List.of("GRP-01"), List.of("/Root/jdoe/xsd/release.xsl"),
            Optional.of("TAG-01"), Optional.of("dev"));

        store().write(deployment, List.of(export("a")));

        Manifest read = store().find(id).orElseThrow();
        assertThat(read.kind()).isEqualTo(Manifest.Kind.DEPLOYMENT);
        assertThat(read.groups()).containsExactly("GRP-01");
        assertThat(read.repositoryPaths()).containsExactly("/Root/jdoe/xsd/release.xsl");
        assertThat(read.tag()).contains("TAG-01");
        assertThat(read.source()).contains("dev");
    }

    @Test
    void aManifestOfFeature004IsReadAsAnImport() throws IOException {
        String id = "00000000-0000-0000-0000-00000000d004";
        store().write(manifest(id, DEV, "jdoe", "g", NOW), List.of(export("a")));
        Path file = root().resolve(id + ".json");
        String json = Files.readString(file);
        assertThat(json).contains("\"kind\": \"IMPORT\"");
        Files.writeString(file, json.replaceAll("(?m)^  \"kind\": \"IMPORT\",\n", ""));

        Manifest read = store().find(id).orElseThrow();

        assertThat(read.kind()).isEqualTo(Manifest.Kind.IMPORT);
        assertThat(read.groups()).isEmpty();
        assertThat(read.tag()).isEmpty();
    }
}

