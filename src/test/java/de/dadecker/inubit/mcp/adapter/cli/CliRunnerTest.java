package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** T022: the StartCLI process layer with {@link FakeProcessLauncher} (research R-6, R-12). */
@Timeout(30)
class CliRunnerTest {

    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-CLI-pw";
    private static final Path JAVA_HOME = Path.of("/opt/jdk-17");

    @TempDir
    Path cliHome;

    private Path script;
    private final Map<String, String> parentEnv = new HashMap<>();

    @BeforeEach
    void installFakeCli() throws IOException {
        script = FakeStartCli.install(cliHome).script();
        parentEnv.put("PATH", Platform.searchPath());
        parentEnv.put("HOME", "/Users/jdoe");
        parentEnv.put("TMPDIR", "/var/tmp/");
        parentEnv.put("LANG", "de_DE.UTF-8");
        parentEnv.put("LC_ALL", "de_DE.UTF-8");
        parentEnv.put("USER", "jdoe");
    }

    private TestNodeConfig server() {
        return TestNodeConfig.node()
            .baseUrl("https://inubit-dev-1.example.test:8443")
            .cliHome(cliHome)
            .cliJavaHome(JAVA_HOME);
    }

    private CliRunner runner(FakeProcessLauncher launcher) {
        return TestCliRunners.forHost(launcher, parentEnv, new CliResources("acme"));
    }

    private CliResult run(FakeProcessLauncher launcher, EffectiveNodeConfig config) {
        return runner(launcher).run(config, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard());
    }

