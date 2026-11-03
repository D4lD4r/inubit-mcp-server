package de.dadecker.inubit.mcp.testing;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The substitute StartCLI (research R-12) run through the real {@link SystemProcessLauncher}:
 * {@code bin/startcli.sh} on POSIX, {@code bin\startcli.bat} through the interpreter on Windows.
 */
@Timeout(60)
class FakeStartCliTest {

    private static final List<String> PLAIN = List.of("-u", "jdoe", "--execCommand", "plain",
        "https://inubit-dev-1.example.test/cli");

    @TempDir
    Path cliHome;

    private final SystemProcessLauncher launcher = new SystemProcessLauncher();

    /**
     * Starts the substitute as the server will: the script itself on POSIX; on Windows the
     * interpreter with one pre-quoted inner argument (contracts/windows-invocation.md).
     */
    private LaunchedProcess launch(FakeStartCli cli, List<String> arguments, String stdin)
        throws IOException {
        Map<String, String> environment = new HashMap<>(Map.of("INUBIT_TEST", "value"));
        List<String> command = new ArrayList<>();
        if (Platform.windows()) {
            environment.put("SystemRoot", System.getenv("SystemRoot"));
            command.addAll(List.of(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe")
                .toString(), "/d", "/v:off", "/s", "/c", "\"\"" + cli.script() + "\" "
                + arguments.stream().map(a -> "\"" + a + "\"").collect(Collectors.joining(" "))
                + "\""));
        } else {
            command.add(cli.script().toString());
            command.addAll(arguments);
        }
        LaunchedProcess process = launcher.launch(new LaunchSpec(command, environment, cliHome));
        try (OutputStream in = process.stdin()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        return process;
    }

    private static int exitOf(LaunchedProcess process) throws InterruptedException {
        assertThat(process.waitFor(Duration.ofSeconds(30))).isTrue();
        return process.exitValue();
    }

    @Test
    void installWritesThePlatformsScript() throws IOException {
        FakeStartCli cli = FakeStartCli.install(cliHome);

        assertThat(cli.script()).isRegularFile()
            .isEqualTo(cliHome.resolve("bin")
                .resolve(Platform.windows() ? "startcli.bat" : "startcli.sh"));
    }

    @Test
    void theRecordHoldsTheArgumentsTheEnvironmentAndStdin() throws Exception {
        FakeStartCli cli = FakeStartCli.install(cliHome);

        LaunchedProcess process = launch(cli, PLAIN, "secret\n");

        assertThat(exitOf(process)).as(new String(process.stderr().readAllBytes(),
            StandardCharsets.UTF_8)).isZero();
        FakeStartCli.Recorded record = cli.record();
        assertThat(record.args()).containsExactlyElementsOf(PLAIN);
        assertThat(record.environment()).containsEntry("INUBIT_TEST", "value");
        assertThat(new String(record.stdin(), StandardCharsets.UTF_8)).isEqualTo("secret\n");
        assertThat(record.pid()).isPositive();
        assertThat(record.childPid()).isNull();
        assertThat(Path.of(record.workingDirectory()).toRealPath())
            .isEqualTo(cliHome.toRealPath());
    }

    @Test
    void theRecorderCommandRecordsWithoutTheScript() throws Exception {
        FakeStartCli cli = FakeStartCli.install(cliHome);
        List<String> command = new ArrayList<>(cli.recorderCommand());
        command.add("direct");

        LaunchedProcess process = launcher.launch(new LaunchSpec(command, Map.of(), cliHome));
        process.stdin().close();

        assertThat(exitOf(process)).isZero();
        assertThat(cli.recorderCommand()).doesNotContain(cli.script().toString());
        assertThat(cli.record().args()).containsExactly("direct");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theWindowsScriptRecordsTheInterpretersCommandLine() throws Exception {
        FakeStartCli cli = FakeStartCli.install(cliHome);

        assertThat(exitOf(launch(cli, PLAIN, ""))).isZero();

        assertThat(cli.commandLine()).contains("cmd.exe", cli.script().toString(), "\"jdoe\"");
    }

    @Test
    void replayingPrintsTheRecordedOutputsAndExitCode() throws Exception {
        FakeStartCli cli = FakeStartCli.replaying(cliHome, "login_failed");

        LaunchedProcess process = launch(cli, PLAIN, "secret\n");

        byte[] stdout = process.stdout().readAllBytes();
        byte[] stderr = process.stderr().readAllBytes();
        assertThat(exitOf(process)).isEqualTo(Integer.parseInt(
            FakeProcessLauncher.fixtureText("login_failed.exit").strip())).isNotZero();
        assertThat(stdout).isEqualTo(FakeProcessLauncher.fixture("login_failed.stdout"));
        assertThat(stderr).isEqualTo(FakeProcessLauncher.fixture("login_failed.stderr"));
        assertThat(cli.record().args()).containsExactlyElementsOf(PLAIN);
    }

    @Test
    void theRecorderWritesAFileIntoTheGivenDirectory() throws Exception {
        Path target = Files.createDirectory(cliHome.resolve("target dir"));
        FakeStartCli cli = FakeStartCli.install(cliHome,
            Map.of(ArgvRecorder.WRITE_INTO, target.toString()));

        assertThat(exitOf(launch(cli, PLAIN, ""))).isZero();

        assertThat(target.resolve(ArgvRecorder.WRITTEN_FILE)).isRegularFile();
    }

    @Test
    void theRecorderStartsASleepingChildAndSleepsAfterRecording() throws Exception {
        FakeStartCli cli = FakeStartCli.install(cliHome, Map.of(
            ArgvRecorder.SLEEP_MILLIS, "60000", ArgvRecorder.CHILD_SLEEP_MILLIS, "60000"));
        LaunchedProcess process = launch(cli, PLAIN, "");
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Files.exists(cli.recordFile()) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            FakeStartCli.Recorded record = cli.record();

            assertThat(process.isAlive()).isTrue();
            assertThat(record.childPid()).isNotNull();
            assertThat(alive(record.childPid())).isTrue();
        } finally {
            process.destroy();
            process.destroyForcibly();
        }
        assertThat(process.waitFor(Duration.ofSeconds(10))).isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (process.treeAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(process.treeAlive()).isFalse();
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
