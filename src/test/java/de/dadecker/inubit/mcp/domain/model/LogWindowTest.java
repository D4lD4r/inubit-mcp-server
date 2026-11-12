package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class LogWindowTest {

    @Test
    void startLineIsLinesBeforePlusOne() {
        LogWindow window = new LogWindow(new byte[] {'a', '\n'}, 412_876, false, 1_000_000);

        assertThat(window.startLine()).isEqualTo(412_877);
    }

    @Test
    void wholeFileStartsAtLineOne() {
        LogWindow window = new LogWindow(new byte[] {'a', '\n'}, 0, true, 2);

        assertThat(window.startLine()).isEqualTo(1);
    }

    @Test
    void acceptsExactlyFiftyMebibytes() {
        int max = (int) ServerLogBounds.WINDOW_BYTES;
        LogWindow window = new LogWindow(new byte[max], 3, false, max + 10L);

        assertThat(window.bytes()).hasSize(52_428_800);
    }

    @Test
    void rejectsMoreThanFiftyMebibytes() {
        int over = (int) ServerLogBounds.WINDOW_BYTES + 1;
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new LogWindow(new byte[over], 0, false, over + 10L));
    }

    @Test
    void rejectsNegativeLinesBefore() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new LogWindow(new byte[0], -1, false, 10));
    }

    @Test
    void rejectsAFileSizeBelowTheWindow() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new LogWindow(new byte[] {'a', '\n'}, 0, false, 1));
    }

    @Test
    void aWholeFileWindowStartsAtTheFileStartAndCoversTheFile() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new LogWindow(new byte[] {'a', '\n'}, 1, true, 2));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new LogWindow(new byte[] {'a', '\n'}, 0, true, 3));
    }

    @Test
    void rejectsNullBytes() {
        assertThatNullPointerException()
            .isThrownBy(() -> new LogWindow(null, 0, true, 0));
    }
}
