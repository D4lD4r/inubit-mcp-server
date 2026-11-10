package de.dadecker.inubit.mcp.application.knowledge;

import java.util.regex.Pattern;

/**
 * The reduction of every name, title, topic and source that leaves the server (research D-8,
 * D-14): masked, one line, cut at {@link KnowledgeLimits#LINE_CHARS} code points.
 */
final class Shown {

    static final String ELLIPSIS = "…";

    private static final Pattern BREAKS = Pattern.compile("[\\p{Cc}\\u2028\\u2029\\u0085]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");

    private Shown() {
    }

    /** {@code text} masked by {@code finder}, as one line, cut. */
    static String of(SecretFinder finder, String text) {
        return cut(line(finder.mask(text).text()));
    }

    /** Control characters and line breaks as spaces, runs of white space as one, stripped. */
    static String line(String text) {
        return SPACES.matcher(BREAKS.matcher(text).replaceAll(" ")).replaceAll(" ").strip();
    }

    /** At most {@link KnowledgeLimits#LINE_CHARS} code points; a cut one ends with {@code …}. */
    static String cut(String text) {
        if (text.codePointCount(0, text.length()) <= KnowledgeLimits.LINE_CHARS) {
            return text;
        }
        int end = text.offsetByCodePoints(0, KnowledgeLimits.LINE_CHARS - 1);
        return text.substring(0, end) + ELLIPSIS;
    }
}
