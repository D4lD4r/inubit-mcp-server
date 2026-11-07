package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.WindowsAcl;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * T019 (feature 005, FR-013, research D-8): the deployment ledger records per node and artifact
 * the fingerprint written by the last verified deployment of this server — owner-only, written
 * atomically, names and hashes only.
 */
class DeploymentLedgerTest {

    private static final NodeId INT1 = NodeId.parse("int/node1");
    private static final NodeId INT2 = NodeId.parse("int/node2");
    private static final String FP_A = "sha256:" + "a".repeat(64);
    private static final String FP_B = "sha256:" + "b".repeat(64);
    private static final String AUDIT = "00000000-0000-0000-0000-00000000d019";
    private static final Instant AT = Instant.parse("2026-10-07T10:00:00Z");
    /** Owner-only permissions are checked on POSIX; the Windows access list below (T025). */
    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final PrivateFileSystem FILES = PrivateFiles.forHost();

    @TempDir
    Path temp;

    private Path deployments() {
        return temp.resolve("profile").resolve("deployments");
    }

    private static DeploymentLedger.Entry entry(String fingerprint) {
        return new DeploymentLedger.Entry(fingerprint, AUDIT, "TAG-01", AT);
    }

    @Test
    void anEmptyLedgerHasNoEntries() {
        assertThat(new DeploymentLedger(deployments(), FILES).entries(INT1)).isEmpty();
    }

    @Test
    void recordsAreMergedPerNodeAndArtifact() {
        DeploymentLedger ledger = new DeploymentLedger(deployments(), FILES);

        ledger.record(INT1, Map.of("workflow:GRP-01/Workflow-0001", entry(FP_A),
            "module:Assign/Module-0003", entry(FP_A)));
        ledger.record(INT2, Map.of("workflow:GRP-01/Workflow-0001", entry(FP_B)));
        ledger.record(INT1, Map.of("workflow:GRP-01/Workflow-0001", entry(FP_B)));

        DeploymentLedger again = new DeploymentLedger(deployments(), FILES);
        assertThat(again.entries(INT1)).containsOnlyKeys("workflow:GRP-01/Workflow-0001",
            "module:Assign/Module-0003");
        assertThat(again.entries(INT1).get("workflow:GRP-01/Workflow-0001").fingerprint())
            .isEqualTo(FP_B);
        assertThat(again.entries(INT1).get("module:Assign/Module-0003")).isEqualTo(entry(FP_A));
        assertThat(again.entries(INT2)).hasSize(1);
    }

