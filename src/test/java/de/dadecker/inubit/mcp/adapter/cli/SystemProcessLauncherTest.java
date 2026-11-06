package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Built;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The real {@link ProcessBuilder} launcher. The POSIX cases use POSIX binaries or signals and are
 * guarded per method. Their Windows equivalents (feature 013, T008) run {@link FakeStartCli}'s
 * {@code ArgvRecorder}: directly for the environment, the working directory and stdin, and through
 * the interpreter with the argument list of {@link WindowsCommandLine} for the StartCLI launch
 * (research R-1). The Windows process tree cases follow in T022.
 */
@Timeout(30)
class SystemProcessLauncherTest {

    private static final String URL =
        "https://inubit-dev-1.example.test/ibis/servlet/IBISSoapServlet";

    @TempDir
    Path directory;

    private final SystemProcessLauncher launcher = new SystemProcessLauncher();

    private static String read(LaunchedProcess process) throws IOException {
        return new String(process.stdout().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "uses /usr/bin/env; Windows equivalent:"
        + " onWindowsTheEnvironmentIsTheGivenMapPlusSystemRoot")
    void theEnvironmentIsExactlyTheGivenMap() throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(List.of("/usr/bin/env"),
            Map.of("ONLY_THIS", "1"), directory));

        String output = read(process);

