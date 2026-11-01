package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
        script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        parentEnv.put("PATH", "/usr/bin:/bin");
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
        return new CliRunner(launcher, parentEnv, false, new CliResources("acme"));
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
        CliRunner runner = new CliRunner(launcher, parentEnv, false, resources);

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
        // feature 011 (T016): also the 6.1 session
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

        assertThat(launcher.last().spec().command()).containsExactly(
            script.toString(), "-u", USER,
            "--trustStoreFilePath", trustStore.toString(),
            "--disableHostNameVerification",
            "--execCommand", "processErrorStart 110190387",
            "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet");
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

        assertThat(launcher.last().spec().command()).containsExactly(
            script.toString(), "-u", USER, "--execCommand", "processErrorStart 110190387",
            "https://cli.example.test/ibis/servlet/IBISSoapServlet");
    }

    @Test
    void trustStoreWithoutDisabledHostnameVerificationPassesOnlyTheTrustStore() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        Path trustStore = Path.of("/etc/inubit/trust.p12");

        run(launcher, server().trustStore(trustStore).build());

        assertThat(launcher.last().spec().command())
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
            .containsEntry("JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US");
    }

    @Test
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
        assertThat(env).containsEntry("PATH", "/usr/bin:/bin").containsEntry("USER", "jdoe")
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
        assertThat(error.toString()).contains("startcli.sh");
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
    void cliToolsAreNotSupportedOnWindows() throws IOException {
        Files.writeString(cliHome.resolve("bin").resolve("startcli.bat"), "@echo off\r\n");
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        ToolError error = errorOf(() -> new CliRunner(launcher, parentEnv, true,
            new CliResources("acme")).run(
            server().build(), USER, Secret.of(PASSWORD), CliCommand.kill("1"),
            Duration.ofSeconds(5), guard()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.likelyCause())
            .isEqualTo("CLI tools are not supported on Windows in this version");
        assertThat(launcher.launchCount()).isZero();
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

    // --- interactive session on INUBIT 6.1 (feature 011, T016, research D-5) ---------------

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

        List<String> command = launcher.last().spec().command();
        assertThat(command).containsExactly(script.toString(), "-u", USER,
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
        assertThat(env.keySet()).containsExactlyInAnyOrder("PATH", "HOME", "TMPDIR", "LANG",
            "LC_ALL", "USER", "JAVA_HOME", "JAVA_TOOL_OPTIONS");
        assertThat(env).containsEntry("JAVA_HOME", JAVA_HOME.toString())
            .containsEntry("JAVA_TOOL_OPTIONS", CliRunner.JAVA_TOOL_OPTIONS);
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

    // --- output bound per session (feature 011, T046, research D-10) ----------------------

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
        assertThat(execCommand.spec().command()).containsExactly(
            script.toString(), "-u", USER,
            "--trustStoreFilePath", trustStore.toString(),
            "--disableHostNameVerification",
            "--execCommand", "processErrorStart 110190387",
            "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet");
        assertThat(new String(execCommand.stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
    }

    // --- client trust arguments on INUBIT 6.1 (feature 011, T018, research D-8) -----------

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

        assertThat(process.spec().command()).containsExactly(script.toString(), "-u", USER,
            "--trustStoreFilePath", trustStore.toString(), URL);
        assertThat(process.spec().environment())
            .containsEntry("JAVA_TOOL_OPTIONS", CliRunner.JAVA_TOOL_OPTIONS);
    }

    @Test
    void aClientWithoutTheTrustStoreOptionGetsAPkcs12TrustStoreThroughJavaToolOptions()
        throws Exception {
        Path trustStore = trustStoreFile("trust.p12", "PKCS12");

        FakeProcessLauncher.FakeProcess process =
            session(server().trustStore(trustStore).build(), NO_OPTIONS);

        assertThat(process.spec().command()).containsExactly(script.toString(), "-u", USER, URL);
        assertThat(process.spec().environment()).containsEntry("JAVA_TOOL_OPTIONS",
            CliRunner.JAVA_TOOL_OPTIONS + " -Djavax.net.ssl.trustStore=" + trustStore
                + " -Djavax.net.ssl.trustStoreType=PKCS12");
    }

    @Test
    void aJksTrustStoreIsPassedWithItsType() throws Exception {
        Path trustStore = trustStoreFile("trust.jks", "JKS");

        FakeProcessLauncher.FakeProcess process =
            session(server().trustStore(trustStore).build(), NO_OPTIONS);

        assertThat(process.spec().environment()).containsEntry("JAVA_TOOL_OPTIONS",
            CliRunner.JAVA_TOOL_OPTIONS + " -Djavax.net.ssl.trustStore=" + trustStore
                + " -Djavax.net.ssl.trustStoreType=JKS");
    }

    @Test
    void withoutATrustStoreNeitherTheArgumentNorTheRuntimeSettingIsPassed() {
        for (CliClientOptions options : List.of(ALL_OPTIONS, NO_OPTIONS)) {
            FakeProcessLauncher.FakeProcess process = session(server().build(), options);

            assertThat(process.spec().command()).as("%s", options)
                .containsExactly(script.toString(), "-u", USER, URL);
            assertThat(process.spec().environment()).as("%s", options)
                .containsEntry("JAVA_TOOL_OPTIONS", CliRunner.JAVA_TOOL_OPTIONS);
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
        for (String name : List.of("trust store.p12", "trust\tstore.p12", "trust\nstore.p12")) {
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

        assertThat(process.spec().command()).containsExactly(script.toString(), "-u", USER,
            "--disableHostNameVerification", URL);
    }

    @Test
    void bothClientOptionsComeBeforeTheUrl() {
        Path trustStore = cliHome.resolve("trust.p12");

        FakeProcessLauncher.FakeProcess process = session(
            server().trustStore(trustStore).disableHostnameVerification(PIN).build(), ALL_OPTIONS);

        assertThat(process.spec().command()).containsExactly(script.toString(), "-u", USER,
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
            assertThat(process.spec().command())
                .containsExactly(script.toString(), "-u", USER, URL);
            assertThat(process.spec().environment())
                .containsEntry("JAVA_TOOL_OPTIONS", CliRunner.JAVA_TOOL_OPTIONS);
        });
    }
}