    private ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().toString() + e.getMessage()).doesNotContain(PASSWORD);
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    // --- credential guard shared with REST (Phase 3 review M2) -----------------------------

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));

    private CredentialGuard guard() {
        return new CredentialGuard(server().build().secretLabels(), clock);
    }

    private CliResult runGuarded(FakeProcessLauncher launcher, CredentialGuard guard) {
        return runner(launcher).run(server().build(), USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard);
    }

    @Test
    void aCliLoginFailureBlocksFurtherCliAndRestLoginsForSixtySeconds() {
        CredentialGuard guard = new CredentialGuard(server().build().secretLabels(), clock);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("login_failed");

        runGuarded(launcher, guard);
        ToolError cached = errorOf(() -> runGuarded(launcher, guard));

        assertThat(cached.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(cached.message()).contains("avoid account lockout");
        assertThat(launcher.launchCount()).as("no second StartCLI login").isEqualTo(1);
        assertThatThrownBy(guard::acquire).as("REST shares the guard")
            .isInstanceOf(ToolErrorException.class);

        clock.advance(CredentialGuard.PAUSE);
        runGuarded(launcher, guard);
        assertThat(launcher.launchCount()).isEqualTo(2);
    }

    @Test
    void afterShutdownNoStartCliProcessIsStarted() {
        // Phase 6 review W2: a launch after close is refused before anything is started
        CliResources resources = new CliResources("acme");
        resources.close();
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        CliRunner runner = TestCliRunners.forHost(launcher, parentEnv, resources);

        ToolError error = errorOf(() -> runner.run(server().build(), USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("shutting down");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aSuccessfulCliRunConfirmsTheCredentials() {
        CredentialGuard guard = new CredentialGuard(server().build().secretLabels(), clock);

        runGuarded(FakeProcessLauncher.replaying("processErrorStart_ok"), guard);

        assertThat(guard.confirmed()).isTrue();
    }

    @Test
    void aPausedGuardRefusesTheCliCallWithoutStartingStartCli() {
        // Phase 3 re-review N1: the CLI path consults the guard shared with REST
        CredentialGuard guard = guard();
        try (CredentialGuard.Permit permit = guard.acquire()) {
            permit.rejected(); // e.g. a REST call got HTTP 401
        }
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        ToolError error = errorOf(() -> runGuarded(launcher, guard));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).contains("avoid account lockout");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void onlyTheGuardedRunIsAccessibleOutsideTheRunner() {
        // Phase 3 re-review N1: tools cannot bypass the credential guard
        List<Method> runs = Arrays.stream(CliRunner.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("run"))
            .filter(method -> !Modifier.isPrivate(method.getModifiers()))
            .toList();

        assertThat(runs).isNotEmpty().allSatisfy(method ->
            assertThat(method.getParameterTypes()).contains(CredentialGuard.class));
    }

    @Test
    void theCommandIsAnArgumentArrayWithTlsFlagsAndWithoutPassword() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        Path trustStore = Path.of("/Users/jdoe/.config/inubit-mcp/truststore.p12");

        run(launcher, server().trustStore(trustStore).disableHostnameVerification("AB".repeat(32))
            .build());

        assertThat(launcher.last().spec().startCliArguments()).containsExactly(
            "-u", USER,
            "--trustStoreFilePath", trustStore.toString(),
            "--disableHostNameVerification",
            "--execCommand", "processErrorStart 110190387",
            "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void onPosixTheCommandIsTheScriptFollowedByTheStartCliArguments() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        List<String> command = launcher.last().spec().command();
        assertThat(command.get(0)).isEqualTo(script.toString());
        assertThat(command.subList(1, command.size()))
            .isEqualTo(launcher.last().spec().startCliArguments());
    }

    @Test
    void theLaunchSpecDescriptionShowsNoStartCliArgument() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        assertThat(launcher.last().spec().toString()).doesNotContain(USER)
            .doesNotContain("processErrorStart").doesNotContain("inubit-dev-1");
    }

    @Test
    void neverAPasswordArgument() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        List<String> command = launcher.last().spec().command();
        assertThat(command).doesNotContain("-p", "--password", PASSWORD);
        assertThat(String.join(" ", command)).doesNotContain(PASSWORD);
        assertThat(launcher.last().spec().environment().values()).doesNotContain(PASSWORD);
    }

    @Test
    void withoutTlsConfigurationNoTlsFlagsArePassed() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().cliUrl("https://cli.example.test/ibis/servlet/IBISSoapServlet")
            .build());

        assertThat(launcher.last().spec().startCliArguments()).containsExactly(
            "-u", USER, "--execCommand", "processErrorStart 110190387",
            "https://cli.example.test/ibis/servlet/IBISSoapServlet");
    }

    @Test
    void trustStoreWithoutDisabledHostnameVerificationPassesOnlyTheTrustStore() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        Path trustStore = Path.of("/etc/inubit/trust.p12");

        run(launcher, server().trustStore(trustStore).build());

        assertThat(launcher.last().spec().startCliArguments())
            .containsSubsequence("--trustStoreFilePath", trustStore.toString())
            .doesNotContain("--disableHostNameVerification");
    }

    @Test
    void thePasswordPlusNewlineIsWrittenToStdinWhichIsThenClosed() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
        assertThat(launcher.last().stdinClosed()).isTrue();
    }

    @Test
    void javaHomeComesFromCliJavaHome() {
        parentEnv.put("JAVA_HOME", "/opt/other-jdk");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        assertThat(launcher.last().spec().environment())
            .containsEntry("JAVA_HOME", JAVA_HOME.toString());
    }

    @Test
    void javaHomeFallsBackToTheServersOwnJavaHome() {
        parentEnv.put("JAVA_HOME", "/opt/own-jdk");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, TestNodeConfig.node().cliHome(cliHome).build());

        assertThat(launcher.last().spec().environment())
            .containsEntry("JAVA_HOME", "/opt/own-jdk");
    }

    @Test
    void withoutAnyJavaHomeTheCliIsUnavailableAndNothingIsLaunched() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        ToolError error = errorOf(() -> run(launcher,
            TestNodeConfig.node().cliHome(cliHome).build()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.toString()).contains("cli.javaHome");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void javaToolOptionsForceTheEnglishLocaleEvenIfTheParentSetsAnotherValue() {
        parentEnv.put("JAVA_TOOL_OPTIONS", "-Duser.language=de -javaagent:/evil.jar");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        assertThat(launcher.last().spec().environment())
            .containsEntry("JAVA_TOOL_OPTIONS", Platform.windows()
                ? StartCliEnvironment.WINDOWS_JAVA_TOOL_OPTIONS
                : "-Duser.language=en -Duser.country=US");
    }

    /** Windows: {@link #onWindowsTheEnvironmentIsTheWindowsAllowlist()}. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the POSIX allowlist; the Windows one is"
        + " checked on every host with the simulated Windows runner")
    void theChildEnvironmentIsAnAllowlist() {
        parentEnv.put("INUBIT_DEV_PASSWORD", "dev-secret");
        parentEnv.put("INUBIT_QA_NODE2_PASSWORD", "qa-secret");
        parentEnv.put("INUBIT_DEV_USERNAME", "jdoe");
        parentEnv.put("MY_DB_PASSWORD", "db-secret");
        parentEnv.put("GITHUB_TOKEN", "ghp_token");
        parentEnv.put("CLASSPATH", "/evil");
        parentEnv.put("SHELL", "/bin/zsh");
        parentEnv.put("DYLD_INSERT_LIBRARIES", "/evil.dylib");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        Map<String, String> env = launcher.last().spec().environment();
        assertThat(env.keySet()).containsExactlyInAnyOrder("PATH", "HOME", "TMPDIR", "LANG",
            "LC_ALL", "USER", "JAVA_HOME", "JAVA_TOOL_OPTIONS");
        assertThat(env).containsEntry("PATH", Platform.searchPath()).containsEntry("USER", "jdoe")
            .containsEntry("LANG", "de_DE.UTF-8");
        assertThat(env.values()).doesNotContain("dev-secret", "qa-secret", "db-secret",
            "ghp_token", "/evil", "/evil.dylib");
    }

    @Test
    void unsetParentVariablesAreNotInvented() {
        parentEnv.remove("LC_ALL");
        parentEnv.remove("TMPDIR");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        assertThat(launcher.last().spec().environment()).doesNotContainKeys("LC_ALL", "TMPDIR");
    }

    @Test
    void theWorkingDirectoryIsTheCliHome() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        run(launcher, server().build());

        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(cliHome);
    }

    @Test
    void theResultCarriesExitCodeStdoutStderrAndDuration() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("login_failed");

        CliResult result = run(launcher, server().build());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout())
            .isEqualTo(FakeProcessLauncher.fixtureText("login_failed.stdout"));
        assertThat(result.stderr()).startsWith("Picked up JAVA_TOOL_OPTIONS");
        assertThat(result.duration().isNegative()).isFalse();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aTimeoutDestroysTheProcessAndReportsTimeout() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();

        ToolError error = errorOf(() -> runner(launcher).run(server().build(), USER,
            Secret.of(PASSWORD), CliCommand.kill("1"), Duration.ofMillis(200), guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(launcher.last().destroyed()).isTrue();
        assertThat(launcher.last().destroyedForcibly()).isFalse();
    }

    @Test
    void aProcessIgnoringDestroyIsKilledForciblyAfterTwoSeconds() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).ignoringDestroy();

        ToolError error = errorOf(() -> runner(launcher).run(server().build(), USER,
            Secret.of(PASSWORD), CliCommand.kill("1"), Duration.ofMillis(200), guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(launcher.last().forcibleKillDelay()).hasValueSatisfying(delay ->
            assertThat(delay).isBetween(Duration.ofMillis(1900), Duration.ofMillis(4000)));
    }

    @Test
    void anInterruptedCallDestroysTheProcess() throws InterruptedException {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                runner(launcher).run(server().build(), USER, Secret.of(PASSWORD),
                    CliCommand.kill("1"), Duration.ofSeconds(20), guard());
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });
        while (launcher.launchCount() == 0) {
            Thread.sleep(10);
        }
        Thread.sleep(100);

        caller.interrupt();
        caller.join(5000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(launcher.last().destroyed()).isTrue();
        assertThat(thrown.get()).isInstanceOfSatisfying(ToolErrorException.class,
            e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
    }

    @Test
    void stdoutAndStderrAreBoundedToOneMegabyteEachWhileTheProcessIsDrained() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).producing(3 << 20);

        CliResult result = runner(launcher).run(server().build(), USER, Secret.of(PASSWORD),
            CliCommand.kill("1"), Duration.ofSeconds(20), guard());

        assertThat(result.stdout().length()).isEqualTo(CliRunner.MAX_OUTPUT_BYTES);
        assertThat(result.stderr().length()).isEqualTo(CliRunner.MAX_OUTPUT_BYTES);
        assertThat(result.truncated()).isTrue();
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void withoutCliHomeTheCliIsUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        ToolError error = errorOf(() -> run(launcher, TestNodeConfig.node()
            .cliJavaHome(JAVA_HOME).build()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aMissingStartCliScriptMakesTheCliUnavailable() throws IOException {
        Files.delete(script);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        ToolError error = errorOf(() -> run(launcher, server().build()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.toString()).contains(script.getFileName().toString());
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aFailedLaunchMakesTheCliUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok")
            .failingToLaunch(new IOException("error=13, Permission denied " + PASSWORD));

        assertThat(errorOf(() -> run(launcher, server().build())).code())
            .isEqualTo(ErrorCode.CLI_UNAVAILABLE);
    }

    @Test
    void survivorsOfTheTreeAreKilledForciblyEvenWhenTheRootExited() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).leavingSurvivor();

        ToolError error = errorOf(() -> runner(launcher).run(server().build(), USER,
            Secret.of(PASSWORD), CliCommand.kill("1"), Duration.ofMillis(200), guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(launcher.last().destroyed()).isTrue();
        assertThat(launcher.last().forcibleKillDelay()).hasValueSatisfying(delay ->
            assertThat(delay).isBetween(Duration.ofMillis(1900), Duration.ofMillis(4000)));
    }

    @Test
    void anInvalidCommandValueFailsBeforeAnyLaunch() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        assertThat(errorOf(() -> runner(launcher).run(server().build(), USER,
            Secret.of(PASSWORD), CliCommand.command("export")
                .quoted("--exportWorkflowGroup", "x' ; kill 1 ; '").build(),
            Duration.ofSeconds(5), guard())).code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(launcher.launchCount()).isZero();
    }

    // --- feature 013 T013: the Windows launch, on every host with a simulated Windows runner ---

    private static final String SYSTEM_DIRECTORY = TestCliRunners.SYSTEM_DIRECTORY;
    private static final String URL =
        "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet";
    private static final String COMMAND = "processErrorStart 110190387";

    /** {@code <cliHome>/bin/startcli.bat}, which the Windows runner starts. */
    private Path batchFile(Path home) throws IOException {
        Path bat = Files.createDirectories(home.resolve("bin")).resolve("startcli.bat");
        Files.writeString(bat, "@echo off\r\n");
        return bat;
    }

    private CliRunner windowsRunner(FakeProcessLauncher launcher) {
        return TestCliRunners.simulatedWindows(launcher, parentEnv,
            new ProcessContainment.Bound(), new CliResources("acme"));
    }

    private CliResult runOnWindows(CliRunner runner, EffectiveNodeConfig config,
        CliCommand command) {
        return runner.run(config, USER, Secret.of(PASSWORD), command, Duration.ofSeconds(5),
            guard());
    }

    /** {@code export} with diagram groups, whose command line has exactly {@code length} chars. */
    private static CliCommand commandOfLength(int length) {
        CliCommand.Builder builder = CliCommand.command("export");
        int remaining = length - "export".length();
        // each group adds " --exportWorkflowGroup '<value>'": 25 characters plus the value
        while (remaining > 225) {
            int value = Math.min(190, remaining - 25 - 26);
            builder.quoted("--exportWorkflowGroup", "G".repeat(value));
            remaining -= 25 + value;
        }
        builder.quoted("--exportWorkflowGroup", "G".repeat(remaining - 25));
        CliCommand command = builder.build();
        assertThat(command.commandLine()).hasSize(length);
        return command;
    }

    /**
     * The length of the command that makes the inner line of {@code -u <user> --execCommand
     * <command> <url>} exactly {@link WindowsCommandLine#MAX_INNER_LENGTH} long: the outer
     * quotes, {@code "<script>"} and {@code  "<arg>"} per argument.
     */
    private int longestCommand(Path bat) {
        int configured = 4 + bat.toString().length()
            + List.of("-u", USER, "--execCommand", URL).stream()
                .mapToInt(argument -> 3 + argument.length()).sum();
        return WindowsCommandLine.MAX_INNER_LENGTH - configured - 3;
    }

    private ToolError windowsRefusal(EffectiveNodeConfig config) {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        ToolError error = errorOf(() -> runOnWindows(windowsRunner(launcher), config,
            CliCommand.processErrorStart("110190387")));
        assertThat(launcher.launchCount()).as("nothing is started").isZero();
        return error;
    }

    @Test
    void onWindowsTheInterpreterStartsTheBatchFileWithEveryArgumentQuoted() throws IOException {
        Path bat = batchFile(cliHome);
        Path trustStore = Path.of("C:\\certs\\trust store.p12");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        runOnWindows(windowsRunner(launcher), server().trustStore(trustStore)
            .disableHostnameVerification("AB".repeat(32)).build(),
            CliCommand.processErrorStart("110190387"));

        ProcessLauncher.LaunchSpec spec = launcher.last().spec();
        assertThat(spec.command()).containsExactly(SYSTEM_DIRECTORY + "\\cmd.exe", "/d",
            "/v:off", "/s", "/c",
            "\"\"" + bat + "\" \"-u\" \"" + USER + "\" \"--trustStoreFilePath\" \"" + trustStore
                + "\" \"--disableHostNameVerification\" \"--execCommand\" \"" + COMMAND + "\" \""
                + URL + "\"\"");
        assertThat(spec.startCliArguments()).containsExactly("-u", USER, "--trustStoreFilePath",
            trustStore.toString(), "--disableHostNameVerification", "--execCommand", COMMAND,
            URL);
        assertThat(spec.workingDirectory()).isEqualTo(cliHome);
        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
        assertThat(String.join(" ", spec.command())).doesNotContain(PASSWORD);
    }

    @Test
    void onWindowsTheEnvironmentIsTheWindowsAllowlist() throws IOException {
        batchFile(cliHome);
        parentEnv.put("SystemRoot", "C:\\Windows");
        parentEnv.put("USERPROFILE", "C:\\Users\\jdoe");
        parentEnv.put("INUBIT_DEV_PASSWORD", "dev-secret");
        parentEnv.put("JAVA_TOOL_OPTIONS", "-javaagent:C:\\evil.jar");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        runOnWindows(windowsRunner(launcher), server().build(),
            CliCommand.processErrorStart("110190387"));

        Map<String, String> environment = launcher.last().spec().environment();
        assertThat(environment).isEqualTo(StartCliEnvironment.windows(parentEnv,
            JAVA_HOME.toString(), SYSTEM_DIRECTORY));
        assertThat(environment).containsEntry("PATH",
                JAVA_HOME + "\\bin;" + SYSTEM_DIRECTORY)
            .containsEntry("ComSpec", SYSTEM_DIRECTORY + "\\cmd.exe")
            .containsEntry("JAVA_TOOL_OPTIONS", StartCliEnvironment.WINDOWS_JAVA_TOOL_OPTIONS)
            .containsEntry("USERPROFILE", "C:\\Users\\jdoe")
            .doesNotContainKeys("HOME", "INUBIT_DEV_PASSWORD");
        assertThat(environment.values()).doesNotContain("dev-secret", PASSWORD);
    }

    @Test
    void onWindowsTheServersJavaHomeIsFoundInAnySpelling() throws IOException {
        batchFile(cliHome);
        parentEnv.put("Java_Home", "C:\\Program Files\\Java\\jdk-17");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        runOnWindows(windowsRunner(launcher), TestNodeConfig.node().cliHome(cliHome).build(),
            CliCommand.processErrorStart("110190387"));

        assertThat(launcher.last().spec().environment())
            .containsEntry("JAVA_HOME", "C:\\Program Files\\Java\\jdk-17")
            .containsEntry("PATH", "C:\\Program Files\\Java\\jdk-17\\bin;" + SYSTEM_DIRECTORY);
    }

    @Test
    void onWindowsTheOutputIsDecodedWithCrlfAsLf() throws IOException {
        batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.of("line 1\r\nline 2\r\n",
            "warning\r\n", 0);

        CliResult result = runOnWindows(windowsRunner(launcher), server().build(),
            CliCommand.processErrorStart("110190387"));

        assertThat(result.stdout()).isEqualTo("line 1\nline 2\n");
        assertThat(result.stderr()).isEqualTo("warning\n");
    }

    @Test
    void onWindowsTheLongestAllowedCommandRuns() throws IOException {
        Path bat = batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        runOnWindows(windowsRunner(launcher), server().cliUrl(URL).build(),
            commandOfLength(longestCommand(bat)));

        assertThat(launcher.launchCount()).isOne();
        assertThat(launcher.last().spec().command().get(5))
            .hasSize(WindowsCommandLine.MAX_INNER_LENGTH);
    }

    @Test
    void onWindowsAToolInputOverTheLimitIsInvalidInputAndNothingStarts() throws IOException {
        Path bat = batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        CliCommand command = commandOfLength(longestCommand(bat) + 1);

        ToolError error = errorOf(() -> runOnWindows(windowsRunner(launcher),
            server().cliUrl(URL).build(), command));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("6144", "6143");
        assertThat(error.toString()).doesNotContain("GGGG");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void onWindowsConfiguredValuesOverTheLimitAreCliUnavailableNamingTheSetting()
        throws IOException {
        batchFile(cliHome);
        Path trustStore = Path.of("C:\\" + "t".repeat(6200) + ".p12");

        ToolError error = windowsRefusal(server().trustStore(trustStore).build());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("dev/node1",
            "the StartCLI command line would exceed 6143 characters (tls.trustStore)");
        assertThat(error.toString()).doesNotContain("tttt");
    }

    @Test
    void onWindowsACliHomeWithAnInterpretedCharacterIsRefusedWithoutTheValue()
        throws IOException {
        Path home = Files.createDirectories(cliHome.resolve("client%PATH%"));
        batchFile(home);

        ToolError error = windowsRefusal(server().cliHome(home).build());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("cli.home contains a character the Windows command"
            + " interpreter interprets (percent sign)");
        assertThat(error.toString()).doesNotContain("client%PATH%");
    }

    @Test
    void onWindowsANetworkCliHomeIsRefused() {
        Path unc = Path.of("\\\\fileserver\\share\\inubit-client");

        ToolError error = windowsRefusal(server().cliHome(unc).build());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("cli.home is a network path (\\\\…)");
        assertThat(error.nextStep()).contains("local drive");
        assertThat(error.toString()).doesNotContain("fileserver");
    }

    @Test
    void onWindowsATrustStoreWithAnInterpretedCharacterIsRefusedWithoutTheValue()
        throws IOException {
        batchFile(cliHome);

        ToolError error = windowsRefusal(server()
            .trustStore(Path.of("C:\\certs\\a&calc.p12")).build());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("tls.trustStore contains a character the Windows"
            + " command interpreter interprets (ampersand)");
        assertThat(error.toString()).doesNotContain("calc");
    }

    @Test
    void onWindowsAStartCliUrlWithAnInterpretedCharacterIsRefusedWithoutTheValue()
        throws IOException {
        batchFile(cliHome);

        ToolError error = windowsRefusal(server()
            .cliUrl("https://cli.example.test/ibis!x!/servlet/IBISSoapServlet").build());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("cli.url contains a character the Windows command"
            + " interpreter interprets (exclamation mark)");
        assertThat(error.toString()).doesNotContain("ibis!x!");
    }

    @Test
    void onWindowsAJavaHomeWithASemicolonIsRefusedBecauseItGoesIntoPath() throws IOException {
        batchFile(cliHome);

        ToolError error = windowsRefusal(server()
            .cliJavaHome(Path.of("C:\\jdk17;decoy")).build());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("cli.javaHome contains a character the Windows"
            + " command interpreter interprets (semicolon)");
        assertThat(error.toString()).doesNotContain("decoy");
    }

    @Test
    void onWindowsTheServersJavaHomeWithAnInterpretedCharacterIsRefusedNamingJavaHome()
        throws IOException {
        batchFile(cliHome);
        parentEnv.put("JAVA_HOME", "C:\\jdk^17");

        ToolError error = windowsRefusal(TestNodeConfig.node().cliHome(cliHome).build());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("JAVA_HOME contains a character the Windows"
            + " command interpreter interprets (caret)");
        assertThat(error.toString()).doesNotContain("jdk^17");
    }

    @Test
    void onWindowsUnboundStartCliProcessesMakeTheCliUnavailable() throws IOException {
        batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        CliRunner runner = TestCliRunners.simulatedWindows(launcher, parentEnv,
            new ProcessContainment.Unavailable("AssignProcessToJobObject failed (Windows error"
                + " 5)"), new CliResources("acme"));

        ToolError check = errorOf(() -> runner.checkAvailable(server().build()));
        ToolError run = errorOf(() -> runOnWindows(runner, server().build(),
            CliCommand.processErrorStart("110190387")));

        assertThat(check.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(run).isEqualTo(check);
        assertThat(run.message()).contains("StartCLI processes could not be bound to the server"
            + " (AssignProcessToJobObject failed (Windows error 5))");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void buildingAWindowsRunnerAsksTheSystemNothing() {
        CliSystem untouched = new CliSystem() {
            @Override
            public SystemDirectory systemDirectory() {
                throw new AssertionError("system directory read while building the runner");
            }

            @Override
            public java.nio.charset.Charset oemCharset() {
                throw new AssertionError("OEM code page read while building the runner");
            }
        };

        new CliRunner(FakeProcessLauncher.replaying("processErrorStart_ok"), parentEnv, true,
            untouched, ProcessContainment.Bound::new, new CliResources("acme"));
    }

    @Test
    void onWindowsAnUnreadableSystemDirectoryMakesTheCliUnavailable() throws IOException {
        batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        CliRunner runner = new CliRunner(launcher, parentEnv, true,
            new TestCliRunners.FixedSystem(new CliSystem.Unreadable(
                "GetSystemDirectoryW failed (Windows error 87)"), StandardCharsets.ISO_8859_1),
            ProcessContainment.Bound::new, new CliResources("acme"));

        ToolError error = errorOf(() -> runOnWindows(runner, server().build(),
            CliCommand.processErrorStart("110190387")));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("the Windows system directory could not be read"
            + " (GetSystemDirectoryW failed (Windows error 87))");
        assertThat(launcher.launchCount()).isZero();
    }

    /**
     * The client directory without a batch file is a fresh one: on a Windows host the
     * {@code @BeforeEach} substitute is a real {@code bin\\startcli.bat} in {@link #cliHome}.
     */
    @Test
    void onWindowsTheNextStepsNameTheBatchFileOrTheInterpreter() throws IOException {
        Path withoutScript = Files.createDirectories(cliHome.resolve("without-script"));
        FakeProcessLauncher missing = FakeProcessLauncher.replaying("processErrorStart_ok");
        ToolError notFound = errorOf(() -> runOnWindows(windowsRunner(missing),
            server().cliHome(withoutScript).build(), CliCommand.processErrorStart("110190387")));
        batchFile(cliHome);
        FakeProcessLauncher failing = FakeProcessLauncher.replaying("processErrorStart_ok")
            .failingToLaunch(new IOException("CreateProcess error=267"));
        ToolError notStarted = errorOf(() -> runOnWindows(windowsRunner(failing),
            server().build(), CliCommand.processErrorStart("110190387")));

        assertThat(notFound.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(notFound.nextStep()).contains("bin\\startcli.bat").doesNotContain(".sh");
        assertThat(missing.launchCount()).isZero();
        // the JDK could not create the interpreter process: its path or the working directory
        assertThat(notStarted.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(notStarted.likelyCause()).contains(SYSTEM_DIRECTORY + "\\cmd.exe",
            "cli.home");
        assertThat(notStarted.likelyCause() + notStarted.nextStep())
            .doesNotContain("startcli.sh", "permissions", "cli.javaHome");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the POSIX runner of the host")
    void onPosixTheWindowsCharacterRulesDoNotApply() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        Path trustStore = Path.of("/etc/inubit/trust%x%&!.p12");

        run(launcher, server().trustStore(trustStore).build());

        assertThat(launcher.last().spec().startCliArguments()).contains(trustStore.toString());
        assertThat(launcher.last().spec().command().get(0)).isEqualTo(script.toString());
    }

    // --- feature 014 T012: the StartCLI pin probe of a marked stage (FR-005a, R-15) -------------

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final String PIN_A = TestCertificates.fingerprint(CERTS.localhostCertificate());

    /** A probe that only counts its calls. */
    private static final class CountingProbe implements StartCliPinProbe {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void check(EffectiveNodeConfig server) {
            calls.incrementAndGet();
        }

        int calls() {
            return calls.get();
        }
    }

    private CliRunner runner(FakeProcessLauncher launcher, StartCliPinProbe probe) {
        return TestCliRunners.forHost(launcher, parentEnv, new CliResources("acme"), probe);
    }

    /** {@code server} as a server of a stage marked with {@code tls.pinPerServer}. */
    private static EffectiveNodeConfig marked(EffectiveNodeConfig server) {
        EffectiveNodeConfig.Tls tls = server.tls();
        return new EffectiveNodeConfig(server.id(), server.production(), server.baseUrl(),
            server.allowInsecureHttp(), server.versionLine(), server.write(),
            new EffectiveNodeConfig.Tls(tls.trustStore(), tls.disableHostnameVerification(),
                tls.pinnedCertificateSha256(), true),
            server.cli(), server.inventory(), server.timeout(), server.cliTimeout(),
            server.cliExportTimeout(), server.hangingThreshold(), server.confirmationTtl(),
            server.credentialPrefix(), server.development(), server.secretPlan(),
            server.archivesProductionOptIn());
    }

    /** {@code int/a} with pin A, the shared stage trust store and its StartCLI at {@code port}. */
    private TestNodeConfig stageServerA(int port) {
        return server().id("int/a")
            .baseUrl("https://localhost:" + port)
            .trustStore(CERTS.trustStore())
            .pin(PIN_A);
    }

    private CliResult runOn(CliRunner runner, EffectiveNodeConfig config, CredentialGuard guard) {
        return runner.run(config, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard);
    }

    @Test
    void aMarkedServerWhoseStartCliAddressPresentsTheOtherServersCertificateIsNotStarted()
        throws IOException, GeneralSecurityException, InterruptedException {
        try (TlsTestServer other = TlsTestServer.start(CERTS.selfsignedKeyStore())) {
            FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
            EffectiveNodeConfig swapped = marked(stageServerA(other.port()).build());

            ToolError error = errorOf(() -> runOn(runner(launcher,
                new HandshakeStartCliPinProbe()), swapped, guard()));

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(error.message()).contains("int/a").contains("(CertificateException)")
                .contains("StartCLI was not started");
            assertThat(launcher.launchCount()).isZero();
        }
    }

    @Test
    void aMarkedServerWhoseStartCliAddressIsUnreachableIsNotStarted() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        EffectiveNodeConfig unreachable = marked(stageServerA(closedPort).build());

        ToolError error = errorOf(() -> runOn(runner(launcher, new HandshakeStartCliPinProbe()),
            unreachable, guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aMarkedServerWithItsOwnCertificateStartsStartCliWithTheUsualArguments()
        throws IOException, GeneralSecurityException, InterruptedException {
        try (TlsTestServer own = TlsTestServer.start(CERTS.localhostKeyStore())) {
            FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
            EffectiveNodeConfig server = marked(stageServerA(own.port()).build());

            runOn(runner(launcher, new HandshakeStartCliPinProbe()), server, guard());

            assertThat(own.handshakes()).isEqualTo(1);
            assertThat(launcher.launchCount()).isEqualTo(1);
            // feature 013: on Windows the script runs through the interpreter
            assertThat(launcher.last().spec().startCliArguments()).containsExactly("-u", USER,
                "--trustStoreFilePath", CERTS.trustStore().toString(),
                "--execCommand", "processErrorStart 110190387",
                "https://localhost:" + own.port() + EffectiveNodeConfig.DEFAULT_CLI_PATH);
        }
    }

    @Test
    void theProbeRunsBeforeTheGuardSoItDoesNotWaitForARunningFirstLogin()
        throws IOException, GeneralSecurityException, InterruptedException {
        try (TlsTestServer other = TlsTestServer.start(CERTS.selfsignedKeyStore())) {
            FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
            EffectiveNodeConfig swapped = marked(stageServerA(other.port()).build());
            CliRunner runner = runner(launcher, new HandshakeStartCliPinProbe());
            CredentialGuard guard = guard();
            ToolError error;

            try (CredentialGuard.Permit firstLogin = guard.acquire()) {
                error = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> errorOf(() -> runOn(runner, swapped, guard)));
            }

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(launcher.launchCount()).isZero();
            assertThat(guard.confirmed()).isFalse();
            runOn(runner(launcher, new CountingProbe()), swapped, guard);
            assertThat(launcher.launchCount()).as("not refused with a cached AUTH_FAILED")
                .isEqualTo(1);
        }
    }

    @Test
    void anUnmarkedServerIsNeverProbedAndLaunchesExactlyAsBefore() {
        CountingProbe probe = new CountingProbe();
        FakeProcessLauncher before = FakeProcessLauncher.replaying("processErrorStart_ok");
        FakeProcessLauncher after = FakeProcessLauncher.replaying("processErrorStart_ok");
        EffectiveNodeConfig server = server().trustStore(CERTS.trustStore())
            .disableHostnameVerification(PIN_A).build();

        runOn(runner(before), server, guard());
        runOn(runner(after, probe), server, guard());
        runner(after, probe).run(server, USER, Secret.of(PASSWORD), CliCommand.kill("1"),
            Duration.ofSeconds(5), "cliExportTimeout", guard());

        assertThat(probe.calls()).isZero();
        assertThat(after.processes().get(0).spec()).isEqualTo(before.last().spec());
        assertThat(after.processes().get(0).stdinBytes()).isEqualTo(before.last().stdinBytes());
    }

    @Test
    void theClientVersionProbeNeverCallsThePinProbe() {
        CountingProbe probe = new CountingProbe();
        FakeProcessLauncher launcher = FakeProcessLauncher.of("8.1.0", "", 0);
        EffectiveNodeConfig server = marked(server().trustStore(CERTS.trustStore())
            .pin(PIN_A).build());

        runner(launcher, probe).execute(server, List.of("-v"), Optional.empty(),
            Duration.ofSeconds(5));

        assertThat(probe.calls()).isZero();
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void eachRunSignatureProbesAMarkedServerOnce() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        EffectiveNodeConfig server = marked(server().trustStore(CERTS.trustStore())
            .pin(PIN_A).build());
        CountingProbe probe = new CountingProbe();
        CountingProbe probeWithSetting = new CountingProbe();

        runner(launcher, probe).run(server, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard());
        runner(launcher, probeWithSetting).run(server, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5),
            "cliExportTimeout", guard());

        assertThat(probe.calls()).isEqualTo(1);
        assertThat(probeWithSetting.calls()).isEqualTo(1);
        assertThat(launcher.launchCount()).isEqualTo(2);
    }

    @Test
    void theFourArgumentConstructorUsesTheHandshakeProbe()
        throws IOException, GeneralSecurityException, InterruptedException {
        try (TlsTestServer other = TlsTestServer.start(CERTS.selfsignedKeyStore())) {
            FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
            EffectiveNodeConfig swapped = marked(stageServerA(other.port()).build());

            ToolError error = errorOf(() -> runOn(runner(launcher), swapped, guard()));

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(launcher.launchCount()).isZero();
        }
    }
}