        assertThat(process.waitFor(Duration.ofSeconds(10))).isTrue();
        assertThat(output.strip().lines().toList()).containsExactly("ONLY_THIS=1");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "uses /bin/pwd; Windows equivalent:"
        + " onWindowsTheWorkingDirectoryIsApplied")
    void theWorkingDirectoryIsApplied() throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(List.of("/bin/pwd"),
            Map.of(), directory));

        assertThat(Path.of(read(process).strip()).toRealPath())
            .isEqualTo(directory.toRealPath());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "uses /bin/echo and /bin/cat;"
        + " Windows equivalent: onWindowsStdinIsPipedAndArgumentsAreNotInterpretedByAShell")
    void stdinIsPipedAndArgumentsAreNotInterpretedByAShell() throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(
            List.of("/bin/echo", "$HOME", "a;b", "'x'"), Map.of("HOME", "/nowhere"), directory));

        assertThat(read(process).strip()).isEqualTo("$HOME a;b 'x'");

        LaunchedProcess cat = launcher.launch(new LaunchSpec(List.of("/bin/cat"), Map.of(),
            directory));
        try (OutputStream stdin = cat.stdin()) {
            stdin.write("line\n".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(read(cat)).isEqualTo("line\n");
        assertThat(cat.waitFor(Duration.ofSeconds(10))).isTrue();
        assertThat(cat.exitValue()).isZero();
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "uses /bin/sh; Windows process tree case"
        + " in T022")
    void destroyAlsoTerminatesDescendants() throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(
            List.of("/bin/sh", "-c", "sleep 60 & echo $!; wait"), Map.of("PATH", "/bin:/usr/bin"),
            directory));
        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.stdout(),
            StandardCharsets.UTF_8));
        long child = Long.parseLong(stdout.readLine().strip());
        assertThat(ProcessHandle.of(child)).hasValueSatisfying(
            handle -> assertThat(handle.isAlive()).isTrue());

        process.destroy();

        assertThat(process.waitFor(Duration.ofSeconds(10))).isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)
            && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "uses /bin/sh and SIGTERM; Windows process"
        + " tree case in T022")
    void aGrandchildIgnoringTermIsKilledWhenTheCliCallTimesOut() throws Exception {
        // fake startcli.sh: the root exits on TERM, its child ignores TERM and keeps running
        Path script = Files.createDirectories(directory.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n"
            + "[ \"$1\" = warm-up ] && exit 0\n"
            + "sh -c 'trap \"\" TERM; echo $$ > grandchild.pid; while :; do sleep 1; done' &\n"
            + "wait\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        // The first start of a freshly written script can take over a second on macOS (measured
        // 0.13 s typically, up to 1.8 s), a later one a few milliseconds. Pay that cost before
        // the timed call, so the grandchild is running when the timeout fires.
        assertThat(new ProcessBuilder(script.toString(), "warm-up").start().waitFor()).isZero();
        CliRunner runner = TestCliRunners.forHost(launcher, Map.of("PATH", "/bin:/usr/bin"),
            new CliResources("acme", PrivateFiles.forHost()));
        EffectiveNodeConfig server = TestNodeConfig.node().cliHome(directory)
            .cliJavaHome(Path.of("/no-jdk-needed")).build();
        Path pidFile = directory.resolve("grandchild.pid");

        try {
            assertThatThrownBy(() -> runner.run(server, "jdoe", Secret.of("pw"),
                CliCommand.kill("1"), Duration.ofSeconds(2),
                new CredentialGuard(server.secretLabels(), Clock.systemUTC())))
                .isInstanceOfSatisfying(ToolErrorException.class,
                    e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));

            assertThat(pidFile).as("the grandchild started before the timeout fired").exists();
            long grandchild = Long.parseLong(Files.readString(pidFile).strip());
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false))
                .as("grandchild ignoring SIGTERM is gone").isFalse();
        } finally {
            if (Files.exists(pidFile)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip()))
                    .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    // --- every platform: the launcher's Windows path (feature 013, T008) -----------------------

    /**
     * On Windows the JDK quotes the arguments of a batch file by its own rules (research R-1), so
     * a batch file reaches the JDK only through the interpreter call of {@link WindowsCommandLine}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"C:\\cli\\bin\\startcli.bat", "C:\\cli\\bin\\STARTCLI.CMD",
        "startcli.Bat", "C:\\cli\\bin\\startcli.bat. ."})
    void onWindowsABatchFileIsNeverHandedToTheJdk(String program) {
        SystemProcessLauncher windows = new SystemProcessLauncher(true);

        assertThatThrownBy(() -> windows.launch(new LaunchSpec(List.of(program, "-v"), Map.of(),
            directory)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("WindowsCommandLine")
            .hasMessageNotContaining("startcli");
    }

    /** FR-017: on POSIX every program goes to the JDK as today, whatever its name. */
    @Test
    void onPosixABatchFileNameIsHandedToTheJdkAsGiven() {
        SystemProcessLauncher posix = new SystemProcessLauncher(false);

        assertThatThrownBy(() -> posix.launch(new LaunchSpec(
            List.of(directory.resolve("missing.bat").toString()), Map.of(), directory)))
            .as("the JDK, not the launcher, fails to start the missing program")
            .isInstanceOf(IOException.class);
    }

    // --- Windows (feature 013, T008) ----------------------------------------------------------

    /** {@code SystemRoot} of the test JVM; the JDK adds it to every child environment. */
    private static String systemRoot() {
        return System.getenv("SystemRoot");
    }

    /** Runs {@code command} (stdin {@code stdin}, then closed) and expects exit code 0. */
    private void run(List<String> command, Map<String, String> environment, String stdin)
        throws Exception {
        LaunchedProcess process = launcher.launch(new LaunchSpec(command, environment,
            directory));
        try (OutputStream in = process.stdin()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        String stdout = read(process);
        String stderr = new String(process.stderr().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(Duration.ofSeconds(20))).isTrue();
        assertThat(process.exitValue()).as("stdout: %s, stderr: %s", stdout, stderr).isZero();
    }

    /**
     * Pins the JDK behaviour of research R-1: the interpreter receives exactly the line of the
     * argument list of {@link WindowsCommandLine} (the inner argument unchanged), and StartCLI's
     * Java main receives exactly {@code arguments}; the password arrives on stdin.
     */
    private void assertStartCliReceives(List<String> arguments) throws Exception {
        FakeStartCli cli = FakeStartCli.install(directory);
        WindowsCommandLine.Result result = WindowsCommandLine.build(
            Path.of(systemRoot(), "System32").toString(), cli.script().toString(), arguments);
        assertThat(result).isInstanceOf(Built.class);
        List<String> command = ((Built) result).command();

        run(command, Map.of("SystemRoot", systemRoot()), "secret\n");

        assertThat(cli.commandLine()).isEqualTo(String.join(" ", command));
        FakeStartCli.Recorded record = cli.record();
        assertThat(record.args()).containsExactlyElementsOf(arguments);
        assertThat(new String(record.stdin(), StandardCharsets.UTF_8)).isEqualTo("secret\n");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsStartCliReceivesThePlainValuesUnchanged() throws Exception {
        assertStartCliReceives(List.of("-u", "jdoe", "--trustStoreFilePath",
            "C:\\certs\\trust.p12", "--disableHostNameVerification", "--execCommand",
            "export --exportModule 'Mod_1' --exportFile 'C:/Users/svc/AppData/Local/Temp/out.zip'",
            URL));
    }

    /** The test values of contracts/windows-invocation.md, as username and trust-store path. */
    @ParameterizedTest
    @EnabledOnOs(OS.WINDOWS)
    @ValueSource(strings = {"plain", "with space", "''", "a'b", "(x)", "a;b", "a,b", "a=b",
        "C:\\dir\\", "C:/dir/x", "*", "a?b"})
    void onWindowsStartCliReceivesAContractTestValueUnchanged(String value) throws Exception {
        assertStartCliReceives(List.of("-u", value, "--trustStoreFilePath", value,
            "--execCommand", "plain", URL));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheEnvironmentIsTheGivenMapPlusSystemRoot() throws Exception {
        FakeStartCli cli = FakeStartCli.install(directory);

        run(cli.recorderCommand(), Map.of("ONLY_THIS", "1"), "");

        assertThat(cli.record().environment())
            .as("nothing inherited; the JDK adds SystemRoot to every Windows child environment")
            .containsExactlyInAnyOrderEntriesOf(Map.of("ONLY_THIS", "1",
                "SystemRoot", systemRoot()));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheWorkingDirectoryIsApplied() throws Exception {
        FakeStartCli cli = FakeStartCli.install(directory);

        run(cli.recorderCommand(), Map.of(), "");

        assertThat(Path.of(cli.record().workingDirectory()).toRealPath())
            .isEqualTo(directory.toRealPath());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsStdinIsPipedAndArgumentsAreNotInterpretedByAShell() throws Exception {
        FakeStartCli cli = FakeStartCli.install(directory);
        List<String> command = new ArrayList<>(cli.recorderCommand());
        List<String> arguments = List.of("%PATH%", "a;b", "'x'", "with space");
        command.addAll(arguments);

        run(command, Map.of("PATH", "C:\\nowhere"), "line\n");

        FakeStartCli.Recorded record = cli.record();
        assertThat(record.args()).containsExactlyElementsOf(arguments);
        assertThat(new String(record.stdin(), StandardCharsets.UTF_8)).isEqualTo("line\n");
    }
}
