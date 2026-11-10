package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
        return TestCliRunners.forHost(launcher, parentEnv,
            new CliResources("acme", PrivateFiles.forHost()));
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
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
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
        // feature 012 (T016): also the 6.1 session
        List<Method> runs = Arrays.stream(CliRunner.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("run")
                || method.getName().equals("runSession"))
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
    void theTrustStoreAlsoReachesTheClientThroughJavaToolOptions() throws Exception {
        // the INUBIT 9.0.10 client ignores --trustStoreFilePath for its login connection
        // (T047 finding F-1, 2026-11-09); the JVM-wide trust store reaches it
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        Path trustStore = trustStoreFile("trust.p12", "PKCS12");

        run(launcher, server().trustStore(trustStore).build());

        assertThat(launcher.last().spec().startCliArguments())
            .containsSubsequence("--trustStoreFilePath", trustStore.toString());
        assertThat(launcher.last().spec().environment()).containsEntry("JAVA_TOOL_OPTIONS",
            TOOL_OPTIONS + " -Djavax.net.ssl.trustStore=" + trustStore
                + " -Djavax.net.ssl.trustStoreType=PKCS12");
    }

    @Test
    void aTrustStorePathWithWhitespaceIsPassedOnlyAsArgument() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        Path trustStore = cliHome.resolve("trust store.p12");

        run(launcher, server().trustStore(trustStore).build());

        assertThat(launcher.last().spec().startCliArguments())
            .containsSubsequence("--trustStoreFilePath", trustStore.toString());
        assertThat(launcher.last().spec().environment())
            .containsEntry("JAVA_TOOL_OPTIONS", TOOL_OPTIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trust's.p12", "trust\"s.p12"})
    void aTrustStorePathWithAQuoteIsPassedOnlyAsArgument(String name) {
        // Java 9+ reads ' and " in JAVA_TOOL_OPTIONS as quotes; an unpaired one stops the JVM
        // (Windows CI, WindowsCommandLineInjectionTest TRUST_STORE with APOSTROPHE)
        assumeFalse(Platform.windows() && name.contains("\""), "no \" in a Windows path");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        Path trustStore = cliHome.resolve(name);

        run(launcher, server().trustStore(trustStore).build());

        assertThat(launcher.last().spec().startCliArguments())
            .containsSubsequence("--trustStoreFilePath", trustStore.toString());
        assertThat(launcher.last().spec().environment())
            .containsEntry("JAVA_TOOL_OPTIONS", TOOL_OPTIONS);
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

    /** The cause and next step of a timeout as on macOS and Linux (before feature 013). */
    private static final String TIMEOUT_CAUSE =
        "The INUBIT server is slow or unreachable, or StartCLI waits for input";
    private static final String TIMEOUT_NEXT_STEP =
        "Check get_health; raise cliTimeout if the INUBIT server is just slow";

    /** T023 (feature 013, FR-005, US4 AS6): the client cannot be told from a slow server. */
    @Test
    void onWindowsATimeoutAfterThePasswordPromptNamesBothPossibilities() throws IOException {
        batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            "JAVA_HOME is set\r\nPassword: ", "", 0).hanging();

        ToolError error = errorOf(() -> windowsRunner(launcher).run(server().build(), USER,
            Secret.of(PASSWORD), CliCommand.kill("1"), Duration.ofMillis(200), guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).startsWith("StartCLI did not finish within");
        assertThat(error.likelyCause())
            .contains("waiting for the password on its console")
            .contains("the INUBIT server is slow");
        assertThat(error.nextStep()).startsWith(TIMEOUT_NEXT_STEP)
            .contains("docs/live-tests.md").contains("live check");
        assertThat(error.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void onWindowsATimeoutWithOtherLastOutputKeepsTheMessage() throws IOException {
        batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.of("Password: \r\nConnecting\r\n",
            "", 0).hanging();

        ToolError error = errorOf(() -> windowsRunner(launcher).run(server().build(), USER,
            Secret.of(PASSWORD), CliCommand.kill("1"), Duration.ofMillis(200), guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.likelyCause()).isEqualTo(TIMEOUT_CAUSE);
        assertThat(error.nextStep()).isEqualTo(TIMEOUT_NEXT_STEP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Password: "})
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the POSIX runner of the host; the Windows"
        + " cases are onWindowsATimeout…")
    void onPosixATimeoutKeepsTheMessageWithOrWithoutThePrompt(String stdout) {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(stdout, "", 0).hanging();

        ToolError error = errorOf(() -> runner(launcher).run(server().build(), USER,
            Secret.of(PASSWORD), CliCommand.kill("1"), Duration.ofMillis(200), guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.likelyCause()).isEqualTo(TIMEOUT_CAUSE);
        assertThat(error.nextStep()).isEqualTo(TIMEOUT_NEXT_STEP);
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
        assertThat(result.outputBound()).isEqualTo(CliRunner.MAX_OUTPUT_BYTES);
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

    // --- interactive session on INUBIT 6.1 (feature 012, T016, research D-5) ---------------

    /** The JAVA_TOOL_OPTIONS every StartCLI process of this platform gets (feature 013). */
    private static final String TOOL_OPTIONS = Platform.windows()
        ? StartCliEnvironment.WINDOWS_JAVA_TOOL_OPTIONS : StartCliEnvironment.JAVA_TOOL_OPTIONS;

    /**
     * {@code spec} hands StartCLI exactly {@code arguments}; off Windows its command is the script
     * and these arguments, on Windows the command interpreter's last argument starts with the
     * quoted script (the quoting itself is covered by the Windows tests below).
     */
    private void assertStartCli(ProcessLauncher.LaunchSpec spec, String... arguments) {
        assertThat(spec.startCliArguments()).containsExactly(arguments);
        if (Platform.windows()) {
            assertThat(spec.command().getLast()).startsWith("\"\"" + script + "\"");
        } else {
            assertThat(spec.command()).containsExactlyElementsOf(
                Stream.concat(Stream.of(script.toString()), Stream.of(arguments)).toList());
        }
    }

    private static final String SESSION_OK = "Password: \nuname output\n";
    private static final String SESSION_LOGIN_FAILED = "Password: \n"
        + "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailure@@@The user does not"
        + " exist or password does not match.@End@\n";
    private static final List<CliSessionCommand> HEALTH_COMMANDS = List.of(
        CliSessionCommand.UNAME, CliSessionCommand.psCount(CliSessionCommand.Listing.ERROR),
        CliSessionCommand.psList(CliSessionCommand.Listing.ALL, 10_000));

    /** A client with its own trust-store and host-name options. */
    private static final CliClientOptions ALL_OPTIONS =
        new CliClientOptions(true, true, false, Optional.of(8));
    /** A client without either option (trust store through its runtime's settings). */
    private static final CliClientOptions NO_OPTIONS =
        new CliClientOptions(false, false, false, Optional.of(8));

    private CliResult runSession(FakeProcessLauncher launcher, CredentialGuard guard,
        Duration timeout) {
        return runner(launcher).runSession(server().build(), USER, Secret.of(PASSWORD),
            ALL_OPTIONS, HEALTH_COMMANDS, timeout, CliRunner.MAX_OUTPUT_BYTES, guard);
    }

    @Test
    void aSessionPassesOnlyTheUserAndTheCliUrl() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_OK, "", 0);

        runSession(launcher, guard(), Duration.ofSeconds(5));

        assertStartCli(launcher.last().spec(), "-u", USER,
            "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet");
    }

    @Test
    void aSessionHasNeitherThePasswordNorAnyCommandTextInItsArguments() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_OK, "", 0);

        runSession(launcher, guard(), Duration.ofSeconds(5));

        String arguments = String.join(" ", launcher.last().spec().command());
        assertThat(launcher.last().spec().command())
            .doesNotContain("-p", "--password", "--execCommand");
        assertThat(arguments).doesNotContain(PASSWORD, "uname", "ps ", "exit");
    }

    @Test
    void aSessionWritesThePasswordLineTheCommandsAndExitToStdinWhichIsThenClosed() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_OK, "", 0);

        runSession(launcher, guard(), Duration.ofSeconds(5));

        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\nuname\nps -c -e\nps -a -l 10000\nexit\n");
        assertThat(launcher.last().stdinClosed()).isTrue();
    }

    @Test
    void aSessionSharesTheEnvironmentAllowlistAndNoPasswordReachesIt() {
        parentEnv.put("INUBIT_DEV_PASSWORD", PASSWORD);
        parentEnv.put("JAVA_TOOL_OPTIONS", "-javaagent:/evil.jar");
        FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_OK, "", 0);

        runSession(launcher, guard(), Duration.ofSeconds(5));

        Map<String, String> env = launcher.last().spec().environment();
        assertThat(env.keySet()).containsExactlyInAnyOrderElementsOf(Platform.windows()
            ? List.of("PATH", "ComSpec", "NoDefaultCurrentDirectoryInExePath", "JAVA_HOME",
                "JAVA_TOOL_OPTIONS")
            : List.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "USER", "JAVA_HOME",
                "JAVA_TOOL_OPTIONS"));
        assertThat(env).containsEntry("JAVA_HOME", JAVA_HOME.toString())
            .containsEntry("JAVA_TOOL_OPTIONS", TOOL_OPTIONS);
        assertThat(String.join(" ", env.values())).doesNotContain(PASSWORD);
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(cliHome);
    }

    @Test
    void aSessionLoginFailureIsAuthFailedAndRejectsTheCredentials() {
        CredentialGuard guard = guard();
        FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_LOGIN_FAILED, "", 1);

        CliResult result = runSession(launcher, guard, Duration.ofSeconds(5));

        assertThat(CliOutputClassifier.withGlobalScrubber().classify(result))
            .isInstanceOfSatisfying(CliOutcome.Failure.class,
                failure -> assertThat(failure.error().code()).isEqualTo(ErrorCode.AUTH_FAILED));
        ToolError cached = errorOf(() -> runSession(launcher, guard, Duration.ofSeconds(5)));
        assertThat(cached.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).as("no second StartCLI login").isEqualTo(1);
        assertThatThrownBy(guard::acquire).as("REST shares the guard")
            .isInstanceOf(ToolErrorException.class);
    }

    @Test
    void aSuccessfulSessionConfirmsTheCredentials() {
        CredentialGuard guard = guard();

        runSession(FakeProcessLauncher.of(SESSION_OK, "", 0), guard, Duration.ofSeconds(5));

        assertThat(guard.confirmed()).isTrue();
    }

    @Test
    void aPausedGuardRefusesTheSessionWithoutStartingStartCli() {
        CredentialGuard guard = guard();
        try (CredentialGuard.Permit permit = guard.acquire()) {
            permit.rejected();
        }
        FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_OK, "", 0);

        ToolError error = errorOf(() -> runSession(launcher, guard, Duration.ofSeconds(5)));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aSessionTimeoutDestroysTheProcessAndReportsTimeout() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();

        ToolError error = errorOf(() -> runSession(launcher, guard(), Duration.ofMillis(200)));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliTimeout");
        assertThat(launcher.last().destroyed()).isTrue();
    }

    @Test
    void aHealthSessionWithTheDefaultBoundIsStillCutAboveOneMebibyte() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).producing(3 << 20);

        CliResult result = runSession(launcher, guard(), Duration.ofSeconds(20));

        assertThat(result.stdout().length()).isEqualTo(CliRunner.MAX_OUTPUT_BYTES);
        assertThat(result.stderr().length()).isEqualTo(CliRunner.MAX_OUTPUT_BYTES);
        assertThat(result.truncated()).isTrue();
        assertThat(CliOutputClassifier.withGlobalScrubber().classify(result))
            .isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> {
                assertThat(failure.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(failure.error().message()).contains("exceeded 1 MiB");
            });
    }

    // --- output bound per session (feature 012, T046, research D-10) ----------------------

    private CliResult runSession(FakeProcessLauncher launcher, int maxOutputBytes) {
        return runner(launcher).runSession(server().build(), USER, Secret.of(PASSWORD),
            ALL_OPTIONS, HEALTH_COMMANDS, Duration.ofSeconds(20), maxOutputBytes, guard());
    }

    @Test
    void aSessionKeepsItsOutputUpToTheBoundOfTheCall() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).producing(3 << 20);

        CliResult result = runSession(launcher, 4 << 20);

        assertThat(result.stdout().length()).isEqualTo(3 << 20);
        assertThat(result.stderr().length()).isEqualTo(3 << 20);
        assertThat(result.truncated()).isFalse();
        assertThat(result.outputBound()).isEqualTo(4 << 20);
    }

    @Test
    void aSessionAboveItsBoundIsCutAndUnexpectedResponseNamingThatBound() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).producing(3 << 20);

        CliResult result = runSession(launcher, 2 << 20);

        assertThat(result.stdout().length()).isEqualTo(2 << 20);
        assertThat(result.truncated()).isTrue();
        assertThat(CliOutputClassifier.withGlobalScrubber().classify(result))
            .isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> {
                assertThat(failure.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(failure.error().message()).contains("exceeded 2 MiB")
                    .doesNotContain("1 MB");
            });
    }

    @Test
    void theExecCommandRunIsUnchangedBesideSessions() {
        // regression: the 8.1 argument array and stdin stay as before on a runner that also
        // ran a session
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        CliRunner runner = runner(launcher);
        Path trustStore = Path.of("/etc/inubit/trust.p12");
        EffectiveNodeConfig config = server().trustStore(trustStore)
            .disableHostnameVerification("AB".repeat(32)).build();
        runner.runSession(config, USER, Secret.of(PASSWORD), ALL_OPTIONS, HEALTH_COMMANDS,
            Duration.ofSeconds(5), CliRunner.MAX_OUTPUT_BYTES, guard());

        runner.run(config, USER, Secret.of(PASSWORD), CliCommand.processErrorStart("110190387"),
            Duration.ofSeconds(5), guard());

        FakeProcessLauncher.FakeProcess execCommand = launcher.last();
        assertThat(launcher.launchCount()).isEqualTo(2);
        assertStartCli(execCommand.spec(), "-u", USER,
            "--trustStoreFilePath", trustStore.toString(),
            "--disableHostNameVerification",
            "--execCommand", "processErrorStart 110190387",
            "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet");
        assertThat(new String(execCommand.stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
    }

    // --- client trust arguments on INUBIT 6.1 (feature 012, T018, research D-8) -----------

    private static final String PIN = "AB".repeat(32);
    private static final String URL =
        "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet";

    private FakeProcessLauncher.FakeProcess session(EffectiveNodeConfig config,
        CliClientOptions options) {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_OK, "", 0);
        runner(launcher).runSession(config, USER, Secret.of(PASSWORD), options, HEALTH_COMMANDS,
            Duration.ofSeconds(5), CliRunner.MAX_OUTPUT_BYTES, guard());
        return launcher.last();
    }

    /** A password-less trust store of {@code type}, written by the JDK's own key store. */
    private Path trustStoreFile(String name, String type)
        throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(type);
        store.load(null, null);
        Path file = Files.createDirectories(cliHome.resolve("trust")).resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, new char[0]);
        }
        return file;
    }

    @Test
    void aClientWithItsOwnTrustStoreOptionGetsTheTrustStoreAsArgument() {
        Path trustStore = cliHome.resolve("trust store.p12"); // an argument may contain spaces

        FakeProcessLauncher.FakeProcess process =
            session(server().trustStore(trustStore).build(), ALL_OPTIONS);

        assertStartCli(process.spec(), "-u", USER,
            "--trustStoreFilePath", trustStore.toString(), URL);
        assertThat(process.spec().environment())
            .containsEntry("JAVA_TOOL_OPTIONS", TOOL_OPTIONS);
    }

    @Test
    void aClientWithItsOwnTrustStoreOptionAlsoGetsTheTrustStoreThroughJavaToolOptions()
        throws Exception {
        // the INUBIT 9.0.10 client ignores --trustStoreFilePath for its login connection
        // (T047 finding F-1, 2026-11-09); the JVM-wide trust store reaches it
        Path trustStore = trustStoreFile("trust.p12", "PKCS12");

        FakeProcessLauncher.FakeProcess process =
            session(server().trustStore(trustStore).build(), ALL_OPTIONS);

        assertStartCli(process.spec(), "-u", USER,
            "--trustStoreFilePath", trustStore.toString(), URL);
        assertThat(process.spec().environment()).containsEntry("JAVA_TOOL_OPTIONS",
            TOOL_OPTIONS + " -Djavax.net.ssl.trustStore=" + trustStore
                + " -Djavax.net.ssl.trustStoreType=PKCS12");
    }

    @ParameterizedTest
    @ValueSource(strings = {"trust's.p12", "trust\"s.p12"})
    void aClientWithItsOwnTrustStoreOptionGetsATrustStorePathWithAQuoteOnlyAsArgument(
        String name) {
        assumeFalse(Platform.windows() && name.contains("\""), "no \" in a Windows path");
        Path trustStore = cliHome.resolve(name);

        FakeProcessLauncher.FakeProcess process =
            session(server().trustStore(trustStore).build(), ALL_OPTIONS);

        assertStartCli(process.spec(), "-u", USER,
            "--trustStoreFilePath", trustStore.toString(), URL);
        assertThat(process.spec().environment())
            .containsEntry("JAVA_TOOL_OPTIONS", TOOL_OPTIONS);
    }

    @Test
    void aClientWithoutTheTrustStoreOptionGetsAPkcs12TrustStoreThroughJavaToolOptions()
        throws Exception {
        Path trustStore = trustStoreFile("trust.p12", "PKCS12");

        FakeProcessLauncher.FakeProcess process =
            session(server().trustStore(trustStore).build(), NO_OPTIONS);

        assertStartCli(process.spec(), "-u", USER, URL);
        assertThat(process.spec().environment()).containsEntry("JAVA_TOOL_OPTIONS",
            TOOL_OPTIONS + " -Djavax.net.ssl.trustStore=" + trustStore
                + " -Djavax.net.ssl.trustStoreType=PKCS12");
    }

    @Test
    void aJksTrustStoreIsPassedWithItsType() throws Exception {
        Path trustStore = trustStoreFile("trust.jks", "JKS");

        FakeProcessLauncher.FakeProcess process =
            session(server().trustStore(trustStore).build(), NO_OPTIONS);

        assertThat(process.spec().environment()).containsEntry("JAVA_TOOL_OPTIONS",
            TOOL_OPTIONS + " -Djavax.net.ssl.trustStore=" + trustStore
                + " -Djavax.net.ssl.trustStoreType=JKS");
    }

    @Test
    void withoutATrustStoreNeitherTheArgumentNorTheRuntimeSettingIsPassed() {
        for (CliClientOptions options : List.of(ALL_OPTIONS, NO_OPTIONS)) {
            FakeProcessLauncher.FakeProcess process = session(server().build(), options);

            assertStartCli(process.spec(), "-u", USER, URL);
            assertThat(process.spec().environment()).as("%s", options)
                .containsEntry("JAVA_TOOL_OPTIONS", TOOL_OPTIONS);
        }
    }

    @Test
    void neverATrustStorePasswordInTheArgumentsOrTheEnvironment() throws Exception {
        parentEnv.put("INUBIT_DEV_TRUSTSTORE_PASSWORD", "ts-secret");
        parentEnv.put("JAVA_TOOL_OPTIONS", "-Djavax.net.ssl.trustStorePassword=ts-secret");
        Path trustStore = trustStoreFile("trust.p12", "PKCS12");

        for (CliClientOptions options : List.of(ALL_OPTIONS, NO_OPTIONS)) {
            FakeProcessLauncher.FakeProcess process =
                session(server().trustStore(trustStore).build(), options);

            String everything = String.join(" ", process.spec().command()) + " "
                + String.join(" ", process.spec().environment().values());
            assertThat(everything).as("%s", options)
                .doesNotContain("ts-secret", "trustStorePassword", PASSWORD);
        }
    }

    @Test
    void aTrustStorePathWithWhitespaceNeverReachesJavaToolOptions() {
        // V61Connection (T020) refuses such a node first; the runner defends itself anyway
        // because JAVA_TOOL_OPTIONS is split at whitespace without quoting on Java 7/8
        // a tab or line break is not even a valid Windows file name
        List<String> names = Platform.windows() ? List.of("trust store.p12")
            : List.of("trust store.p12", "trust\tstore.p12", "trust\nstore.p12");
        for (String name : names) {
            FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_OK, "", 0);
            CredentialGuard guard = guard();
            EffectiveNodeConfig config = server().trustStore(cliHome.resolve(name)).build();

            assertThatThrownBy(() -> runner(launcher).runSession(config, USER,
                Secret.of(PASSWORD), NO_OPTIONS, HEALTH_COMMANDS, Duration.ofSeconds(5),
                CliRunner.MAX_OUTPUT_BYTES, guard))
                .as(name).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(PASSWORD);
            assertThat(launcher.launchCount()).as(name).isZero();
            assertThat(guard.confirmed()).as(name).isFalse();
        }
    }

    @Test
    void hostNameVerificationIsSkippedWithTheClientsOptionForAPinnedNode() {
        FakeProcessLauncher.FakeProcess process =
            session(server().disableHostnameVerification(PIN).build(), ALL_OPTIONS);

        assertStartCli(process.spec(), "-u", USER,
            "--disableHostNameVerification", URL);
    }

    @Test
    void bothClientOptionsComeBeforeTheUrl() {
        Path trustStore = cliHome.resolve("trust.p12");

        FakeProcessLauncher.FakeProcess process = session(
            server().trustStore(trustStore).disableHostnameVerification(PIN).build(), ALL_OPTIONS);

        assertStartCli(process.spec(), "-u", USER,
            "--trustStoreFilePath", trustStore.toString(), "--disableHostNameVerification", URL);
    }

    @Test
    void hostNameVerificationIsNeverSkippedOtherwise() {
        // the runner refuses nothing (owner: V61Connection, T020); it only leaves the flag out
        CliClientOptions withoutHostNameOption =
            new CliClientOptions(true, false, false, Optional.of(8));
        List<FakeProcessLauncher.FakeProcess> processes = List.of(
            session(server().disableHostnameVerification(PIN).build(), withoutHostNameOption),
            session(server().disableHostnameVerificationWithoutPin().build(), ALL_OPTIONS),
            session(server().pin(PIN).build(), ALL_OPTIONS));

        assertThat(processes).allSatisfy(process -> {
            assertStartCli(process.spec(), "-u", USER, URL);
            assertThat(process.spec().environment())
                .containsEntry("JAVA_TOOL_OPTIONS", TOOL_OPTIONS);
        });
    }

    // --- feature 013 T013: the Windows launch, on every host with a simulated Windows runner ---

    private static final String SYSTEM_DIRECTORY = TestCliRunners.SYSTEM_DIRECTORY;
    private static final String COMMAND = "processErrorStart 110190387";

    /** {@code <cliHome>/bin/startcli.bat}, which the Windows runner starts. */
    private Path batchFile(Path home) throws IOException {
        Path bat = Files.createDirectories(home.resolve("bin")).resolve("startcli.bat");
        Files.writeString(bat, "@echo off\r\n");
        return bat;
    }

    private CliRunner windowsRunner(FakeProcessLauncher launcher) {
        return TestCliRunners.simulatedWindows(launcher, parentEnv,
            new ProcessContainment.Bound(), new CliResources("acme", PrivateFiles.forHost()));
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

    /** Runs on simulated Windows as {@code username} with {@code password}; nothing may start. */
    private ToolError windowsCredentialRefusal(String username, String password,
        CredentialGuard guard) throws IOException {
        batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        ToolError error = errorOf(() -> windowsRunner(launcher).run(server().build(), username,
            Secret.of(password), CliCommand.processErrorStart("110190387"),
            Duration.ofSeconds(5), guard));
        assertThat(launcher.launchCount()).as("nothing is started").isZero();
        return error;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "j\"doe|double quote", "j%doe|percent sign", "j!doe|exclamation mark", "j^doe|caret",
        "j&doe|ampersand", "`j|doe`|vertical bar", "j<doe|less-than sign",
        "j>doe|greater-than sign", "j\tdoe|control character", "jd\u00f6e|non-ASCII character"})
    void onWindowsAUsernameWithARefusedCharacterIsAuthFailedWithoutALoginAttempt(String username,
        String characterClass) throws IOException {
        CredentialGuard guard = guard();
        ToolError error = windowsCredentialRefusal(username, PASSWORD, guard);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).contains("dev/node1", "username",
            "credential store entry", "(" + characterClass + ")");
        assertThat(error.nextStep()).contains("credential store entry", "restart");
        assertThat(error.toString()).doesNotContain(username, PASSWORD);
        // no failed-login count: the guard neither pauses nor confirms
        assertThat(guard.confirmed()).isFalse();
        try (CredentialGuard.Permit permit = guard.acquire()) {
            assertThat(permit).isNotNull();
        }
    }

    @Test
    void onWindowsAPasswordWithANonAsciiCharacterIsAuthFailedWithoutALoginAttempt()
        throws IOException {
        CredentialGuard guard = guard();
        String password = "S3cr3t-p\u00e4ss";

        ToolError error = windowsCredentialRefusal(USER, password, guard);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).contains("dev/node1", "password", "credential store entry",
            "non-ASCII character");
        assertThat(error.toString()).doesNotContain(password);
        try (CredentialGuard.Permit permit = guard.acquire()) {
            assertThat(permit).isNotNull();
        }
    }

    @Test
    void onWindowsAPasswordWithInterpretedAsciiCharactersIsPassedOnStdin() throws IOException {
        batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        windowsRunner(launcher).run(server().build(), USER, Secret.of("p%w^d!&\"<>|"),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard());

        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "runner(…) is the host's; the Windows"
        + " refusal has its own cases")
    void offWindowsTheCredentialsAreNotCheckedForTheCommandInterpreter() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        runner(launcher).run(server().build(), "j^d\u00f6e", Secret.of("p\u00e4ss"),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard());

        assertThat(launcher.launchCount()).isEqualTo(1);
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
    void theEntryProbeCopyOfANodeHasNoTrustStoreAndANeutralUrlSoWindowsDoesNotRefuseIt()
        throws IOException {
        // feature 017, review of stage 7 (N4): --check-config probes a cliClients entry with
        // -v through the first node, which uses neither its trust store nor its cli.url
        batchFile(cliHome);
        EffectiveNodeConfig node = server()
            .cliUrl("https://cli.example.test/ibis!x!/servlet/IBISSoapServlet")
            .trustStore(Path.of("C:\\certs\\a&calc.p12")).build();
        CliRunner runner = windowsRunner(FakeProcessLauncher.replaying("processErrorStart_ok"));
        assertThat(errorOf(() -> runner.checkAvailable(node)).message())
            .contains("the Windows command interpreter interprets");

        EffectiveNodeConfig probe = node.entryProbe(cliHome, Optional.of(JAVA_HOME));

        assertThat(probe.tls().trustStore()).isEmpty();
        assertThat(probe.cli()).isEqualTo(new EffectiveNodeConfig.Cli(
            EffectiveNodeConfig.ENTRY_PROBE_URL, Optional.of(cliHome), Optional.of(JAVA_HOME)));
        assertThat(probe.cliFor(InubitVersion.parse("9.0.7"))).as("the sole client")
            .extracting(choice -> choice.cli().home()).isEqualTo(Optional.of(cliHome));
        assertThat(probe.toString()).doesNotContain("ibis!x!", "calc");
        runner.checkAvailable(probe);
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
                + " 5)"), new CliResources("acme", PrivateFiles.forHost()));

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
            untouched, ProcessContainment.Bound::new,
                new CliResources("acme", PrivateFiles.forHost()));
    }

    @Test
    void onWindowsAnUnreadableSystemDirectoryMakesTheCliUnavailable() throws IOException {
        batchFile(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        CliRunner runner = new CliRunner(launcher, parentEnv, true,
            new TestCliRunners.FixedSystem(new CliSystem.Unreadable(
                "GetSystemDirectoryW failed (Windows error 87)"), StandardCharsets.ISO_8859_1),
            ProcessContainment.Bound::new, new CliResources("acme", PrivateFiles.forHost()));

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
        return TestCliRunners.forHost(launcher, parentEnv,
            new CliResources("acme", PrivateFiles.forHost()), probe);
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

    @Test
    void aSixOneSessionOfAMarkedServerRunsNoSecondPinProbe() {
        CountingProbe probe = new CountingProbe();
        FakeProcessLauncher launcher = FakeProcessLauncher.of(SESSION_OK, "", 0);
        EffectiveNodeConfig server = marked(server().trustStore(CERTS.trustStore())
            .pin(PIN_A).build());

        runner(launcher, probe).runSession(server, USER, Secret.of(PASSWORD), ALL_OPTIONS,
            HEALTH_COMMANDS, Duration.ofSeconds(5), CliRunner.MAX_OUTPUT_BYTES, guard());

        assertThat(probe.calls()).as("V61Connection made the handshake with the pin before")
            .isZero();
        assertThat(launcher.launchCount()).isEqualTo(1);
    }
}
