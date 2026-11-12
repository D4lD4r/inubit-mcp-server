package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.LogWindow;
import de.dadecker.inubit.mcp.domain.model.ServerLogBounds;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads the scanned window of a downloaded server log (feature 021, FR-009 step 1, D-9): a file
 * of at most {@link ServerLogBounds#WINDOW_BYTES} bytes is read whole; of a larger one only the
 * part from the first line that begins within its last {@link ServerLogBounds#WINDOW_BYTES} bytes
 * (a partial first line is not part of it), with the line breaks before it counted by streaming
 * the prefix in 64 KiB blocks. If no line begins in that part (the empty segment after a final
 * {@code '\n'} is no line), the window is empty ({@code bytes.length == 0} with {@code fileSize > 0}: no whole line) and
 * {@code linesBefore} is 0.
 */
final class LogWindowReader {

    private static final int BLOCK = 64 * 1024;

    private LogWindowReader() {
    }

    static LogWindow read(Path file, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (size <= ServerLogBounds.WINDOW_BYTES) {
                return new LogWindow(readFully(channel, 0, (int) size), 0, true, size);
            }
            long start = size - ServerLogBounds.WINDOW_BYTES;
            long windowStart = firstLineStart(channel, start, size);
            if (windowStart < 0) {
                return new LogWindow(new byte[0], 0, false, size);
            }
            return new LogWindow(readFully(channel, windowStart, (int) (size - windowStart)),
                countLineBreaks(channel, windowStart), false, size);
        }
    }

    /** The first {@code p} in {@code [start, size)} with {@code byte[p - 1] == '\n'}, or -1. */
    private static long firstLineStart(FileChannel channel, long start, long size)
        throws IOException {
        ByteBuffer block = ByteBuffer.allocate(BLOCK);
        long position = start - 1;
        while (position < size - 1) {
            block.clear().limit((int) Math.min(BLOCK, size - 1 - position));
            fill(channel, block, position);
            for (int i = 0; i < block.limit(); i++) {
                if (block.get(i) == '\n') {
                    return position + i + 1;
                }
            }
            position += block.limit();
        }
        return -1;
    }

    /** The number of {@code '\n'} bytes in {@code [0, end)}. */
    private static long countLineBreaks(FileChannel channel, long end) throws IOException {
        ByteBuffer block = ByteBuffer.allocate(BLOCK);
        long count = 0;
        for (long position = 0; position < end; position += block.limit()) {
            block.clear().limit((int) Math.min(BLOCK, end - position));
            fill(channel, block, position);
            for (int i = 0; i < block.limit(); i++) {
                if (block.get(i) == '\n') {
                    count++;
                }
            }
        }
        return count;
    }

    private static byte[] readFully(FileChannel channel, long position, int length)
        throws IOException {
        byte[] bytes = new byte[length];
        fill(channel, ByteBuffer.wrap(bytes), position);
        return bytes;
    }

    /** Reads from {@code position} until {@code buffer} is full. */
    private static void fill(FileChannel channel, ByteBuffer buffer, long position)
        throws IOException {
        long next = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, next);
            if (read < 0) {
                throw new EOFException("file ends before " + (next + buffer.remaining()));
            }
            next += read;
        }
    }
}
