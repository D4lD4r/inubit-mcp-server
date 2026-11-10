package de.dadecker.inubit.mcp.domain.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the values of secret-named XML elements, XML attributes, JSON keys, header-style lines
 * and {@code name=value} pairs, and HTTP {@code Authorization} values and {@code Basic}/{@code
 * Bearer} credentials, in the text of a Queue Manager entry (feature 020, FR-011, D-11). Pure,
 * without an XML or JSON parser, so it works on cut-off and broken text; everything outside the
 * masked values stays unchanged.
 *
 * <p>Each rule yields ranges on the original text; overlapping ranges are merged into one, which
 * is replaced by the placeholder of the range with the earliest start, then the longest, then the
 * lower rule number.
 */
public final class SecretTextMasker {

    private static final List<String> TERMS = List.of("password", "passwd", "pwd", "secret",
        "token", "apikey", "api_key", "credential", "authorization", "private", "keystore",
        "cookie");
    private static final int MAX_NAME = 64;
    private static final String DATA = "data";
    private static final String AUTHORIZATION = "${secret:authorization}";
    /** The line terminators after which {@code ^} matches in {@link Pattern#MULTILINE} mode. */
    private static final String LINE_TERMINATORS = "\n\r\u0085\u2028\u2029";

    /*
     * Repeated groups below are possessive: java.util.regex recurses once per iteration of a
     * backtracking group, which overflows the stack on values of a few KiB (a repeated single
     * character class is a loop). Possessive matching changes no result here, as no pattern can
     * succeed by giving characters back.
     */
    private static final String QNAME =
        "[A-Za-z_][A-Za-z0-9_.-]*+(?::[A-Za-z_][A-Za-z0-9_.-]*+)?+";
    /** Attribute part of a start tag; quoted values never contain {@code <} in XML. */
    private static final String ATTRIBUTES =
        "(?:\\s(?:[^<>\"']++|\"[^\"<]*+\"|'[^'<]*+')*+)?";
    private static final Pattern START_TAG =
        Pattern.compile("<(" + QNAME + ")(" + ATTRIBUTES + ")>");
    /** A name without a value is skipped as a whole, so it is not scanned again per character. */
    private static final Pattern ATTRIBUTE_TOKEN = Pattern.compile("\\G(?:\\s++|(" + QNAME
        + ")\\s*+=\\s*+(?:\"([^\"]*+)\"|'([^']*+)')|" + QNAME
        + "|\"[^\"]*+\"|'[^']*+'|[^\\s\"'])");
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(?:.*?]]>|.*\\z)",
        Pattern.DOTALL);
    private static final int CDATA_OPEN = "<![CDATA[".length();
    /** Group 1 CDATA, groups 2 and 3 a start tag (qualified name, attributes), 4 an end tag. */
    private static final Pattern TAG = Pattern.compile("(" + CDATA.pattern() + ")|<(" + QNAME
        + ")(" + ATTRIBUTES + ")>|</(" + QNAME + ")\\s*>", Pattern.DOTALL);
    private static final Pattern JSON_KEY =
        Pattern.compile("(?<!\\\\)\"([^\"\\\\\\r\\n]*+(?:\\\\.[^\"\\\\\\r\\n]*+)*+)\"\\s*+:\\s*+");
    /** Only spaces and tabs around the colon, so an empty value never reaches the next line. */
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
        "^[ \\t]*+(?:Proxy-)?Authorization[ \\t]*+:[ \\t]*+",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern HEADER = Pattern.compile(
        "^[ \\t]*+([A-Za-z0-9][A-Za-z0-9_.-]*+)[ \\t]*+:[ \\t]*+", Pattern.MULTILINE);
    /**
     * The look-behind also excludes {@code :}, so a run of name characters is scanned once from
     * its start, not again after each colon in it.
     */
    private static final Pattern NAME_VALUE =
        Pattern.compile("(?<![A-Za-z0-9_.:-])([A-Za-z0-9_.:-]++)=");
    private static final Pattern BASIC =
        Pattern.compile("\\bBasic\\s++([A-Za-z0-9+/]{4,}+={0,2}+)");
    private static final Pattern BEARER =
        Pattern.compile("\\bBearer\\s++([A-Za-z0-9._~+/-]{16,}+=*+)");

    private SecretTextMasker() {
    }

    /** A part of the original text to replace by {@code replacement}, found by {@code rule}. */
    private record Range(int start, int end, String replacement, int rule) {
    }

    /**
     * True if the lower-cased local name (after the last {@code :}) contains a secret term; a
     * {@code -} counts as {@code _}, so the header {@code X-Api-Key} contains {@code api_key}.
     */
    public static boolean secretName(String name) {
        String local = localName(name).toLowerCase(Locale.ROOT).replace('-', '_');
        for (String term : TERMS) {
            if (local.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ${secret:<scope>.<name>}}, every code point of {@code name} outside
     * {@code [A-Za-z0-9_.-]} replaced by {@code _} and the name cut at 64 characters.
     */
    public static String placeholder(String scope, String name) {
        Objects.requireNonNull(scope, "scope");
        StringBuilder safe = new StringBuilder();
        name.codePoints().limit(MAX_NAME).forEach(c -> safe.append(
            (c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-'))
                ? (char) c : '_'));
        return "${secret:" + scope + "." + safe + "}";
    }

    /** {@code text} with the secret values of rules 1–7 masked; linear in its length. */
    public static String mask(String text) {
        Objects.requireNonNull(text, "text");
        List<Range> ranges = new ArrayList<>();
        xmlElements(text, ranges);
        xmlAttributes(text, ranges);
        jsonKeys(text, ranges);
        authorizationHeaders(text, ranges);
        headerLines(text, ranges);
        nameValuePairs(text, ranges);
        basicAndBearer(text, ranges);
        return replace(text, ranges);
    }

    /** Rule 1: the content of a secret-named element up to its matching end tag. */
    private static void xmlElements(String text, List<Range> ranges) {
        elements(text, 0, text.length(), false, ranges);
    }

    /**
     * One pass over the tags in {@code [from, to)}: a stack of open start tags per secret qualified
     * name pairs each end tag with the innermost open start tag of the same name, so nested start
     * tags of that name are counted. CDATA is opaque to the pairing; the interior of each CDATA
     * section (an embedded document) gets its own pass, bounded by the section. A start tag left
     * open masks up to the next {@code <} (outside CDATA on the outer pass) or the end.
     */
    private static void elements(String text, int from, int to, boolean inCdata,
                                 List<Range> ranges) {
        Map<String, Deque<Integer>> open = new HashMap<>();
        Matcher tag = TAG.matcher(text).region(from, to);
        while (tag.find()) {
            if (tag.group(1) != null) {
                if (!inCdata) {
                    int end = tag.group(1).endsWith("]]>") ? tag.end() - 3 : tag.end();
                    elements(text, tag.start() + CDATA_OPEN, end, true, ranges);
                }
            } else if (tag.group(2) != null) {
                if (!tag.group(3).endsWith("/") && secretName(tag.group(2))) {
                    open.computeIfAbsent(tag.group(2), name -> new ArrayDeque<>()).push(tag.end());
                }
            } else {
                Deque<Integer> starts = open.get(tag.group(4));
                if (starts != null && !starts.isEmpty()) {
                    add(ranges, starts.pop(), tag.start(), elementPlaceholder(tag.group(4)), 1);
                }
            }
        }
        open.forEach((qname, starts) -> starts.forEach(start -> add(ranges, start,
            inCdata ? nextLt(text, start, to) : nextTag(text, start), elementPlaceholder(qname),
            1)));
    }

    private static String elementPlaceholder(String qname) {
        return placeholder(DATA, localName(qname));
    }

    private static int nextLt(String text, int from, int to) {
        int lt = text.indexOf('<', from);
        return lt >= 0 && lt < to ? lt : to;
    }

    private static int nextTag(String text, int from) {
        int lt = text.indexOf('<', from);
        while (lt >= 0) {
            Matcher cdata = CDATA.matcher(text).region(lt, text.length());
            if (!cdata.lookingAt()) {
                return lt;
            }
            lt = text.indexOf('<', cdata.end());
        }
        return text.length();
    }

    /** Rule 2: the value of a secret-named attribute inside any start tag, between the quotes. */
    private static void xmlAttributes(String text, List<Range> ranges) {
        Matcher start = START_TAG.matcher(text);
        while (start.find()) {
            Matcher token = ATTRIBUTE_TOKEN.matcher(text).region(start.start(2), start.end(2));
            while (token.find()) {
                if (token.group(1) != null && secretName(token.group(1))) {
                    int value = token.group(2) != null ? 2 : 3;
                    add(ranges, token.start(value), token.end(value),
                        placeholder(DATA, localName(token.group(1))), 2);
                }
            }
        }
    }

    /**
     * Rule 3: the value of a secret-named JSON key: a string inside its quotes, an object or array
     * as a whole as a quoted placeholder, a scalar up to the next delimiter. Every key is searched,
     * also inside a masked value, because in broken JSON its value can reach past that one. The end
     * of every bracket and of the last scalar is kept, so nested values are not scanned again and
     * the run time stays linear on deeply nested or broken text.
     */
    private static void jsonKeys(String text, List<Range> ranges) {
        Matcher key = JSON_KEY.matcher(text);
        int[] structureEnds = null;
        int scalarTo = 0;
        while (key.find()) {
            int from = key.end();
            if (!secretName(key.group(1)) || from == text.length()) {
                continue;
            }
            String placeholder = placeholder(DATA, localName(key.group(1)));
            char first = text.charAt(from);
            if (first == '"') {
                add(ranges, from + 1, stringEnd(text, from + 1), placeholder, 3);
            } else if (first == '{' || first == '[') {
                if (structureEnds == null) {
                    structureEnds = new int[text.length()];
                }
                add(ranges, from, structureEnd(text, from, structureEnds),
                    "\"" + placeholder + "\"", 3);
            } else {
                if (from >= scalarTo) {
                    scalarTo = scalarEnd(text, from);
                }
                add(ranges, from, scalarTo, placeholder, 3);
            }
        }
    }

    /** The index of the closing quote of a string whose content starts at {@code from}. */
    private static int stringEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return text.length();
    }

    /**
     * The index after the bracket closing the object or array at {@code from}; unterminated: the
     * end of the text. {@code ends} holds the result for every bracket a scan has opened (0 =
     * unknown); a scan reaching such a bracket outside a string continues after its end, as from
     * there on both scans read the text alike.
     */
    private static int structureEnd(String text, int from, int[] ends) {
        Deque<Integer> open = new ArrayDeque<>();
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                i = stringEnd(text, i + 1);
            } else if ((c == '{' || c == '[') && ends[i] > 0) {
                if (open.isEmpty()) {
                    return ends[i];
                }
                i = ends[i] - 1;
            } else if (c == '{' || c == '[') {
                open.push(i);
            } else if (c == '}' || c == ']') {
                ends[open.pop()] = i + 1;
                if (open.isEmpty()) {
                    return i + 1;
                }
            }
            i++;
        }
        open.forEach(start -> ends[start] = text.length());
        return text.length();
    }

    private static int scalarEnd(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                break;
            }
            i++;
        }
        return i;
    }

    /** Rule 4: the value of a {@code (Proxy-)Authorization} header line. */
    private static void authorizationHeaders(String text, List<Range> ranges) {
        Matcher header = AUTHORIZATION_HEADER.matcher(text);
        while (header.find()) {
            add(ranges, header.end(), lineEnd(text, header.end()), AUTHORIZATION, 4);
        }
    }

    /** Rule 5: the value of a header-style {@code name: value} line with a secret name. */
    private static void headerLines(String text, List<Range> ranges) {
        Matcher header = HEADER.matcher(text);
        while (header.find()) {
            if (secretName(header.group(1))) {
                add(ranges, header.end(), lineEnd(text, header.end()),
                    placeholder(DATA, header.group(1)), 5);
            }
        }
    }

    /**
     * Rule 6: the value of a {@code name=value} pair with a secret local name: a quoted value up to
     * the matching quote or the end of the line, otherwise up to the first {@code &}, {@code ;},
     * whitespace or line end. A quoted search stops at the next quote of its kind, which is at or
     * before the opening quote of the next value of that kind, so these searches never overlap.
     * The end of the last unquoted value is kept, as the next one ends there too if it starts
     * before it.
     */
    private static void nameValuePairs(String text, List<Range> ranges) {
        Matcher pair = NAME_VALUE.matcher(text);
        int unquotedTo = 0;
        while (pair.find()) {
            int from = pair.end();
            if (!secretName(pair.group(1)) || from == text.length()) {
                continue;
            }
            String placeholder = placeholder(DATA, localName(pair.group(1)));
            char first = text.charAt(from);
            if (first == '"' || first == '\'') {
                add(ranges, from + 1, quotedEnd(text, from + 1, first), placeholder, 6);
            } else {
                if (from >= unquotedTo) {
                    unquotedTo = unquotedEnd(text, from);
                }
                add(ranges, from, unquotedTo, placeholder, 6);
            }
        }
    }

    private static int quotedEnd(String text, int from, char quote) {
        int i = from;
        while (i < text.length() && text.charAt(i) != quote && !lineTerminator(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int unquotedEnd(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '&' || c == ';' || Character.isWhitespace(c) || lineTerminator(c)) {
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * Rule 7: a {@code Basic} credential whose Base64 decodes to text with a {@code :} and a
     * {@code Bearer} token of at least 16 characters.
     */
    private static void basicAndBearer(String text, List<Range> ranges) {
        Matcher basic = BASIC.matcher(text);
        while (basic.find()) {
            if (userAndPassword(basic.group(1))) {
                add(ranges, basic.start(1), basic.end(1), AUTHORIZATION, 7);
            }
        }
        Matcher bearer = BEARER.matcher(text);
        while (bearer.find()) {
            add(ranges, bearer.start(1), bearer.end(1), AUTHORIZATION, 7);
        }
    }

    /**
     * True if {@code token} is valid Base64 decoding to bytes with a {@code :}. Invalid lengths are
     * refused before decoding, so prose such as "Basic configuration" costs no exception.
     */
    private static boolean userAndPassword(String token) {
        int padding = token.length() - token.replace("=", "").length();
        int data = token.length() - padding;
        if (data % 4 == 1 || (padding > 0 && token.length() % 4 != 0)) {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(token);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        for (byte b : decoded) {
            if (b == ':') {
                return true;
            }
        }
        return false;
    }

    private static int lineEnd(String text, int from) {
        int i = from;
        while (i < text.length() && !lineTerminator(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean lineTerminator(char c) {
        return LINE_TERMINATORS.indexOf(c) >= 0;
    }

    private static void add(List<Range> ranges, int start, int end, String replacement, int rule) {
        if (end > start) {
            ranges.add(new Range(start, end, replacement, rule));
        }
    }

    private static String replace(String text, List<Range> ranges) {
        ranges.sort(Comparator.comparingInt(Range::start)
            .thenComparing(Comparator.comparingInt(Range::end).reversed())
            .thenComparingInt(Range::rule));
        StringBuilder masked = new StringBuilder(text.length());
        int done = 0;
        for (Range range : ranges) {
            if (range.start() >= done) {
                masked.append(text, done, range.start()).append(range.replacement());
            }
            done = Math.max(done, range.end());
        }
        return masked.append(text, done, text.length()).toString();
    }

    private static String localName(String name) {
        return name.substring(name.lastIndexOf(':') + 1);
    }
}
