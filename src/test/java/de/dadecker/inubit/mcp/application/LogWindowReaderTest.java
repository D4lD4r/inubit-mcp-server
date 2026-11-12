package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.LogWindow;
import de.dadecker.inubit.mcp.domain.model.ServerLogBounds;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 021 T016 (FR-009 step 1, D-9, US1-7): the scanned window is the whole file up to
 * 50 MiB, otherwise the part from the first line that begins within the last 50 MiB, with the
 * lines before it counted.
 */
class LogWindowReaderTest {

    private static final long WINDOW = ServerLogBounds.WINDOW_BYTES;
    private static final long SIXTY_MIB = 60L * 1024 * 1024;

    @TempDir
    Path dir;

    @Test
    void aSixtyMebibyteLogStartsAtTheFirstWholeLineInTheLastFiftyMebibytes() throws IOException {
        Path file = dir.resolve("server.log");
        long[] lineStarts = new long[1 << 20];
        int lineCount = 0;
        long size = 0;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            while (size < SIXTY_MIB) {
                byte[] line = ("2026-11-11 12:00:00,000 INFO [worker] line " + lineCount + " "
                    + "x".repeat(lineCount % 97) + "\n").getBytes(StandardCharsets.US_ASCII);
                lineStarts[lineCount++] = size;
                out.write(line);
                size += line.length;
            }
        }
        long start = size - WINDOW;
        int firstInWindow = 0;
        while (lineStarts[firstInWindow] < start) {
            firstInWindow++;
        }
        long expectedStart = lineStarts[firstInWindow];
        assertThat(expectedStart).as("a line straddles the window start").isGreaterThan(start);

        LogWindow window = LogWindowReader.read(file, size);

        assertThat(window.wholeFile()).isFalse();
        assertThat(window.fileSize()).isEqualTo(size);
        assertThat(window.linesBefore()).isEqualTo(firstInWindow);
        assertThat(window.startLine()).isEqualTo(firstInWindow + 1L);
        assertThat(window.bytes()).hasSize((int) (size - expectedStart));
        byte[] all = Files.readAllBytes(file);
        assertThat(Arrays.equals(all, (int) expectedStart, all.length,
            window.bytes(), 0, window.bytes().length)).isTrue();
    }

    @Test
    void exactlyFiftyMebibytesIsTheWholeFile() throws IOException {
        Path file = write("exact.log", new byte[] {'a', 'b'}, WINDOW - 3, "\n");

        LogWindow window = LogWindowReader.read(file, WINDOW);

        assertThat(window.wholeFile()).isTrue();
        assertThat(window.linesBefore()).isZero();
        assertThat(window.fileSize()).isEqualTo(WINDOW);
        assertThat(window.bytes()).hasSize((int) WINDOW);
        assertThat(window.bytes()[0]).isEqualTo((byte) 'a');
        assertThat(window.bytes()[(int) WINDOW - 1]).isEqualTo((byte) '\n');
    }

    @Test
    void oneByteMoreDropsThePartialFirstLine() throws IOException {
        // start = 1: byte[0] = 'x' is no line break, byte[1] = '\n' ends the partial line.
        Path file = write("over.log", "x\nyz".getBytes(StandardCharsets.US_ASCII),
            WINDOW + 1 - 5, "\n");

        LogWindow window = LogWindowReader.read(file, WINDOW + 1);

        assertThat(window.wholeFile()).isFalse();
        assertThat(window.fileSize()).isEqualTo(WINDOW + 1);
        assertThat(window.linesBefore()).isEqualTo(1);
        assertThat(window.bytes()).hasSize((int) (WINDOW + 1 - 2));
        assertThat(window.bytes()[0]).isEqualTo((byte) 'y');
    }

    @Test
    void aLineBreakDirectlyBeforeTheStartBeginsTheWindowAtTheStart() throws IOException {
        long size = WINDOW + 4;
        // start = 4: byte[3] = '\n', so the window begins at position 4.
        Path file = write("edge.log", "a\nb\nc".getBytes(StandardCharsets.US_ASCII),
            size - 6, "\n");

        LogWindow window = LogWindowReader.read(file, size);

        assertThat(window.wholeFile()).isFalse();
        assertThat(window.linesBefore()).isEqualTo(2);
        assertThat(window.startLine()).isEqualTo(3);
        assertThat(window.bytes()).hasSize((int) WINDOW);
        assertThat(window.bytes()[0]).isEqualTo((byte) 'c');
    }

    @Test
    void noLineBreakInTheLastFiftyMebibytesGivesAnEmptyWindow() throws IOException {
        long size = WINDOW + 10;
        // start = 10: the line breaks at 1 and 3 lie before start - 1 = 9.
        Path file = write("noline.log", "a\nb\nc".getBytes(StandardCharsets.US_ASCII),
            size - 5, "");

        LogWindow window = LogWindowReader.read(file, size);

        assertThat(window.bytes()).isEmpty();
        assertThat(window.linesBefore()).isZero();
        assertThat(window.wholeFile()).isFalse();
        assertThat(window.fileSize()).isEqualTo(size);
    }

    @Test
    void aFinalLineBreakAsTheOnlyOneInTheLastFiftyMebibytesGivesAnEmptyWindow()
        throws IOException {
        long size = WINDOW + 10;
        // start = 10: the only line break at or after start - 1 = 9 is the final byte, and the
        // empty segment after it is no line.
        Path file = write("finalbreak.log", "a\nb\nc".getBytes(StandardCharsets.US_ASCII),
            size - 6, "\n");

        LogWindow window = LogWindowReader.read(file, size);

        assertThat(window.bytes()).isEmpty();
        assertThat(window.linesBefore()).isZero();
        assertThat(window.wholeFile()).isFalse();
        assertThat(window.fileSize()).isEqualTo(size);
    }

    @Test
    void anEmptyFileIsAnEmptyWholeWindow() throws IOException {
        Path file = Files.createFile(dir.resolve("empty.log"));

        LogWindow window = LogWindowReader.read(file, 0);

        assertThat(window.bytes()).isEmpty();
        assertThat(window.wholeFile()).isTrue();
        assertThat(window.linesBefore()).isZero();
        assertThat(window.fileSize()).isZero();
    }

    /** Writes {@code head}, then {@code fill} bytes of {@code 'f'} (no line break), then {@code tail}. */
    private Path write(String name, byte[] head, long fill, String tail) throws IOException {
        Path file = dir.resolve(name);
        byte[] block = new byte[1 << 16];
        Arrays.fill(block, (byte) 'f');
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(head);
            for (long left = fill; left > 0; left -= block.length) {
                out.write(block, 0, (int) Math.min(block.length, left));
            }
            out.write(tail.getBytes(StandardCharsets.US_ASCII));
        }
        return file;
    }
}
