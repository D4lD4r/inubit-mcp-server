package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Listing;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.NoteFile;
import de.dadecker.inubit.mcp.domain.model.NoteName;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Feature 019 T019: references to listed notes (research D-9). */
class NoteRefsTest {

    private static final FileTime TIME = FileTime.from(Instant.parse("2026-03-01T10:00:00Z"));

    private static NoteFile file(String... segments) {
        Path relative = Path.of(segments[0], Arrays.copyOfRange(segments, 1,
            segments.length));
        return new NoteFile(NoteName.ofRelativePath(relative), relative,
            Path.of("/knowledge").resolve(relative), 1, TIME);
    }

    private static Listing listing(NoteFile... files) {
        return new Listing(true, List.of(files), List.of(), List.of());
    }

    @Test
    void isR1AndTwentyBase64UrlCharacters() {
        NoteRefs refs = new NoteRefs(new SecureRandom());

        assertThat(refs.of(Path.of("systems", "sap.md"))).matches("r1\\.[A-Za-z0-9_-]{20}");
    }

    @Test
    void isStableWithinOneKeyAndDiffersAcrossKeys() {
        NoteRefs refs = new NoteRefs(new SecureRandom());
        Path path = Path.of("systems", "sap.md");

        assertThat(refs.of(path)).isEqualTo(refs.of(Path.of("systems", "sap.md")));
        assertThat(new NoteRefs(new SecureRandom()).of(path)).isNotEqualTo(refs.of(path));
        assertThat(refs.of(Path.of("systems", "sap2.md"))).isNotEqualTo(refs.of(path));
    }

    @Test
    void isHmacSha256AsTheJdkComputesIt() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        NoteRefs refs = new NoteRefs(new FixedRandom(key));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] message = "systems/sap.md".getBytes(StandardCharsets.UTF_8);
        byte[] expected = mac.doFinal(message);

        assertThat(refs.hmac(message)).isEqualTo(expected);
        assertThat(refs.of(Path.of("systems", "sap.md"))).isEqualTo("r1." + Base64.getUrlEncoder()
            .withoutPadding().encodeToString(Arrays.copyOf(expected, 15)));
    }

    /** A random source that yields fixed bytes. */
    private static final class FixedRandom extends SecureRandom {

        private final byte[] bytes;

        FixedRandom(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public void nextBytes(byte[] out) {
            System.arraycopy(bytes, 0, out, 0, out.length);
        }
    }

    @Test
    void containsNoPartOfTheName() {
        NoteRefs refs = new NoteRefs(new SecureRandom());

        assertThat(refs.of(Path.of("password.md"))).doesNotContainIgnoringCase("password");
    }

    @Test
    void tellsFilesWithNamesEqualAfterNfcApart() {
        NoteRefs refs = new NoteRefs(new SecureRandom());

        assertThat(refs.of(Path.of("Sto\u0308rung.md")))
            .isNotEqualTo(refs.of(Path.of("St\u00f6rung.md")));
    }

    @Test
    void resolvesAgainstTheCurrentListing() {
        NoteRefs refs = new NoteRefs(new SecureRandom());
        NoteFile a = file("a.md");
        NoteFile sap = file("systems", "sap.md");

        assertThat(refs.resolve(refs.of(sap.relative()), listing(a, sap))).contains(sap);
        assertThat(refs.resolve(refs.of(sap.relative()), listing(a))).isEmpty();
        assertThat(refs.resolve("r1.AAAAAAAAAAAAAAAAAAAA", listing(a, sap))).isEmpty();
        assertThat(refs.resolve("not a ref", listing(a, sap))).isEmpty();
        assertThat(new NoteRefs(new SecureRandom()).resolve(refs.of(sap.relative()),
            listing(a, sap))).isEmpty();
    }
}
