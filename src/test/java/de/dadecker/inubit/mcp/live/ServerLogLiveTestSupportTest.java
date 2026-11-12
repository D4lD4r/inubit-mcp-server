package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.ProcessRecorder;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 021 T054 (research D-24): the offline parts of {@link ServerLogLiveTest} — the
 * parsing of {@code INUBIT_LIVE_LOG_FOLDER} and {@code INUBIT_LIVE_RECORD_DIR}, the owner-only
 * recording of the StartCLI and helper output, the checks of the result and the downloaded
 * file, the log check, the summary line and the configuration with the unreachable port. A plain
 * unit test (not tagged {@code live}): fake environments, temporary directories and fake
 * processes; nothing is contacted.
 */
class ServerLogLiveTestSupportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final NodeId NODE = NodeId.of("test", "node1");
    private static final String EXCERPT_LINE = "2026-01-02 03:04:05 INFO acme.OrderService started";

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    private final Map<String, String> env = new HashMap<>();

    // --- INUBIT_LIVE_LOG_FOLDER ----------------------------------------------------------------

    @Test
    void withoutTheLogFolderTheLiveTestIsSkipped() {
        Path repository = temp.resolve("repository");

        assertThatThrownBy(() -> ServerLogLiveTestSupport.logFolder(env, repository))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_LOG_FOLDER");
        env.put("INUBIT_LIVE_LOG_FOLDER", " ");
        assertThatThrownBy(() -> ServerLogLiveTestSupport.logFolder(env, repository))
            .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void aRelativeLogFolderIsRefused() {
        env.put("INUBIT_LIVE_LOG_FOLDER", "logs");

        assertThatThrownBy(() -> ServerLogLiveTestSupport.logFolder(env,
            temp.resolve("repository")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_LOG_FOLDER")
            .hasMessageContaining("absolute");
    }

    @Test
    void aLogFolderInsideTheRepositoryIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path link = symbolicLink(temp.resolve("link"), repository);

        for (Path inside : List.of(repository, repository.resolve("target/logs"),
            link.resolve("logs"))) {
            env.put("INUBIT_LIVE_LOG_FOLDER", inside.toString());
            assertThatThrownBy(() -> ServerLogLiveTestSupport.logFolder(env, repository))
                .as(inside.toString())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_LOG_FOLDER")
                .hasMessageContaining("outside the repository");
        }
    }

    @Test
    void aLogFolderInsideAnotherGitWorkingTreeIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path clone = Files.createDirectories(temp.resolve("clone/.git")).getParent();
        env.put("INUBIT_LIVE_LOG_FOLDER", clone.resolve("logs").toString());

        assertThatThrownBy(() -> ServerLogLiveTestSupport.logFolder(env, repository))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_LOG_FOLDER")
            .hasMessageContaining("git working tree");
    }

    @Test
    void aLogFolderWithDotDotSegmentsIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        env.put("INUBIT_LIVE_LOG_FOLDER", temp + "/other/../repository/logs");

        assertThatThrownBy(() -> ServerLogLiveTestSupport.logFolder(env, repository))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_LOG_FOLDER")
            .hasMessageContaining("must not contain .. segments");
    }

    @Test
    void aLogFolderOutsideTheRepositoryIsUsedAsItsRealPath() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));
        env.put("INUBIT_LIVE_LOG_FOLDER",
            symbolicLink(temp.resolve("elsewhere-link"), elsewhere).resolve("logs").toString());

        assertThat(ServerLogLiveTestSupport.logFolder(env, repository))
            .isEqualTo(temp.toRealPath().resolve("elsewhere/logs"));
    }

    // --- INUBIT_LIVE_RECORD_DIR ----------------------------------------------------------------

    @Test
    void withoutTheRecordDirectoryNothingIsRecorded() throws IOException {
        Path repository = temp.resolve("repository");

        assertThat(ServerLogLiveTestSupport.recordDirectory(env, repository)).isEmpty();
        env.put("INUBIT_LIVE_RECORD_DIR", " ");
        assertThat(ServerLogLiveTestSupport.recordDirectory(env, repository)).isEmpty();
    }

    @Test
    void aRecordDirectoryInsideTheRepositoryIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        env.put("INUBIT_LIVE_RECORD_DIR", repository.resolve("records").toString());

        assertThatThrownBy(() -> ServerLogLiveTestSupport.recordDirectory(env, repository))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_RECORD_DIR")
            .hasMessageContaining("outside the repository");
    }

    @Test
    void aRecordDirectoryOutsideTheRepositoryIsUsed() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        env.put("INUBIT_LIVE_RECORD_DIR", temp.resolve("records").toString());

        assertThat(ServerLogLiveTestSupport.recordDirectory(env, repository))
            .contains(temp.toRealPath().resolve("records"));
    }

    @Test
    void eachRunGetsItsOwnOwnerOnlyDirectoryBelowTheRecordDirectory() throws IOException {
        Path records = temp.resolve("records/live");

        Path first = ServerLogLiveTestSupport.runDirectory(PrivateFiles.forHost(), records);
        Path second = ServerLogLiveTestSupport.runDirectory(PrivateFiles.forHost(), records);

        assertThat(first.getParent()).isEqualTo(records);
        assertThat(first.getFileName().toString()).startsWith("server-log-");
        assertThat(first).isNotEqualTo(second);
        assertOwnerOnly(records, "rwx------");
        assertOwnerOnly(first, "rwx------");
    }

    @Test
    void aTraceRunIsWrittenAsOwnerOnlyStartCliCaseFiles() throws IOException {
        Path run = ServerLogLiveTestSupport.runDirectory(PrivateFiles.forHost(),
            temp.resolve("records"));

        ServerLogLiveTestSupport.record(PrivateFiles.forHost(), run, "trace", "ok",
            finished(false, "Trace data (0.1 MB) stored to: x\n", "warning\n", 0));

        assertThat(run.resolve("trace/ok.stdout")).hasContent("Trace data (0.1 MB) stored to: x\n");
        assertThat(run.resolve("trace/ok.stderr")).hasContent("warning\n");
        assertThat(run.resolve("trace/ok.exit")).hasContent("0\n");
        assertOwnerOnly(run.resolve("trace"), "rwx------");
        assertAllOwnerOnlyFiles(run.resolve("trace"));
    }

    @Test
    void aWebServiceRunIsWrittenAsOwnerOnlyHelperCaseFiles() throws IOException {
        Path run = ServerLogLiveTestSupport.runDirectory(PrivateFiles.forHost(),
            temp.resolve("records"));

        ServerLogLiveTestSupport.record(PrivateFiles.forHost(), run, "webservice", "ok",
            finished(true, "#frame end\n", "", 0));

        try (Stream<Path> files = Files.list(run.resolve("webservice"))) {
            assertThat(files.map(file -> file.getFileName().toString()))
                .containsExactlyInAnyOrder("ok.frames", "ok.exit");
        }
        assertThat(run.resolve("webservice/ok.frames")).hasContent("#frame end\n");
        assertAllOwnerOnlyFiles(run.resolve("webservice"));
    }

    @Test
    void theCaseIsNamedAfterTheOutcomeOfTheCall() {
        assertThat(ServerLogLiveTestSupport.caseName(JSON.readTree(
            "{\"isError\": false, \"structuredContent\": {\"sizeBytes\": 12}}"))).isEqualTo("ok");
        assertThat(ServerLogLiveTestSupport.caseName(JSON.readTree(
            "{\"isError\": false, \"structuredContent\": {\"sizeBytes\": 0}}")))
            .isEqualTo("empty");
        assertThat(ServerLogLiveTestSupport.caseName(JSON.readTree("{\"isError\": true}")))
            .isEqualTo("error");
    }

    // --- recording the processes ---------------------------------------------------------------

    @Test
    void theRecorderKeepsStartCliAndHelperOutputButNoOtherProcess() throws Exception {
        ProcessRecorder recorder = new ProcessRecorder(FakeProcessLauncher.of("out\n", "err\n",
            3));

        finish(recorder.launch(new LaunchSpec(List.of("StartCLI.sh", "-version"), Map.of(), temp,
            List.of("-version"))));
        finish(recorder.launch(new LaunchSpec(List.of("java", "-cp", "x",
            HelperInstallation.MAIN_CLASS), Map.of(), temp)));
        finish(recorder.launch(new LaunchSpec(List.of("tasklist"), Map.of(), temp)));

        List<Run> runs = recorder.runs();
        assertThat(runs).extracting(Run::helper).containsExactly(false, true);
        for (Run run : runs) {
            assertThat(run.stdout().toString(StandardCharsets.UTF_8)).isEqualTo("out\n");
            assertThat(run.stderr().toString(StandardCharsets.UTF_8)).isEqualTo("err\n");
            assertThat(run.exit()).contains(3);
        }
    }

    @Test
    void theRunOfALogIsTheLastStartCliRunForTraceAndTheLastHelperRunForWebService() {
        Run probe = finished(false, "8.1.17\n", "", 0);
        Run download = finished(false, "stored\n", "", 0);
        Run helper = finished(true, "#frame\n", "", 0);

        assertThat(ServerLogLiveTestSupport.lastRun(List.of(probe, download, helper), "trace"))
            .isSameAs(download);
        assertThat(ServerLogLiveTestSupport.lastRun(List.of(probe, helper), "webservice"))
            .isSameAs(helper);
        assertThatThrownBy(() -> ServerLogLiveTestSupport.lastRun(List.of(probe), "webservice"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("webservice")
            .hasMessageContaining("nothing to record");
    }

    // --- the result and the file ---------------------------------------------------------------

    @Test
    void aDownloadedFileMatchingTheResultPasses() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("logs"));
        Path file = ownerOnlyFile(folder.resolve("test-node1-trace-20260102-030405.log"), 5);

        assertThatCode(() -> ServerLogLiveTestSupport.assertDownloaded(result(file, 5, "trace"),
            NODE, "trace", folder, PrivateFiles.forHost())).doesNotThrowAnyException();
    }

    @Test
    void aSizeOtherThanTheReportedOneFails() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("logs"));
        Path file = ownerOnlyFile(folder.resolve("test-node1-trace-20260102-030405.log"), 5);

        assertThatThrownBy(() -> ServerLogLiveTestSupport.assertDownloaded(
            result(file, 6, "trace"), NODE, "trace", folder, PrivateFiles.forHost()))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("sizeBytes");
    }

    @Test
    void aFileOthersMayReadFails() throws IOException {
        assumeTrue(posix(), "POSIX permissions");
        Path folder = Files.createDirectories(temp.resolve("logs"));
        Path file = ownerOnlyFile(folder.resolve("test-node1-trace-20260102-030405.log"), 5);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertThatThrownBy(() -> ServerLogLiveTestSupport.assertDownloaded(
            result(file, 5, "trace"), NODE, "trace", folder, PrivateFiles.forHost()))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("rw-------");
    }

    @Test
    void aFileOutsideTheFolderOrOfAnotherNodeOrLogFails() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("logs"));
        Path file = ownerOnlyFile(temp.resolve("test-node1-trace-20260102-030405.log"), 5);
        Path inside = ownerOnlyFile(folder.resolve("test-node1-trace-20260102-030405.log"), 5);

        assertThatThrownBy(() -> ServerLogLiveTestSupport.assertDownloaded(
            result(file, 5, "trace"), NODE, "trace", folder, PrivateFiles.forHost()))
            .isInstanceOf(AssertionError.class).hasMessageContaining("file");
        assertThatThrownBy(() -> ServerLogLiveTestSupport.assertDownloaded(
            result(inside, 5, "trace"), NodeId.of("test", "node2"), "trace", folder,
            PrivateFiles.forHost()))
            .isInstanceOf(AssertionError.class).hasMessageContaining("node");
        assertThatThrownBy(() -> ServerLogLiveTestSupport.assertDownloaded(
            result(inside, 5, "trace"), NODE, "webservice", folder, PrivateFiles.forHost()))
            .isInstanceOf(AssertionError.class).hasMessageContaining("log");
    }

    @Test
    void aResultNotMatchingTheOutputSchemaFails() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("logs"));
        Path file = ownerOnlyFile(folder.resolve("test-node1-trace-20260102-030405.log"), 5);
        JsonNode result = JSON.readTree(result(file, 5, "trace").toString()
            .replace("\"hints\":[],", ""));

        assertThatThrownBy(() -> ServerLogLiveTestSupport.assertDownloaded(result, NODE, "trace",
            folder, PrivateFiles.forHost()))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("output schema");
    }

    @Test
    void aWebServiceResultMustNameTheInubitNode() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("logs"));
        Path file = ownerOnlyFile(folder.resolve("test-node1-webservice-20260102-030405.log"), 5);

        assertThatThrownBy(() -> ServerLogLiveTestSupport.assertDownloaded(
            result(file, 5, "webservice"), NODE, "webservice", folder, PrivateFiles.forHost()))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("inubitNode");
        assertThatCode(() -> ServerLogLiveTestSupport.assertDownloaded(
            withInubitNode(result(file, 5, "webservice")), NODE, "webservice", folder,
            PrivateFiles.forHost())).doesNotThrowAnyException();
    }

    // --- the log and the summary ---------------------------------------------------------------

    @Test
    void aLogWithoutExcerptLinesPasses() {
        assertThatCode(() -> ServerLogLiveTestSupport.assertNoExcerptLineLogged(
            result(temp.resolve("x.log"), 5, "trace"),
            List.of("download_server_log test/node1 trace: 5 bytes", "}")))
            .doesNotThrowAnyException();
    }

    @Test
    void anExcerptLineInTheLogFailsWithoutQuotingIt() {
        assertThatThrownBy(() -> ServerLogLiveTestSupport.assertNoExcerptLineLogged(
            result(temp.resolve("x.log"), 5, "trace"),
            List.of("DEBUG read line " + EXCERPT_LINE + " of the window")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("1 of 1 excerpt lines")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("OrderService"));
    }

    /** A short line such as a closing bracket also occurs in unrelated log text. */
    @Test
    void anExcerptLineShorterThanTenCharactersIsNotChecked() {
        JsonNode result = JSON.readTree(result(temp.resolve("x.log"), 5, "trace").toString()
            .replace(EXCERPT_LINE, "  }  "));

        assertThatCode(() -> ServerLogLiveTestSupport.assertNoExcerptLineLogged(result,
            List.of("}"))).doesNotThrowAnyException();
    }

    @Test
    void theSummaryNamesNodeLogSizeAndWindow() {
        assertThat(ServerLogLiveTestSupport.summary(result(temp.resolve("x.log"), 5, "trace")))
            .isEqualTo("[live] download_server_log test/node1 trace: 5 bytes, window 1");
        assertThat(ServerLogLiveTestSupport.summary(withInubitNode(
            result(temp.resolve("x.log"), 5, "webservice"))))
            .isEqualTo("[live] download_server_log test/node1 webservice: 5 bytes, window 1,"
                + " INUBIT node node1");
        assertThat(ServerLogLiveTestSupport.summary(JSON.readTree("""
            {"node": "test/node1", "log": "trace", "sizeBytes": 0, "excerpt": {"kind": "empty"}}
            """))).isEqualTo("[live] download_server_log test/node1 trace: 0 bytes, window none"
                + " (empty)");
    }

    // --- the forced failure --------------------------------------------------------------------

    @Test
    void theUnreachableCopyMovesOnlyTheLiveNodeToALoopbackPort() {
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false).parse("""
            profile:
              name: acme
            groups:
              - name: test
                cli:
                  url: https://cli.example.test:9443/custom
                nodes:
                  - name: node1
                    baseUrl: https://node1.example.test:8443
                  - name: node2
                    baseUrl: https://node2.example.test:8443
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://dev1.example.test:8443
            """, temp.resolve("config.yaml")).config();

        ProfileConfig unreachable = ServerLogLiveTestSupport.unreachable(config, NODE, 1234);

        assertThat(effective(unreachable, NODE).baseUrl())
            .isEqualTo(URI.create("https://127.0.0.1:1234"));
        assertThat(effective(unreachable, NODE).cli().url())
            .isEqualTo(URI.create("https://127.0.0.1:1234/custom"));
        for (NodeId other : List.of(NodeId.of("test", "node2"), NodeId.of("dev", "node1"))) {
            assertThat(effective(unreachable, other)).as(other.value())
                .isEqualTo(effective(config, other));
        }
        assertThat(unreachable.profile()).isEqualTo(config.profile());
    }

    // --- helpers -------------------------------------------------------------------------------

    /** A schema-valid result for {@code file} with one excerpt line. */
    private static JsonNode result(Path file, long size, String log) {
        return JSON.readTree("""
            {"node": "test/node1", "log": "%s", "file": "%s", "sizeBytes": %d,
             "downloadedAt": "2026-01-02T03:04:05Z",
             "excerpt": {"kind": "text", "charset": "UTF-8",
               "window": {"startLine": 1, "lines": 1, "bytes": %d, "wholeFile": true},
               "selection": "tail", "returnedLines": 1,
               "lines": [{"number": 1, "text": "%s"}], "truncated": false},
             "hints": [], "warnings": []}
            """.formatted(log, file.toString().replace("\\", "\\\\"), size, size, EXCERPT_LINE));
    }

    private static JsonNode withInubitNode(JsonNode result) {
        return JSON.readTree(result.toString().replace("\"log\":", "\"inubitNode\":\"node1\","
            + "\"log\":"));
    }

    private static EffectiveNodeConfig effective(ProfileConfig config, NodeId node) {
        return config.effectiveNodes().stream().filter(candidate -> candidate.id().equals(node))
            .findFirst().orElseThrow();
    }

    private static Run finished(boolean helper, String stdout, String stderr, int exit) {
        Run run = new Run(helper);
        run.stdout().writeBytes(stdout.getBytes(StandardCharsets.UTF_8));
        run.stderr().writeBytes(stderr.getBytes(StandardCharsets.UTF_8));
        run.finished(exit);
        return run;
    }

    /** Reads the process's output to the end and waits for it, as {@code ProcessRun} does. */
    private static void finish(LaunchedProcess process) throws Exception {
        process.stdout().readAllBytes();
        process.stderr().readAllBytes();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
    }

    private static Path ownerOnlyFile(Path file, int size) throws IOException {
        PrivateFiles.forHost().createFile(file);
        Files.write(file, new byte[size]);
        return file;
    }

    /** {@code link} to {@code target}; skips the test where links cannot be created. */
    private static Path symbolicLink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            throw new TestAbortedException("symbolic links cannot be created here", e);
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void assertOwnerOnly(Path path, String posixPermissions) throws IOException {
        if (posix()) {
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
