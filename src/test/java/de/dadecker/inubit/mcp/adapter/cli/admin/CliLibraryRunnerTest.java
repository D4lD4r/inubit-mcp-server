package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T042 (feature 022, US3, research D-21): the library list is one script-mode run of
 * {@code library --list --type <type>} per type, replaying the 8.1.17 answers recorded in T039
 * ({@code admin-model-library-driver}, {@code admin-model-library-plugin}: the empty list). No
 * listed library is recorded (L-21); the non-empty answers below are RECONSTRUCTED.
 */
@Timeout(30)
class CliLibraryRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-library-pw";
    private static final String CLI_URL =
        "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet";
    private static final String PREAMBLE = "JAVA_HOME is set\nPassword: \n";

    @TempDir
    Path cliHome;

    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-14T08:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node().baseUrl("https://inubit-dev-1.example.test:8443")
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"));
    }

    private CliLibraryRunner runner(FakeProcessLauncher launcher) {
        return runner(node().build(), launcher);
    }

    private CliLibraryRunner runner(EffectiveNodeConfig server, FakeProcessLauncher launcher) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliLibraryRunner(server, credentials, guard, cliRunner(launcher),
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    private static CliRunner cliRunner(FakeProcessLauncher launcher) {
        return TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
    }

    private static ToolError errorOf(ThrowingCallable call) {
        ToolError[] error = new ToolError[1];
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolErrorException.class,
            e -> error[0] = e.error());
        assertThat(error[0].toString()).doesNotContain(PASSWORD);
        return error[0];
    }

    // --- the recorded answers -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"driver", "plugin"})
    void theRecordedEmptyAnswerIsOneScriptRunWithThePasswordOnlyOnStdin(String type) {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("admin-model-library-" + type);

        LibraryEntry.Listing listing = runner(launcher).list(type);

        assertThat(listing.entries()).isEmpty();
        assertThat(listing.rowFormatVerified()).isFalse();
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().startCliArguments()).containsExactly("-u", USER,
            "--execCommand", "library --list --type " + type, CLI_URL);
        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
        assertThat(launcher.last().spec().command())
            .noneMatch(argument -> argument.contains(PASSWORD));
        assertThat(launcher.last().spec().environment().values())
            .noneMatch(value -> value.contains(PASSWORD));
    }

    /** RECONSTRUCTED (L-21): each line that is not blank is one file name of the type asked. */
    @Test
    void eachLineOfANonEmptyAnswerIsOneFileName() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(PREAMBLE
            + "postgresql-42.7.7.jar\r\n\r\nojdbc11.jar\r\n\n", "", 0);

        LibraryEntry.Listing listing = runner(launcher).list("driver");

        assertThat(listing.entries()).containsExactly(
            new LibraryEntry("postgresql-42.7.7.jar", "driver"),
            new LibraryEntry("ojdbc11.jar", "driver"));
        assertThat(listing.rowFormatVerified()).isFalse();
    }

    @Test
    void aLineThatIsNoFileNameIsUnexpectedForTheNode() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(PREAMBLE + "x".repeat(256) + "\n",
            "", 0);

        ToolError error = errorOf(() -> runner(launcher).list("plugin"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
    }

    // --- failed runs --------------------------------------------------------------------------

    /** An {@code n-NOK} answer is never taken for a file name. */
    @Test
    void aNokAnswerFailsTheReadWithTheNode() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(PREAMBLE
            + "2-NOK: Missing option.\n", "", 0);

        ToolError error = errorOf(() -> runner(launcher).list("driver"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
        assertThat(error.excerpt()).hasValueSatisfying(
            excerpt -> assertThat(excerpt).contains("2-NOK: Missing option."));
    }

    @Test
    void aFailedRunKeepsAnExcerptWithSecretNamedValuesMasked() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(PREAMBLE
            + "Unexpected failure password=synthetic-secret-4203\n", "", 1);

        ToolError error = errorOf(() -> runner(launcher).list("driver"));

        assertThat(error.node()).contains(DEV);
        assertThat(error.excerpt()).isPresent();
        assertThat(error.toString()).doesNotContain("synthetic-secret-4203");
    }

    /** The recorded 8.1 answer to wrong credentials pauses the node: nothing else starts. */
    @Test
    void wrongCredentialsFailTheReadAndPauseTheNode() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("login_failed");

        ToolError error = errorOf(() -> runner(launcher).list("driver"));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(DEV);
        FakeProcessLauncher next = FakeProcessLauncher.replaying("admin-model-library-plugin");
        assertThat(errorOf(() -> runner(next).list("plugin")).code())
            .isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(next.launchCount()).isZero();
    }

    @Test
    void theRunIsBoundedByCliTimeout() {
        FakeProcessLauncher launcher =
            FakeProcessLauncher.replaying("admin-model-library-driver").hanging();

        ToolError error = errorOf(() -> runner(node().cliTimeout(Duration.ofMillis(300)).build(),
            launcher).list("driver"));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliTimeout");
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void missingCredentialsLaunchNothing() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("admin-model-library-driver");
        CliLibraryRunner runner = new CliLibraryRunner(node().build(),
            new NodeCredentials(DEV, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()),
            guard, cliRunner(launcher), new CliOutputClassifier(new SecretScrubber()));

        ToolError error = errorOf(() -> runner.list("driver"));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(DEV);
        assertThat(launcher.launchCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jar", "Driver", "driver;kill 1"})
    void anotherTypeLaunchesNothing(String type) {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("admin-model-library-driver");

        assertThat(errorOf(() -> runner(launcher).list(type)).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(launcher.launchCount()).isZero();
    }
}
