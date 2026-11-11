package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.domain.model.partner.SecretFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Computes the {@link SecretFingerprint} of a stored secret value (feature 018 FR-012b, research
 * D-5): {@code HMAC-SHA256(key, UTF-8 bytes of the value)} with a 32-byte key from
 * {@link SecureRandom}. The key exists only in memory and only in this object; there is no way to
 * read it, so a fingerprint is useless outside the process. The process uses one instance
 * ({@link #process()}), created on first use.
 */
final class SecretFingerprinter {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    SecretFingerprinter() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        key = new SecretKeySpec(bytes, ALGORITHM);
        Arrays.fill(bytes, (byte) 0);
    }

    /** The fingerprinter of this process; its key is created on the first call. */
    static SecretFingerprinter process() {
        return Process.INSTANCE;
    }

    /** The fingerprint of {@code storedValue} as stored in the export (e.g. the ciphertext). */
    SecretFingerprint fingerprint(String storedValue) {
        Objects.requireNonNull(storedValue, "storedValue");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return SecretFingerprint.of(mac.doFinal(storedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every Java platform
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }

    @Override
    public String toString() {
        return "SecretFingerprinter[redacted]";
    }

    private static final class Process {
        static final SecretFingerprinter INSTANCE = new SecretFingerprinter();
    }
}
