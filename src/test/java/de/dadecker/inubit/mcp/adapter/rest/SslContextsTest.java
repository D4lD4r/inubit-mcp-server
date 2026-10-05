package de.dadecker.inubit.mcp.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig.Tls;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** T020 / T042: per-server {@link SSLContext} from {@code tls.trustStore}. */
@Timeout(30)
class SslContextsTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    /** Profile {@code acme} with its default credential prefix (002 research D-6). */
    private static final CredentialVariables NODE =
        new CredentialVariables("INUBIT_ACME", NodeId.parse("test/node1"));

    @TempDir
    Path tmp;

    private static Tls trustStore(Path path) {
        return new Tls(Optional.of(path), false, Optional.empty());
    }

    private static void assertTlsError(Runnable call, String... messageParts) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(e.error().toString()).contains(messageParts)
                .doesNotContain(TestCertificates.PASSWORD);
        });
    }

    @Test
    void passwordLessPkcs12TrustStoreIsLoaded() throws Exception {
        SSLContext context = SslContexts.forServer(NODE, trustStore(CERTS.trustStore()),
            Optional.empty());

        try (TlsTestServer server = TlsTestServer.start(CERTS.localhostKeyStore())) {
            assertThatCode(() -> server.handshake(context, "localhost"))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void protectedTrustStoreIsLoadedWithItsPassword() throws Exception {
        SSLContext context = SslContexts.forServer(NODE, trustStore(CERTS.protectedTrustStore()),
            Optional.of(Secret.of(TestCertificates.PASSWORD)));

        try (TlsTestServer server = TlsTestServer.start(CERTS.localhostKeyStore())) {
            assertThatCode(() -> server.handshake(context, "localhost"))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void protectedTrustStoreWithoutPasswordIsATlsErrorNamingTheVariableKind() {
        assertTlsError(() -> SslContexts.forServer(NODE, trustStore(CERTS.protectedTrustStore()),
            Optional.empty()), "no certificates", "TRUSTSTORE_PASSWORD");
    }

    @Test
    void trustStoreErrorsNameTheConcreteVariablesOfTheNode() {
        // 002 review U2: no level words, the node's own variable names under the effective
        // prefix of its profile (research D-6)
        assertTlsError(() -> SslContexts.forServer(NODE, trustStore(CERTS.protectedTrustStore()),
            Optional.empty()), "INUBIT_ACME_TEST_NODE1_TRUSTSTORE_PASSWORD",
            "INUBIT_ACME_TEST_TRUSTSTORE_PASSWORD");
        assertThatThrownBy(() -> SslContexts.forServer(NODE,
            trustStore(CERTS.protectedTrustStore()), Optional.of(Secret.of("wrong-pw-123"))))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error()
                .nextStep()).contains("INUBIT_ACME_TEST_NODE1_TRUSTSTORE_PASSWORD",
                    "INUBIT_ACME_TEST_TRUSTSTORE_PASSWORD")
                .doesNotContainPattern("(?i)\\b(stage|server)\\b|<"));
    }

    @Test
    void wrongTrustStorePasswordIsATlsErrorWithoutThePassword() {
        assertTlsError(() -> SslContexts.forServer(NODE, trustStore(CERTS.protectedTrustStore()),
            Optional.of(Secret.of("wrong-" + TestCertificates.PASSWORD))), "trust store");
    }

    @Test
    void missingTrustStoreIsATlsError() {
        assertTlsError(() -> SslContexts.forServer(NODE, trustStore(tmp.resolve("missing.p12")),
            Optional.empty()), "trust store", "missing.p12");
    }

    @Test
    void corruptTrustStoreIsATlsError() throws Exception {
        Path corrupt = Files.writeString(tmp.resolve("corrupt.p12"), "not a key store");

        assertTlsError(() -> SslContexts.forServer(NODE, trustStore(corrupt), Optional.empty()),
            "trust store");
    }

    @Test
    void withoutTrustStoreTheJvmDefaultTrustIsUsed() throws Exception {
        SSLContext context = SslContexts.forServer(NODE, 
            new Tls(Optional.empty(), false, Optional.empty()), Optional.empty());

        try (TlsTestServer server = TlsTestServer.start(CERTS.localhostKeyStore())) {
            assertThatThrownBy(() -> server.handshake(context, "localhost"))
                .isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void everyServerGetsItsOwnContext() throws Exception {
        Tls tls = trustStore(CERTS.trustStore());

        assertThat(SslContexts.forServer(NODE, tls, Optional.empty()))
            .isNotSameAs(SslContexts.forServer(NODE, tls, Optional.empty()))
            .isNotSameAs(SSLContext.getDefault());
    }
}
