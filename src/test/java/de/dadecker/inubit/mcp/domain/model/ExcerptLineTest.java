package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ExcerptLineTest {

    @Test
    void holdsAnAbsoluteLineNumber() {
        ExcerptLine line = new ExcerptLine(412_877, "java.lang.OutOfMemoryError", true);

        assertThat(line.number()).isEqualTo(412_877);
        assertThat(line.text()).isEqualTo("java.lang.OutOfMemoryError");
        assertThat(line.cut()).isTrue();
    }

    @Test
    void numberStartsAtOne() {
        assertThat(new ExcerptLine(1, "", false).number()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> new ExcerptLine(0, "x", false));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExcerptLine(-5, "x", false));
    }

    @Test
    void rejectsNullText() {
        assertThatNullPointerException().isThrownBy(() -> new ExcerptLine(1, null, false));
    }
}
