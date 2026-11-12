package de.dadecker.inubit.mcp.domain.model.node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The reference of a listed I/O log entry (research D-8, contracts/helper-protocol-delta.md
 * "Message reference"): Base64url without padding of the first 16 bytes of SHA-256 over the
 * UTF-8 bytes of the full entry, 22 characters. The helper computes it the same way;
 * {@code fixtures/node-tools/io-message-ref.vectors} pins both.
 */
public record IoMessageRef(String value) {

    private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final int BYTES = 16;

    public IoMessageRef {
        if (!isValid(value)) {
            throw new IllegalArgumentException(
                "Invalid message reference: expected 22 characters of [A-Za-z0-9_-]");
        }
    }

    /** The reference of the listed {@code entry}. */
    public static IoMessageRef of(String entry) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        byte[] digest = sha256.digest(entry.getBytes(StandardCharsets.UTF_8));
        return new IoMessageRef(
            Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, BYTES)));
    }

    /** True if {@code value} has 22 characters of {@code [A-Za-z0-9_-]}. */
    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }
}
