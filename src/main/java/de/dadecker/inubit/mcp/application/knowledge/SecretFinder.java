package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.domain.model.KeyMaterial;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.SecretPlaceholder;
import de.dadecker.inubit.mcp.domain.model.SecretSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The secret rules of the local knowledge on free text (feature 019, FR-010, research D-6): key
 * material (b), INUBIT encrypted values (c) and the values of assignments (d). A finding covers
 * the value only; keys stay readable. Used for masking on read and for refusing on write.
 */
public final class SecretFinder {

    /** The kind of a finding, in the order of FR-010. */
    public enum Kind {
        CONFIGURED_CREDENTIAL, KEY_MATERIAL, INUBIT_ENCRYPTED, ASSIGNMENT
    }

    /**
     * One secret: the char range {@code [start, end)}, the 1-based line of its start and, for an
     * assignment, the key. Never holds the value.
     */
    public record Finding(Kind kind, int start, int end, int line, Optional<String> key) {

        public Finding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
            if (start < 0 || end <= start || line < 1) {
                throw new IllegalArgumentException(
                    "a finding needs 0 <= start < end and a line >= 1");
            }
        }

        public SecretSpan span() {
            return new SecretSpan(start, end);
        }
    }

    private static final List<String> KEY_WORDS = List.of("password", "passwd", "pwd", "secret",
        "token", "passphrase", "credential", "apikey");
    private static final String BEGIN = "-----BEGIN";
    private static final String END = "-----END";
    private static final String PRIVATE_KEY = "PRIVATE KEY-----";
    private static final int KEYSTORE_BASE64 = 64;
    private static final int ENCRYPTED_BASE64 = 16;
    private static final Pattern ENCRYPTED_TOKEN =
        Pattern.compile("(?<![A-Za-z0-9+/])(?:AES-|AESG)[A-Za-z0-9+/=:-]+");
    private static final Pattern START_TAG =
        Pattern.compile("<([\\p{L}_][\\p{L}\\p{N}_.:\\-]*)([^<>]*)>");
    private static final Pattern ENCRYPTED_ATTRIBUTE = Pattern.compile(
        "(?i)(?:^|\\s)(?:encrypted\\s*=\\s*([\"'])true\\1|type\\s*=\\s*([\"'])password\\2)");
    private static final Pattern NAME_ATTRIBUTE =
        Pattern.compile("(?i)(?:^|\\s)name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern BLOCK_INDICATOR =
        Pattern.compile("[|>](?:[1-9][+-]?|[+-][1-9]?)?(?:\\s+#.*)?");

    /**
     * Every secret in {@code text}, ordered by position.
     *
     * @throws IllegalArgumentException if the text is longer than {@link
     *                                  KnowledgeLimits#READ_BYTES} characters
     */
    public List<Finding> find(String text) {
        if (text.length() > KnowledgeLimits.READ_BYTES) {
            throw new IllegalArgumentException("a text of " + text.length() + " characters is"
                + " longer than the bound of " + KnowledgeLimits.READ_BYTES);
        }
        Scan scan = new Scan(text);
        scan.privateKeys();
        scan.keystores();
        scan.encryptedTokens();
        scan.elements();
        scan.assignments();
        return scan.findings.stream()
            .sorted(Comparator.comparingInt(Finding::start).thenComparingInt(Finding::end)
                .thenComparing(Finding::kind))
            .toList();
    }

    /** {@code text} with every finding replaced by the mask token. */
    public Masked mask(String text) {
        return Masked.of(text, find(text).stream().map(Finding::span).toList());
    }

    private static boolean hasKeyWord(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return KEY_WORDS.stream().anyMatch(lower::contains);
    }

    private static boolean exempt(String value) {
        return value.isEmpty() || Masked.MASK_TOKEN.equals(value)
            || SecretPlaceholder.isPlaceholder(value);
    }

    private static boolean isKeyCodePoint(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '.'
            || codePoint == '-';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }

    /** One run of {@link #find} over a text. */
    private static final class Scan {

        private final String text;
        private final int[] lineStarts;
        private final List<Finding> findings = new ArrayList<>();
        private int nextPrivateKey = -2;

        Scan(String text) {
            this.text = text;
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        /** The 0-based line of {@code offset}. */
        int lineIndex(int offset) {
            int low = 0;
            int high = lineStarts.length - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (lineStarts[middle] <= offset) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return low;
        }

        /** The end of line {@code index}, before its line feed. */
        int lineEnd(int index) {
            return index + 1 < lineStarts.length ? lineStarts[index + 1] - 1 : text.length();
        }

        void add(Kind kind, int start, int end, Optional<String> key) {
            findings.add(new Finding(kind, start, end, lineIndex(start) + 1, key));
        }

        /** Adds the value {@code [start, end)} without its outer white space, unless exempt. */
        void value(Kind kind, int start, int end, Optional<String> key) {
            while (start < end && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            if (!exempt(text.substring(start, end))) {
                add(kind, start, end, key);
            }
        }

        /**
         * The first {@link #PRIVATE_KEY} in {@code [from, limit)}, or -1; {@code from} never
         * decreases between calls, so the text is searched once.
         */
        int privateKeyMarker(int from, int limit) {
            if (nextPrivateKey != -1 && nextPrivateKey < from) {
                nextPrivateKey = text.indexOf(PRIVATE_KEY, from);
            }
            return nextPrivateKey >= 0 && nextPrivateKey + PRIVATE_KEY.length() <= limit
                ? nextPrivateKey : -1;
        }

        /** (b) A PEM private key, from its begin marker to its end marker or the end. */
        void privateKeys() {
            int from = 0;
            while (from < text.length()) {
                int begin = text.indexOf(BEGIN, from);
                if (begin < 0) {
                    return;
                }
                int marker = privateKeyMarker(begin, lineEnd(lineIndex(begin)));
                if (marker < 0) {
                    from = begin + BEGIN.length();
                    continue;
                }
                int end = text.length();
                int search = marker + PRIVATE_KEY.length();
                while (true) {
                    int endMarker = text.indexOf(END, search);
                    if (endMarker < 0) {
                        break;
                    }
                    int closing = privateKeyMarker(endMarker, lineEnd(lineIndex(endMarker)));
                    if (closing >= 0) {
                        end = closing + PRIVATE_KEY.length();
                        break;
                    }
                    search = endMarker + END.length();
                }
                add(Kind.KEY_MATERIAL, begin, end, Optional.empty());
                from = end;
            }
        }

        /**
         * (b) A base64 keystore: runs of the base64 alphabet (padding ends a run); a run that
         * ends its line continues with runs that fill the following lines (MIME-wrapped).
         */
        void keystores() {
            List<int[]> runs = new ArrayList<>();
            int i = 0;
            while (i < text.length()) {
                if (!KeyMaterial.isBase64(text.charAt(i))) {
                    i++;
                    continue;
                }
                int end = i + 1;
                while (end < text.length() && KeyMaterial.isBase64(text.charAt(end))
                    && !(text.charAt(end - 1) == '=' && text.charAt(end) != '=')) {
                    end++;
                }
                runs.add(new int[] {i, end});
                i = end;
            }
            int r = 0;
            while (r < runs.size()) {
                int first = r;
                int count = runs.get(r)[1] - runs.get(r)[0];
                while (r + 1 < runs.size() && continues(runs.get(r), runs.get(r + 1))) {
                    r++;
                    count += runs.get(r)[1] - runs.get(r)[0];
                }
                int start = runs.get(first)[0];
                int end = runs.get(r)[1];
                r++;
                if (count >= KEYSTORE_BASE64 && KeyMaterial.decodeDocument(text.substring(start,
                    end)).filter(KeyMaterial::isKeyMaterial).isPresent()) {
                    add(Kind.KEY_MATERIAL, start, end, Optional.empty());
                }
            }
        }

        private boolean continues(int[] run, int[] next) {
            int end = run[1];
            if (text.charAt(end - 1) == '=') {
                return false;
            }
            while (end < text.length() && isBlank(text.charAt(end))) {
                end++;
            }
            if (end >= text.length() || text.charAt(end) != '\n') {
                return false;
            }
            int start = next[0];
            while (start > 0 && (text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\t')) {
                start--;
            }
            return start == end + 1;
        }

        /** (c) An {@code AES-}/{@code AESG} token with at least 16 base64 characters. */
        void encryptedTokens() {
            Matcher matcher = ENCRYPTED_TOKEN.matcher(text);
            while (matcher.find()) {
                int base64 = 0;
                for (int i = matcher.start() + 4; i < matcher.end(); i++) {
                    if (KeyMaterial.isBase64(text.charAt(i))) {
                        base64++;
                    }
                }
                if (base64 >= ENCRYPTED_BASE64) {
                    add(Kind.INUBIT_ENCRYPTED, matcher.start(), matcher.end(), Optional.empty());
                }
            }
        }

        /**
         * (c) and (d) The content of an XML element marked {@code encrypted="true"} or {@code
         * type="Password"}, or named by a key word (element name or {@code name} attribute), up
         * to its matching closing tag or the end of the text. Elements inside such content are
         * part of it.
         */
        void elements() {
            Matcher matcher = START_TAG.matcher(text);
            int coveredUntil = 0;
            while (matcher.find()) {
                String attributes = matcher.group(2);
                if (matcher.start() < coveredUntil || attributes.endsWith("/")) {
                    continue;
                }
                String name = matcher.group(1);
                Kind kind;
                Optional<String> key;
                if (ENCRYPTED_ATTRIBUTE.matcher(attributes).find()) {
                    kind = Kind.INUBIT_ENCRYPTED;
                    key = Optional.empty();
                } else if (hasKeyWord(name)) {
                    kind = Kind.ASSIGNMENT;
                    key = Optional.of(name);
                } else {
                    key = nameAttribute(attributes).filter(SecretFinder::hasKeyWord);
                    if (key.isEmpty()) {
                        continue;
                    }
                    kind = Kind.ASSIGNMENT;
                }
                int closing = closingTag(name, matcher.end());
                value(kind, matcher.end(), closing, key);
                coveredUntil = closing;
            }
        }

        private static Optional<String> nameAttribute(String attributes) {
            Matcher matcher = NAME_ATTRIBUTE.matcher(attributes);
            if (!matcher.find()) {
                return Optional.empty();
            }
            return Optional.of(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }

        /** The start of the closing tag matching an element {@code name} opened before. */
        private int closingTag(String name, int from) {
            Matcher matcher = Pattern.compile("<(/?)" + Pattern.quote(name)
                + "(?![\\p{L}\\p{N}_.:\\-])([^<>]*)>").matcher(text);
            matcher.region(from, text.length());
            int depth = 1;
            while (matcher.find()) {
                if (!matcher.group(1).isEmpty()) {
                    depth--;
                    if (depth == 0) {
                        return matcher.start();
                    }
                } else if (!matcher.group(2).endsWith("/")) {
                    depth++;
                }
            }
            return text.length();
        }

        /** (d) {@code key: value}, {@code key=value}, quoted keys and values, YAML blocks. */
        void assignments() {
            int line = 0;
            while (line < lineStarts.length) {
                line = assignments(line) + 1;
            }
        }

        /**
         * The assignments of {@code line}; a separator inside a found value is part of it.
         * Returns the last line covered (a YAML block).
         */
        private int assignments(int line) {
            int start = lineStarts[line];
            int end = lineEnd(line);
            for (int s = start; s < end; s++) {
                char c = text.charAt(s);
                if (c != ':' && c != '=') {
                    continue;
                }
                Optional<String> key = keyBefore(start, s).filter(SecretFinder::hasKeyWord);
                if (key.isEmpty()) {
                    continue;
                }
                int v = s + 1;
                while (v < end && (text.charAt(v) == ' ' || text.charAt(v) == '\t')) {
                    v++;
                }
                if (v < end && (text.charAt(v) == '"' || text.charAt(v) == '\'')) {
                    int closing = closingQuote(v + 1, end, text.charAt(v));
                    value(Kind.ASSIGNMENT, v + 1, closing, key);
                    s = closing;
                } else if (BLOCK_INDICATOR.matcher(text.substring(v, end).strip()).matches()) {
                    return block(line, key);
                } else {
                    value(Kind.ASSIGNMENT, v, end, key);
                    return line;
                }
            }
            return line;
        }

        /** The key right before the separator at {@code separator}, plain or quoted. */
        private Optional<String> keyBefore(int lineStart, int separator) {
            int end = separator;
            while (end > lineStart && isSpace(text.charAt(end - 1))) {
                end--;
            }
            if (end > lineStart && (text.charAt(end - 1) == '"' || text.charAt(end - 1) == '\'')) {
                int open = text.lastIndexOf(text.charAt(end - 1), end - 2);
                return open >= lineStart && open < end - 2
                    ? Optional.of(text.substring(open + 1, end - 1)) : Optional.empty();
            }
            int start = end;
            while (start > lineStart && isKeyCodePoint(text.codePointBefore(start))) {
                start -= Character.charCount(text.codePointBefore(start));
            }
            boolean placeholder = start >= 2 && text.startsWith("${", start - 2);
            return start < end && !placeholder ? Optional.of(text.substring(start, end))
                : Optional.empty();
        }

        private int closingQuote(int from, int end, char quote) {
            for (int i = from; i < end; i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    return i;
                }
            }
            return end;
        }

        /**
         * A YAML block scalar after the key on {@code line}: the following lines that are empty
         * or indented deeper, as one span. Returns the last line of the block.
         */
        private int block(int line, Optional<String> key) {
            int indent = indent(line);
            int last = line;
            int lastContent = -1;
            int firstContent = -1;
            for (int next = line + 1; next < lineStarts.length; next++) {
                boolean blank = text.substring(lineStarts[next], lineEnd(next)).isBlank();
                if (!blank && indent(next) <= indent) {
                    break;
                }
                last = next;
                if (!blank) {
                    lastContent = next;
                    if (firstContent < 0) {
                        firstContent = next;
                    }
                }
            }
            if (firstContent >= 0) {
                value(Kind.ASSIGNMENT, lineStarts[firstContent], lineEnd(lastContent), key);
            }
            return last;
        }

        private int indent(int line) {
            int i = lineStarts[line];
            int end = lineEnd(line);
            while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                i++;
            }
            return i - lineStarts[line];
        }
    }
}
