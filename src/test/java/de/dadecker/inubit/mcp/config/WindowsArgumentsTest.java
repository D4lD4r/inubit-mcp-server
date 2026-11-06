package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 013 (T006, FR-002, research R-2, SC-002): the characters the Windows command interpreter
 * interprets inside a quoted argument are refused with their class; the others pass. Pure, so it
 * runs on every platform.
 */
class WindowsArgumentsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
        "'\"'                  | double quote",
        "%                     | percent sign",
        "!                     | exclamation mark",
        "^                     | caret",
        "&                     | ampersand",
        "'|'                   | vertical bar",
        "<                     | less-than sign",
        ">                     | greater-than sign",
    })
    void refusesEveryInterpretedCharacterOfScTwoWithItsClass(String character, String expected) {
        assertThat(WindowsArguments.refusal(character)).contains(expected);
        assertThat(WindowsArguments.refusal("C:\\Program Files\\x" + character + "y"))
            .contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t", "\n", "\r", "\r\n", "\u0000", "\u001b", "\u007f"})
    void refusesControlCharactersIncludingTabAndLineBreaks(String character) {
        assertThat(WindowsArguments.refusal("user" + character + "name"))
            .contains("control character");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00fc", "\u00a0", "\u20ac", "\ud83d\ude00"})
    void refusesCharactersOutsideAscii(String character) {
        assertThat(WindowsArguments.refusal("C:\\inubit" + character))
            .contains("non-ASCII character");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "'", "(", ")", ";", ",", "=", "\\", "*", "?"})
    void passesTheCharactersOfScTwoThatReachStartCliUnchanged(String character) {
        assertThat(WindowsArguments.refusal("a" + character + "b")).isEmpty();
        assertThat(WindowsArguments.refusal(character)).isEmpty();
    }

    @Test
    void passesValuesEndingInABackslashAndOtherOrdinaryValues() {
        assertThat(WindowsArguments.refusal("C:\\dir\\")).isEmpty();
        assertThat(WindowsArguments.refusal("C:\\Program Files (x86)\\inubit 8.1")).isEmpty();
        assertThat(WindowsArguments.refusal("https://inubit.example:8443/ibis/servlet"))
            .isEmpty();
        assertThat(WindowsArguments.refusal("")).isEmpty();
        assertThat(WindowsArguments.refusal("''")).isEmpty();
    }

    @Test
    void namesTheClassOfTheFirstRefusedCharacter() {
        assertThat(WindowsArguments.refusal("a&b\"c\u00fc")).isEqualTo(Optional.of("ampersand"));
        assertThat(WindowsArguments.refusal("\u00fca&b")).isEqualTo(
            Optional.of("non-ASCII character"));
    }

    @Test
    void recognisesUncPaths() {
        assertThat(WindowsArguments.isUnc(Path.of("\\\\server\\share\\inubit"))).isTrue();
        assertThat(WindowsArguments.isUnc(Path.of("\\\\server\\share"))).isTrue();
    }

    @Test
    void localDriveAndPosixPathsAreNotUnc() {
        assertThat(WindowsArguments.isUnc(Path.of("C:\\inubit"))).isFalse();
        assertThat(WindowsArguments.isUnc(Path.of("C:/inubit"))).isFalse();
        assertThat(WindowsArguments.isUnc(Path.of("/opt/inubit"))).isFalse();
        assertThat(WindowsArguments.isUnc(Path.of("inubit"))).isFalse();
    }

    // --- T015: the length of the inner line, shared by the command line and the validator ----

    @Test
    void theLineLengthCountsTheOuterQuotesAndEveryQuotedPart() {
        String line = "\"\"C:\\c\\bin\\startcli.bat\" \"-u\" \"jdoe\"\"";

        assertThat(WindowsArguments.lineLength("C:\\c\\bin\\startcli.bat",
            List.of("-u", "jdoe"))).isEqualTo(line.length());
    }

    @Test
    void theLineLengthCountsDoubledTrailingBackslashesAndEmptyArguments() {
        assertThat(WindowsArguments.lineLength("s", List.of("C:\\dir\\", "")))
            .isEqualTo("\"\"s\" \"C:\\dir\\\\\" \"\"\"".length());
    }

    @Test
    void theLimitLeavesRoomForWhatStartCliAdds() {
        assertThat(WindowsArguments.MAX_LINE_LENGTH).isEqualTo(8191 - 2048);
    }

    @Test
    void environmentNamesMatchIgnoringCaseWithTheExactSpellingFirst() {
        assertThat(WindowsArguments.environmentValue(
            Map.of("JAVA_HOME", "", "Java_Home", "b"), "JAVA_HOME")).isEmpty();
        assertThat(WindowsArguments.environmentValue(
            Map.of("java_home", "a", "Java_Home", "b"), "JAVA_HOME")).isEqualTo("b");
        assertThat(WindowsArguments.environmentValue(Map.of("PATH", "p"), "JAVA_HOME")).isNull();
    }
}
