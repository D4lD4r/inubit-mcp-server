package de.dadecker.inubit.mcp.adapter.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T015: the server's TLS handshake before StartCLI (feature 011 FR-012, research D-7). */
@Timeout(30)
class TlsHandshakeCheckTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String CLI_PATH = EffectiveNodeConfig.DEFAULT_CLI_PATH;

    private static WireMockServer localhost;
    private static WireMockServer selfsigned;

    @BeforeAll
    static void startServers() {
        localhost = TestCertificates.httpsWireMock(CERTS.localhostKeyStore());
        selfsigned = TestCertificates.httpsWireMock(CERTS.selfsignedKeyStore());
    }

    @AfterAll
    static void stopServers() {
        localhost.stop();
        selfsigned.stop();
    }

    @BeforeEach
    void resetRequests() {
        localhost.resetRequests();
        selfsigned.resetRequests();
    }

    @Test
    void aTrustedCertificatePassesWithoutAnHttpRequest() {
        assertThatCode(() -> TlsHandshakeCheck.verify(node().trustStore(CERTS.trustStore())
            .build(), url(localhost))).doesNotThrowAnyException();

        assertNoHttpRequest(localhost);
    }

    @Test
    void aMatchingPinPasses() {
        assertThatCode(() -> TlsHandshakeCheck.verify(node().trustStore(CERTS.trustStore())
            .pin(TestCertificates.fingerprint(CERTS.localhostCertificate())).build(),
            url(localhost))).doesNotThrowAnyException();

        assertNoHttpRequest(localhost);
    }

    @Test
    void aPinMismatchIsATlsErrorNamingThePin() {
        ToolError error = errorOf(node().trustStore(CERTS.trustStore())
            .pin(TestCertificates.fingerprint(CERTS.selfsignedCertificate())).build(),
            url(localhost));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).contains(ID.toString(), "127.0.0.1:" + localhost.httpsPort());
        assertThat(error.likelyCause()).contains("pinned SHA-256 fingerprint");
        assertThat(error.nextStep()).contains("tls.pinnedCertificateSha256");
        assertNoHttpRequest(localhost);
    }

    @Test
    void anUntrustedCertificateIsATlsError() {
        // no tls.trustStore: the JVM default trust store does not know the test certificate
        ToolError error = errorOf(node().build(), url(localhost));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.likelyCause()).contains("not trusted");
        assertNoHttpRequest(localhost);
    }

    @Test
    void aWrongHostNameIsATlsError() {
        // CN=selfsigned.example.test without SAN is trusted, but not issued for localhost
        ToolError error = errorOf(node().trustStore(CERTS.trustStore()).build(),
            URI.create("https://localhost:" + selfsigned.httpsPort() + CLI_PATH));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.likelyCause()).contains("host name localhost");
        assertNoHttpRequest(selfsigned);
    }

    @Test
    void theHostNameIsSkippedOnlyTogetherWithThePin() {
        String pin = TestCertificates.fingerprint(CERTS.selfsignedCertificate());

        assertThatCode(() -> TlsHandshakeCheck.verify(node().trustStore(CERTS.trustStore())
            .disableHostnameVerification(pin).build(),
            URI.create("https://localhost:" + selfsigned.httpsPort() + CLI_PATH)))
            .doesNotThrowAnyException();
        assertThat(errorOf(node().trustStore(CERTS.trustStore())
            .disableHostnameVerification(TestCertificates.fingerprint(
                CERTS.localhostCertificate())).build(),
            URI.create("https://localhost:" + selfsigned.httpsPort() + CLI_PATH)).likelyCause())
            .contains("pinned SHA-256 fingerprint");
        assertNoHttpRequest(selfsigned);
    }

    @Test
    void theHostNameGoesOutAsSniOnOneConnection() throws IOException,
        GeneralSecurityException, InterruptedException {
        try (TlsTestServer server = TlsTestServer.start(CERTS.localhostKeyStore())) {
            TlsHandshakeCheck.verify(node().trustStore(CERTS.trustStore()).build(),
                URI.create("https://localhost:" + server.port() + CLI_PATH));

            assertThat(server.connections()).isEqualTo(1);
            assertThat(server.requestedServerNames()).containsExactly("localhost");
        }
    }

    @Test
    void anIpLiteralGoesOutWithoutSni() throws IOException, GeneralSecurityException,
        InterruptedException {
        try (TlsTestServer server = TlsTestServer.start(CERTS.localhostKeyStore())) {
            TlsHandshakeCheck.verify(node().trustStore(CERTS.trustStore()).build(),
                URI.create("https://127.0.0.1:" + server.port() + CLI_PATH));

            assertThat(server.connections()).isEqualTo(1);
            assertThat(server.requestedServerNames()).isEmpty();
        }
    }

    @Test
    void aRefusedConnectionIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        ToolError error = errorOf(node().trustStore(CERTS.trustStore()).build(),
            URI.create("https://127.0.0.1:" + port + CLI_PATH));

        assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.node()).contains(ID);
    }

    @Test
    void aServerThatNeverAnswersTheHandshakeTimesOutAfterTheNodeTimeout() throws IOException {
        // the backlog completes the TCP connection, but nobody accepts it or answers
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            long start = System.nanoTime();

            ToolError error = errorOf(node().trustStore(CERTS.trustStore())
                .timeout(Duration.ofMillis(300)).build(),
                URI.create("https://127.0.0.1:" + silent.getLocalPort() + CLI_PATH));

            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(Duration.ofSeconds(2));
        }
    }

    private static TestNodeConfig node() {
        return TestNodeConfig.node().id(ID.toString());
    }

    private static URI url(WireMockServer server) {
        return URI.create("https://127.0.0.1:" + server.httpsPort() + CLI_PATH);
    }

    private static ToolError errorOf(EffectiveNodeConfig node, URI url) {
        try {
            TlsHandshakeCheck.verify(node, url);
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    private static void assertNoHttpRequest(WireMockServer server) {
        server.verify(0, anyRequestedFor(anyUrl()));
        assertThat(server.getAllServeEvents()).isEmpty();
    }
}
