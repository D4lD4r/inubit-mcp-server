package de.dadecker.inubit.mcp.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig.Tls;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T020: chain validation against the trust store, then the leaf pin; only endpoint identification
 * is skipped, and only when disabled and pinned (research R-5a). Uses {@link TestCertificates}.
 */
@Timeout(30)
class PinningTrustManagerTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final CredentialVariables VARIABLES =
        new CredentialVariables("INUBIT", NodeId.parse("test/node1"));

    private static Tls tls(boolean disableHostnameVerification, String pin) {
        return new Tls(Optional.of(CERTS.trustStore()), disableHostnameVerification,
            Optional.ofNullable(pin));
    }

    private static SSLContext context(boolean disableHostnameVerification, String pin) {
        return SslContexts.forServer(VARIABLES, tls(disableHostnameVerification, pin),
            Optional.empty());
    }

    private static String localhostPin() {
        return TestCertificates.fingerprint(CERTS.localhostCertificate());
    }

    private static String selfsignedPin() {
        return TestCertificates.fingerprint(CERTS.selfsignedCertificate());
    }

    private static void assertHandshakeSucceeds(Path serverKeyStore, SSLContext client)
        throws Exception {
        try (TlsTestServer server = TlsTestServer.start(serverKeyStore)) {
            assertThatCode(() -> server.handshake(client, "localhost")).doesNotThrowAnyException();
        }
    }

    private static void assertHandshakeFails(Path serverKeyStore, SSLContext client)
        throws Exception {
        try (TlsTestServer server = TlsTestServer.start(serverKeyStore)) {
            assertThatThrownBy(() -> server.handshake(client, "localhost"))
                .isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void trustedCertificateWithMatchingHostnameIsAccepted() throws Exception {
        assertHandshakeSucceeds(CERTS.localhostKeyStore(), context(false, null));
    }

    @Test
    void certificateOutsideTheTrustStoreIsRejected() throws Exception {
        assertHandshakeFails(CERTS.rogueKeyStore(), context(false, null));
    }

    @Test
    void pinAndDisabledHostnameVerificationNeverReplaceChainValidation() throws Exception {
        String roguePin = TestCertificates.fingerprint(CERTS.rogueCertificate());

        assertHandshakeFails(CERTS.rogueKeyStore(), context(false, roguePin));
        assertHandshakeFails(CERTS.rogueKeyStore(), context(true, roguePin));
    }

    @Test
    void leafWhoseFingerprintDiffersFromThePinIsRejected() throws Exception {
        // trusted and matching hostname, but pinned to another certificate
        assertHandshakeFails(CERTS.localhostKeyStore(), context(false, selfsignedPin()));
    }

    @Test
    void leafMatchingThePinIsAcceptedInEveryPinSpelling() throws Exception {
        String colonUpper = localhostPin();
        String plainLower = colonUpper.replace(":", "").toLowerCase(Locale.ROOT);

        assertHandshakeSucceeds(CERTS.localhostKeyStore(), context(false, colonUpper));
        assertHandshakeSucceeds(CERTS.localhostKeyStore(), context(false, plainLower));
    }

    @Test
    void hostnameMismatchIsRejectedUnlessVerificationIsDisabled() throws Exception {
        // CN=selfsigned.example.test, no SAN: connecting as "localhost" cannot pass identification
        assertHandshakeFails(CERTS.selfsignedKeyStore(), context(false, null));
        assertHandshakeFails(CERTS.selfsignedKeyStore(), context(false, selfsignedPin()));
    }

    @Test
    void hostnameMismatchIsAcceptedWhenDisabledAndPinned() throws Exception {
        assertHandshakeSucceeds(CERTS.selfsignedKeyStore(), context(true, selfsignedPin()));
    }

    @Test
    void disabledHostnameVerificationStillRequiresTheMatchingPin() throws Exception {
        assertHandshakeFails(CERTS.selfsignedKeyStore(), context(true, localhostPin()));
    }

    @Test
    void disablingHostnameVerificationIsPerContextNotJvmWide() throws Exception {
        SSLContext relaxed = context(true, selfsignedPin());
        SSLContext strict = context(false, null);

        try (TlsTestServer server = TlsTestServer.start(CERTS.selfsignedKeyStore())) {
            assertThatCode(() -> server.handshake(relaxed, "localhost"))
                .doesNotThrowAnyException();
            assertThatThrownBy(() -> server.handshake(strict, "localhost"))
                .isInstanceOf(SSLHandshakeException.class);
        }
        assertThat(System.getProperty("jdk.internal.httpclient.disableHostnameVerification"))
            .isNull();
    }

    @Test
    void javaNetHttpClientSkipsOnlyTheHostnameCheckWhenDisabledAndPinned() throws Exception {
        try (TlsTestServer server = TlsTestServer.start(CERTS.selfsignedKeyStore())) {
            URI uri = URI.create("https://localhost:" + server.port() + "/ibis/rest/healthcheck");

            assertThat(send(context(true, selfsignedPin()), uri)).isEqualTo(204);
            assertThatThrownBy(() -> send(context(false, selfsignedPin()), uri))
                .isInstanceOf(SSLHandshakeException.class);
            assertThatThrownBy(() -> send(context(true, localhostPin()), uri))
                .isInstanceOf(SSLHandshakeException.class);
        }
    }

    private static int send(SSLContext context, URI uri) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().sslContext(context)
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Test
    void disablingHostnameVerificationWithoutAPinIsRefused() throws Exception {
        assertThatThrownBy(() -> new PinningTrustManager(jvmDefaultTrustManager(),
            Optional.empty(), true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pinnedCertificateSha256");
    }

    @Test
    void pinsAreParsedFromHexWithOrWithoutColons() {
        byte[] expected = new byte[32];
        Arrays.fill(expected, (byte) 0xAB);
        String hex = "AB".repeat(32);

        assertThat(PinningTrustManager.parsePin(hex)).isEqualTo(expected);
        assertThat(PinningTrustManager.parsePin(hex.toLowerCase(Locale.ROOT)))
            .isEqualTo(expected);
        assertThat(PinningTrustManager.parsePin(String.join(":", hex.split("(?<=\\G..)"))))
            .isEqualTo(expected);
    }

    @Test
    void invalidPinsAreRejected() {
        assertThatThrownBy(() -> PinningTrustManager.parsePin("AB:CD"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PinningTrustManager.parsePin("ZZ".repeat(32)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptedIssuersComeFromTheTrustStore() {
        X509ExtendedTrustManager manager = new PinningTrustManager(
            SslContexts.trustManager(SslContexts.loadTrustStore(CERTS.trustStore(),
                Optional.empty(), VARIABLES)), Optional.empty(), false);

        assertThat(manager.getAcceptedIssuers())
            .containsExactlyInAnyOrder(CERTS.localhostCertificate(), CERTS.selfsignedCertificate());
    }

    private static X509ExtendedTrustManager jvmDefaultTrustManager() throws Exception {
        TrustManagerFactory factory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return (X509ExtendedTrustManager) factory.getTrustManagers()[0];
    }
}
