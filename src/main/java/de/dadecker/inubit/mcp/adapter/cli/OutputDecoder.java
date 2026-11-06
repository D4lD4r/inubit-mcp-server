package de.dadecker.inubit.mcp.adapter.cli;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns the bytes StartCLI writes to stdout or stderr into text (feature 013, FR-006, research
 * R-6).
 *
 * <ul>
 *   <li>macOS and Linux ({@link #posix}): UTF-8 as before feature 013, malformed bytes replaced, no
 *       normalisation (FR-017).
 *   <li>Windows ({@link #windows}): per line (split at LF), strictly as UTF-8, which the client
 *       writes whatever the console code page
 *       ({@link StartCliEnvironment#WINDOWS_JAVA_TOOL_OPTIONS}); a line that is not valid UTF-8
 *       (one the interpreter or the batch file prints itself) in the console OEM code page. CRLF
 *       becomes LF; a CR not followed by LF is kept, as on macOS. A UTF-8 byte order mark at the
 *       start of the output is dropped, so the first line reads as on macOS.
 * </ul>
 */
public final class OutputDecoder {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final boolean windows;
    private final Charset oemCharset;

    private OutputDecoder(boolean windows, Charset oemCharset) {
        this.windows = windows;
        this.oemCharset = oemCharset;
    }

    /** Decoding on macOS and Linux. */
    public static OutputDecoder posix() {
        return new OutputDecoder(false, StandardCharsets.UTF_8);
    }

    /** Decoding on Windows; {@code oemCharset} is the console OEM code page ({@code GetOEMCP}). */
    public static OutputDecoder windows(Charset oemCharset) {
        return new OutputDecoder(true, oemCharset);
    }

    /** The text of one complete stream (stdout or stderr) as kept by {@code CliRunner}. */
    public String decode(byte[] bytes) {
        if (!windows) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder(); // reports malformed input
        StringBuilder text = new StringBuilder(bytes.length);
        int start = startsWithBom(bytes) ? BOM.length : 0;
        while (start < bytes.length) {
            int lf = indexOfLf(bytes, start);
            int end = lf < 0 ? bytes.length : lf;
            int contentEnd = lf >= 0 && end > start && bytes[end - 1] == '\r' ? end - 1 : end;
            text.append(line(utf8, bytes, start, contentEnd));
            if (lf < 0) {
                break;
            }
            text.append('\n');
            start = lf + 1;
        }
        return text.toString();
    }

    private String line(CharsetDecoder utf8, byte[] bytes, int from, int to) {
        try {
            return utf8.decode(ByteBuffer.wrap(bytes, from, to - from)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, from, to - from, oemCharset);
        }
    }

    private static boolean startsWithBom(byte[] bytes) {
        return bytes.length >= BOM.length
            && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2];
    }

    private static int indexOfLf(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
