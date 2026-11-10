package de.dadecker.inubit.mcp.domain.model.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T005: a setting value is shown (cut only when rendered) or masked (maybe fingerprinted). */
class SettingValueTest {

    private static final SecretFingerprint FINGERPRINT = SecretFingerprint.of(new byte[] {1, 2, 3});

    @Test
    void aShortShownValueIsRenderedUnchanged() {
        String value = "x".repeat(ItemBounds.MAX_FIELD_CHARS);

        assertThat(new SettingValue.Shown(value).rendered()).isEqualTo(value);
        assertThat(new SettingValue.Shown("").rendered()).isEmpty();
    }

    @Test
    void aLongShownValueIsCutAt200CharactersWithTheMarkerWhenRendered() {
        String value = "a".repeat(150) + "b".repeat(100);

        String rendered = new SettingValue.Shown(value).rendered();

        assertThat(rendered).hasSize(ItemBounds.MAX_FIELD_CHARS)
            .endsWith(ItemBounds.TRUNCATION_MARKER)
            .startsWith("a".repeat(150) + "b".repeat(38));
    }

    @Test
    void renderingNeverSplitsASurrogatePair() {
        int keep = ItemBounds.MAX_FIELD_CHARS - ItemBounds.TRUNCATION_MARKER.length();
        String value = "a".repeat(keep - 1) + "😀" + "a".repeat(50);

        String rendered = new SettingValue.Shown(value).rendered();

        assertThat(rendered).isEqualTo("a".repeat(keep - 1) + ItemBounds.TRUNCATION_MARKER);
    }

    @Test
    void shownValuesAreComparedUncut() {
        String common = "c".repeat(300);
        SettingValue.Shown one = new SettingValue.Shown(common + "1");
        SettingValue.Shown two = new SettingValue.Shown(common + "2");

        assertThat(one.rendered()).isEqualTo(two.rendered());
        assertThat(one).isNotEqualTo(two);
        assertThat(one.value()).hasSize(301);
        assertThat(one).isEqualTo(new SettingValue.Shown(common + "1"))
            .hasSameHashCodeAs(new SettingValue.Shown(common + "1"));
    }

    @Test
    void aShownValueIsNeverNull() {
        assertThatNullPointerException().isThrownBy(() -> new SettingValue.Shown(null))
            .withMessage("value");
    }

    @Test
    void aSetMaskedValueMayCarryAFingerprint() {
        SettingValue.Masked masked = new SettingValue.Masked(true, Optional.of(FINGERPRINT));

        assertThat(masked.set()).isTrue();
        assertThat(masked.fingerprint()).contains(FINGERPRINT);
        assertThat(new SettingValue.Masked(true, Optional.empty()).fingerprint()).isEmpty();
    }

    @Test
    void anUnsetMaskedValueHasNoFingerprint() {
        assertThat(new SettingValue.Masked(false, Optional.empty()).fingerprint()).isEmpty();
        assertThat(new SettingValue.Masked(false, null).fingerprint()).isEmpty();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new SettingValue.Masked(false, Optional.of(FINGERPRINT)))
            .withMessageContaining("fingerprint");
    }

    @Test
    void maskedValuesAreEqualWhenSetAndFingerprintAreEqual() {
        SecretFingerprint same = SecretFingerprint.of(new byte[] {1, 2, 3});

        assertThat(new SettingValue.Masked(true, Optional.of(same)))
            .isEqualTo(new SettingValue.Masked(true, Optional.of(FINGERPRINT)));
        assertThat(new SettingValue.Masked(true, Optional.of(SecretFingerprint.of(new byte[] {9}))))
            .isNotEqualTo(new SettingValue.Masked(true, Optional.of(FINGERPRINT)));
        assertThat(new SettingValue.Masked(true, Optional.empty()))
            .isNotEqualTo(new SettingValue.Masked(false, Optional.empty()));
    }

    @Test
    void aMaskedValueNeverShowsItsFingerprintBytes() {
        assertThat(new SettingValue.Masked(true, Optional.of(FINGERPRINT)).toString())
            .contains("SecretFingerprint[redacted]")
            .doesNotContain("[1, 2, 3]");
    }
}
