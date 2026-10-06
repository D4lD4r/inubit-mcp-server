package de.dadecker.inubit.mcp.adapter.archive.v81;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Recognizes key material outside {@code type="KeyStore"} properties (review I3, FR-023): JKS
 * ({@code FEEDFEED}), JCEKS ({@code CECECECE}), PKCS#12 (DER {@code PFX} with version 3 and a
 * PKCS#7 content), PEM private keys, and file names ending in {@code .jks}, {@code .jceks},
 * {@code .p12}, {@code .pfx}, {@code .keystore} or {@code .key}. PKCS#12 is accepted in DER and
 * in BER with indefinite lengths.
 */
final class KeyMaterial {

    private static final Pattern KEY_NAME =
        Pattern.compile("(?i).*\\.(jks|jceks|p12|pfx|keystore|key)$");
    private static final byte[] JKS = {(byte) 0xfe, (byte) 0xed, (byte) 0xfe, (byte) 0xed};
    private static final byte[] JCEKS = {(byte) 0xce, (byte) 0xce, (byte) 0xce, (byte) 0xce};
    /** OID 1.2.840.113549.1.7 (PKCS#7) without its last arc. */
    private static final byte[] PKCS7 = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
        (byte) 0xf7, 0x0d, 0x01, 0x07};
    private static final int MAX_DECODED = 64 << 20;

    private KeyMaterial() {
    }

    /** True if {@code name} (a file or document name) names a keystore or key file. */
    static boolean hasKeyName(String name) {
        return name != null && KEY_NAME.matcher(name.strip()).matches();
    }

    /** True if {@code content} is a JKS/JCEKS/PKCS#12 keystore or holds a PEM private key. */
    static boolean isKeyMaterial(byte[] content) {
        if (startsWith(content, JKS) || startsWith(content, JCEKS) || isPkcs12(content)) {
            return true;
        }
        String text = new String(content, StandardCharsets.ISO_8859_1);
        return text.contains("-----BEGIN") && text.contains("PRIVATE KEY-----");
    }

    /**
     * The decoded content of an {@code InternalDocument} value (base64, gzip-compressed or not),
     * or empty if it is not base64.
     */
    static Optional<byte[]> decodeDocument(String value) {
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (decoded.length > 2 && decoded[0] == (byte) 0x1f && decoded[1] == (byte) 0x8b) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
                return Optional.of(in.readNBytes(MAX_DECODED));
            } catch (IOException e) {
                return Optional.of(decoded);
            }
        }
        return Optional.of(decoded);
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length
            && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** {@code PFX ::= SEQUENCE { version INTEGER (3), authSafe ContentInfo (PKCS#7), … }}. */
    private static boolean isPkcs12(byte[] der) {
        int content = contentStart(der, 0, 0x30);
        if (content < 0 || content + 3 > der.length || der[content] != 0x02
            || der[content + 1] != 0x01 || der[content + 2] != 0x03) {
            return false;
        }
        int authSafe = contentStart(der, content + 3, 0x30);
        return authSafe >= 0 && startsWith(Arrays.copyOfRange(der, authSafe,
            Math.min(der.length, authSafe + PKCS7.length)), PKCS7);
    }

    /** The offset of the content of the DER element at {@code at} with {@code tag}, or -1. */
    private static int contentStart(byte[] der, int at, int tag) {
        if (at + 2 > der.length || (der[at] & 0xff) != tag) {
            return -1;
        }
        int length = der[at + 1] & 0xff;
        if (length <= 0x80) { // short form, or 0x80: BER indefinite length
            return at + 2;
        }
        int bytes = length & 0x7f;
        return bytes >= 1 && bytes <= 4 && at + 2 + bytes <= der.length ? at + 2 + bytes : -1;
    }
}
