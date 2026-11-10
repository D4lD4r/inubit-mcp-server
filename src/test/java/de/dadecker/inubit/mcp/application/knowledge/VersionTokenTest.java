package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Feature 019 T019: the version token of a note (research D-9, FR-011). */
class VersionTokenTest {

    private static final FileTime TIME = FileTime.from(Instant.parse("2026-03-01T10:00:00.5Z"));

    @Test
    void isV1AndTwentyFourBase64UrlCharacters() {
        assertThat(VersionToken.of(TIME, "text")).matches("v1\\.[A-Za-z0-9_-]{24}");
        assertThat(VersionToken.of(TIME, "")).matches("v1\\.[A-Za-z0-9_-]{24}");
    }

    @Test
    void isTheFirst18BytesOfTheHashOverTimeAndMaskedText() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest("1772359200.500000000\nBody ***\n".getBytes(StandardCharsets.UTF_8));
        String expected = "v1." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Arrays.copyOf(hash, 18));

        assertThat(VersionToken.of(TIME, "Body ***\n")).isEqualTo(expected);
    }

    @Test
    void isStableAndChangesWithTheTimeOrTheText() {
        String token = VersionToken.of(TIME, "text");

        assertThat(VersionToken.of(TIME, "text")).isEqualTo(token);
        assertThat(VersionToken.of(FileTime.from(Instant.parse("2026-03-01T10:00:00.500000001Z")),
            "text")).isNotEqualTo(token);
        assertThat(VersionToken.of(TIME, "text ")).isNotEqualTo(token);
    }

    @Test
    void revealsNothingOfTheText() {
        assertThat(VersionToken.of(TIME, "secret-value")).doesNotContain("secret");
    }
}
