package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class TruncationTest {

    @Test
    void holdsDroppedAndCutLines() {
        Truncation truncation = new Truncation(140, 1);

        assertThat(truncation.droppedLines()).isEqualTo(140);
        assertThat(truncation.cutLines()).isEqualTo(1);
    }

    @Test
    void cutLinesIsZeroOrOne() {
        assertThat(new Truncation(5, 0).cutLines()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> new Truncation(0, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new Truncation(0, -1));
    }

    @Test
    void rejectsNegativeDroppedLines() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Truncation(-1, 0));
    }
}
