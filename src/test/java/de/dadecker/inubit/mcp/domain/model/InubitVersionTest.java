package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class InubitVersionTest {

    @Test
    void parsesMajorMinorPatch() {
        assertThat(InubitVersion.parse("8.1.17"))
            .contains(new InubitVersion(8, 1, OptionalInt.of(17), "8.1.17"));
    }

    @Test
    void parsesMajorMinorWithoutPatch() {
        assertThat(InubitVersion.parse("8.1"))
            .contains(new InubitVersion(8, 1, OptionalInt.empty(), "8.1"));
    }

    @Test
    void keepsAHyphenSuffixInRaw() {
        assertThat(InubitVersion.parse("8.1.17-HF3"))
            .contains(new InubitVersion(8, 1, OptionalInt.of(17), "8.1.17-HF3"));
    }

    @Test
    void keepsABuildSuffixInRaw() {
        assertThat(InubitVersion.parse("9.0.7 build 42"))
            .contains(new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7 build 42"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"x", "", "   ", "8", "8.", ".1", "v8.1.17", "8.x.1"})
    void isEmptyWithoutAParsableMajorMinor(String value) {
        assertThat(InubitVersion.parse(value)).isEmpty();
    }

    @Test
    void ordersByPatchNumericallyNotTextually() {
        InubitVersion older = InubitVersion.parse("8.1.9").orElseThrow();
        InubitVersion newer = InubitVersion.parse("8.1.17").orElseThrow();

        assertThat(older).isLessThan(newer);
        assertThat(newer).isGreaterThan(older);
    }

    @Test
    void ordersByMajorThenMinorThenPatch() {
        assertThat(version("8.2.0")).isGreaterThan(version("8.1.99"));
        assertThat(version("9.0.0")).isGreaterThan(version("8.9.9"));
        assertThat(version("8.10")).isGreaterThan(version("8.9"));
    }

    @Test
    void ordersAnAbsentPatchAsZero() {
        assertThat(version("8.1")).isEqualByComparingTo(version("8.1.0"));
        assertThat(version("8.1")).isLessThan(version("8.1.1"));
    }

    @Test
    void ignoresTheSuffixForOrdering() {
        assertThat(version("8.1.17-HF3")).isEqualByComparingTo(version("8.1.17"));
    }

    @Test
    void sameMinorComparesMajorAndMinorOnly() {
        assertThat(version("8.1.17").sameMinor(version("8.1"))).isTrue();
        assertThat(version("8.1.17-HF3").sameMinor(version("8.1.2"))).isTrue();
        assertThat(version("8.1.17").sameMinor(version("8.2.17"))).isFalse();
        assertThat(version("8.1.17").sameMinor(version("9.1.17"))).isFalse();
    }

    @Test
    void rejectsNegativeComponentsAndMissingRaw() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new InubitVersion(-1, 1, OptionalInt.empty(), "x"));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new InubitVersion(8, -1, OptionalInt.empty(), "x"));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new InubitVersion(8, 1, OptionalInt.of(-1), "x"));
        assertThatNullPointerException()
            .isThrownBy(() -> new InubitVersion(8, 1, OptionalInt.empty(), null));
    }

    @Test
    void treatsANullPatchAsAbsent() {
        assertThat(new InubitVersion(8, 1, null, "8.1").patch()).isEmpty();
    }

    private static InubitVersion version(String value) {
        return InubitVersion.parse(value).orElseThrow();
    }
}
