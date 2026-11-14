package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T013 (feature 022 research D-19, FR-010): the secret rules of feature 018 (FR-008 there: secret
 * types, {@code encrypted} flags, the secret name pattern, key material) as one public domain rule
 * set for configuration values, module properties and user records.
 */
class SecretRulesTest {

    private static final String PEM_KEY = """
        -----BEGIN PRIVATE KEY-----
        MIIBfixture
        -----END PRIVATE KEY-----""";

    @Test
    void isAPublicDomainType() {
        assertThat(Modifier.isPublic(SecretRules.class.getModifiers())).isTrue();
        assertThat(SecretRules.class.getPackageName())
            .isEqualTo("de.dadecker.inubit.mcp.domain.model");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FtpPassword", "PASSWD", "loginPwd", "ClientSecret", "AuthToken",
        "TrustKeyStoreRef", "PrivateKeyAlias", "KeyPassphrase", "CredentialRef", "ApiKey",
        "AccessKeyId"})
    void aSecretNameMatchesInAnyCase(String name) {
        assertThat(SecretRules.isSecretName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Login", "ServerName", "host", "port", "licensedThreads", ""})
    void aHarmlessNameDoesNotMatch(String name) {
        assertThat(SecretRules.isSecretName(name)).isFalse();
    }

    @Test
    void aPathMatchesWhenAnyOfItsSegmentsIsASecretName() {
        assertThat(SecretRules.isSecretPath("Mail/smtpCredentials/user")).isTrue();
        assertThat(SecretRules.isSecretPath("Database.connection.Password")).isTrue();
        assertThat(SecretRules.isSecretPath("Mail/smtp/host")).isFalse();
    }

    @Test
    void anEncryptedValueIsMaskedWhateverItsNameAndType() {
        assertThat(SecretRules.masks("Login", Optional.empty(), true, "AES-c3ludGhldGlj"))
            .isTrue();
        assertThat(SecretRules.masks("Login", Optional.empty(), false, "login1")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password", "WSSPassword", "KeyStore", "HTTPConnectorTrustStore",
        "PrivateKeyEntry", "X509", "ClientCertificate", "SSHKey", "key"})
    void aSecretTypeIsMaskedWhateverItsNameAndValue(String type) {
        assertThat(SecretRules.masks("Reference", Optional.of(type), false, "harmless-0001"))
            .isTrue();
    }

    @Test
    void aHarmlessOrMissingTypeDoesNotMask() {
        assertThat(SecretRules.masks("Reference", Optional.of("Boolean"), false, "true"))
            .isFalse();
        assertThat(SecretRules.masks("Reference", Optional.of(""), false, "harmless-0001"))
            .isFalse();
        assertThat(SecretRules.masks("Reference", Optional.empty(), false, "harmless-0001"))
            .isFalse();
    }

    @Test
    void aSecretNameIsMaskedEvenWithAHarmlessValue() {
        assertThat(SecretRules.masks("smtpPassword", Optional.empty(), false, "harmless-0001"))
            .isTrue();
    }

    @Test
    void keyMaterialIsMaskedWhateverItsName() {
        byte[] jks = Arrays.copyOf(new byte[] {(byte) 0xfe, (byte) 0xed, (byte) 0xfe,
            (byte) 0xed, 0, 0, 0, 2}, 64);
        String longJks = Base64.getMimeEncoder().encodeToString(jks);
        String shortJks = Base64.getEncoder().encodeToString(Arrays.copyOf(jks, 8));

        assertThat(SecretRules.masks("Name", Optional.empty(), false, PEM_KEY)).as("PEM key")
            .isTrue();
        assertThat(SecretRules.masks("Name", Optional.empty(), false, longJks))
            .as("base64 keystore").isTrue();
        assertThat(SecretRules.masks("Name", Optional.of("InternalDocument"), false, shortJks))
            .as("a short keystore in a document").isTrue();
        assertThat(SecretRules.masks("Name", Optional.empty(), false, shortJks))
            .as("short base64 outside a document is not decoded").isFalse();
    }

    @Test
    void theKeyMaterialRuleLooksAtTheValueOnly() {
        // T017: the one key-material rule that SecretPaths (features 003, 018) also calls
        byte[] jks = Arrays.copyOf(new byte[] {(byte) 0xfe, (byte) 0xed, (byte) 0xfe,
            (byte) 0xed, 0, 0, 0, 2}, 8);
        String shortJks = Base64.getEncoder().encodeToString(jks);

        assertThat(SecretRules.isKeyMaterial(Optional.empty(), PEM_KEY)).isTrue();
        assertThat(SecretRules.isKeyMaterial(Optional.of("InternalDocument"), shortJks)).isTrue();
        assertThat(SecretRules.isKeyMaterial(Optional.of("String"), shortJks)).isFalse();
        assertThat(SecretRules.isKeyMaterial(Optional.of("Password"), "harmless-0001"))
            .as("a secret type is not key material").isFalse();
    }
}
