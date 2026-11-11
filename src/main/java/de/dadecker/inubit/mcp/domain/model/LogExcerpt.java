package de.dadecker.inubit.mcp.domain.model;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * The excerpt of a downloaded server log (feature 021, FR-009 steps 2–5, research D-9, D-10,
 * D-12): what the scanned window is, its figures, how lines were selected, the selected lines
 * oldest first with their masked text, how they were shortened to fit {@code maxChars}, and fixed
 * hints. {@code charset} only for {@link ContentKind#TEXT} with a window; {@code detected} only for
 * a binary window with a known signature; {@code matchingLines} only for a text window searched
 * with {@code contains}.
 */
public record LogExcerpt(ContentKind kind, Optional<BinarySignature> detected,
    Optional<Charset> charset, Window window, Selection selection, OptionalLong matchingLines,
    List<ExcerptLine> lines, Optional<Truncation> truncated, List<String> hints) {

    private static final String WINDOW_HINT =
        "Only the last 50 MiB were scanned; line numbers count the whole file";
    private static final String NO_WHOLE_LINE_HINT = "No whole line in the last 50 MiB of the log";
    private static final String BINARY_HINT = "Binary content is not returned";
    private static final String NO_MATCH_HINT =
        "No line of the scanned window contains the search text";

    /** How the lines were selected: the last lines, or the last lines containing a text. */
    public enum Selection {
        TAIL,
        CONTAINS
    }

    /**
     * The scanned window: the absolute number of its first line, its line count (0 for a binary
     * window, whose lines are not counted), its size in bytes, and whether it is the whole file.
     */
    public record Window(long startLine, long lines, long bytes, boolean wholeFile) {
    }

    public LogExcerpt {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detected, "detected");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(matchingLines, "matchingLines");
        lines = List.copyOf(lines);
        Objects.requireNonNull(truncated, "truncated");
        hints = List.copyOf(hints);
    }

    /** A selected line before {@code maxChars}: its index in the window and its char range. */
    private record Selected(long index, int start, int end) {
        int length() {
            return end - start;
        }
    }

    /**
     * Classifies {@code window} ({@link EntryContentClassifier#classify}), masks the whole decoded
     * window with {@link SecretTextMasker#maskLines} and the spans {@code known} finds in it, splits
     * it into lines (D-9: {@code \n}, a {@code \r} right before it belongs to the terminator, a
     * final segment without terminator is a line, the empty one after a final {@code \n} is not),
     * selects the last {@code tailLines} lines, or with {@code contains} the last {@code tailLines}
     * lines containing it case-insensitively as plain text, and drops the oldest selected lines
     * until their texts fit {@code maxChars} chars; a newest line longer than {@code maxChars} alone
     * is cut to {@code maxChars} chars without splitting a surrogate pair. Pure.
     */
    public static LogExcerpt build(LogWindow window, int tailLines, Optional<String> contains,
        int maxChars, Function<String, List<SecretSpan>> known) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(contains, "contains");
        Objects.requireNonNull(known, "known");
        Selection selection = contains.isPresent() ? Selection.CONTAINS : Selection.TAIL;
        List<String> hints = new ArrayList<>();
        if (!window.wholeFile()) {
            hints.add(WINDOW_HINT);
        }
        long bytes = window.bytes().length;
        if (bytes == 0 && window.fileSize() > 0) {
            hints.add(NO_WHOLE_LINE_HINT);
            return new LogExcerpt(ContentKind.TEXT, Optional.empty(), Optional.empty(),
                new Window(window.startLine(), 0, 0, false), selection,
                contains.isPresent() ? OptionalLong.of(0) : OptionalLong.empty(), List.of(),
                Optional.empty(), hints);
        }
        EntryContentClassifier.Classified classified = EntryContentClassifier.classify(
            window.bytes());
        if (classified.kind() != ContentKind.TEXT) {
            if (classified.kind() == ContentKind.BINARY) {
                hints.add(BINARY_HINT);
            }
            return new LogExcerpt(classified.kind(), classified.detected(), Optional.empty(),
                new Window(window.startLine(), 0, bytes, window.wholeFile()), selection,
                OptionalLong.empty(), List.of(), Optional.empty(), hints);
        }
        Optional<Charset> charset = classified.charset();
        long startLine = window.startLine();
        long linesBefore = window.linesBefore();
        boolean wholeFile = window.wholeFile();
        String text = classified.text().orElseThrow();
        // D-13: from here on only the figures are kept, so the window bytes can be collected
        // while the window is masked, and the decoded text once it is masked
        window = null;
        classified = null;
        String masked = SecretTextMasker.maskLines(text, known.apply(text));
        text = null;

        ArrayDeque<Selected> selected = new ArrayDeque<>();
        long lineCount = 0;
        long matches = 0;
        int start = 0;
        while (start < masked.length()) {
            int newline = masked.indexOf('\n', start);
            int end = newline < 0 ? masked.length() : newline;
            if (newline >= 0 && end > start && masked.charAt(end - 1) == '\r') {
                end--;
            }
            if (contains.isEmpty() || containsIgnoreCase(masked, start, end, contains.get())) {
                matches++;
                selected.addLast(new Selected(lineCount, start, end));
                if (selected.size() > tailLines) {
                    selected.removeFirst();
                }
            }
            lineCount++;
            start = newline < 0 ? masked.length() : newline + 1;
        }
        if (contains.isPresent() && matches == 0) {
            hints.add(NO_MATCH_HINT);
        }

        int total = 0;
        for (Selected line : selected) {
            total += line.length();
        }
        int dropped = 0;
        while (total > maxChars && selected.size() > 1) {
            total -= selected.removeFirst().length();
            dropped++;
        }
        // only a single remaining line can still be longer than maxChars
        List<ExcerptLine> lines = new ArrayList<>(selected.size());
        int cutLines = 0;
        for (Selected line : selected) {
            String lineText = masked.substring(line.start(), line.end());
            boolean cut = lineText.length() > maxChars;
            if (cut) {
                lineText = cut(lineText, maxChars);
                cutLines++;
            }
            lines.add(new ExcerptLine(linesBefore + line.index() + 1, lineText, cut));
        }
        Optional<Truncation> truncated = dropped > 0 || cutLines > 0
            ? Optional.of(new Truncation(dropped, cutLines))
            : Optional.empty();
        return new LogExcerpt(ContentKind.TEXT, Optional.empty(), charset,
            new Window(startLine, lineCount, bytes, wholeFile), selection,
            contains.isPresent() ? OptionalLong.of(matches) : OptionalLong.empty(), lines,
            truncated, hints);
    }

    /** True if {@code text[start, end)} contains {@code search}, ignoring case, as plain text. */
    private static boolean containsIgnoreCase(String text, int start, int end, String search) {
        for (int i = start; i <= end - search.length(); i++) {
            if (text.regionMatches(true, i, search, 0, search.length())) {
                return true;
            }
        }
        return false;
    }

    /** The first {@code maxChars} chars of {@code text}, one less if that would split a pair. */
    private static String cut(String text, int maxChars) {
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
            && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }
}
