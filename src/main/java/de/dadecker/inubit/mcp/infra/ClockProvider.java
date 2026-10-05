package de.dadecker.inubit.mcp.infra;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/** The time source of the server; tests inject a fixed {@link Clock}. */
public record ClockProvider(Clock clock) {

    public ClockProvider {
        Objects.requireNonNull(clock, "clock");
    }

    /** The system clock in UTC. */
    public static ClockProvider system() {
        return new ClockProvider(Clock.systemUTC());
    }

    /** A clock that always returns {@code instant}. */
    public static ClockProvider fixed(Instant instant) {
        return new ClockProvider(Clock.fixed(instant, ZoneOffset.UTC));
    }

    public Instant now() {
        return clock.instant();
    }
}
