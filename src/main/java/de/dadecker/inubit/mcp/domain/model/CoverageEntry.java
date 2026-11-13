package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One row of the coverage list (feature 022, research D-2, data-model.md → CoverageEntry): an
 * entry of the StartCLI or REST interface, its state per version line, and what a still
 * {@code planned} row of a delivered story waits for. The rules that depend on the
 * {@code delivered-stories} line are checked by {@link InterfaceCoverage#load}.
 */
public record CoverageEntry(Channel channel, String entry, Kind kind, Area area,
    Optional<String> story, Map<MajorMinor, CoverageState> states, List<String> waits) {

    /** The version lines every row has a state for, in column order: {@code v8_1}, {@code v9_0}. */
    public static final List<MajorMinor> LINES =
        List.of(new MajorMinor(8, 1), new MajorMinor(9, 0));

    /**
     * The stories of the spec, {@code US1} … {@code US7}: the values of {@code story} and of the
     * {@code delivered-stories} line.
     */
    public static final List<String> STORIES =
        List.of("US1", "US2", "US3", "US4", "US5", "US6", "US7");

    /** {@code L-1} … {@code L-18} (a live check) or {@code 9.0-pin}. */
    private static final Pattern WAITS_ITEM = Pattern.compile("L-([1-9]|1[0-8])|9\\.0-pin");

    /** What the entry does on the INUBIT side. */
    public enum Kind {
        READ,
        WRITE,
        EXPORT
    }

    /** The area of the spec Background table: A–E, the portal functions, the server start. */
    public enum Area {
        A,
        B,
        C,
        D,
        E,
        PORTAL,
        START
    }

    public CoverageEntry {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(story, "story");
        if (channel != Channel.CLI && channel != Channel.REST) {
            throw new IllegalArgumentException("Channel must be CLI or REST: " + channel);
        }
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("Empty entry");
        }
        story.ifPresent(s -> {
            if (!STORIES.contains(s)) {
                throw new IllegalArgumentException("Unknown story " + Names.quote(s));
            }
        });
        states = Map.copyOf(states);
        if (!states.keySet().equals(Set.copyOf(LINES))) {
            throw new IllegalArgumentException("States must be given for exactly 8.1 and 9.0");
        }
        waits = List.copyOf(waits);
        for (String item : waits) {
            if (!WAITS_ITEM.matcher(item).matches()) {
                throw new IllegalArgumentException("Unknown waits value " + Names.quote(item));
            }
        }
        if (isPlanned(states) && story.isEmpty()) {
            throw new IllegalArgumentException("A planned entry needs a story");
        }
        if (!waits.isEmpty() && !isPlanned(states)) {
            throw new IllegalArgumentException("Only a planned entry may carry waits");
        }
    }

    /** The key ({@code channel}, {@code entry}). */
    public CoverageKey key() {
        return new CoverageKey(channel, entry);
    }

    /** Whether the entry is {@code planned} on at least one version line. */
    public boolean planned() {
        return isPlanned(states);
    }

    private static boolean isPlanned(Map<MajorMinor, CoverageState> states) {
        return states.values().stream().anyMatch(CoverageState.Planned.class::isInstance);
    }
}
