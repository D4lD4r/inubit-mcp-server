package de.dadecker.inubit.mcp.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StdoutGuardTest {

    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream fakeStdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream fakeStderr = new ByteArrayOutputStream();
    private PrintStream stdout;

    @BeforeEach
    void redirectProcessStreams() {
        originalOut = System.out;
        originalErr = System.err;
        stdout = new PrintStream(fakeStdout, true, StandardCharsets.UTF_8);
        System.setOut(stdout);
        System.setErr(new PrintStream(fakeStderr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreProcessStreams() {
        StdoutGuard.restore();
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String out() {
        return fakeStdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return fakeStderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    void strayStdoutWritesEndUpOnStderr() {
        StdoutGuard.install();

        System.out.println("stray library output");
        System.out.flush();

        assertThat(out()).isEmpty();
        assertThat(err()).contains("stray library output");
    }

    @Test
    void theCapturedOriginalStdoutIsReturnedForTheTransport() {
        PrintStream protocol = StdoutGuard.install();

        protocol.println("{\"jsonrpc\":\"2.0\"}");
        protocol.flush();

        assertThat(protocol).isSameAs(stdout);
        assertThat(out()).isEqualTo("{\"jsonrpc\":\"2.0\"}" + System.lineSeparator());
        assertThat(err()).isEmpty();
    }

    @Test
    void installIsIdempotent() {
        PrintStream first = StdoutGuard.install();
        PrintStream second = StdoutGuard.install();

        assertThat(second).isSameAs(first).isSameAs(stdout);
    }

    @Test
    void restorePutsTheOriginalStdoutBack() {
        StdoutGuard.install();

        StdoutGuard.restore();

        assertThat(System.out).isSameAs(stdout);
    }

    @Test
    void clockProviderReturnsAnInjectedFixedClock() {
        Instant instant = Instant.parse("2026-10-01T08:00:00Z");
        Clock fixed = Clock.fixed(instant, ZoneOffset.UTC);

        ClockProvider provider = new ClockProvider(fixed);

        assertThat(provider.clock()).isSameAs(fixed);
        assertThat(provider.now()).isEqualTo(instant);
        assertThat(ClockProvider.fixed(instant).now()).isEqualTo(instant);
    }

    @Test
    void systemClockProviderUsesUtc() {
        ClockProvider provider = ClockProvider.system();
        Instant before = Instant.now();

        Instant now = provider.now();

        assertThat(provider.clock().getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(now).isAfterOrEqualTo(before);
    }
}
