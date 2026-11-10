package de.dadecker.inubit.mcp.application.knowledge;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The metadata block of a note (feature 019, research D-5, FR-004, FR-004a, FR-004d): a
 * front-matter block whose first line is exactly {@code ---}, closed by the next line that is
 * exactly {@code ---}, within the first {@value #BLOCK_LINES} lines and {@value #BLOCK_BYTES}
 * bytes. Inside, one {@code key: value} per line, empty lines and {@code #} comments. Values are
 * plain, {@code "double-quoted"} ({@code \"} and {@code \\} only) or {@code 'single-quoted'}
 * ({@code ''} for {@code '}); {@code topics} is a flow list {@code [a, 'b']}.
 *
 * <p>A malformed block leaves every field empty, {@code bodyStart} 0 (the whole text is the
 * body) and names the problem by line and key, never by value.
 *
 * @param title      the title, if given and not empty
 * @param topics     the topics, empty if none
 * @param source     who created the note ({@code by hand} or {@code assistant (<client>)})
 * @param lastWriter who wrote last
 * @param lastChange the informative time of the last write (FR-004a), as written
 * @param unknown    the other lines of the block (unknown keys, comments, empty lines), in order
 * @param bodyStart  where the body starts: after the closing line, 0 without a valid block
 * @param malformed  whether a block was opened but is malformed
 * @param problem    what is malformed
 */
public record NoteMetadata(Optional<String> title, List<String> topics, Optional<String> source,
    Optional<String> lastWriter, Optional<String> lastChange, List<Entry> unknown, int bodyStart,
    boolean malformed, Optional<String> problem) {

    /** Most lines of the block, both {@code ---} lines included. */
    public static final int BLOCK_LINES = 64;
    /** Most UTF-8 bytes of the block, up to and including the line break of its closing line. */
    public static final int BLOCK_BYTES = 8192;

    /** A text without a block. */
    public static final NoteMetadata NONE = new NoteMetadata(Optional.empty(), List.of(),
        Optional.empty(), Optional.empty(), Optional.empty(), List.of(), 0, false,
        Optional.empty());

    private static final String DASHES = "---";
    private static final List<String> KNOWN =
        List.of("title", "topics", "source", "lastWriter", "lastChange");
    private static final Pattern ENTRY = Pattern.compile("([A-Za-z_][A-Za-z0-9_.-]*):(?: (.*))?");

    public NoteMetadata {
        Objects.requireNonNull(title, "title");
        topics = List.copyOf(topics);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lastWriter, "lastWriter");
        Objects.requireNonNull(lastChange, "lastChange");
        unknown = List.copyOf(unknown);
        Objects.requireNonNull(problem, "problem");
    }

    /**
     * A line of the block the server does not interpret, kept on a write.
     *
     * @param key  the key of a {@code key: value} line; empty for a comment or an empty line
     * @param line the line as written, without its line break
     */
    public record Entry(Optional<String> key, String line) {

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(line, "line");
        }
    }

    /**
     * The values of a block the server writes.
     *
     * @param unknown the kept lines of the previous block, written after the server's keys
     */
    public record Written(String title, List<String> topics, String source, String lastWriter,
        Instant lastChange, List<Entry> unknown) {

        public Written {
            Objects.requireNonNull(title, "title");
            topics = List.copyOf(topics);
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(lastWriter, "lastWriter");
            Objects.requireNonNull(lastChange, "lastChange");
            unknown = List.copyOf(unknown);
        }
    }

    /** The metadata of {@code text}; never throws for any text. */
    public static NoteMetadata parse(String text) {
        Objects.requireNonNull(text, "text");
        int lineEnd = text.indexOf('\n');
        String first = lineEnd < 0 ? text : text.substring(0, lineEnd);
        if (!stripCr(first).equals(DASHES)) {
            return NONE;
        }
        List<String> lines = new ArrayList<>();
        int start = lineEnd < 0 ? text.length() : lineEnd + 1;
        long bytes = utf8(text, 0, start);
        while (lineEnd >= 0 && lines.size() + 2 <= BLOCK_LINES) {
            int next = text.indexOf('\n', start);
            // a line that would exceed the byte bound ends the scan before it is searched further
            int end = next < 0 ? text.length() : next + 1;
            bytes += utf8(text, start, Math.min(end, start + BLOCK_BYTES + 1));
            if (bytes > BLOCK_BYTES) {
                break;
            }
            String line = stripCr(text.substring(start, next < 0 ? text.length() : next));
            if (line.equals(DASHES)) {
                return entries(lines, end);
            }
            if (next < 0) {
                break;
            }
            lines.add(line);
            start = end;
        }
        return malformed("no closing '---' within " + BLOCK_LINES + " lines and "
            + BLOCK_BYTES / 1024 + " KiB");
    }

    private static NoteMetadata entries(List<String> lines, int bodyStart) {
        String title = null;
        List<String> topics = null;
        String source = null;
        String lastWriter = null;
        String lastChange = null;
        List<Entry> unknown = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int number = i + 2;
            if (line.isBlank() || line.startsWith("#")) {
                unknown.add(new Entry(Optional.empty(), line));
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                return malformed("line " + number + " is not 'key: value', a comment or empty");
            }
            String key = entry.group(1);
            String raw = entry.group(2) == null ? "" : entry.group(2).strip();
            if (!KNOWN.contains(key)) {
                unknown.add(new Entry(Optional.of(key), line));
                continue;
            }
            if (!seen.add(key)) {
                return malformed("duplicate key " + key + " (line " + number + ")");
            }
            if (key.equals("topics")) {
                Optional<List<String>> list = flowList(raw);
                if (list.isEmpty()) {
                    return malformed("the value of topics (line " + number
                        + ") is not a flow list such as [a, 'b c']");
                }
                topics = list.get();
                continue;
            }
            Optional<String> value = scalar(raw);
            if (value.isEmpty()) {
                return malformed("the value of " + key + " (line " + number + ") is not a valid"
                    + " one-line value (quoting, or a control character)");
            }
            String found = value.get();
            switch (key) {
                case "title" -> title = found;
                case "source" -> source = found;
                case "lastWriter" -> lastWriter = found;
                default -> lastChange = found;
            }
        }
        return new NoteMetadata(present(title), topics == null ? List.of() : topics,
            present(source), present(lastWriter), present(lastChange), unknown, bodyStart, false,
            Optional.empty());
    }

    private static Optional<String> present(String value) {
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static NoteMetadata malformed(String problem) {
        return new NoteMetadata(Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
            Optional.empty(), List.of(), 0, true, Optional.of(problem));
    }

    /** A whole value: plain, double- or single-quoted; empty if it is not valid. */
    private static Optional<String> scalar(String raw) {
        if (raw.startsWith("\"") || raw.startsWith("'")) {
            Quoted quoted = quoted(raw, 0);
            return quoted == null || quoted.end() != raw.length() ? Optional.empty()
                : Optional.of(quoted.value());
        }
        return clean(raw) ? Optional.of(raw) : Optional.empty();
    }

    /** A quoted value and the index after its closing quote. */
    private record Quoted(String value, int end) {
    }

    /** The quoted value starting at {@code from}, or {@code null} if it is not valid. */
    private static Quoted quoted(String raw, int from) {
        char quote = raw.charAt(from);
        StringBuilder value = new StringBuilder();
        int i = from + 1;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c)) {
                return null;
            }
            if (quote == '\'' && c == '\'') {
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\'') {
                    value.append('\'');
                    i += 2;
                    continue;
                }
                return new Quoted(value.toString(), i + 1);
            }
            if (quote == '"' && c == '"') {
                return new Quoted(value.toString(), i + 1);
            }
            if (quote == '"' && c == '\\') {
                char escaped = i + 1 < raw.length() ? raw.charAt(i + 1) : 0;
                if (escaped != '"' && escaped != '\\') {
                    return null;
                }
                value.append(escaped);
                i += 2;
                continue;
            }
            value.append(c);
            i++;
        }
        return null;
    }

    /** A flow list {@code [a, 'b', "c"]}; empty if it is not one. */
    private static Optional<List<String>> flowList(String raw) {
        if (!raw.startsWith("[") || !raw.endsWith("]")) {
            return Optional.empty();
        }
        List<String> items = new ArrayList<>();
        int i = skipSpaces(raw, 1);
        if (raw.charAt(i) == ']') {
            return i == raw.length() - 1 ? Optional.of(items) : Optional.empty();
        }
        while (true) {
            if (raw.charAt(i) == '\'' || raw.charAt(i) == '"') {
                Quoted item = quoted(raw, i);
                if (item == null) {
                    return Optional.empty();
                }
                items.add(item.value());
                i = skipSpaces(raw, item.end());
            } else {
                int end = i;
                while (end < raw.length() && ",[]".indexOf(raw.charAt(end)) < 0) {
                    end++;
                }
                String item = raw.substring(i, end).strip();
                if (item.isEmpty() || !clean(item)) {
                    return Optional.empty();
                }
                items.add(item);
                i = end;
            }
            if (i >= raw.length()) {
                return Optional.empty();
            }
            if (raw.charAt(i) == ']') {
                return i == raw.length() - 1 ? Optional.of(items) : Optional.empty();
            }
            if (raw.charAt(i) != ',') {
                return Optional.empty();
            }
            i = skipSpaces(raw, i + 1);
            if (i >= raw.length()) {
                return Optional.empty();
            }
        }
    }

    private static int skipSpaces(String raw, int from) {
        int i = from;
        while (i < raw.length() && raw.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean clean(String value) {
        return value.chars().noneMatch(Character::isISOControl);
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static long utf8(String text, int from, int to) {
        return text.substring(from, Math.min(to, text.length()))
            .getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The block for {@code values}, every string single-quoted, ending with a line break; empty if
     * it would exceed {@value #BLOCK_LINES} lines or {@value #BLOCK_BYTES} bytes (D-5: the write is
     * refused).
     *
     * @throws IllegalArgumentException for a value with a control character (the caller checks the
     *     inputs first)
     */
    public static Optional<String> write(Written values) {
        StringBuilder block = new StringBuilder(DASHES).append('\n');
        block.append("title: ").append(quote(values.title())).append('\n');
        block.append("topics: [");
        for (int i = 0; i < values.topics().size(); i++) {
            block.append(i == 0 ? "" : ", ").append(quote(values.topics().get(i)));
        }
        block.append("]\n");
        block.append("source: ").append(quote(values.source())).append('\n');
        block.append("lastWriter: ").append(quote(values.lastWriter())).append('\n');
        block.append("lastChange: ").append(quote(values.lastChange().toString())).append('\n');
        int lines = 7;
        for (Entry entry : values.unknown()) {
            block.append(entry.line()).append('\n');
            lines++;
        }
        block.append(DASHES).append('\n');
        String text = block.toString();
        return lines > BLOCK_LINES || text.getBytes(StandardCharsets.UTF_8).length > BLOCK_BYTES
            ? Optional.empty() : Optional.of(text);
    }

    private static String quote(String value) {
        if (!clean(value)) {
            throw new IllegalArgumentException("a metadata value must be one line without"
                + " control characters");
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
