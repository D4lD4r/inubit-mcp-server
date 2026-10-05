package de.dadecker.inubit.mcp.domain.model;

import java.time.Duration;

/** Formats durations for messages: {@code 2 s}, {@code 2.9 s}, {@code 500 ms}. */
public final class Durations {

    private Durations() {
    }

    public static String human(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis % 1000 == 0) {
            return millis / 1000 + " s";
        }
        return java.math.BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()
            + " s";
    }
}
