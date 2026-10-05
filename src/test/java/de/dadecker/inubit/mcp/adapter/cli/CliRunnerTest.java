package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        return new CredentialGuard(server().build().credentialVariables(), clock);
    }

    private CliResult runGuarded(FakeProcessLauncher launcher, CredentialGuard guard) {
        return runner(launcher).run(server().build(), USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5), guard);
    }

    @Test
    void aCliLoginFailureBlocksFurtherCliAndRestLoginsForSixtySeconds() {
        CredentialGuard guard = new CredentialGuard(server().build().credentialVariables(), clock);
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
        CredentialGuard guard = new CredentialGuard(server().build().credentialVariables(), clock);

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
}
