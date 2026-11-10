package de.dadecker.inubit.mcp.application.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * The version token of a note (feature 019, research D-9, FR-011): {@code v1.} and the base64url
 * form of the first 18 bytes of SHA-256 over the modification time ({@code <epoch seconds>.<nine
 * digits of nanos>}), a line feed and the masked text. Masked values are masked before hashing,
 * so the token reveals nothing of them.
 */
public final class VersionToken {

    private static final String PREFIX = "v1.";
    private static final int BYTES = 18;

    private VersionToken() {
    }

    public static String of(FileTime modified, String maskedText) {
        Instant time = modified.toInstant();
        String input = time.getEpochSecond() + "." + String.format("%09d", time.getNano()) + "\n"
            + maskedText;
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Arrays.copyOf(hash, BYTES));
    }
}
