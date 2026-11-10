package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The key-material rule of the export (feature 015) in the domain, so that the local knowledge
 * (feature 019, research D-6 (b)) uses the same rule; behaviour unchanged by the move.
 */
class KeyMaterialTest {

    private static final byte[] PKCS12 = {0x30, (byte) 0x82, 0x01, 0x00, 0x02, 0x01, 0x03,
        0x30, (byte) 0x80, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d,
        0x01, 0x07, 0x01};

    @Test
    void isAPublicDomainType() {
        assertThat(Modifier.isPublic(KeyMaterial.class.getModifiers())).isTrue();
        assertThat(KeyMaterial.class.getPackageName()).isEqualTo("de.dadecker.inubit.mcp.domain.model");
    }

    @Test
    void keystoreAndKeyFileNames() {
        assertThat(KeyMaterial.hasKeyName("certs/server.JKS")).isTrue();
        assertThat(KeyMaterial.hasKeyName(" a.jceks ")).isTrue();
        assertThat(KeyMaterial.hasKeyName("store.p12")).isTrue();
        assertThat(KeyMaterial.hasKeyName("store.pfx")).isTrue();
        assertThat(KeyMaterial.hasKeyName("my.keystore")).isTrue();
        assertThat(KeyMaterial.hasKeyName("private.key")).isTrue();
        assertThat(KeyMaterial.hasKeyName("keys.txt")).isFalse();
        assertThat(KeyMaterial.hasKeyName(null)).isFalse();
    }

    @Test
    void keystoresAndPrivateKeysAreKeyMaterial() {
        assertThat(KeyMaterial.isKeyMaterial(new byte[] {(byte) 0xfe, (byte) 0xed, (byte) 0xfe,
            (byte) 0xed, 0, 0})).as("JKS").isTrue();
        assertThat(KeyMaterial.isKeyMaterial(new byte[] {(byte) 0xce, (byte) 0xce, (byte) 0xce,
            (byte) 0xce, 0})).as("JCEKS").isTrue();
        assertThat(KeyMaterial.isKeyMaterial(PKCS12)).as("PKCS#12, BER").isTrue();
        assertThat(KeyMaterial.isKeyMaterial(("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n"
            + "-----END RSA PRIVATE KEY-----").getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(KeyMaterial.isKeyMaterial("-----BEGIN CERTIFICATE-----\nAAAA"
            .getBytes(StandardCharsets.US_ASCII))).as("certificate").isFalse();
        assertThat(KeyMaterial.isKeyMaterial("plain".getBytes(StandardCharsets.US_ASCII)))
            .isFalse();
    }

    @Test
    void documentsAreDecodedFromBase64WithOrWithoutGzip() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.US_ASCII);
        assertThat(KeyMaterial.decodeDocument(Base64.getEncoder().encodeToString(content)))
            .hasValueSatisfying(d -> assertThat(d).isEqualTo(content));
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(content);
        }
        assertThat(KeyMaterial.decodeDocument(Base64.getMimeEncoder()
            .encodeToString(gz.toByteArray()))).hasValueSatisfying(
                d -> assertThat(d).isEqualTo(content));
        assertThat(KeyMaterial.decodeDocument("not base64 !")).isEmpty();
    }

    @Test
    void longBase64NeedsSixtyFourCharactersOfTheAlphabet() {
        assertThat(KeyMaterial.longBase64("A".repeat(64))).isTrue();
        assertThat(KeyMaterial.longBase64("A".repeat(32) + "\n  " + "B".repeat(32))).isTrue();
        assertThat(KeyMaterial.longBase64("A".repeat(63))).isFalse();
        assertThat(KeyMaterial.longBase64("A".repeat(64) + "!")).isFalse();
        assertThat(KeyMaterial.longBase64("A".repeat(200_000))).as("no stack overflow").isTrue();
    }
}
