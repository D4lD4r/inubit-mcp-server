package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 009 (T005, research D-2, data-model.md → VersionSetting); feature 011 (T003, FR-002,
 * research D-1): {@code V6_1} and concrete 6.1 pins.
 */
class VersionSettingTest {

    @Test
    void parsesTheKeywords() {
        assertThat(VersionSetting.parse("AUTO")).contains(VersionSetting.AUTO);
        assertThat(VersionSetting.parse("V8_1"))
            .contains(new VersionSetting.Line(8, OptionalInt.of(1)));
        assertThat(VersionSetting.parse("V9_X"))
            .contains(new VersionSetting.Line(9, OptionalInt.empty()));
        assertThat(VersionSetting.parse("V6_1"))
            .contains(new VersionSetting.Line(6, OptionalInt.of(1)));
    }

    @Test
    void parsesAFullVersionAsPin() {
        assertThat(VersionSetting.parse("9.0.7-HF1")).contains(new VersionSetting.Pinned(
            new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7-HF1")));
    }

    @Test
    void aFourPartSixOnePinIsSixOneZeroWithTheTextKept() {
        assertThat(VersionSetting.parse("6.1.0.89")).contains(new VersionSetting.Pinned(
            new InubitVersion(6, 1, OptionalInt.of(0), "6.1.0.89")));
        assertThat(VersionSetting.parse("6.1.0.89-HF1")).contains(new VersionSetting.Pinned(
            new InubitVersion(6, 1, OptionalInt.of(0), "6.1.0.89-HF1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.1", "6.1", "V6_X", "V10", "auto", "", " AUTO", "V8_2", "x8.1.17"})
    void refusesEverythingElse(String text) {
        assertThat(VersionSetting.parse(text)).isEmpty();
    }

    @Test
    void textIsTheConfiguredSpelling() {
        assertThat(VersionSetting.AUTO.text()).isEqualTo("AUTO");
        assertThat(VersionSetting.V8_1.text()).isEqualTo("V8_1");
        assertThat(VersionSetting.V9_X.text()).isEqualTo("V9_X");
        assertThat(VersionSetting.V6_1.text()).isEqualTo("V6_1");
        assertThat(VersionSetting.parse("8.1.17-HF3").orElseThrow().text())
            .isEqualTo("8.1.17-HF3");
    }

    @Test
    void aPinNeedsAPatch() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionSetting.Pinned(
            new InubitVersion(8, 1, OptionalInt.empty(), "8.1")));
    }
}
