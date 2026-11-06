package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.net.ServerSocket;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 014 T011: the certificate check at the StartCLI address of a server of a marked stage
 * (FR-005a, research R-15). {@code int/a} presents the {@code localhost} certificate, {@code int/b}
 * the self-signed one; both are in the shared stage trust store.
 */
@Timeout(30)
class StartCliPinProbeTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final String PIN_A = TestCertificates.fingerprint(CERTS.localhostCertificate());
    private static final String PIN_B =
        TestCertificates.fingerprint(CERTS.selfsignedCertificate());

    private final StartCliPinProbe probe = new HandshakeStartCliPinProbe();
    private TlsTestServer serverA;
    private TlsTestServer serverB;

    @BeforeEach
    void startServers() throws IOException, GeneralSecurityException {
        serverA = TlsTestServer.start(CERTS.localhostKeyStore());
        serverB = TlsTestServer.start(CERTS.selfsignedKeyStore());
    }

    @AfterEach
    void stopServers() throws IOException, InterruptedException {
        serverA.close();
        serverB.close();
    }

    @Test
    void anAddressPresentingTheServersOwnCertificatePasses() {
        assertThatCode(() -> probe.check(stageServer("int/a", serverA.port(), false, PIN_A)))
            .doesNotThrowAnyException();
        assertThatCode(() -> probe.check(stageServer("int/b", serverB.port(), true, PIN_B)))
            .doesNotThrowAnyException();
        assertThat(serverA.handshakes()).isEqualTo(1);
        assertThat(serverB.handshakes()).isEqualTo(1);
    }

    @Test
    void anAddressPresentingTheOtherServersCertificateIsRefusedWithTlsError() {
        ToolError error = errorOf(stageServer("int/a", serverB.port(), true, PIN_A));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.node()).contains(NodeId.parse("int/a"));
        assertThat(error.message())
            .contains("int/a")
            .contains("localhost:" + serverB.port())
            .contains("(CertificateException)")
            .contains("StartCLI was not started");
    }

    @Test
    void theTlsErrorTextsAreThoseOfTheRestClientForTheSameSwap() {
        EffectiveNodeConfig swapped = stageServer("int/a", serverB.port(), true, PIN_A);

        ToolError probeError = errorOf(swapped);
        ToolError restError;
        try (InubitHttpClient client = new InubitHttpClient(swapped, Optional.empty(),
            Optional.empty(), MaintenanceProbe.NONE, new SecretScrubber())) {
            restError = catchThrowableOfType(ToolErrorException.class,
                () -> client.get("/ibis/rest/healthcheck", Map.of())).error();
        }

        assertThat(restError.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(probeError.likelyCause()).isEqualTo(restError.likelyCause());
        assertThat(probeError.nextStep()).isEqualTo(restError.nextStep());
    }

    @Test
    void aClosedPortIsUnreachableAndTheNextStepNamesTheCliUrl() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String cliUrl = "https://localhost:" + closedPort + "/startcli/endpoint";
        EffectiveNodeConfig server = TestNodeConfig.node().id("int/a")
            .baseUrl("https://localhost:" + serverA.port())
            .cliUrl(cliUrl)
            .trustStore(CERTS.trustStore()).pin(PIN_A).build();

        ToolError error = errorOf(server);

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.message())
            .contains("int/a")
            .contains("localhost:" + closedPort)
            .contains("StartCLI was not started");
        assertThat(error.nextStep())
            .isEqualTo("Check cli.url (" + cliUrl + "), DNS and the VPN, then retry")
            .doesNotContain("baseUrl");
        assertThat(serverA.handshakes()).isZero();
    }

    @Test
    void aListenerThatNeverAnswersTheHandshakeIsATimeout()
        throws IOException, GeneralSecurityException, InterruptedException {
        try (TlsTestServer silent = TlsTestServer.startSilent(CERTS.localhostKeyStore())) {
            Duration timeout = Duration.ofMillis(300);
            EffectiveNodeConfig server = TestNodeConfig.node().id("int/a")
                .baseUrl("https://localhost:" + silent.port())
                .trustStore(CERTS.trustStore()).pin(PIN_A).timeout(timeout).build();

            long start = System.nanoTime();
            ToolError error = errorOf(server);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message())
                .contains("int/a")
                .contains("localhost:" + silent.port())
                .contains("StartCLI was not started");
            assertThat(elapsed).isLessThan(timeout.plusSeconds(3));
            assertThat(silent.handshakes()).isEqualTo(1);
        }
    }

    @Test
    void anHttpCliUrlIsNotProbed() {
        EffectiveNodeConfig server = TestNodeConfig.node().id("int/a")
            .baseUrl("https://localhost:" + serverB.port())
            .cliUrl("http://localhost:" + serverB.port() + EffectiveNodeConfig.DEFAULT_CLI_PATH)
            .trustStore(CERTS.trustStore()).pin(PIN_A).build();

        assertThatCode(() -> probe.check(server)).doesNotThrowAnyException();
        assertThat(serverB.handshakes()).isZero();
    }

    @Test
    void withHostnameVerificationOnACertificateForAnotherHostIsRefused() {
        // the self-signed certificate has no subjectAltName for localhost; only the pin matches
        ToolError error = errorOf(stageServer("int/b", serverB.port(), false, PIN_B));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.message()).contains("StartCLI was not started");
    }

    private ToolError errorOf(EffectiveNodeConfig server) {
        ToolErrorException thrown =
            catchThrowableOfType(ToolErrorException.class, () -> probe.check(server));
        assertThat(thrown).as("the probe refuses %s", server.cli().url()).isNotNull();
        return thrown.error();
    }

    /** A server of the marked stage {@code int}: shared trust store, its own pin. */
    private static EffectiveNodeConfig stageServer(String id, int port,
        boolean disableHostnameVerification, String pin) {
        TestNodeConfig config = TestNodeConfig.node().id(id)
            .baseUrl("https://localhost:" + port)
            .trustStore(CERTS.trustStore());
        return (disableHostnameVerification
            ? config.disableHostnameVerification(pin) : config.pin(pin)).build();
    }
}
