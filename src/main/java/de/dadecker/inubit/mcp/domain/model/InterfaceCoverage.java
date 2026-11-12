package de.dadecker.inubit.mcp.domain.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The coverage list of the StartCLI and REST interfaces (feature 022, FR-001, FR-003, research
 * D-2, data-model.md → InterfaceCoverage): one {@link CoverageEntry} per documented entry with its
 * state per version line, and the gate check {@link #offered}.
 *
 * <p>{@link #load} reads the tab-separated format of contracts/coverage-list.md and fails with
 * the line number on the first row that breaks a rule.
 */
public final class InterfaceCoverage {

    /** The classpath resource {@link #production} reads. */
    public static final String RESOURCE = "/coverage/interface-coverage.tsv";

    private static final String HEADER = "channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits";

    private static final int COLUMNS = 8;
    private static final String DELIVERED_STORIES = "delivered-stories:";
    private static final String SOURCE = "source:";
    private static final String NONE = "-";

    private final List<CoverageEntry> entries;
    private final Map<CoverageKey, CoverageEntry> byKey;
    private final Set<String> deliveredStories;
    private final Optional<String> source;

    private InterfaceCoverage(List<CoverageEntry> entries,
        Map<CoverageKey, CoverageEntry> byKey, Set<String> deliveredStories,
        Optional<String> source) {
        this.entries = List.copyOf(entries);
        this.byKey = Map.copyOf(byKey);
        this.deliveredStories = Set.copyOf(deliveredStories);
        this.source = source;
    }

    /**
     * Reads a coverage list: comment lines start with {@code #}, the comment
     * {@code # delivered-stories: US1, US2} names the delivered stories (none when absent or
     * empty), the comment {@code # source: ...} names where the entries come from, then the
     * header and one row per entry; blank lines are skipped.
     *
     * @throws IllegalArgumentException naming the line, on a delivered story other than
     *     {@code US1} … {@code US7}, a missing or wrong header, a row that
     *     breaks a rule of data-model.md → InterfaceCoverage, or a duplicate key
     */
    public static InterfaceCoverage load(Reader reader) {
        List<String> lines;
        try (BufferedReader buffered = new BufferedReader(reader)) {
            lines = buffered.lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> delivered = Set.of();
        Optional<String> source = Optional.empty();
        boolean header = false;
        Map<CoverageKey, CoverageEntry> byKey = new HashMap<>();
        Map<Integer, CoverageEntry> rows = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int number = index + 1;
            if (line.startsWith("#")) {
                String comment = line.substring(1).strip();
                if (comment.startsWith(DELIVERED_STORIES)) {
                    delivered = stories(number, comment.substring(DELIVERED_STORIES.length()));
                } else if (comment.startsWith(SOURCE)) {
                    source = Optional.of(comment.substring(SOURCE.length()).strip());
                }
            } else if (line.isBlank()) {
                continue;
            } else if (!header) {
                if (!line.equals(HEADER)) {
                    throw failure(number, "expected the header " + Names.quote(HEADER));
                }
                header = true;
            } else {
                CoverageEntry entry;
                try {
                    entry = row(line);
                } catch (IllegalArgumentException e) {
                    throw failure(number, e.getMessage());
                }
                if (byKey.putIfAbsent(entry.key(), entry) != null) {
                    throw failure(number, "duplicate entry " + entry.channel() + " "
                        + Names.quote(entry.entry()));
                }
                rows.put(number, entry);
            }
        }
        if (!header) {
            throw new IllegalArgumentException("Coverage list without the header "
                + Names.quote(HEADER));
        }
        for (Map.Entry<Integer, CoverageEntry> row : rows.entrySet()) {
            checkDelivery(row.getKey(), row.getValue(), delivered);
        }
        return new InterfaceCoverage(new ArrayList<>(rows.values()), byKey, delivered, source);
    }

    /**
     * The coverage list shipped with the server, the resource {@value #RESOURCE}.
     *
     * @throws IllegalStateException if the resource is missing
     * @throws IllegalArgumentException if it breaks a rule of {@link #load}
     */
    public static InterfaceCoverage production() {
        InputStream in = InterfaceCoverage.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing resource " + RESOURCE);
        }
        return load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Every row, in file order. */
    public List<CoverageEntry> entries() {
        return entries;
    }

    /** The stories of the {@code delivered-stories} line, e.g. {@code US1}; empty if none. */
    public Set<String> deliveredStories() {
        return deliveredStories;
    }

    /** The text of the {@code # source:} comment, e.g. the vendor pages; empty if none. */
    public Optional<String> source() {
        return source;
    }

    /**
     * The state of the entry on {@code line}; empty when the list has no such entry or no state
     * for that line (any line but 8.1 and 9.0).
     */
    public Optional<CoverageState> state(Channel channel, String entry, MajorMinor line) {
        Objects.requireNonNull(line, "line");
        return Optional.ofNullable(byKey.get(new CoverageKey(channel, entry)))
            .map(found -> found.states().get(line));
    }

    /**
     * Whether a call or part that sends {@code entries} is offered on {@code line}: true only
     * when every entry is {@link CoverageState.Reachable} there (research D-2, "gate per call").
     * An empty set is not offered.
     */
    public boolean offered(Set<CoverageKey> entries, MajorMinor line) {
        return !entries.isEmpty() && entries.stream().allMatch(key ->
            state(key.channel(), key.entry(), line)
                .filter(CoverageState.Reachable.class::isInstance).isPresent());
    }

    private static CoverageEntry row(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != COLUMNS) {
            throw new IllegalArgumentException("expected " + COLUMNS + " columns, found "
                + fields.length);
        }
        Map<MajorMinor, CoverageState> states = new HashMap<>();
        for (int column = 0; column < CoverageEntry.LINES.size(); column++) {
            MajorMinor version = CoverageEntry.LINES.get(column);
            String text = fields[5 + column];
            if (text.isEmpty()) {
                throw new IllegalArgumentException("missing state for " + version.text());
            }
            states.put(version, state(text));
        }
        return new CoverageEntry(
            value(Channel.class, fields[0], "channel"),
            fields[1],
            value(CoverageEntry.Kind.class, fields[2], "kind"),
            value(CoverageEntry.Area.class, fields[3], "area"),
            fields[4].equals(NONE) ? Optional.empty() : Optional.of(fields[4]),
            states,
            fields[7].equals(NONE) ? List.of() : List.of(fields[7].split("\\+", -1)));
    }

    /** {@code reachable:<tool>/<action>}, {@code covered:<tool>}, {@code planned}, ... */
    private static CoverageState state(String text) {
        int colon = text.indexOf(':');
        String word = colon < 0 ? text : text.substring(0, colon);
        String value = colon < 0 ? null : text.substring(colon + 1);
        try {
            return switch (word) {
                case "planned" -> withoutValue(value, new CoverageState.Planned());
                case "undocumented" -> withoutValue(value, new CoverageState.Undocumented());
                case "covered" -> new CoverageState.Covered(value);
                case "excluded" -> new CoverageState.Excluded(value);
                case "reachable" -> {
                    int slash = value == null ? -1 : value.indexOf('/');
                    if (slash < 0) {
                        throw new IllegalArgumentException("expected <tool>/<action>");
                    }
                    yield new CoverageState.Reachable(value.substring(0, slash),
                        value.substring(slash + 1));
                }
                default -> throw new IllegalArgumentException("unknown state word");
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid state " + Names.quote(text) + ": "
                + e.getMessage(), e);
        }
    }

    private static CoverageState withoutValue(String value, CoverageState state) {
        if (value != null) {
            throw new IllegalArgumentException("this state takes no value");
        }
        return state;
    }

    private static <E extends Enum<E>> E value(Class<E> type, String text, String name) {
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown " + name + " " + Names.quote(text), e);
        }
    }

    private static Set<String> stories(int number, String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return Set.of();
        }
        List<String> stories = List.of(stripped.split("[,\\s]+"));
        for (String story : stories) {
            if (!CoverageEntry.STORY.matcher(story).matches()) {
                throw failure(number, "unknown delivered story " + Names.quote(story));
            }
        }
        return Set.copyOf(stories);
    }

    /** The rules of the {@code waits} column that depend on {@code delivered-stories}. */
    private static void checkDelivery(int number, CoverageEntry entry, Set<String> delivered) {
        boolean storyDelivered = entry.story().filter(delivered::contains).isPresent();
        if (!entry.waits().isEmpty() && !storyDelivered) {
            throw failure(number, "waits " + String.join("+", entry.waits())
                + " on a row whose story is not delivered");
        }
        if (entry.planned() && storyDelivered && entry.waits().isEmpty()) {
            throw failure(number, "a planned row of the delivered story "
                + entry.story().orElseThrow() + " needs a waits value");
        }
    }

    private static IllegalArgumentException failure(int number, String message) {
        return new IllegalArgumentException("Coverage list line " + number + ": " + message);
    }
}
