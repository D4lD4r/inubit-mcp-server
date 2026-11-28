package de.dadecker.inubit.mcp.adapter.cli.v74;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CliHealthSnapshot;
import de.dadecker.inubit.mcp.domain.model.ContactedException;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MemoryFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessCounts;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestMatrices;
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

/**
 * Feature 025 T031 (spec FR-024 – FR-027, FR-029): the 7.4 health port of
 * {@link V74PortSet#cliHealth} — the 6.1 health adapter with the 7.4 profile — against the
 * reconstructed 7.4 session fixtures ({@code fixtures/v7_4/cli/}) and variants built here from
 * {@code health_session_ok}: CRLF line ends and German labels exactly as {@code V61HealthParser}
 * accepts them (no German 7.4 fixture, FR-025). The client options come from the {@code -h} probe
 * replaying the real 7.4 help; the production matrix runs no {@code -h} on 7.4 before the flip
 * (T035), so probe and limiter get the T038 test matrix.
 */
@Timeout(30)
class V74HealthContractTest {

    private static final NodeId ID = NodeId.parse("legacy74/node1");
    private static final String PASSWORD = "S3cr3t-v74-health-pw";
    private static final Duration BUDGET = Duration.ofSeconds(15);
    private static final MemoryFigures MEMORY = new MemoryFigures(1210, 838, 2048);
    private static final ProcessCounts COUNTS = new ProcessCounts(3, 0, 12, 0);
    private static final CapabilityMatrix MATRIX = TestMatrices.unverified74();
    private static final TestCertificates CERTS = TestCertificates.get();

    @TempDir
    Path cliHome;

    @TempDir
    Path javaHome;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", ID), clock);

    @BeforeEach
    void install() throws IOException {
        Path bin = Files.createDirectories(cliHome.resolve("bin"));
        Files.writeString(bin.resolve("startcli.sh"), "#!/bin/sh\n");
        Files.writeString(bin.resolve("startcli.bat"), "@echo off\r\n");
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node().id(ID.toString()).versionLine(VersionSetting.V7_4)
            .cliHome(cliHome).cliJavaHome(javaHome).cliTimeout(Duration.ofSeconds(10));
    }

