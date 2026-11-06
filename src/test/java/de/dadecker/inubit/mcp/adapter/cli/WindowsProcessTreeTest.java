package de.dadecker.inubit.mcp.adapter.cli;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.ArgvRecorder;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * T022 (feature 013, FR-005, SC-004): on Windows the whole process tree of a StartCLI call ends
 * after a timeout and after an orderly stop (the MCP client closed the connection). The
 * substitute StartCLI ({@link FakeStartCli}) runs through the real interpreter call, hangs and
 * starts a child, standing for the JVM {@code startcli.bat} starts. 20 runs each. The forced end
 * of the server (the job object) is {@code ExportShutdownProcessTest}'s Windows case.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(60)
class WindowsProcessTreeTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "process-tree-pw";
    private static final String HANG = "300000";
    /** Long enough for the interpreter, the substitute and its child to start on a CI runner. */
    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final Duration AFTER_GRACE = Duration.ofSeconds(1);

    @TempDir
    Path directory;

    private Path cliHome;
    private Path tempRoot;
    private final ChildRecordingLauncher launcher = new ChildRecordingLauncher();
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-10-01T08:00:00Z")));

    /** The first start of a JVM on a fresh runner is slow; pay it before the timed runs. */
    @BeforeAll
    static void warmUp(@TempDir Path warm) throws Exception {
        FakeStartCli cli = FakeStartCli.install(warm);
        Process process = new ProcessBuilder(cli.recorderCommand())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();
        assertThat(process.waitFor()).isZero();
    }

    @BeforeEach
    void layout() throws IOException {
        cliHome = Files.createDirectories(directory.resolve("client"));
        tempRoot = Files.createDirectories(directory.resolve("tmp"));
    }

    @RepeatedTest(20)
    void afterTheTimeoutNoProcessOfTheTreeIsAlive() throws Exception {
        FakeStartCli cli = FakeStartCli.install(cliHome, Map.of(
            ArgvRecorder.CHILD_SLEEP_MILLIS, HANG, ArgvRecorder.SLEEP_MILLIS, HANG));
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        long start = System.nanoTime();

        ToolError error = errorOf(() -> runner(resources).run(server(), "jdoe",
            Secret.of(PASSWORD), CliCommand.kill("1"), TIMEOUT, guard));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        Map<String, Long> tree = tree(cli);
        long deadline = start + TIMEOUT.plus(CliRunner.KILL_GRACE).plus(AFTER_GRACE).toNanos();
        assertTreeGoneBy(tree, deadline);
        resources.close();
    }

    @RepeatedTest(20)
    void afterAnOrderlyStopNoProcessOfTheTreeIsAliveAndTheExportDirectoryIsGone()
        throws Exception {
        FakeStartCli cli = FakeStartCli.install(cliHome, Map.of(
            ArgvRecorder.CHILD_SLEEP_MILLIS, HANG, ArgvRecorder.SLEEP_MILLIS, HANG,
            ArgvRecorder.EXPORT_FIXTURE, "export_modules_sample.zip"));
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        CliExportRunner exports = new CliExportRunner(server(), credentials(), guard,
            runner(resources), new CliOutputClassifier(new SecretScrubber()), tempRoot);
        AtomicReference<ToolError> error = new AtomicReference<>();
        Thread call = Thread.ofVirtual().start(() -> error.set(errorOf(
            () -> exports.exportModules("OWNERS"))));
        long started = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(cli.recordFile()) && System.nanoTime() < started) {
            Thread.sleep(20);
        }
        assertThat(cli.recordFile()).as("the substitute and its child run").exists();
        Map<String, Long> tree = tree(cli);

        long stop = System.nanoTime();
        resources.close();
        call.join(Duration.ofSeconds(10));

        assertThat(call.isAlive()).isFalse();
        assertThat(error.get().message()).contains("shutting down");
        assertTreeGoneBy(tree, stop + CliRunner.KILL_GRACE.plus(AFTER_GRACE).toNanos());
        try (Stream<Path> left = Files.list(tempRoot)) {
            assertThat(left).as("the export directory").isEmpty();
        }
    }

    /** The interpreter, the substitute and its child, which must have started. */
    private Map<String, Long> tree(FakeStartCli cli) throws IOException {
        assertThat(cli.recordFile()).as("the substitute ran before the call ended").exists();
        FakeStartCli.Recorded record = cli.record();
        assertThat(record.childPid()).as("the substitute started its child").isNotNull();
        Map<String, Long> tree = new LinkedHashMap<>();
        tree.put("the interpreter", launcher.interpreter.get());
        tree.put("the substitute", record.pid());
        tree.put("its child", record.childPid());
        return tree;
    }

    private static void assertTreeGoneBy(Map<String, Long> tree, long deadline)
        throws InterruptedException {
        while (tree.values().stream().anyMatch(WindowsProcessTreeTest::alive)
            && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Set<String> alive = tree.entrySet().stream().filter(entry -> alive(entry.getValue()))
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        try {
            assertThat(alive).as("alive one second after the grace period").isEmpty();
        } finally {
            tree.values().forEach(pid -> ProcessHandle.of(pid)
                .ifPresent(ProcessHandle::destroyForcibly));
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node().cliHome(cliHome)
            .cliJavaHome(Path.of(System.getProperty("java.home"))).build();
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private CliRunner runner(CliResources resources) {
        return TestCliRunners.forHost(launcher, new HashMap<>(System.getenv()), resources);
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("the call did not fail");
    }

    /** The real launcher, remembering the process it started: the interpreter. */
    private static final class ChildRecordingLauncher implements ProcessLauncher {

        final AtomicReference<Long> interpreter = new AtomicReference<>();
        private final SystemProcessLauncher system = new SystemProcessLauncher();

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            Set<Long> before = children();
            LaunchedProcess process = system.launch(spec);
            Set<Long> started = children();
            started.removeAll(before);
            assertThat(started).as("the one process the launcher started").hasSize(1);
            interpreter.set(started.iterator().next());
            return process;
        }

        private static Set<Long> children() {
            return ProcessHandle.current().children().map(ProcessHandle::pid)
                .collect(Collectors.toSet());
        }
    }
}
