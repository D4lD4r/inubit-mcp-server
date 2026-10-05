package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** One human-readable duration format for every message (Phase 3 review NIT). */
class DurationsTest {

    @Test
    void wholeSecondsFractionsAndMilliseconds() {
        assertThat(Durations.human(Duration.ofSeconds(2))).isEqualTo("2 s");
        assertThat(Durations.human(Duration.ofSeconds(30))).isEqualTo("30 s");
        assertThat(Durations.human(Duration.ofMillis(2900))).isEqualTo("2.9 s");
        assertThat(Durations.human(Duration.ofMillis(2250))).isEqualTo("2.25 s");
        assertThat(Durations.human(Duration.ofMillis(500))).isEqualTo("500 ms");
        assertThat(Durations.human(Duration.ZERO)).isEqualTo("0 ms");
        assertThat(Durations.human(Duration.ofMinutes(2))).isEqualTo("120 s");
    }
}
