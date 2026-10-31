package de.dadecker.inubit.mcp.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig.Tls;
import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.config.StoreLocation;
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
    private static final SecretLabels NODE = new SecretLabels.Store(
        new CredentialNames("INUBIT_ACME", NodeId.parse("test/node1")), StoreKind.KEYCHAIN);

    /** An AWS node (feature 010, research R-15), with or without a trust-store parameter. */
    private static SecretLabels awsNode(Optional<String> trustStorePassword) {
        return new SecretLabels.Aws(NodeId.parse("test/node1"),
            new StoreLocation("eu-central-1", Optional.of("inubit")),
            new ParameterNames(Optional.of("/inubit/test/node1/username"),
                Optional.of("/inubit/test/node1/password"), trustStorePassword,
                Optional.empty(), Optional.empty()));
    }

    private static ToolErrorException tlsError(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().code()).isEqualTo(ErrorCode.TLS_ERROR);
            return e;
        }
        throw new AssertionError("expected a TLS_ERROR");
    }

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
    void trustStoreErrorsOfACredentialStoreNodeNameItsEntry() {
        // feature 011 (T016, research R-12)
        String entry = "INUBIT_ACME_TEST_NODE1_TRUSTSTORE_PASSWORD (or"
            + " INUBIT_ACME_TEST_TRUSTSTORE_PASSWORD)";
        ToolErrorException empty = tlsError(() -> SslContexts.forServer(NODE,
            trustStore(CERTS.protectedTrustStore()), Optional.empty()));
        assertThat(empty.error().likelyCause()).isEqualTo("The trust store is empty, or its"
            + " certificates are encrypted and no trust store password entry " + entry
            + " is in the macOS Keychain");
        assertThat(empty.error().nextStep()).isEqualTo("Import the INUBIT server certificate"
            + " into a password-less PKCS12 trust store, or store its password in the macOS"
            + " Keychain as entry " + entry + ", service inubit-mcp (not allowed together with a"
            + " CLI)");

        ToolErrorException wrong = tlsError(() -> SslContexts.forServer(NODE,
            trustStore(CERTS.protectedTrustStore()), Optional.of(Secret.of("wrong-pw-123"))));
        assertThat(wrong.error().nextStep()).isEqualTo("Check tls.trustStore and credential"
            + " store entry " + entry + " in the macOS Keychain");
    }

    @Test
    void trustStoreErrorsOfAnAwsNodeNameItsParameterInsteadOfVariables() {
        SecretLabels aws = awsNode(Optional.of("/inubit/test/node1/truststore-password"));
        String parameter =
            "Parameter Store /inubit/test/node1/truststore-password (eu-central-1, profile inubit)";

        ToolErrorException empty = tlsError(() -> SslContexts.forServer(aws,
            trustStore(CERTS.protectedTrustStore()), Optional.empty()));
        assertThat(empty.error().likelyCause()).isEqualTo("The trust store is empty, or its"
            + " certificates are encrypted and no trust store password is set in " + parameter);
        assertThat(empty.error().nextStep()).isEqualTo("Import the INUBIT server certificate"
            + " into a password-less PKCS12 trust store, or store its password in " + parameter
            + " (not allowed together with a CLI)");

        ToolErrorException wrong = tlsError(() -> SslContexts.forServer(aws,
            trustStore(CERTS.protectedTrustStore()), Optional.of(Secret.of("wrong-pw-123"))));
        assertThat(wrong.error().nextStep()).isEqualTo("Check tls.trustStore and " + parameter);
        assertThat(wrong.error().toString()).doesNotContain("INUBIT_", "wrong-pw-123");
    }

    @Test
    void anAwsNodeWithoutTrustStoreParameterIsToldWhichSettingToConfigure() {
        ToolErrorException empty = tlsError(() -> SslContexts.forServer(awsNode(Optional.empty()),
            trustStore(CERTS.protectedTrustStore()), Optional.empty()));

        assertThat(empty.error().likelyCause()).isEqualTo("The trust store is empty, or its"
            + " certificates are encrypted and no trust store password parameter is configured"
            + " (credentials.parameters.trustStorePassword)");
        assertThat(empty.error().nextStep()).isEqualTo("Import the INUBIT server certificate"
            + " into a password-less PKCS12 trust store, or store its password in Parameter Store"
            + " (eu-central-1, profile inubit) and set credentials.parameters.trustStorePassword"
            + " to its name (not allowed together with a CLI)");
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
