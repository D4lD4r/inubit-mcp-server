package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Masked;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T013 (feature 022 research D-19, FR-010): a JSON tree from INUBIT is masked leaf by leaf by
 * {@code SecretRules}; the result is a masked copy with the number of masked leaves.
 */
class JsonLeafMaskerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MASK = Masked.MASK_TOKEN;

    @Test
    void nestedObjectsAreMaskedBySecretNameAndTheOriginalIsUnchanged() {
        JsonNode tree = JSON.readTree("""
            {"Mail": {"smtp": {"host": "mail.example", "port": 25,
                               "smtpPassword": "synthetic-secret-0001"}}}""");

        JsonLeafMasker.Result result = JsonLeafMasker.mask(tree);

        JsonNode smtp = result.masked().path("Mail").path("smtp");
        assertThat(smtp.path("host").asString()).isEqualTo("mail.example");
        assertThat(smtp.path("port").isNumber()).isTrue();
        assertThat(smtp.path("smtpPassword").asString()).isEqualTo(MASK);
        assertThat(result.maskedCount()).isEqualTo(1);
        assertThat(tree.path("Mail").path("smtp").path("smtpPassword").asString())
            .as("the input tree is not changed").isEqualTo("synthetic-secret-0001");
    }

    @Test
    void everyLeafBelowASecretNameIsMasked() {
        JsonNode tree = JSON.readTree("""
            {"proxyCredentials": {"user": "synthetic-user-0001", "port": 3128},
             "proxy": {"host": "proxy.example"}}""");

        JsonLeafMasker.Result result = JsonLeafMasker.mask(tree);

        assertThat(result.masked().path("proxyCredentials").path("user").asString())
            .isEqualTo(MASK);
        assertThat(result.masked().path("proxyCredentials").path("port").asString())
            .isEqualTo(MASK);
        assertThat(result.masked().path("proxy").path("host").asString())
            .isEqualTo("proxy.example");
        assertThat(result.maskedCount()).isEqualTo(2);
    }

    @Test
    void arrayElementsAreMaskedByTheNameOfTheirArrayAndTheirOwnFields() {
        JsonNode tree = JSON.readTree("""
            {"apiKeys": ["synthetic-secret-0002", "synthetic-secret-0003"],
             "servers": [{"name": "a", "token": "synthetic-secret-0004"}, {"name": "b"}],
             "ports": [80, 443]}""");

        JsonLeafMasker.Result result = JsonLeafMasker.mask(tree);

        JsonNode masked = result.masked();
        assertThat(masked.path("apiKeys").get(0).asString()).isEqualTo(MASK);
        assertThat(masked.path("apiKeys").get(1).asString()).isEqualTo(MASK);
        assertThat(masked.path("servers").get(0).path("name").asString()).isEqualTo("a");
        assertThat(masked.path("servers").get(0).path("token").asString()).isEqualTo(MASK);
        assertThat(masked.path("servers").get(1).path("name").asString()).isEqualTo("b");
        assertThat(masked.path("ports").get(1).asInt()).isEqualTo(443);
        assertThat(result.maskedCount()).isEqualTo(3);
    }

    @Test
    void anEncryptedSiblingFlagMasksTheValuesBesideIt() {
        JsonNode tree = JSON.readTree("""
            {"login": {"value": "AES-c3ludGhldGlj", "encrypted": " TRUE "},
             "user": {"value": "synthetic-user-0002", "encrypted": true},
             "plain": {"value": "login1", "encrypted": false}}""");

        JsonLeafMasker.Result result = JsonLeafMasker.mask(tree);

        JsonNode masked = result.masked();
        assertThat(masked.path("login").path("value").asString()).isEqualTo(MASK);
        assertThat(masked.path("login").path("encrypted").asString()).as("the flag itself")
            .isEqualTo(" TRUE ");
        assertThat(masked.path("user").path("value").asString()).isEqualTo(MASK);
        assertThat(masked.path("plain").path("value").asString()).isEqualTo("login1");
        assertThat(result.maskedCount()).isEqualTo(2);
    }

    @Test
    void keyMaterialIsMaskedWhateverItsName() {
        String jks = Base64.getEncoder().encodeToString(Arrays.copyOf(new byte[] {(byte) 0xfe,
            (byte) 0xed, (byte) 0xfe, (byte) 0xed, 0, 0, 0, 2}, 64));
        JsonNode tree = JSON.createObjectNode()
            .put("description", "-----BEGIN PRIVATE KEY-----\nMIIBfixture\n"
                + "-----END PRIVATE KEY-----")
            .put("trust", jks)
            .put("comment", "harmless");

        JsonLeafMasker.Result result = JsonLeafMasker.mask(tree);

        assertThat(result.masked().path("description").asString()).isEqualTo(MASK);
        assertThat(result.masked().path("trust").asString()).isEqualTo(MASK);
        assertThat(result.masked().path("comment").asString()).isEqualTo("harmless");
        assertThat(result.maskedCount()).isEqualTo(2);
        assertThat(result.masked().toString()).doesNotContain("PRIVATE KEY", jks);
    }

    @Test
    void nullLeavesStayAndAreNotCounted() {
        JsonNode tree = JSON.readTree("{\"password\": null, \"host\": null}");

        JsonLeafMasker.Result result = JsonLeafMasker.mask(tree);

        assertThat(result.masked().path("password").isNull()).isTrue();
        assertThat(result.maskedCount()).isZero();
    }
}
