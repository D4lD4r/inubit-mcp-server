package de.dadecker.inubit.mcp.adapter.cli.v61;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 017 (FR-009, research D-4): which 6.1 sessions the {@code CliRunner} launch guard
 * judges, with a real runner and the recorded checks of {@link NodeVersions}. The health session
 * of {@code get_health} is admitted without a client check ({@code VersionGate.monitoringAccess})
 * and is never guarded, as in 0.8.0: an excluding client only shows in its client part. The
 * process-query session is admitted through the gate's client check and stays guarded.
 */
@Timeout(30)
class V61LaunchGuardTest {

    private static final NodeId ID = NodeId.parse("legacy/node1");
    private static final String PASSWORD = "S3cr3t-v61-guard-pw";
    private static final Instant NOW = Instant.parse("2026-11-01T07:00:00Z");
    private static final Duration BUDGET = Duration.ofSeconds(15);
    /** A 9.0 client, whose range excludes INUBIT 6.1. */
    private static final ClientVersion EXCLUDING = new ClientVersion(version("9.0.7"),
        Optional.of(version("8.1.0")), Optional.of(version("9.0.7")));

    @TempDir
    Path cliHome;

    @TempDir
    Path javaHome;

    private final MutableClock clock = new MutableClock(NOW);
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", ID), clock);
    private final NodeVersions versions = new NodeVersions(clock, id -> VersionSetting.V6_1,
        id -> Duration.ofMinutes(10));

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    @BeforeEach
    void install() throws IOException {
        Path bin = Files.createDirectories(cliHome.resolve("bin"));
        Files.writeString(bin.resolve("startcli.sh"), "#!/bin/sh\n");
        Files.writeString(bin.resolve("startcli.bat"), "@echo off\r\n");
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
    }

    /** A 6.1 node with StartCLI over http, so that no TLS handshake precedes the session. */
    private EffectiveNodeConfig node() {
        return TestNodeConfig.node().id(ID.toString()).versionLine(VersionSetting.V6_1)
            .baseUrl("http://inubit-legacy-1.example.test:8000").allowInsecureHttp(true)
            .cliHome(cliHome).cliJavaHome(javaHome).cliTimeout(Duration.ofSeconds(20)).build();
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(ID,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_LEGACY_NODE1_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_LEGACY_NODE1_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    /** The real runner with the version source and the recorded checks of {@link #versions}. */
    private CliRunner runner(ProcessLauncher launcher) {
        return TestCliRunners.forHost(launcher, Map.of("PATH", "/usr/bin"),
            new CliResources("acme", PrivateFiles.forHost()), versions::effectiveVersion,
            versions);
    }

    private V61Connection connection(EffectiveNodeConfig server, CliRunner runner) {
        return new V61Connection(server, credentials(), guard, runner,
            new CliClientProbe(runner, id -> server, CapabilityMatrix.production(), clock),
            new CliConcurrencyLimiter(CapabilityMatrix.production()));
    }

    private static CliOutputClassifier classifier(EffectiveNodeConfig server, CliRunner runner) {
        return new CliOutputClassifier(new SecretScrubber(), server.secretLabels(),
            runner.windows());
    }

    /** Records the {@code -v} result of the client the runner chooses for the node. */
    private void recordExcludingCheck(CliRunner runner, EffectiveNodeConfig server) {
        versions.clientProbed(ID, new ProbedClient(runner.select(server).key(), EXCLUDING));
    }

    /** The reconstructed 6.1 {@code -h} for the options probe, {@code session} for sessions. */
    private static final class Launchers implements ProcessLauncher {

        final FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
        final FakeProcessLauncher session;

        Launchers(String fixtureCase) {
            this.session = FakeProcessLauncher.replaying("v6_1", fixtureCase);
        }

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            boolean probe = spec.startCliArguments().equals(List.of("-h"));
            return probe ? help.launch(spec) : session.launch(spec);
        }

        int sessions() {
            return session.launchCount();
        }
    }

    @Test
    void theHealthSessionLaunchesAgainAfterAnExcludingCheckWasRecorded() {
        Launchers launchers = new Launchers("health_session_ok");
        EffectiveNodeConfig server = node();
        CliRunner runner = runner(launchers);
        V61HealthAdapter health = new V61HealthAdapter(ID, connection(server, runner),
            classifier(server, runner));

        // the first get_health: no check yet; its parallel -v probe records the excluding client
        assertThat(health.snapshot(BUDGET).alive()).isTrue();
        recordExcludingCheck(runner, server);

        assertThat(health.snapshot(BUDGET).alive()).as("0.8.0: the session still runs").isTrue();
        assertThat(launchers.sessions()).isEqualTo(2);
    }

    @Test
    void theProcessQuerySessionIsStillRefusedForAnExcludingCheck() {
        Launchers launchers = new Launchers("ps_session_error");
        EffectiveNodeConfig server = node();
        CliRunner runner = runner(launchers);
        V61ProcessQueryAdapter processes = new V61ProcessQueryAdapter(ID,
            connection(server, runner), classifier(server, runner));
        recordExcludingCheck(runner, server);

        ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
            () -> processes.find(new ProcessQuery(Set.of(ProcessState.ERROR), false,
                Duration.ofMinutes(60), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0, 50, NOW)));

        assertThat(refused).isNotNull();
        ToolError error = refused.error();
        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("excludes INUBIT 6.1").doesNotContain(PASSWORD,
            "jdoe", "inubit-legacy-1");
        assertThat(launchers.sessions()).isZero();
    }
}
