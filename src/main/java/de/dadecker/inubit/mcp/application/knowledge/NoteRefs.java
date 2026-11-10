package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Listing;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.NoteFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * References to listed notes (feature 019, research D-9): {@code r1.} and the base64url form of
 * the first 15 bytes of HMAC-SHA-256 over the exact relative path, with a key drawn once per
 * process (RFC 2104 over {@link MessageDigest}: the application layer uses {@code java.*} only).
 * A reference is stable while the server runs, tells apart files whose names are equal
 * after NFC, and reveals nothing of the name; it resolves against the current listing only.
 */
public final class NoteRefs {

    private static final String PREFIX = "r1.";
    private static final int KEY_BYTES = 32;
    private static final int BLOCK_BYTES = 64;
    private static final int REF_BYTES = 15;

    private final byte[] inner = new byte[BLOCK_BYTES];
    private final byte[] outer = new byte[BLOCK_BYTES];

    public NoteRefs(SecureRandom random) {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        for (int i = 0; i < BLOCK_BYTES; i++) {
            byte k = i < KEY_BYTES ? key[i] : 0;
            inner[i] = (byte) (k ^ 0x36);
            outer[i] = (byte) (k ^ 0x5c);
        }
    }

    /** The reference of the note at {@code relative} (below the knowledge directory). */
    public String of(Path relative) {
        String path = StreamSupport.stream(relative.spliterator(), false).map(Path::toString)
            .collect(Collectors.joining("/"));
        byte[] mac = hmac(path.getBytes(StandardCharsets.UTF_8));
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Arrays.copyOf(mac, REF_BYTES));
    }

    /** HMAC-SHA-256 of {@code message} under the key of this process. */
    byte[] hmac(byte[] message) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
        digest.update(inner);
        byte[] first = digest.digest(message);
        digest.update(outer);
        return digest.digest(first);
    }

    /** The listed file {@code ref} refers to; empty if none does (e.g. after a restart). */
    public Optional<NoteFile> resolve(String ref, Listing listing) {
        byte[] wanted = ref.getBytes(StandardCharsets.UTF_8);
        return listing.files().stream()
            .filter(file -> MessageDigest.isEqual(of(file.relative())
                .getBytes(StandardCharsets.UTF_8), wanted))
            .findFirst();
    }
}
