package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 020 T008 (research D-7): the TLS handshake of the StartCLI pin probe, extracted so the
 * queue-entry helper can capture the verified peer chain. All listeners are on 127.0.0.1.
 */
@Timeout(30)
class TlsPeerTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final String PIN_LOCALHOST =
        TestCertificates.fingerprint(CERTS.localhostCertificate());
    private static final String PIN_SELFSIGNED =
        TestCertificates.fingerprint(CERTS.selfsignedCertificate());
    private static final String PIN_ROGUE = TestCertificates.fingerprint(CERTS.rogueCertificate());
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final TlsPeer peer = new TlsPeer("the queue-entry helper");

    @Test
    void aMatchingPinReturnsThePeerChain() throws Exception {
        try (TlsTestServer server = TlsTestServer.start(CERTS.localhostKeyStore())) {
            List<X509Certificate> chain =
                peer.handshake(node(server.port(), PIN_LOCALHOST), TIMEOUT);

            assertThat(chain).containsExactly(CERTS.localhostCertificate());
            assertThat(server.handshakes()).isEqualTo(1);
        }
    }

    @Test
    void aPinMismatchIsATlsError() throws Exception {
        try (TlsTestServer server = TlsTestServer.start(CERTS.localhostKeyStore())) {
            ToolError error = errorOf(node(server.port(), PIN_SELFSIGNED), TIMEOUT);

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(error.node()).contains(NodeId.parse("int/a"));
            assertThat(error.message())
                .isEqualTo("The TLS connection to int/a failed for the StartCLI address"
                    + " 127.0.0.1:" + server.port()
                    + " (CertificateException); the queue-entry helper was not started");
        }
    }

    @Test
    void aCertificateFromAnUnknownCaIsATlsError() throws Exception {
        // the rogue certificate names 127.0.0.1 and is pinned, but is not in the trust store
        try (TlsTestServer server = TlsTestServer.start(CERTS.rogueKeyStore())) {
            ToolError error = errorOf(node(server.port(), PIN_ROGUE), TIMEOUT);

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(error.message()).contains("the queue-entry helper was not started");
        }
    }

    @Test
    void withHostnameVerificationACertificateForAnotherHostIsATlsError() throws Exception {
        // the self-signed certificate is trusted and pinned but names no 127.0.0.1
        try (TlsTestServer server = TlsTestServer.start(CERTS.selfsignedKeyStore())) {
            ToolError error = errorOf(node(server.port(), PIN_SELFSIGNED), TIMEOUT);

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        }
    }

    @Test
    void withHostnameVerificationDisabledTheSameCertificateReturnsItsChain() throws Exception {
        try (TlsTestServer server = TlsTestServer.start(CERTS.selfsignedKeyStore())) {
            EffectiveNodeConfig node = TestNodeConfig.node().id("int/a")
                .baseUrl("https://127.0.0.1:" + server.port())
                .trustStore(CERTS.trustStore())
                .disableHostnameVerification(PIN_SELFSIGNED).build();

            assertThat(peer.handshake(node, TIMEOUT))
                .containsExactly(CERTS.selfsignedCertificate());
        }
    }

    @Test
    void aClosedPortIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }

        ToolError error = errorOf(node(closedPort, PIN_LOCALHOST), TIMEOUT);

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.message())
            .contains("127.0.0.1:" + closedPort)
            .contains("the queue-entry helper was not started");
        assertThat(error.nextStep()).startsWith("Check cli.url (https://127.0.0.1:" + closedPort);
    }

    @Test
    void aServerThatNeverAnswersTheHandshakeIsATimeoutWithinTheGivenDuration() throws Exception {
        try (TlsTestServer silent = TlsTestServer.startSilent(CERTS.localhostKeyStore())) {
            Duration timeout = Duration.ofMillis(300);

            long start = System.nanoTime();
            ToolError error = errorOf(node(silent.port(), PIN_LOCALHOST), timeout);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message())
                .isEqualTo("The StartCLI address 127.0.0.1:" + silent.port() + " of int/a did not"
                    + " complete the TLS handshake within 300 ms;"
                    + " the queue-entry helper was not started");
            assertThat(elapsed).isLessThan(timeout.plusSeconds(3));
        }
    }

    private ToolError errorOf(EffectiveNodeConfig node, Duration timeout) {
        ToolErrorException thrown =
            catchThrowableOfType(ToolErrorException.class, () -> peer.handshake(node, timeout));
        assertThat(thrown).as("the handshake to %s is refused", node.cli().url()).isNotNull();
        ToolError error = thrown.error();
        TestSecrets.assertNoLeak(error.message() + error.likelyCause() + error.nextStep());
        return error;
    }

    /** A node whose StartCLI address is 127.0.0.1:{@code port}, the shared trust store and a pin. */
    private static EffectiveNodeConfig node(int port, String pin) {
        return TestNodeConfig.node().id("int/a")
            .baseUrl("https://127.0.0.1:" + port)
            .trustStore(CERTS.trustStore()).pin(pin).build();
    }
}
