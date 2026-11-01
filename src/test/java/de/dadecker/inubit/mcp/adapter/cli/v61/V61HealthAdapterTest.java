package de.dadecker.inubit.mcp.adapter.cli.v61;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.environmentLabels;
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
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CliHealthSnapshot;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MemoryFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessCounts;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T026 (feature 011, research D-5, D-6): the 6.1 health port over the reconstructed T022 health
 * session fixtures. StartCLI launches are counted by their argument array: the local
 * {@code startcli.sh -h} run of the client probe (cached per node) is not a session; every other
 * launch is a StartCLI session.
 */
@Timeout(30)
class V61HealthAdapterTest {

    private static final NodeId ID = NodeId.parse("legacy/node1");
    private static final String PASSWORD = "S3cr3t-v61-health-pw";
    private static final Duration BUDGET = Duration.ofSeconds(15);
    private static final MemoryFigures MEMORY = new MemoryFigures(1210, 838, 2048);
    private static final ProcessCounts COUNTS = new ProcessCounts(3, 0, 12, 0);

    @TempDir
    Path cliHome;

    @TempDir
    Path javaHome;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final CredentialGuard guard =
        new CredentialGuard(environmentLabels("INUBIT", ID), clock);

    @BeforeEach
    void install() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
    }

    /** A 6.1 node with StartCLI over http, so that no TLS handshake precedes the session. */
    private EffectiveNodeConfig node(Duration cliTimeout) {
        return TestNodeConfig.node().id(ID.toString()).versionLine(VersionSetting.V6_1)
            .baseUrl("http://inubit-legacy-1.example.test:8000").allowInsecureHttp(true)
            .cliHome(cliHome).cliJavaHome(javaHome).cliTimeout(cliTimeout).build();
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(ID,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_LEGACY_NODE1_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_LEGACY_NODE1_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private V61HealthAdapter adapter(Launchers launchers) {
        return adapter(node(Duration.ofSeconds(10)), launchers);
    }

    private V61HealthAdapter adapter(EffectiveNodeConfig server, Launchers launchers) {
        CliRunner runner = new CliRunner(launchers, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        V61Connection connection = new V61Connection(server, credentials(), guard, runner,
            new CliClientProbe(runner, id -> server, CapabilityMatrix.production(), clock),
            new CliConcurrencyLimiter(CapabilityMatrix.production()));
        return new V61HealthAdapter(ID, connection,
            new CliOutputClassifier(new SecretScrubber(), server.secretLabels()));
    }

    /** The reconstructed 6.1 {@code -h} for the probe, {@code session} for every session. */
    private static final class Launchers implements ProcessLauncher {

        final FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
        final FakeProcessLauncher session;

        Launchers(FakeProcessLauncher session) {
            this.session = session;
        }

        static Launchers replaying(String fixtureCase) {
            return new Launchers(FakeProcessLauncher.replaying("v6_1", fixtureCase));
        }

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            boolean probe = spec.command().size() == 2 && spec.command().get(1).equals("-h");
            return probe ? help.launch(spec) : session.launch(spec);
        }

        int sessions() {
            return session.launchCount();
        }

        int probes() {
            return help.launchCount();
        }
    }

    private static ToolError errorOf(V61HealthAdapter adapter, Duration budget) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> adapter.snapshot(budget));
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    // --- one session per snapshot ---------------------------------------------------------------

    @Test
    void anOkSessionGivesTheWholeSnapshotFromOneLaunch() {
        Launchers launchers = Launchers.replaying("health_session_ok");

        CliHealthSnapshot snapshot = adapter(launchers).snapshot(BUDGET);

        assertThat(snapshot.alive()).isTrue();
        assertThat(snapshot.versionText()).contains("6.1.0.89");
        assertThat(snapshot.maintenance()).contains(false);
        assertThat(snapshot.memory()).contains(MEMORY);
        assertThat(snapshot.counts()).contains(COUNTS);
        assertThat(launchers.sessions()).isEqualTo(1);
        assertThat(launchers.probes()).isEqualTo(1);
    }

    @Test
    void theSessionSendsThePasswordAndTheHealthCommandsOnStandardInput() {
        Launchers launchers = Launchers.replaying("health_session_ok");

        adapter(launchers).snapshot(BUDGET);

        assertThat(new String(launchers.session.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\nuname\nuptime\nmaintenance\nfree -m\nps -c -e\nps -c -w\n"
                + "ps -c -q\nps -c -a\nexit\n");
        assertThat(launchers.session.last().spec().command()).doesNotContain(PASSWORD)
            .contains("-u", "jdoe");
    }

    @Test
    void everySnapshotRunsItsOwnSessionWhileTheProbeIsCached() {
        Launchers launchers = Launchers.replaying("health_session_ok");
        V61HealthAdapter adapter = adapter(launchers);

        adapter.snapshot(BUDGET);
        adapter.snapshot(BUDGET);

        assertThat(launchers.sessions()).isEqualTo(2);
        assertThat(launchers.probes()).isEqualTo(1);
    }

    @Test
    void maintenanceOnIsReported() {
        Launchers launchers = Launchers.replaying("health_session_maintenance");

        CliHealthSnapshot snapshot = adapter(launchers).snapshot(BUDGET);

        assertThat(snapshot.maintenance()).contains(true);
        assertThat(snapshot.alive()).isTrue();
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void germanLabelsGiveTheSameSnapshot() {
        CliHealthSnapshot german = adapter(Launchers.replaying("health_session_de"))
            .snapshot(BUDGET);

        assertThat(german).isEqualTo(adapter(Launchers.replaying("health_session_ok"))
            .snapshot(BUDGET));
    }

    @Test
    void aFailedAliveCheckStillReportsTheOtherParts() {
        Launchers launchers = Launchers.replaying("health_session_alive_failed");

        CliHealthSnapshot snapshot = adapter(launchers).snapshot(BUDGET);

        assertThat(snapshot.alive()).isFalse();
        assertThat(snapshot.versionText()).contains("6.1.0.89");
        assertThat(snapshot.maintenance()).contains(false);
        assertThat(snapshot.memory()).contains(MEMORY);
        assertThat(snapshot.counts()).contains(COUNTS);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    // --- version origin and missing parts --------------------------------------------------------

    @Test
    void theVersionOriginFollowsTheRecordingDecision() {
        CliHealthSnapshot snapshot = adapter(Launchers.replaying("health_session_ok"))
            .snapshot(BUDGET);

        assertThat(snapshot.versionIsServer())
            .isEqualTo(V61HealthAdapter.UNAME_REPORTS_SERVER_VERSION);
    }

    @Test
    void untilTheRecordingsConfirmItTheVersionCountsAsTheClients() {
        // research D-6, FR-006: only recordings (T037) may declare uname the server's version
        assertThat(V61HealthAdapter.UNAME_REPORTS_SERVER_VERSION).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"health_session_ok", "health_session_alive_failed"})
    void readinessThreadsAndBlockingQueueAreAlwaysMissing(String fixtureCase) {
        CliHealthSnapshot snapshot = adapter(Launchers.replaying(fixtureCase)).snapshot(BUDGET);

        assertThat(snapshot.missing()).containsExactly(
            new UnavailablePart(UnavailablePart.READY,
                "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 has no readiness check"),
            new UnavailablePart(UnavailablePart.THREADS,
                "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no thread figures"),
            new UnavailablePart(UnavailablePart.BLOCKING_QUEUE,
                "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no blocking queue"));
    }

    // --- failures propagate ----------------------------------------------------------------------

    @Test
    void aRejectedLoginIsAuthFailedForTheNode() {
        Launchers launchers = Launchers.replaying("login_failed");

        ToolError error = errorOf(adapter(launchers), BUDGET);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).doesNotContain(PASSWORD);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void aSecondSnapshotAfterARejectedLoginLaunchesNothing() {
        Launchers launchers = Launchers.replaying("login_failed");
        V61HealthAdapter adapter = adapter(launchers);
        errorOf(adapter, BUDGET);

        ToolError second = errorOf(adapter, BUDGET);

        // the credential guard of the runner (US1-6)
        assertThat(second.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void anUnreachableServerIsUnreachableForTheNode() {
        Launchers launchers = Launchers.replaying("unreachable");

        ToolError error = errorOf(adapter(launchers), BUDGET);

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.node()).contains(ID);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void anUnknownSessionOutputIsUnexpectedResponseNeverAnEmptySnapshot() {
        Launchers launchers = new Launchers(FakeProcessLauncher.of(
            "Password: \ncli> INUBIT Process Engine 6.1.0.89\ncli> \n", "", 0));

        ToolError error = errorOf(adapter(launchers), BUDGET);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void aSessionWithAnotherExitCodeIsNeverParsed() {
        String ok = FakeProcessLauncher.fixtureText("v6_1", "health_session_ok.stdout");
        Launchers launchers = new Launchers(FakeProcessLauncher.of(ok, "", 2));

        ToolError error = errorOf(adapter(launchers), BUDGET);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
    }

    // --- budget -----------------------------------------------------------------------------------

    @Test
    void aBudgetShorterThanCliTimeoutEndsAHangingSession() {
        Launchers launchers = new Launchers(FakeProcessLauncher.of("", "", 0).hanging());
        V61HealthAdapter adapter = adapter(node(Duration.ofSeconds(20)), launchers);
        long start = System.nanoTime();

        ToolError error = errorOf(adapter, Duration.ofMillis(300));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(launchers.session.last().destroyed()).isTrue();
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void cliTimeoutStillBoundsASessionWithALongerBudget() {
        Launchers launchers = new Launchers(FakeProcessLauncher.of("", "", 0).hanging());
        V61HealthAdapter adapter = adapter(node(Duration.ofMillis(300)), launchers);
        long start = System.nanoTime();

        ToolError error = errorOf(adapter, Duration.ofSeconds(20));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }
}
