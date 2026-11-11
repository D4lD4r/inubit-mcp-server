package de.dadecker.inubit.mcp.domain.model;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The name of a note of the local knowledge (feature 019, research D-4): its path relative to the
 * knowledge directory with {@code /} separators and without {@code .md}, NFC-normalized.
 *
 * <p>Two names are equal if their NFC code points are equal (lookups). {@link #fold()} gives the
 * case-insensitive identity used for {@code create} collisions: upper then lower case repeated to
 * a fixpoint, then NFC — at least as coarse as Unicode full case folding for letters.
 *
 * <p>{@link #writableProblem} is the record rule for names the server writes. Hand-written notes
 * may have other names; they are listed and readable but not writable by the server.
 */
public record NoteName(String value) {

    public static final int MAX_SEGMENTS = 4;
    public static final int MAX_SEGMENT_CODE_POINTS = 100;
    public static final String SUFFIX = ".md";

    private static final Pattern SEGMENT = Pattern.compile("[\\p{L}\\p{N}_. -]+");
    private static final Set<String> DEVICES = Set.of("CON", "PRN", "AUX", "NUL");
    private static final Pattern NUMBERED_DEVICE = Pattern.compile("(COM|LPT)[0-9¹²³]");
    private static final int FOLD_ROUNDS = 4;

    public NoteName {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("a note name is never empty");
        }
        value = Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    /** The name as given (NFC-normalized); no rule is checked. */
    public static NoteName of(String value) {
        return new NoteName(value);
    }

    /** The name of the note file at {@code relative} (a path ending in {@code .md}). */
    public static NoteName ofRelativePath(Path relative) {
        List<String> parts = new ArrayList<>();
        relative.forEach(p -> parts.add(p.toString()));
        String last = parts.isEmpty() ? "" : parts.getLast();
        if (!last.endsWith(SUFFIX) || last.length() == SUFFIX.length()) {
            throw new IllegalArgumentException("not a note file (no .md suffix)");
        }
        parts.set(parts.size() - 1, last.substring(0, last.length() - SUFFIX.length()));
        return new NoteName(String.join("/", parts));
    }

    /**
     * The first violated rule of the record rule for {@code name}, naming the rule and the segment
     * number but never the name; empty if the server may write a note of this name.
     */
    public static Optional<String> writableProblem(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.of("the name is empty");
        }
        String nfc = Normalizer.normalize(name, Normalizer.Form.NFC);
        String[] segments = nfc.split("/", -1);
        if (segments.length > MAX_SEGMENTS) {
            return Optional.of("the name has more than " + MAX_SEGMENTS + " segments");
        }
        for (int i = 0; i < segments.length; i++) {
            Optional<String> problem = segmentProblem(segments[i]);
            if (problem.isPresent()) {
                return Optional.of("segment " + (i + 1) + " " + problem.get());
            }
        }
        return Optional.empty();
    }

    /** True if the name follows the record rule. */
    public boolean writable() {
        return writableProblem(value).isEmpty();
    }

    /** The segments of the name, split at {@code /}. */
    public List<String> segments() {
        return List.of(value.split("/", -1));
    }

    /** The last segment (the file name without {@code .md}). */
    public String lastSegment() {
        return segments().getLast();
    }

    /** The case-insensitive identity of this name (research D-4). */
    public String fold() {
        return fold(value);
    }

    /** NFC, then upper/lower case ({@link Locale#ROOT}) to a fixpoint, then NFC again. */
    public static String fold(String text) {
        String current = Normalizer.normalize(text, Normalizer.Form.NFC);
        for (int round = 0; round < FOLD_ROUNDS; round++) {
            String next = current.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        return Normalizer.normalize(current, Normalizer.Form.NFC);
    }

    @Override
    public String toString() {
        return value;
    }

    private static Optional<String> segmentProblem(String segment) {
        if (segment.isEmpty()) {
            return Optional.of("is empty");
        }
        if (segment.codePointCount(0, segment.length()) > MAX_SEGMENT_CODE_POINTS) {
            return Optional.of("is longer than " + MAX_SEGMENT_CODE_POINTS + " characters");
        }
        if (!SEGMENT.matcher(segment).matches()) {
            return Optional.of("may contain only letters, digits, _ . - and spaces");
        }
        if (segment.startsWith(".")) {
            return Optional.of("starts with . (it would be hidden)");
        }
        if (segment.endsWith(".") || segment.endsWith(" ")) {
            return Optional.of("ends with . or a space");
        }
        if (segment.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) {
            return Optional.of("ends in " + SUFFIX);
        }
        int dot = segment.indexOf('.');
        String base = (dot < 0 ? segment : segment.substring(0, dot)).strip()
            .toUpperCase(Locale.ROOT);
        if (DEVICES.contains(base) || NUMBERED_DEVICE.matcher(base).matches()) {
            return Optional.of("is a reserved Windows device name");
        }
        return Optional.empty();
    }
}
