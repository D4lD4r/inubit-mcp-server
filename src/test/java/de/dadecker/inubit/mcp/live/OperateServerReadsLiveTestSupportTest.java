package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint.Method;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.CliRead;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.RestRead;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * Feature 022 T054 (read set of L-2, L-3): the offline parts of
 * {@link OperateServerReadsLiveTest} — the reads R1–R9 and their order, the opt-in and record
 * directory variables, the owner-only task directory, the 8.1 gate, the case files and the
 * summary lines. A plain unit test (not tagged {@code live}): fake environments, temporary
 * directories and fake runs; nothing is contacted.
 */
class OperateServerReadsLiveTestSupportTest {

    private static final NodeId NODE = NodeId.of("test", "node1");
    private static final String RAW_LINE = "mail.smtp.password=example-only-0001";

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    private final Map<String, String> env = new HashMap<>();

    // --- the read set ----------------------------------------------------------------------------

    @Test
    void theReadSetIsR1ToR9InTheApprovedOrderAndForms() {
        List<Read> reads = OperateServerReadsLiveTestSupport.READS;

        assertThat(reads).extracting(Read::id)
            .containsExactly("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9");
        assertThat(reads).extracting(Read::form).containsExactly("maintenance -s", "maintenance",
            "GET /ready", "trace", "trace --help", "debug", "debug --help", "GET /system/info",
            "GET /configurations");
        assertThat(reads).extracting(Read::stem).containsExactly("R1-maintenance-s",
            "R2-maintenance", "R3-get-ready", "R4-trace", "R5-trace-help", "R6-debug",
            "R7-debug-help", "R8-get-system-info", "R9-get-configurations");
    }

    @Test
    void theRestReadsAskAsTheProductionReadersDo() {
        assertThat(OperateServerReadsLiveTestSupport.READS).filteredOn(RestRead.class::isInstance)
            .map(RestRead.class::cast)
            .extracting(read -> read.endpoint().method(), RestRead::endpoint,
                RestRead::authenticated, RestRead::range)
            .containsExactly(tuple(Method.GET, RestEndpoint.READY, false, MediaRange.JSON),
                tuple(Method.GET, RestEndpoint.SYSTEM_INFO, true, MediaRange.XML),
                tuple(Method.GET, RestEndpoint.CONFIGURATIONS, true, MediaRange.JSON));
    }

    @Test
    void r1AndR2AreTheProductionFactoriesAndTheUnlistedReadsStayOutsideTheAllowlist() {
        List<CliRead> cli = OperateServerReadsLiveTestSupport.READS.stream()
            .filter(CliRead.class::isInstance).map(CliRead.class::cast).toList();

        assertThat(cli.get(0).command().commandLine())
            .isEqualTo(CliCommand.maintenanceState().commandLine());
        assertThat(cli.get(1).command().commandLine())
            .isEqualTo(CliCommand.maintenance().commandLine());
        for (String name : List.of("trace", "debug")) {
            assertThatThrownBy(() -> CliCommand.command(name).build()).as(name)
                .isInstanceOf(ToolErrorException.class);
            assertThatThrownBy(() -> CliCommand.command(name).flag("--help")).as(name + " --help")
                .isInstanceOf(ToolErrorException.class);
        }
    }

    // --- gating ----------------------------------------------------------------------------------

