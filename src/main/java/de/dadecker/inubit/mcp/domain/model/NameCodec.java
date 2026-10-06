package de.dadecker.inubit.mcp.domain.model;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * INUBIT names (workflows, modules, diagram groups, owners, plugin types, property names) as
 * file name segments of the workspace and back (research D-3, FR-016).
 *
 * <ul>
 *   <li>Every character outside {@code [A-Za-z0-9 ._()+,=@-]} is percent-encoded as the
 *       upper-case {@code %XX} of its UTF-8 bytes, {@code %} itself included; so are a leading
 *       {@code .} and every space or {@code .} of a trailing run of them. A segment is
 *       therefore never empty, {@code .} or {@code ..}, never hidden, never ends in a space or
 *       dot (which some file systems drop) and never contains a path separator.
 *   <li>{@link #decode} accepts only the canonical form {@link #encode} produces, so every name
 *       has exactly one segment and every segment exactly one name.
 * </ul>
 *
 * <p>Case-insensitive collisions of two names are not resolved here; the writer refuses them
 * (D-3).
 */
public final class NameCodec {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private NameCodec() {
    }

    /**
     * The segment of {@code name}.
     *
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     */
    public static String encode(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("A name must not be empty");
        }
        int trailingFrom = name.length();
        while (trailingFrom > 0 && isSpaceOrDot(name.charAt(trailingFrom - 1))) {
            trailingFrom--;
        }
        StringBuilder segment = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            boolean forced = (i == 0 && codePoint == '.') || i >= trailingFrom;
            if (!forced && isSafe(codePoint)) {
                segment.append((char) codePoint);
            } else {
                for (byte b : new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8)) {
                    segment.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            }
            i += Character.charCount(codePoint);
        }
        return segment.toString();
    }

    /**
     * The name of {@code segment}.
     *
     * @throws IllegalArgumentException if {@code segment} is not the canonical encoding of a
     *     name (e.g. a malformed or lower-case escape, an escaped safe character, an unescaped
     *     unsafe one, or invalid UTF-8)
     */
    public static String decode(String segment) {
        if (segment == null || segment.isEmpty()) {
            throw new IllegalArgumentException("A path segment must not be empty");
        }
        StringBuilder name = new StringBuilder(segment.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%') {
                if (i + 2 >= segment.length()) {
                    throw notCanonical();
                }
                pending.write((hex(segment.charAt(i + 1)) << 4) | hex(segment.charAt(i + 2)));
                i += 2;
            } else {
                flush(pending, name);
                name.append(c);
            }
        }
        flush(pending, name);
        String decoded = name.toString();
        if (decoded.isEmpty() || !encode(decoded).equals(segment)) {
            throw notCanonical();
        }
        return decoded;
    }

    private static void flush(ByteArrayOutputStream pending, StringBuilder name) {
        if (pending.size() == 0) {
            return;
        }
        try {
            name.append(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(pending.toByteArray())));
        } catch (CharacterCodingException e) {
            throw notCanonical();
        }
        pending.reset();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw notCanonical();
    }

    private static boolean isSafe(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || " ._()+,=@-".indexOf(c) >= 0;
    }

    private static boolean isSpaceOrDot(char c) {
        return c == ' ' || c == '.';
    }

    private static IllegalArgumentException notCanonical() {
        return new IllegalArgumentException("Not a canonically encoded name segment");
    }
}
