package de.dadecker.inubit.mcp.adapter.cli.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T103: restart and kill through StartCLI with {@link FakeProcessLauncher} and the S-3 fixtures
 * (research R-6, R-7). The success fixtures {@code cli/processErrorStart_ok.*} and
 * {@code cli/kill_ok.*} are SYNTHETIC (user decision in T009); T115 replaces them with
 * recordings.
 */
@Timeout(30)
class V81ProcessControlAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "S3cr3t-control-pw";

    @TempDir
    Path cliHome;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T08:00:00Z"));
    private final CredentialGuard guard =
        new CredentialGuard(new CredentialVariables("INUBIT", DEV), clock);
    private final SecretScrubber scrubber = new SecretScrubber();

    @BeforeEach
    void installFakeCli() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        scrubber.register(PASSWORD);
    }

    private EffectiveNodeConfig config() {
        return TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .cliTimeout(Duration.ofSeconds(7)).build();
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private V81ProcessControlAdapter adapter(FakeProcessLauncher launcher) {
        return adapter(launcher, config(), credentials(), false);
    }

    private V81ProcessControlAdapter adapter(FakeProcessLauncher launcher,
        EffectiveNodeConfig config, NodeCredentials credentials, boolean windows) {
        return new V81ProcessControlAdapter(config, credentials, guard,
            new CliRunner(launcher, Map.of("PATH", "/usr/bin"), windows, new CliResources("acme")),
            new CliOutputClassifier(scrubber));
    }

    private static String execCommand(FakeProcessLauncher launcher) {
        List<String> command = launcher.last().spec().command();
        return command.get(command.indexOf("--execCommand") + 1);
    }

    private static ToolError errorOf(Executable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, () -> {
            try {
                call.execute();
            } catch (ToolErrorException e) {
                throw e;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    @Test
    void restartRunsProcessErrorStartWithTheQueueManagerId() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        String message = adapter(launcher).restart("110190387");

        assertThat(execCommand(launcher)).isEqualTo("processErrorStart 110190387");
        assertThat(message).isEqualTo("Process 110190387 restarted.");
        assertThat(guard.confirmed()).isTrue();
    }

    @Test
    void killRunsKillWithTheQueueManagerId() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("kill_ok");

        String message = adapter(launcher).kill("110190387");

        assertThat(execCommand(launcher)).isEqualTo("kill 110190387");
        assertThat(message).isEqualTo("Process 110190387 killed.");
    }

    @Test
    void thePasswordGoesToStdinAndNeverIntoTheArguments() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        adapter(launcher).restart("110190387");

        assertThat(launcher.last().spec().command()).noneMatch(arg -> arg.contains(PASSWORD))
            .containsSequence("-u", "jdoe").doesNotContain("-p");
        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
        assertThat(launcher.last().spec().environment()).doesNotContainKeys(
            "INUBIT_DEV_PASSWORD");
    }

    @Test
    void theCliTimeoutOfTheServerApplies() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok")
            .hanging();
        EffectiveNodeConfig fast = TestNodeConfig.node().cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).cliTimeout(Duration.ofMillis(200)).build();

        ToolError error = errorOf(() -> adapter(launcher, fast, credentials(), false)
            .restart("110190387"));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(DEV);
        assertThat(launcher.last().destroyed()).isTrue();
    }

    @Test
    void anUnknownProcessOnRestartIsNotFound() {
        ToolError error = errorOf(() -> adapter(FakeProcessLauncher.replaying(
            "processErrorStart_unknown")).restart("999999999"));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains("No process found with id [999999999]");
    }

    @Test
    void anUnknownProcessOnKillIsNotFound() {
        ToolError error = errorOf(() -> adapter(FakeProcessLauncher.replaying("kill_unknown"))
            .kill("999999999"));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains("999999999");
    }

    @Test
    void aRejectedLoginIsAuthFailedAndPausesFurtherAttempts() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("login_failed");
        V81ProcessControlAdapter adapter = adapter(launcher);

        ToolError first = errorOf(() -> adapter.restart("110190387"));
        ToolError second = errorOf(() -> adapter.kill("110190387"));

        assertThat(first.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(first.node()).contains(DEV);
        assertThat(second.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void anUnreachableServerIsUnreachable() {
        assertThat(errorOf(() -> adapter(FakeProcessLauncher.replaying("unreachable"))
            .kill("110190387")).code()).isEqualTo(ErrorCode.UNREACHABLE);
    }

    @Test
    void unrecognizedOutputIsNeverASuccess() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("JAVA_HOME is set\nPassword: \n"
            + "something else " + PASSWORD + "\n", "", 0);

        ToolError error = errorOf(() -> adapter(launcher).restart("110190387"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.excerpt()).hasValueSatisfying(excerpt ->
            assertThat(excerpt).doesNotContain(PASSWORD).contains("***"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"7b0c4f1e-3a52-4d5e-9a40-1f2e3d4c5b6a", "", "12a", "-1",
        "12345678901234567890", "1 2", "1;kill 2", "0", "0110190387"})
    void anIdThatIsNotAQueueManagerIdIsRejectedBeforeLaunching(String processId) {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");

        ToolError restart = errorOf(() -> adapter(launcher).restart(processId));
        ToolError kill = errorOf(() -> adapter(launcher).kill(processId));

        assertThat(restart.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(kill.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void withoutCliHomeTheActionsAreCliUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        EffectiveNodeConfig noCli = TestNodeConfig.node().build();
        V81ProcessControlAdapter adapter = adapter(launcher, noCli, credentials(), false);

        assertThat(errorOf(adapter::checkAvailable).code())
            .isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(errorOf(() -> adapter.restart("1")).code())
            .isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void onWindowsTheActionsAreCliUnavailable() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("kill_ok");

        assertThat(errorOf(() -> adapter(launcher, config(), credentials(), true).kill("1"))
            .code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void withoutCredentialsTheActionsAreAuthFailedWithoutLaunching() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("kill_ok");
        NodeCredentials none = new NodeCredentials(DEV, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());
        V81ProcessControlAdapter adapter = adapter(launcher, config(), none, false);

        ToolError check = errorOf(adapter::checkAvailable);
        ToolError kill = errorOf(() -> adapter.kill("1"));

        assertThat(check.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(check.node()).contains(DEV);
        assertThat(kill.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void checkAvailableLaunchesNothing() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("kill_ok");

        adapter(launcher).checkAvailable();

        assertThat(launcher.launchCount()).isZero();
    }
}
