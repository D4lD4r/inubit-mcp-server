package de.dadecker.inubit.mcp.security;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles a Python regular expression (the left-hand side of a {@code tools/neutralize.py} map
 * rule) as a {@link Pattern} with Python's semantics.
 *
 * <p>Compiled with {@link Pattern#UNICODE_CHARACTER_CLASS} (Python's {@code \w}, {@code \b},
 * {@code \d}, {@code \s} and {@code (?i)} are Unicode-aware for text patterns; {@code (?a)}
 * switches to ASCII) and {@link Pattern#UNIX_LINES} (only {@code \n} ends a line). {@code \w},
 * {@code \W}, {@code \b}, {@code \B}, {@code \s} and {@code \S} are written out with Python's
 * definitions (letters, numbers and {@code _}; whitespace plus {@code \x1c}-{@code \x1f}),
 * because Java's Unicode classes differ. Remaining known difference: case-insensitive matching
 * uses the JDK's case folding, which misses a few pairs that Python's re matches (measured: at
 * most 7 pairs in the BMP, e.g. {@code ß}/{@code ẞ} on JDK 25, depending on the JDK);
 * {@code tools/check-identifiers.py} uses Python's own semantics. Also translated:
 * {@code \Z} to {@code \z}, {@code {,n}} to {@code {0,n}}, a brace that is no quantifier to a
 * literal brace, {@code (?P<name>...)} and {@code (?P=name)} to Java named groups, {@code [} and
 * {@code &} inside a set to literals (Java would read nested sets and intersections), {@code \b}
 * inside a set to a backspace, {@code \v}, {@code \U...}, octal escapes and {@code (?#...)}
 * comments. Rejected, because Java cannot express them faithfully: conditional groups, the flags
 * {@code x} (verbose), {@code L} and a scoped {@code a}, and escapes Python does not know (such as
 * Java's {@code \p{...}}).
 *
 * <p>Error messages describe the construct, never the pattern text: the patterns name customer
 * values.
 */
final class PythonRegex {

    /**
     * Python's {@code \w} for text: letters, numbers and the underscore (str.isalnum). Java's
     * Unicode {@code \w} also has combining marks, connector punctuation and join controls, and
     * lacks the other numbers (No).
     */
    private static final String WORD_IN_SET = "\\p{L}\\p{N}_";
    private static final String WORD = "[" + WORD_IN_SET + "]";
    private static final String WORD_BOUNDARY =
        "(?:(?<=" + WORD + ")(?!" + WORD + ")|(?<!" + WORD + ")(?=" + WORD + "))";
    private static final String NOT_WORD_BOUNDARY =
        "(?:(?<=" + WORD + ")(?=" + WORD + ")|(?<!" + WORD + ")(?!" + WORD + "))";
    /** Python's {@code \s} (str.isspace) also has the separators {@code \x1c}-{@code \x1f}. */
    private static final String SPACE_IN_SET = "\\s\\x1c-\\x1f";
    private static final String SPACE = "[" + SPACE_IN_SET + "]";

    private final String source;
    private final StringBuilder out = new StringBuilder();
    private final Map<String, String> groupNames = new HashMap<>();
    private int pos;
    private boolean ascii;

    private PythonRegex(String source) {
        this.source = source;
    }

    /**
     * @throws IllegalArgumentException if the pattern is invalid or cannot be translated; the
     *     message names the construct only
     */
    static Pattern compile(String pythonRegex) {
        PythonRegex translator = new PythonRegex(pythonRegex);
        String java = translator.translate();
        int flags = Pattern.UNIX_LINES | (translator.ascii ? 0 : Pattern.UNICODE_CHARACTER_CLASS);
        try {
            return Pattern.compile(java, flags);
        } catch (PatternSyntaxException e) {
            throw fail("invalid regular expression (or not expressible in Java): "
                + e.getDescription().toLowerCase(Locale.ROOT));
        }
    }

    private static IllegalArgumentException fail(String reason) {
        return new IllegalArgumentException(reason, null);
    }

    private String translate() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            switch (c) {
                case '\\' -> escape(false);
                case '[' -> characterSet();
                case '(' -> group();
                case '{' -> brace();
                default -> {
                    out.append(c);
                    pos++;
                }
            }
        }
        return out.toString();
    }

    private char peek(int offset) {
        int index = pos + offset;
        return index < source.length() ? source.charAt(index) : '\0';
    }

    private boolean more(int offset) {
        return pos + offset < source.length();
    }

    // --- escapes --------------------------------------------------------------------------------

    private void escape(boolean inSet) {
        if (!more(1)) {
            throw fail("trailing backslash");
        }
        char d = peek(1);
        boolean asciiLetterOrDigit = d < 128 && Character.isLetterOrDigit(d);
        if (!asciiLetterOrDigit) {
            out.append('\\').append(d); // a literal character, the same in Python and Java
            pos += 2;
            return;
        }
        switch (d) {
            case 'A' -> {
                rejectInSet(inSet);
                keep(2);
            }
            case 'B' -> {
                rejectInSet(inSet);
                translateClass(ascii ? "\\B" : NOT_WORD_BOUNDARY);
            }
            case 'Z' -> {
                rejectInSet(inSet);
                out.append("\\z");
                pos += 2;
            }
            case 'b' -> translateClass(inSet ? "\\x08" : ascii ? "\\b" : WORD_BOUNDARY);
            case 's' -> translateClass(ascii ? "\\s" : inSet ? SPACE_IN_SET : SPACE);
            case 'S' -> translateClass(ascii ? "\\S" : "[^" + SPACE_IN_SET + "]");
            case 'w' -> translateClass(ascii ? "\\w" : inSet ? WORD_IN_SET : WORD);
            case 'W' -> translateClass(ascii ? "\\W" : "[^" + WORD_IN_SET + "]");
            case 'd', 'D', 'n', 'r', 't', 'f', 'a' -> keep(2);
            case 'v' -> {
                out.append("\\x0B");
                pos += 2;
            }
            case 'x' -> hexEscape(2);
            case 'u' -> hexEscape(4);
            case 'U' -> hexEscape(8);
            case 'N' -> namedCharacter();
            case '0' -> octal(1, 2);
            default -> {
                if (d >= '1' && d <= '9') {
                    digitEscape(inSet);
                } else {
                    throw fail("unsupported escape (a letter Python does not know)");
                }
            }
        }
    }

    /** Replaces the two-character escape at {@code pos} by {@code java}. */
    private void translateClass(String java) {
        out.append(java);
        pos += 2;
    }

    private void rejectInSet(boolean inSet) {
        if (inSet) {
            throw fail("bad escape in a character set");
        }
    }

    private void keep(int length) {
        out.append(source, pos, pos + length);
        pos += length;
    }

    private void hexEscape(int digits) {
        int start = pos + 2;
        if (start + digits > source.length()) {
            throw fail("incomplete hex escape");
        }
        String hex = source.substring(start, start + digits);
        if (!hex.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
            throw fail("incomplete hex escape");
        }
        int value = Integer.parseInt(hex, 16);
        if (value > Character.MAX_CODE_POINT) {
            throw fail("hex escape out of range");
        }
        out.append("\\x{").append(Integer.toHexString(value)).append('}');
        pos = start + digits;
    }

    private void namedCharacter() {
        int close = source.indexOf('}', pos);
        if (peek(2) != '{' || close < 0) {
            throw fail("bad named character escape");
        }
        keep(close + 1 - pos);
    }

    /** {@code \0}, {@code \0o}, {@code \0oo}: an octal escape; {@code first} digits consumed. */
    private void octal(int first, int maxMore) {
        int end = pos + 1 + first;
        while (end < source.length() && end < pos + 1 + first + maxMore
                && source.charAt(end) >= '0' && source.charAt(end) <= '7') {
            end++;
        }
        int value = Integer.parseInt(source.substring(pos + 1, end), 8);
        if (value > 0377) {
            throw fail("octal escape out of range");
        }
        out.append("\\x{").append(Integer.toHexString(value)).append('}');
        pos = end;
    }

    /** {@code \1}..{@code \99} is a group reference, three octal digits an octal escape. */
    private void digitEscape(boolean inSet) {
        if (isOctal(peek(1)) && isOctal(peek(2)) && isOctal(peek(3))) {
            int value = Integer.parseInt(source.substring(pos + 1, pos + 4), 8);
            if (value > 0377) {
                throw fail("octal escape out of range");
            }
            out.append("\\x{").append(Integer.toHexString(value)).append('}');
            pos += 4;
            return;
        }
        if (inSet) {
            throw fail("bad escape in a character set");
        }
        int end = pos + 2;
        if (Character.isDigit(peek(2))) {
            end++;
        }
        // The group keeps Java from reading following digits as part of the group number.
        out.append("(?:").append(source, pos, end).append(')');
        pos = end;
    }

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }

    // --- sets, groups, quantifiers ------------------------------------------------------------

    private void characterSet() {
        out.append('[');
        pos++;
        if (peek(0) == '^') {
            out.append('^');
            pos++;
        }
        if (peek(0) == ']') {
            out.append("\\]");
            pos++;
        }
        while (true) {
            if (!more(0)) {
                throw fail("unterminated character set");
            }
            char c = peek(0);
            switch (c) {
                case ']' -> {
                    out.append(']');
                    pos++;
                    return;
                }
                case '\\' -> escape(true);
                case '[', '&' -> {
                    out.append('\\').append(c);
                    pos++;
                }
                default -> {
                    out.append(c);
                    pos++;
                }
            }
        }
    }

    private void group() {
        if (peek(1) != '?') {
            out.append('(');
            pos++;
            return;
        }
        char kind = peek(2);
        switch (kind) {
            case 'P' -> namedGroup();
            case '#' -> {
                int close = source.indexOf(')', pos);
                if (close < 0) {
                    throw fail("missing ) after comment");
                }
                pos = close + 1;
            }
            case '(' -> throw fail("conditional groups are not supported");
            case ':', '=', '!', '>' -> keep(3);
            case '<' -> {
                if (peek(3) != '=' && peek(3) != '!') {
                    throw fail("unknown extension (?<");
                }
                keep(4);
            }
            default -> flags();
        }
    }

    private void namedGroup() {
        char what = peek(3);
        if (what == '<') {
            int close = source.indexOf('>', pos);
            String name = close < 0 ? "" : source.substring(pos + 4, close);
            if (name.isEmpty() || !name.codePoints().allMatch(
                    cp -> Character.isLetterOrDigit(cp) || cp == '_')
                    || Character.isDigit(name.codePointAt(0))) {
                throw fail("bad group name");
            }
            if (groupNames.containsKey(name)) {
                throw fail("redefinition of a group name");
            }
            String javaName = "pyg" + (groupNames.size() + 1);
            groupNames.put(name, javaName);
            out.append("(?<").append(javaName).append('>');
            pos = close + 1;
        } else if (what == '=') {
            int close = source.indexOf(')', pos);
            String javaName = close < 0 ? null : groupNames.get(source.substring(pos + 4, close));
            if (javaName == null) {
                throw fail("unknown group name");
            }
            out.append("\\k<").append(javaName).append('>');
            pos = close + 1;
        } else {
            throw fail("unknown extension (?P");
        }
    }

    /** {@code (?flags)} at the start or {@code (?flags-flags:...)}. */
    private void flags() {
        int end = pos + 2;
        while (end < source.length()
                && (Character.isLetter(source.charAt(end)) || source.charAt(end) == '-')) {
            end++;
        }
        char terminator = end < source.length() ? source.charAt(end) : '\0';
        if (end == pos + 2 || (terminator != ')' && terminator != ':')) {
            throw fail("unknown extension");
        }
        boolean scoped = terminator == ':';
        StringBuilder kept = new StringBuilder();
        for (char flag : source.substring(pos + 2, end).toCharArray()) {
            switch (flag) {
                case 'i', 'm', 's', '-' -> kept.append(flag);
                case 'u' -> {
                    // Unicode matching is Python's default for text patterns.
                }
                case 'a' -> {
                    if (scoped) {
                        throw fail("a scoped ASCII flag is not supported");
                    }
                    ascii = true;
                }
                case 'x' -> throw fail("the verbose flag is not supported");
                case 'L' -> throw fail("the locale flag is not supported");
                default -> throw fail("unknown flag");
            }
        }
        if (kept.isEmpty() || kept.toString().equals("-")) {
            if (scoped) {
                out.append("(?:");
            }
        } else {
            out.append("(?").append(kept).append(terminator);
        }
        pos = end + 1;
    }

    /** A Python quantifier {@code {m}}, {@code {m,}}, {@code {,n}}, {@code {m,n}}, else literal. */
    private void brace() {
        int end = pos + 1;
        int minStart = end;
        while (end < source.length() && Character.isDigit(source.charAt(end))) {
            end++;
        }
        String min = source.substring(minStart, end);
        String max = null;
        if (end < source.length() && source.charAt(end) == ',') {
            int maxStart = ++end;
            while (end < source.length() && Character.isDigit(source.charAt(end))) {
                end++;
            }
            max = source.substring(maxStart, end);
        }
        boolean quantifier = end < source.length() && source.charAt(end) == '}'
            && (!min.isEmpty() || max != null);
        if (!quantifier) {
            out.append("\\{");
            pos++;
            return;
        }
        out.append('{').append(min.isEmpty() ? "0" : min);
        if (max != null) {
            out.append(',').append(max);
        }
        out.append('}');
        pos = end + 1;
    }
}