    @Test
    void withoutTheApprovalTheReadSetIsSkipped() {
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.requireApproval(env))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_T054_READS");
        env.put("INUBIT_LIVE_T054_READS", " ");
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.requireApproval(env))
            .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void anApprovalOtherThanApprovedFails() {
        env.put("INUBIT_LIVE_T054_READS", "yes");

        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.requireApproval(env))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_T054_READS must be exactly approved");
    }

    @Test
    void theApprovedValuePasses() {
        env.put("INUBIT_LIVE_T054_READS", "approved");

        assertThatCode(() -> OperateServerReadsLiveTestSupport.requireApproval(env))
            .doesNotThrowAnyException();
    }

    @Test
    void withoutTheRecordDirectoryTheReadSetIsSkipped() {
        Path repository = temp.resolve("repository");

        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.recordDirectory(env,
            repository))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_RECORD_DIR");
        env.put("INUBIT_LIVE_RECORD_DIR", " ");
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.recordDirectory(env,
            repository))
            .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void aRecordDirectoryInsideTheRepositoryOrAnyGitWorkingTreeIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path clone = Files.createDirectories(temp.resolve("clone/.git")).getParent();

        env.put("INUBIT_LIVE_RECORD_DIR", repository.resolve("records").toString());
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.recordDirectory(env,
            repository))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_RECORD_DIR")
            .hasMessageContaining("outside the repository");
        env.put("INUBIT_LIVE_RECORD_DIR", clone.resolve("records").toString());
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.recordDirectory(env,
            repository))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("git working tree");
        env.put("INUBIT_LIVE_RECORD_DIR", "records");
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.recordDirectory(env,
            repository))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("absolute");
    }

    @Test
    void aRecordDirectoryOutsideTheRepositoryIsUsedAsItsRealPath() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        env.put("INUBIT_LIVE_RECORD_DIR", temp.resolve("records").toString());

        assertThat(OperateServerReadsLiveTestSupport.recordDirectory(env, repository))
            .isEqualTo(temp.toRealPath().resolve("records"));
    }

    @Test
    void theTaskDirectoryIsCreatedOwnerOnlyAndNeverReused() throws IOException {
        Path records = temp.resolve("records/022");

        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            records, "T054");

        assertThat(directory).isEqualTo(records.resolve("T054"));
        assertOwnerOnly(records, "rwx------");
        assertOwnerOnly(directory, "rwx------");
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.taskDirectory(
            PrivateFiles.forHost(), records, "T054"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("T054 already exists")
            .hasMessageContaining("nothing was run");
    }

    @Test
    void anEarlierRecordingIsRefusedBeforeAnythingIsCreated() throws IOException {
        Path records = temp.resolve("records/022");

        assertThatCode(() -> OperateServerReadsLiveTestSupport.requireNoTaskDirectory(records,
            "T054"))
            .doesNotThrowAnyException();
        assertThat(records).doesNotExist();

        Files.createDirectories(records.resolve("T054"));
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.requireNoTaskDirectory(records,
            "T054"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("T054 already exists")
            .hasMessageContaining("nothing was run");
    }

    @Test
    void onlyANodeReportingInubit81Passes() {
        assertThat(OperateServerReadsLiveTestSupport.requireV81(NODE, "8.1.17").minor())
            .isEqualTo(1);
        for (String reported : List.of("9.0.10", "8.0.5", "7.1", "unknown")) {
            assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.requireV81(NODE, reported))
                .as(reported)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("runs on 8.1 only")
                .hasMessageContaining("nothing was run");
        }
    }

    // --- recording -------------------------------------------------------------------------------

    @Test
    void aStartCliReadIsWrittenAsOwnerOnlyCaseFilesAndItsLineHoldsNoOutput() throws IOException {
        Path directory = taskDirectory();
        CliRead read = cliRead("R5");

        String line = OperateServerReadsLiveTestSupport.recordCli(PrivateFiles.forHost(),
            directory, NODE, read, List.of(finished(RAW_LINE + "\n", "warning\n", 0)),
            Optional.empty());

        assertThat(fileNames(directory)).containsExactlyInAnyOrder("R5-trace-help.stdout",
            "R5-trace-help.stderr", "R5-trace-help.exit");
        assertThat(directory.resolve("R5-trace-help.stdout")).hasContent(RAW_LINE + "\n");
        assertThat(directory.resolve("R5-trace-help.stderr")).hasContent("warning\n");
        assertThat(directory.resolve("R5-trace-help.exit")).hasContent("0\n");
        assertAllOwnerOnlyFiles(directory);
        assertThat(line).isEqualTo("[live] T054 R5 trace --help on test/node1: exit 0, stdout "
            + (RAW_LINE.length() + 1) + " bytes, stderr 8 bytes");
    }

    @Test
    void aStartCliReadRefusedBeforeTheLaunchRecordsItsCodeOnly() throws IOException {
        Path directory = taskDirectory();

        String line = OperateServerReadsLiveTestSupport.recordCli(PrivateFiles.forHost(),
            directory, NODE, cliRead("R4"), List.of(), Optional.of(ErrorCode.CLI_UNAVAILABLE));

        assertThat(fileNames(directory)).containsExactly("R4-trace.error");
        assertThat(directory.resolve("R4-trace.error")).hasContent("CLI_UNAVAILABLE\n");
        assertAllOwnerOnlyFiles(directory);
        assertThat(line).isEqualTo(
            "[live] T054 R4 trace on test/node1: not started, error CLI_UNAVAILABLE");
    }

    @Test
    void aStartCliReadThatTimedOutKeepsItsOutputWithoutExitFile() throws IOException {
        Path directory = taskDirectory();
        Run run = new Run(false);
        run.stdout().writeBytes("partial".getBytes(StandardCharsets.UTF_8));

        String line = OperateServerReadsLiveTestSupport.recordCli(PrivateFiles.forHost(),
            directory, NODE, cliRead("R1"), List.of(run), Optional.of(ErrorCode.TIMEOUT));

        assertThat(fileNames(directory)).containsExactlyInAnyOrder("R1-maintenance-s.stdout",
            "R1-maintenance-s.stderr", "R1-maintenance-s.error");
        assertThat(line).endsWith(": exit none, stdout 7 bytes, stderr 0 bytes, error TIMEOUT");
    }

    @Test
    void aStartCliReadWithMoreThanOneRunFailsBeforeWriting() throws IOException {
        Path directory = taskDirectory();

        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.recordCli(
            PrivateFiles.forHost(), directory, NODE, cliRead("R2"),
            List.of(finished("a", "", 0), finished("b", "", 0)), Optional.empty()))
            .isInstanceOf(AssertionError.class)
            .hasMessage("R2 started 2 StartCLI runs, expected one");
        assertThat(fileNames(directory)).isEmpty();
    }

    @Test
    void aRestReadIsWrittenAsStatusAndRawBodyAndItsLineHoldsNoBody() throws IOException {
        Path directory = taskDirectory();
        byte[] body = ("{\"values\": [\"" + RAW_LINE + "\"]}").getBytes(StandardCharsets.UTF_8);

        String line = OperateServerReadsLiveTestSupport.recordRest(PrivateFiles.forHost(),
            directory, NODE, restRead("R9"),
            new RestResponse(200, Optional.of("application/json"), body));

        assertThat(fileNames(directory)).containsExactlyInAnyOrder(
            "R9-get-configurations.status", "R9-get-configurations.body");
        assertThat(directory.resolve("R9-get-configurations.status")).hasContent("200\n");
        assertThat(Files.readAllBytes(directory.resolve("R9-get-configurations.body")))
            .isEqualTo(body);
        assertAllOwnerOnlyFiles(directory);
        assertThat(line).isEqualTo("[live] T054 R9 GET /configurations on test/node1: status 200,"
            + " body " + body.length + " bytes");
    }

    @Test
    void aRestReadWithoutAnswerRecordsItsCodeOnly() throws IOException {
        Path directory = taskDirectory();

        String line = OperateServerReadsLiveTestSupport.recordRestFailure(PrivateFiles.forHost(),
            directory, NODE, restRead("R3"), ErrorCode.TIMEOUT);

        assertThat(fileNames(directory)).containsExactly("R3-get-ready.error");
        assertThat(directory.resolve("R3-get-ready.error")).hasContent("TIMEOUT\n");
        assertThat(line).isEqualTo("[live] T054 R3 GET /ready on test/node1: error TIMEOUT");
    }

    @Test
    void failedReadsAreNamedWithTheirCodesOnly() {
        Map<String, ErrorCode> failed = new LinkedHashMap<>();
        assertThatCode(() -> OperateServerReadsLiveTestSupport.assertNoFailure(failed))
            .doesNotThrowAnyException();

        failed.put("R4", ErrorCode.CLI_UNAVAILABLE);
        failed.put("R9", ErrorCode.TIMEOUT);
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.assertNoFailure(failed))
            .isInstanceOf(AssertionError.class)
            .hasMessage("T054 reads that ended in an error (each ran once, not retried):"
                + " R4 CLI_UNAVAILABLE, R9 TIMEOUT");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Path taskDirectory() throws IOException {
        return OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            temp.resolve("records"), OperateServerReadsLiveTestSupport.TASK_DIRECTORY);
    }

    private static CliRead cliRead(String id) {
        return OperateServerReadsLiveTestSupport.READS.stream()
            .filter(read -> read.id().equals(id)).map(CliRead.class::cast).findFirst()
            .orElseThrow();
    }

    private static RestRead restRead(String id) {
        return OperateServerReadsLiveTestSupport.READS.stream()
            .filter(read -> read.id().equals(id)).map(RestRead.class::cast).findFirst()
            .orElseThrow();
    }

    private static Run finished(String stdout, String stderr, int exit) {
        Run run = new Run(false);
        run.stdout().writeBytes(stdout.getBytes(StandardCharsets.UTF_8));
        run.stderr().writeBytes(stderr.getBytes(StandardCharsets.UTF_8));
        run.finished(exit);
        return run;
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).toList();
        }
    }

    private static void assertOwnerOnly(Path path, String posixPermissions) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
                .as(path.toString()).isEqualTo(posixPermissions);
        } else {
            assertThat(PrivateFiles.forHost().check(path)).as(path.toString()).isEmpty();
        }
    }

    private static void assertAllOwnerOnlyFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                assertOwnerOnly(file, "rw-------");
            }
        }
    }
}
