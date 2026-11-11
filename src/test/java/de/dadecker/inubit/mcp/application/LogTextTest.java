package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link LogText}: the log form of a request value (feature 018 phase 3 review). */
class LogTextTest {

    @Test
    void aShortValueWithoutLineBreakingCharactersIsKept() {
        assertThat(LogText.loggable("dev/node1 Ä € 😀"))
            .isEqualTo("dev/node1 Ä € 😀");
    }

    @Test
    void controlCharactersAndLineSeparatorsBecomeQuestionMarks() {
        assertThat(LogText.loggable("a\u0000b\nc\rd\u001Fe\u007Ff\u0080g\u0085h\u009Fi"))
            .isEqualTo("a?b?c?d?e?f?g?h?i");
        assertThat(LogText.loggable("line\u2028separator\u2029paragraph"))
            .isEqualTo("line?separator?paragraph");
    }

    @Test
    void aLongValueIsCutTo500Chars() {
        assertThat(LogText.loggable("x".repeat(600))).isEqualTo("x".repeat(500));
        assertThat(LogText.loggable("x".repeat(500))).isEqualTo("x".repeat(500));
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        String emoji = "😀";

        assertThat(LogText.loggable("x".repeat(499) + emoji + "y")).isEqualTo("x".repeat(499));
        assertThat(LogText.loggable("x".repeat(498) + emoji + "y"))
            .isEqualTo("x".repeat(498) + emoji);
    }
}
