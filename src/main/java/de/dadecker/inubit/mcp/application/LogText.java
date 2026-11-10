package de.dadecker.inubit.mcp.application;

import java.util.regex.Pattern;

/**
 * A request value for a log line (feature 018 phase 3 review): cut to {@value #MAX_CHARS} chars,
 * never inside a surrogate pair, with every character that could end or forge a line replaced by
 * {@code ?}: C0 controls, DEL, C1 controls (also NEL, U+0085) and the line and paragraph
 * separators U+2028 and U+2029.
 */
final class LogText {

    static final int MAX_CHARS = 500;
    private static final Pattern LINE_BREAKING =
        Pattern.compile("[\\x{0}-\\x{1F}\\x{7F}-\\x{9F}\\x{2028}\\x{2029}]");

    private LogText() {
    }

    static String loggable(String value) {
        String bounded = value;
        if (value.length() > MAX_CHARS) {
            int end = Character.isLowSurrogate(value.charAt(MAX_CHARS))
                && Character.isHighSurrogate(value.charAt(MAX_CHARS - 1))
                ? MAX_CHARS - 1 : MAX_CHARS;
            bounded = value.substring(0, end);
        }
        return LINE_BREAKING.matcher(bounded).replaceAll("?");
    }
}
