package de.dadecker.inubit.mcp.adapter.cli;

import static de.dadecker.inubit.mcp.testing.TestSecrets.CONFIGURED_PASSWORD;
import static de.dadecker.inubit.mcp.testing.TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T012 (feature 022, research D-7, FR-022): a version-neutral StartCLI session whose command
 * carries a secret. The secret reaches StartCLI on stdin only and is registered with the
 * scrubber before the start, so the client's echo of the command is masked in the result.
 */
@Timeout(30)
class CliRunnerSessionTest {

    private static final String USER = "jdoe";
    private static final String PROMPT = "CLI|jdoe@inubit-dev-1.example.test> ";
    /** A command with a secret value; the production allowlist admits none yet (T093). */
    private static final CliSessionCommand WITH_SECRET = CliSessionCommand.command("passwd",
        Map.of("passwd", Set.of("--password"))).quoted("--password", CREDENTIAL_PAYLOAD_VALUE)
        .build();
    /** The session as the 8.1 client prints it: each command echoed after its prompt (L-1). */
    private static final String ECHOING_STDOUT = "Password: \n" + PROMPT + WITH_SECRET.text()
        + "\nPassword changed.\n" + PROMPT + "exit\n";
    private static final CliClientOptions OPTIONS =
        new CliClientOptions(true, true, false, Optional.of(8));

    @TempDir
    Path cliHome;

    private final Map<String, String> parentEnv = new HashMap<>();
    private final SecretScrubber scrubber = new SecretScrubber();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-12T08:00:00Z"));

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
        parentEnv.put("PATH", Platform.searchPath());
        parentEnv.put("HOME", "/Users/jdoe");
        // a credential in the server's own environment never reaches the child either
        parentEnv.put("INUBIT_DEV_PASSWORD", CONFIGURED_PASSWORD);
    }

    private CliResult runSession(FakeProcessLauncher launcher) {
        TestNodeConfig node = TestNodeConfig.node()
            .baseUrl("https://inubit-dev-1.example.test:8443")
            .cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17"));
        CliRunner runner = TestCliRunners.forHost(launcher, parentEnv,
            new CliResources("acme", PrivateFiles.forHost()));
        return runner.runSession(runner.select(node.build()), USER,
            Secret.of(CONFIGURED_PASSWORD), Set.of(Secret.of(CREDENTIAL_PAYLOAD_VALUE)),
            scrubber, OPTIONS, List.of(WITH_SECRET), Duration.ofSeconds(5),
            CliRunner.MAX_OUTPUT_BYTES, new CredentialGuard(node.build().secretLabels(), clock));
    }

    @Test
    void theSecretsAreOnStdinOnly() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(ECHOING_STDOUT, "", 0);

        runSession(launcher);

        LaunchSpec spec = launcher.last().spec();
        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(CONFIGURED_PASSWORD + "\npasswd --password '" + CREDENTIAL_PAYLOAD_VALUE
                + "'\nexit\n");
        TestSecrets.assertNoLeak(String.join(" ", spec.command()));
        TestSecrets.assertNoLeak(String.join(" ", spec.startCliArguments()));
        TestSecrets.assertNoLeak(String.join(" ", spec.environment().keySet()));
        TestSecrets.assertNoLeak(String.join(" ", spec.environment().values()));
        assertThat(spec.environment()).doesNotContainKey("INUBIT_DEV_PASSWORD");
    }

    @Test
    void theSecretIsRegisteredBeforeTheStart() {
        AtomicBoolean registeredAtLaunch = new AtomicBoolean();
        FakeProcessLauncher launcher = FakeProcessLauncher.of(ECHOING_STDOUT, "", 0)
            .onLaunch(spec -> registeredAtLaunch.set(
                scrubber.containsSecret(CREDENTIAL_PAYLOAD_VALUE)));

        runSession(launcher);

        assertThat(registeredAtLaunch).isTrue();
    }

    @Test
    void anEchoOfTheSecretInTheOutputIsMasked() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(ECHOING_STDOUT,
            "echo: " + CREDENTIAL_PAYLOAD_VALUE + "\n", 0);

        CliResult result = runSession(launcher);

        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
        assertThat(result.stdout()).contains(PROMPT + "passwd --password '"
            + SecretScrubber.MASK + "'", "Password changed.");
        assertThat(result.stderr()).isEqualTo("echo: " + SecretScrubber.MASK + "\n");
    }
}