    /** StartCLI over http, so that no TLS handshake precedes the session. */
    private EffectiveNodeConfig overHttp() {
        return node().baseUrl("http://inubit-legacy74-1.example.test:8000")
            .allowInsecureHttp(true).build();
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(ID,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_LEGACY74_NODE1_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_LEGACY74_NODE1_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    /** The health port as {@code Wiring} builds it for a 7.4 node. */
    private CliHealthPort port(EffectiveNodeConfig server, Launchers launchers) {
        CliRunner runner = TestCliRunners.forHost(launchers, Map.of("PATH", "/usr/bin"),
            new CliResources("acme", PrivateFiles.forHost()));
        return V74PortSet.cliHealth(new CliClientProbe(runner, id -> server, MATRIX, clock),
                new CliConcurrencyLimiter(MATRIX))
            .create(new PortSetRegistry.NodeResources(server, credentials(), guard, null, runner,
                new SecretScrubber()));
    }

    private CliHealthPort port(Launchers launchers) {
        return port(overHttp(), launchers);
    }

    /** The real 7.4 {@code -h} for the probe, {@code session} for every session. */
    private static final class Launchers implements ProcessLauncher {

        final FakeProcessLauncher help = FakeProcessLauncher.replaying("v7_4", "help");
        final FakeProcessLauncher session;

        Launchers(FakeProcessLauncher session) {
            this.session = session;
        }

        static Launchers replaying(String fixtureCase) {
            return new Launchers(FakeProcessLauncher.replaying("v7_4", fixtureCase));
        }

        static Launchers printing(String stdout) {
            return new Launchers(FakeProcessLauncher.of(stdout, "", 0));
        }

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            boolean probe = spec.startCliArguments().equals(List.of("-h"));
            return probe ? help.launch(spec) : session.launch(spec);
        }

        int sessions() {
            return session.launchCount();
        }

        int probes() {
            return help.launchCount();
        }
    }

    private static ToolError errorOf(CliHealthPort port) {
        return exceptionOf(port).error();
    }

    private static ToolErrorException exceptionOf(CliHealthPort port) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> port.snapshot(BUDGET));
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception;
    }

    /** The error of a snapshot, raised after contact began (T041, FR-030 (b)). */
    private static ToolError contactedErrorOf(CliHealthPort port) {
        ToolErrorException exception = exceptionOf(port);
        assertThat(exception).as("an error after contact").isInstanceOf(ContactedException.class);
        return exception.error();
    }

    /** The error of a snapshot, raised before contact (T041, FR-030 (b)). */
    private static ToolError plainErrorOf(CliHealthPort port) {
        ToolErrorException exception = exceptionOf(port);
        assertThat(exception).as("an error before contact")
            .isNotInstanceOf(ContactedException.class);
        return exception.error();
    }

    private static String okSession() {
        return FakeProcessLauncher.fixtureText("v7_4", "health_session_ok.stdout");
    }

    // --- the parts of FR-026 -----------------------------------------------------------------

    @Test
    void anOkSessionGivesTheSixOneParts() {
        Launchers launchers = Launchers.replaying("health_session_ok");

        CliHealthSnapshot snapshot = port(launchers).snapshot(BUDGET);

        assertThat(snapshot.alive()).isTrue();
        assertThat(snapshot.maintenance()).contains(false);
        assertThat(snapshot.memory()).contains(MEMORY);
        assertThat(snapshot.counts()).contains(COUNTS);
        assertThat(launchers.sessions()).isEqualTo(1);
        assertThat(launchers.probes()).as("the options come from the 7.4 help").isEqualTo(1);
    }

    @Test
    void theSessionSendsThePasswordAndTheSixOneHealthCommandsOnStandardInput() {
        Launchers launchers = Launchers.replaying("health_session_ok");

        port(launchers).snapshot(BUDGET);

        assertThat(new String(launchers.session.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\nuname\nuptime\nmaintenance\nfree -m\nps -c -e\nps -c -w\n"
                + "ps -c -q\nps -c -a\nexit\n");
        assertThat(String.join(" ", launchers.session.last().spec().command()))
            .doesNotContain(PASSWORD);
    }

    @Test
    void readinessThreadsAndBlockingQueueAreNotAvailableOn74() {
        CliHealthSnapshot snapshot = port(Launchers.replaying("health_session_ok"))
            .snapshot(BUDGET);

        assertThat(snapshot.missing()).containsExactly(
            new UnavailablePart(UnavailablePart.READY,
                "NOT_AVAILABLE_ON_7_4: INUBIT 7.4 has no readiness check"),
            new UnavailablePart(UnavailablePart.THREADS,
                "NOT_AVAILABLE_ON_7_4: INUBIT 7.4 reports no thread figures"),
            new UnavailablePart(UnavailablePart.BLOCKING_QUEUE,
                "NOT_AVAILABLE_ON_7_4: INUBIT 7.4 reports no blocking queue"));
    }

    @Test
    void theUnameVersionIsTheClientsNotTheServers() {
        // FR-027: 6.1 behaviour assumed; get_health neither reports nor observes it
        CliHealthSnapshot snapshot = port(Launchers.replaying("health_session_ok"))
            .snapshot(BUDGET);

        assertThat(snapshot.versionText()).contains("7.4.0.90");
        assertThat(snapshot.versionIsServer()).isFalse();
    }

    @Test
    void maintenanceOnIsReported() {
        CliHealthSnapshot snapshot = port(Launchers.replaying("health_session_maintenance"))
            .snapshot(BUDGET);

        assertThat(snapshot.maintenance()).contains(true);
        assertThat(snapshot.alive()).isTrue();
    }

    @Test
    void aFailedAliveCheckStillReportsTheOtherParts() {
        // get_health reports this as status ERROR
        CliHealthSnapshot snapshot = port(Launchers.replaying("health_session_alive_failed"))
            .snapshot(BUDGET);

        assertThat(snapshot.alive()).isFalse();
        assertThat(snapshot.maintenance()).contains(false);
        assertThat(snapshot.memory()).contains(MEMORY);
        assertThat(snapshot.counts()).contains(COUNTS);
    }

    // --- variants built from health_session_ok (FR-025) -----------------------------------------

    @Test
    void crlfLineEndsGiveTheSameSnapshot() {
        String crlf = okSession().replace("\n", "\r\n");

        assertThat(port(Launchers.printing(crlf)).snapshot(BUDGET))
            .isEqualTo(port(Launchers.replaying("health_session_ok")).snapshot(BUDGET));
    }

    @Test
    void germanLabelsGiveTheSameSnapshot() {
        String german = okSession()
            .replace("Process Engine is alive (up 2 days, 03:14:07)",
                "Process Engine ist aktiv (läuft seit 2 Tagen, 03:14:07)")
            .replace("Maintenance mode: off", "Wartungsmodus: aus")
            .replace("Used memory:", "Belegter Speicher:")
            .replace("Free memory:", "Freier Speicher:")
            .replace("Max memory:", "Maximaler Speicher:")
            .replace("Total:", "Gesamt:");
        assertThat(german).as("every English label replaced")
            .doesNotContain("alive", "Maintenance", "memory", "Total");

        assertThat(port(Launchers.printing(german)).snapshot(BUDGET))
            .isEqualTo(port(Launchers.replaying("health_session_ok")).snapshot(BUDGET));
    }

    // --- failures --------------------------------------------------------------------------------

    @Test
    void aRejectedLoginIsAuthFailedForTheNode() {
        Launchers launchers = Launchers.replaying("login_failed");

        ToolError error = contactedErrorOf(port(launchers));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).doesNotContain(PASSWORD);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void anUnreachableServerIsUnreachableForTheNode() {
        Launchers launchers = Launchers.replaying("unreachable");

        ToolError error = contactedErrorOf(port(launchers));

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.node()).contains(ID);
    }

    @Test
    void aNonZeroExitIsClassifiedForTheNode() {
        Launchers launchers = new Launchers(
            FakeProcessLauncher.of("Password: \ncli> something went wrong\n", "", 1));

        ToolError error = contactedErrorOf(port(launchers));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
    }

    @Test
    void anUnrecognizedSessionOutputIsUnexpectedResponseNeverAnEmptySnapshot() {
        Launchers launchers = Launchers.printing(
            "Password: \ncli> INUBIT Process Engine 7.4.0.90\ncli> \n");

        ToolError error = contactedErrorOf(port(launchers));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
        // T032: the format names the profile's release
        assertThat(error.likelyCause())
            .isEqualTo("StartCLI's session output differs from the expected INUBIT 7.4 format");
    }

    // --- TLS at the adapter (FR-029, feature 012 FR-012, FR-012a) ------------------------------

    @Test
    void aCertificateTheHandshakeRejectsIsATlsErrorAndStartsNoSession() throws Exception {
        try (TlsTestServer inubit = TlsTestServer.start(CERTS.localhostKeyStore())) {
            Launchers launchers = Launchers.replaying("health_session_ok");
            EffectiveNodeConfig server = node().baseUrl("https://localhost:" + inubit.port())
                .trustStore(CERTS.trustStore())
                .pin(TestCertificates.fingerprint(CERTS.selfsignedCertificate())).build();

            ToolError error = contactedErrorOf(port(server, launchers));

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(error.node()).contains(ID);
            assertThat(inubit.connections()).as("the handshake is contact").isEqualTo(1);
            assertThat(launchers.sessions()).isZero();
        }
    }

    @Test
    void skippingHostNameVerificationIsRefusedBeforeContactAsThe74ClientHasNoSuchOption()
        throws Exception {
        try (TlsTestServer inubit = TlsTestServer.start(CERTS.localhostKeyStore())) {
            Launchers launchers = Launchers.replaying("health_session_ok");
            EffectiveNodeConfig server = node().baseUrl("https://localhost:" + inubit.port())
                .trustStore(CERTS.trustStore())
                .disableHostnameVerification(
                    TestCertificates.fingerprint(CERTS.localhostCertificate())).build();

            ToolError error = plainErrorOf(port(server, launchers));

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(error.message()).contains("--disableHostNameVerification");
            assertThat(launchers.probes()).as("decided by the 7.4 help").isEqualTo(1);
            assertThat(inubit.connections()).isZero();
            assertThat(launchers.sessions()).isZero();
        }
    }
}
