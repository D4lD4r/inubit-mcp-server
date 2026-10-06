package de.dadecker.inubit.mcp.adapter.xslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Stage-3 minor 1: an abandoned or oversized run cannot keep writing. */
class GuardedOutputTest {

    @Test
    void anAbandonedRunCannotWriteAnyMore() throws IOException {
        AtomicBoolean abandoned = new AtomicBoolean();
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        GuardedOutput out = new GuardedOutput(target, abandoned, 100);

        out.write(new byte[] {1, 2, 3});
        abandoned.set(true);

        assertThatIOException().isThrownBy(() -> out.write(4)).withMessageContaining("abandoned");
        assertThatIOException().isThrownBy(() -> out.write(new byte[] {5}, 0, 1));
        assertThat(target.toByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    void theCapIsEnforcedAndReported() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        GuardedOutput out = new GuardedOutput(target, new AtomicBoolean(), 4);

        out.write(new byte[] {1, 2, 3, 4});

        assertThatIOException().isThrownBy(() -> out.write(5))
            .withMessageContaining("the output exceeds 4 bytes");
        assertThat(out.exceeded()).isTrue();
        assertThat(target.size()).isEqualTo(4);
    }
}
