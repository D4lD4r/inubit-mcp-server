package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ExcerptLine;
import de.dadecker.inubit.mcp.domain.model.LogExcerpt;
import de.dadecker.inubit.mcp.domain.model.LogWindow;
import de.dadecker.inubit.mcp.domain.model.ServerLogBounds;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 021 T018 (D-11, D-13): the excerpt of a 50 MiB log, read by {@link LogWindowReader}
 * and masked with the real known-credential spans of a {@link SecretScrubber} (as
 * {@code adapter/ProfileSecrets} delegates to it), is built within 20 s, and neither configured
 * password is in it.
 */
class LogExcerptPerformanceTest {

    private static final String FIRST = TestSecrets.CONFIGURED_PASSWORD;
    private static final String SECOND = "second-node-password-0002";
    private static final Duration BOUND = Duration.ofSeconds(20);
    /** Every line of this kind carries one of the passwords as plain text. */
    private static final String SESSION = "opening session";

    @TempDir
    Path dir;

    @Test
    void aFiftyMebibyteWindowIsReadMaskedAndSelectedWithinTwentySeconds() throws IOException {
        Path file = dir.resolve("server.log");
        long size = writeLog(file);
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(FIRST);
        scrubber.register(SECOND);

        long started = System.nanoTime();
        LogWindow window = LogWindowReader.read(file, size);
        LogExcerpt excerpt = LogExcerpt.build(window, ServerLogBounds.MAX_TAIL_LINES,
            Optional.of(SESSION), ServerLogBounds.MAX_MAX_CHARS, scrubber::occurrences);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).as("window read, scrubber scan, masking and selection")
            .isLessThan(BOUND);
        assertThat(excerpt.window().bytes()).isGreaterThan(ServerLogBounds.WINDOW_BYTES - 200);
        assertThat(excerpt.lines()).as("lines that carried a password are selected").isNotEmpty();
        for (ExcerptLine line : excerpt.lines()) {
            assertThat(line.text()).contains(SESSION + " with ***")
                .doesNotContain(FIRST, SECOND);
        }
    }

    /**
     * Writes a log just over 50 MiB: plain lines, every 1,000th a secret-looking line (rotating
     * through XML element, JSON key, header, {@code name=value} and Basic credential), and every
     * 997th a line carrying one of the two passwords as plain text, which only the known spans
     * mask. Returns the file size.
     */
    private static long writeLog(Path file) throws IOException {
        long size = 0;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            for (int i = 0; size <= ServerLogBounds.WINDOW_BYTES + 1_000_000; i++) {
                String prefix = "2026-11-12 10:00:00," + (i % 1000) + " INFO [worker-" + (i % 16)
                    + "] de.example.Flow - ";
                String text;
                if (i % 1000 == 999) {
                    text = secretLooking(i / 1000);
                } else if (i % 997 == 996) {
                    text = SESSION + " with " + (i % 2 == 0 ? FIRST : SECOND) + " for svc-" + i;
                } else {
                    text = "processed message " + i + " in " + (i % 250) + " ms";
                }
                byte[] line = (prefix + text + "\n").getBytes(StandardCharsets.UTF_8);
                out.write(line);
                size += line.length;
            }
        }
        return size;
    }

    private static String secretLooking(int n) {
        return switch (n % 5) {
            case 0 -> "request <password>xml-secret-" + n + "</password> sent";
            case 1 -> "body {\"apiKey\": \"json-secret-" + n + "\"}";
            case 2 -> "Authorization: Bearer header-secret-" + n;
            case 3 -> "connect user=svc password=pair-secret-" + n + " done";
            default -> "header Basic c3ZjOmJhc2ljLXNlY3JldA== used";
        };
    }
}
