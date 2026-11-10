package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Masking of secret spans (feature 019, research D-6). */
class MaskedTest {

    @Test
    void spansAreValidRanges() {
        assertThat(new SecretSpan(0, 1).length()).isEqualTo(1);
        assertThatThrownBy(() -> new SecretSpan(-1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretSpan(2, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretSpan(3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Masked.of("abc", List.of(new SecretSpan(1, 4))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutSpansTheTextIsUnchanged() {
        assertThat(Masked.of("plain", List.of())).isEqualTo(new Masked("plain", 0));
        assertThat(Masked.MASK_TOKEN).isEqualTo("***");
    }

    @Test
    void eachSpanBecomesOneMask() {
        assertThat(Masked.of("a=secret; b=other!", List.of(new SecretSpan(2, 8), new SecretSpan(12, 17))))
            .isEqualTo(new Masked("a=***; b=***!", 2));
    }

    @Test
    void overlappingAndAdjacentSpansAreMergedInAnyOrder() {
        Masked masked = Masked.of("0123456789", List.of(
            new SecretSpan(6, 8), new SecretSpan(2, 5), new SecretSpan(4, 6), new SecretSpan(3, 4)));
        assertThat(masked).isEqualTo(new Masked("01***89", 1));
        assertThat(Masked.of("0123456789", List.of(new SecretSpan(1, 3), new SecretSpan(1, 3))))
            .isEqualTo(new Masked("0***3456789", 1));
    }

    @Test
    void aSurrogatePairIsNeverSplit() {
        String text = "a😀b😀c";
        assertThat(Masked.of(text, List.of(new SecretSpan(2, 3))).text()).isEqualTo("a***b😀c");
        assertThat(Masked.of(text, List.of(new SecretSpan(3, 5))).text()).isEqualTo("a😀***c");
        assertThat(Masked.of(text, List.of(new SecretSpan(4, 5))).text()).isEqualTo("a😀b***c");
    }
}