    @Test
    void theLedgerIsOwnerOnlyAndWrittenAtomically() throws IOException {
        new DeploymentLedger(deployments(), FILES).record(INT1,
            Map.of("repository:/Root/jdoe/x.xsd", entry(FP_A)));

        Path file = deployments().resolve("int.ledger.json");
        if (POSIX) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
                deployments()))).isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        }
        try (Stream<Path> files = Files.list(deployments())) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    void theLedgerHoldsNamesAndHashesOnly() throws IOException {
        new DeploymentLedger(deployments(), FILES).record(INT1,
            Map.of("workflow:GRP-01/Workflow-0001", entry(FP_A)));

        String json = Files.readString(deployments().resolve("int.ledger.json"));
        assertThat(json).contains("\"int/node1\"", "\"workflow:GRP-01/Workflow-0001\"", FP_A,
            AUDIT, "\"TAG-01\"", "2026-10-07T10:00:00Z");
        assertThat(json.replaceAll("\"[^\"]*\"", "\"\"").replaceAll("\\s", ""))
            .isEqualTo("{\"\":{\"\":{\"\":\"\",\"\":\"\",\"\":\"\",\"\":\"\"}}}");
        // only a fingerprint can be recorded, never content
        assertThatThrownBy(() -> entry("<Workflow>secret</Workflow>"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeploymentLedger.Entry(FP_A, "not-an-id", "TAG-01", AT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnreadableLedgerIsReportedNotOverwritten() throws IOException {
        Files.createDirectories(deployments());
        Files.writeString(deployments().resolve("int.ledger.json"), "{broken");
        DeploymentLedger ledger = new DeploymentLedger(deployments(), FILES);

        assertThatThrownBy(() -> ledger.entries(INT1)).isInstanceOfSatisfying(
            ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).contains("int.ledger.json");
            });
        assertThatThrownBy(() -> ledger.record(INT1, Map.of("a", entry(FP_A))))
            .isInstanceOf(ToolErrorException.class);
        assertThat(Files.readString(deployments().resolve("int.ledger.json")))
            .isEqualTo("{broken");
    }

    @Test
    void eachTargetGroupHasItsOwnLedgerGuardedByItsDeployLock() {
        // stage 2 review m3: deployments into different groups may run in parallel
        DeploymentLedger ledger = new DeploymentLedger(deployments(), FILES);

        ledger.record(INT1, Map.of("a", entry(FP_A)));
        ledger.record(NodeId.parse("qa/node1"), Map.of("a", entry(FP_B)));

        assertThat(deployments().resolve("int.ledger.json")).isRegularFile();
        assertThat(deployments().resolve("qa.ledger.json")).isRegularFile();
        assertThat(ledger.entries(INT1).get("a").fingerprint()).isEqualTo(FP_A);
        assertThat(ledger.entries(NodeId.parse("qa/node1")).get("a").fingerprint())
            .isEqualTo(FP_B);
    }

    // --- feature 013 T025 (FR-007): created through the PrivateFileSystem port -------------

    @Test
    void aDirectoryThatCannotBeMadePrivateFailsTheRecordAndIsNotCreated() {
        Path file = deployments().resolve("int.ledger.json");

        assertThatThrownBy(() -> new DeploymentLedger(deployments(), PrivateFiles.failing())
            .record(INT1, Map.of("a", entry(FP_A)))).isInstanceOfSatisfying(
                ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                    assertThat(e.error().message()).isEqualTo("The deployment ledger " + file
                        + " cannot be written (FileSystemException)");
                });
        assertThat(temp.resolve("profile")).doesNotExist();
    }

    @Test
    void aLedgerThatCannotBeMadePrivateIsNotWrittenAndTheOldOneIsKept() throws IOException {
        Path file = deployments().resolve("int.ledger.json");
        DeploymentLedger failing = new DeploymentLedger(deployments(),
            PrivateFiles.failingFiles());

        assertThatThrownBy(() -> failing.record(INT1, Map.of("a", entry(FP_A))))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().message()).isEqualTo("The deployment ledger " + file
                    + " cannot be written (FileSystemException)"));
        try (Stream<Path> files = Files.list(deployments())) {
            assertThat(files).isEmpty();
        }

        new DeploymentLedger(deployments(), FILES).record(INT1, Map.of("a", entry(FP_A)));
        String written = Files.readString(file);
        assertThatThrownBy(() -> failing.record(INT1, Map.of("a", entry(FP_B))))
            .isInstanceOf(ToolErrorException.class);
        assertThat(Files.readString(file)).isEqualTo(written);
        try (Stream<Path> files = Files.list(deployments())) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheDirectoryAndTheLedgerArePrivate() throws Exception {
        DeploymentLedger ledger = new DeploymentLedger(deployments(), FILES);

        ledger.record(INT1, Map.of("a", entry(FP_A)));
        ledger.record(INT2, Map.of("a", entry(FP_B))); // replaces the file

        WindowsAcl.assertPrivate(temp.resolve("profile"), WindowsAcl.DIRECTORY_INHERITANCE);
        WindowsAcl.assertPrivate(deployments(), WindowsAcl.DIRECTORY_INHERITANCE);
        // a new private temporary file moved into place keeps its own access list
        WindowsAcl.assertPrivate(deployments().resolve("int.ledger.json"), "None");
    }
}
