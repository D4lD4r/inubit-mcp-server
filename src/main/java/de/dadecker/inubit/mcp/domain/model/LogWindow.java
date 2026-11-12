package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * The scanned end of a downloaded server log (feature 021, D-9): at most
 * {@link ServerLogBounds#WINDOW_BYTES} bytes starting at a line start, the number of lines
 * before it, whether it is the whole file, and the file size. The bytes are not copied.
 */
public record LogWindow(byte[] bytes, long linesBefore, boolean wholeFile, long fileSize) {

    public LogWindow {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > ServerLogBounds.WINDOW_BYTES) {
            throw new IllegalArgumentException(
                "bytes must be <= " + ServerLogBounds.WINDOW_BYTES + ": " + bytes.length);
        }
        if (linesBefore < 0) {
            throw new IllegalArgumentException("linesBefore must be >= 0: " + linesBefore);
        }
        if (fileSize < bytes.length) {
            throw new IllegalArgumentException("fileSize must be >= bytes: " + fileSize);
        }
        if (wholeFile && (linesBefore != 0 || fileSize != bytes.length)) {
            throw new IllegalArgumentException(
                "a whole-file window must start at line 1 and cover the file");
        }
    }

    /** The absolute number of the window's first line. */
    public long startLine() {
        return linesBefore + 1;
    }
}
