package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T007 (research D-3, FR-016): INUBIT names to path segments and back. */
class NameCodecTest {

    @ParameterizedTest
    @ValueSource(strings = {"Workflow-0001", "XSLT Converter", "Module_01.v2", "a(b)+c,d=e@f",
        "GRP-01", "x.y", "OWNERS"})
    void namesOfTheSafeSetStayReadable(String name) {
        assertThat(NameCodec.encode(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "a/b|a%2Fb",
        "a\\b|a%5Cb",
        "100%|100%25",
        "a:b|a%3Ab",
        "Ä|%C3%84",
        "tab\there|tab%09here",
        ".hidden|%2Ehidden",
        ".|%2E",
        "..|%2E%2E",
        "name.|name%2E",
        "name. .|name%2E%20%2E",
        "'trailing  '|trailing%20%20",
        "'  lead'|'  lead'",
        "a*b?c|a%2Ab%3Fc",
        "😀|%F0%9F%98%80"})
    void everythingElseIsPercentEncoded(String name, String encoded) {
        assertThat(NameCodec.encode(name)).isEqualTo(encoded);
        assertThat(NameCodec.decode(encoded)).isEqualTo(name);
    }

    @Test
    void decodingInvertsEncodingForGeneratedNames() {
        Random random = new Random(20261006L);
        int[] interesting = {' ', '.', '/', '\\', '%', ':', '*', '?', '"', '<', '>', '|', '\t',
            '#', '~', 'Ä', 'ß', 'é', '€', 0x1F600, '(', ')', '+', ',', '=', '@', '-', '_', 'a',
            'Z', '0'};
        for (int run = 0; run < 5000; run++) {
            StringBuilder name = new StringBuilder();
            int length = 1 + random.nextInt(12);
            for (int i = 0; i < length; i++) {
                if (random.nextBoolean()) {
                    name.appendCodePoint(interesting[random.nextInt(interesting.length)]);
                } else {
                    int codePoint;
                    do {
                        codePoint = 0x20 + random.nextInt(0x2FFFF);
                    } while (Character.isSurrogate((char) codePoint) && codePoint <= 0xFFFF
                        || !Character.isValidCodePoint(codePoint));
                    name.appendCodePoint(codePoint);
                }
            }
            String original = name.toString();
            String segment = NameCodec.encode(original);

            assertThat(NameCodec.decode(segment)).as("run %d", run).isEqualTo(original);
            assertThat(segment).as("run %d", run).isNotEmpty().isNotEqualTo(".")
                .isNotEqualTo("..").doesNotContain("/").doesNotContain("\\")
                .matches("[A-Za-z0-9 ._()+,=@%-]+").doesNotStartWith(".")
                .doesNotEndWith(".").doesNotEndWith(" ");
        }
    }

    @Test
    void anEmptyOrMissingNameHasNoSegment() {
        assertThatIllegalArgumentException().isThrownBy(() -> NameCodec.encode(""));
        assertThatIllegalArgumentException().isThrownBy(() -> NameCodec.encode(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%", "%2", "%G1", "a/b", "%C3", "%FF", ".x", "x.", "x ",
        "a%41", "a%2f", "a*b", ".", ".."})
    void decodingAcceptsOnlyCanonicalSegments(String segment) {
        // one name, one segment: no second spelling (e.g. %41 for A) maps to the same name
        assertThatIllegalArgumentException().isThrownBy(() -> NameCodec.decode(segment));
    }
}
