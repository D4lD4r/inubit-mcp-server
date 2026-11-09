package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.Arrays;
import java.util.Objects;

/**
 * The keyed fingerprint of a set secret value (data-model.md → SecretFingerprint, research D-5):
 * two masked values with equal fingerprints hold the same stored value. Not a record and without
 * any accessor, so the bytes never leave this object and the result mapper refuses to serialize
 * it.
 */
public final class SecretFingerprint {

    private final byte[] bytes;

    private SecretFingerprint(byte[] bytes) {
        this.bytes = bytes;
    }

    /** A fingerprint over a copy of {@code bytes}; the caller's array is not kept. */
    public static SecretFingerprint of(byte[] bytes) {
        if (Objects.requireNonNull(bytes, "bytes").length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        return new SecretFingerprint(bytes.clone());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SecretFingerprint fingerprint
            && Arrays.equals(bytes, fingerprint.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "SecretFingerprint[redacted]";
    }
}
