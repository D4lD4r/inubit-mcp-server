package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 017 (T002, data-model.md → MajorMinor): the key of a {@code cliClients} entry. */
class MajorMinorTest {

    @Test
    void parsesMajorDotMinor() {
        assertThat(MajorMinor.parse("9.0")).contains(new MajorMinor(9, 0));
        assertThat(MajorMinor.parse("8.1")).contains(new MajorMinor(8, 1));
        assertThat(MajorMinor.parse("10.12")).contains(new MajorMinor(10, 12));
        assertThat(MajorMinor.parse("0.0")).contains(new MajorMinor(0, 0));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "9", "9.", ".0", "9.00", "09.0", "9.x", "9.X", "V9_0", "9.0.7",
        " 9.0", "9.0 ", "9,0", "-1.0", "9.-1", "+9.0", "9.0-HF1", "9.0\n", "٩.٠", "99999999999.0"})
    void refusesEverythingElse(String text) {
        assertThat(MajorMinor.parse(text)).isEmpty();
    }

    @Test
    void ofAVersionDropsThePatchAndTheSuffix() {
        assertThat(MajorMinor.of(new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7-HF1")))
            .isEqualTo(new MajorMinor(9, 0));
        assertThat(MajorMinor.of(new InubitVersion(8, 1, OptionalInt.empty(), "8.1")))
            .isEqualTo(new MajorMinor(8, 1));
    }

    @Test
    void textIsMajorDotMinor() {
        assertThat(new MajorMinor(9, 0).text()).isEqualTo("9.0");
        assertThat(new MajorMinor(10, 12).text()).isEqualTo("10.12");
        assertThat(MajorMinor.parse("8.1").orElseThrow().text()).isEqualTo("8.1");
    }

    @Test
    void rejectsNegativeComponents() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MajorMinor(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new MajorMinor(9, -1));
    }
}
