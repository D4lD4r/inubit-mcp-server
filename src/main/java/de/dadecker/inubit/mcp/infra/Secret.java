package de.dadecker.inubit.mcp.infra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * A password, token or other credential value. {@link #toString()} never reveals it; only
 * {@link #reveal()} does, and only code that hands the value to INUBIT may call it.
 */
public final class Secret {

    private static final String MASK = "***";

    private final String value;

    private Secret(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** Wraps a value without registering it; prefer {@link SecretScrubber#register(String)}. */
    public static Secret of(String value) {
        return new Secret(value);
    }

    public String reveal() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Secret secret
            && MessageDigest.isEqual(bytes(), secret.bytes());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes());
    }

    @Override
    public String toString() {
        return MASK;
    }

    private byte[] bytes() {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
