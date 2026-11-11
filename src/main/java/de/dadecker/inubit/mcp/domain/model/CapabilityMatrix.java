package de.dadecker.inubit.mcp.domain.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Declares per INUBIT version range whether each capability is supported, unverified or
 * unsupported (FR-003, research D-3, data-model.md → CapabilityMatrix).
 *
 * <p>A known version is looked up in the first entry whose range contains it; a version outside
 * every range and an unknown version use the declaration for unknown versions. A capability an
 * entry (or the unknown declaration) does not list is {@link SupportState#UNSUPPORTED}.
 * {@link SupportState#UNVERIFIED} is rejected for write capabilities (it means "runs with a
 * warning", which only reads may do).
 *
 * <p>An entry may declare channels per capability that replace the capability's own
 * {@link Capability#channels()}, and may be {@code cliOnly}: the node is reached through StartCLI
 * only (feature 012, research D-3). It may carry a note per capability that the refusal uses,
 * e.g. "not yet verified" (feature 020, research D-6; feature 021: the server-log reads).
 */
public final class CapabilityMatrix {

    /**
     * The states of one version range; capabilities not listed are unsupported. A capability
     * without declared {@code channels} uses its own channels.
     */
    public record Entry(VersionRange range, Map<Capability, SupportState> states,
        Map<Capability, Set<Channel>> channels, boolean cliOnly, Map<Capability, String> notes) {

        public Entry {
            Objects.requireNonNull(range, "range");
            states = checked(states);
            Map<Capability, Set<Channel>> copy = new EnumMap<>(Capability.class);
            channels.forEach((capability, declared) -> copy.put(capability, Set.copyOf(declared)));
            channels = Map.copyOf(copy);
            notes = Map.copyOf(notes);
        }

        /** An entry without notes. */
        public Entry(VersionRange range, Map<Capability, SupportState> states,
            Map<Capability, Set<Channel>> channels, boolean cliOnly) {
            this(range, states, channels, cliOnly, Map.of());
        }

        /** An entry without declared channels that is not CLI-only. */
        public Entry(VersionRange range, Map<Capability, SupportState> states) {
            this(range, states, Map.of(), false);
        }
    }

    private static final Map<Capability, SupportState> ALL_SUPPORTED = all(SupportState.SUPPORTED);

    /** Reads run with a warning, writes are refused. */
    private static final Map<Capability, SupportState> READS_UNVERIFIED = readsUnverified();

    /**
     * 6.1: StartCLI only. A capability becomes {@code SUPPORTED} only together with its recorded
     * fixtures (FR-005, research D-15); until then everything is refused.
     */
    private static final Entry V6_1 = new Entry(new VersionRange(6, 1, 6, 1),
        Map.of(Capability.MONITORING, SupportState.UNSUPPORTED,
            Capability.PROCESS_DIAGNOSIS, SupportState.UNSUPPORTED,
            Capability.PARTNER_MANAGEMENT, SupportState.UNSUPPORTED),
        Map.of(Capability.MONITORING, Set.of(Channel.CLI),
            Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)),
        true);

    /**
     * Feature 021 (FR-017, research D-6): each server log is refused on 8.1 and 9.0 until the
     * commit that adds its recording declares it {@code SUPPORTED} and drops its note.
     */
    private static final String NOT_YET_VERIFIED = "not yet verified";

    /** 8.1: everything except the server-log reads of feature 021 (not yet verified). */
    private static final Entry V8_1 = new Entry(new VersionRange(8, 1, 8, 1),
        with(with(ALL_SUPPORTED, Capability.TRACE_LOG_READ, SupportState.UNSUPPORTED),
            Capability.WEBSERVICE_LOG_READ, SupportState.UNSUPPORTED),
        Map.of(), false,
        Map.of(Capability.TRACE_LOG_READ, NOT_YET_VERIFIED,
            Capability.WEBSERVICE_LOG_READ, NOT_YET_VERIFIED));

    /**
     * 9.0: everything as on 8.1 except partner management, which runs with a warning until recorded
     * 9.0 responses are under contract test (feature 018, FR-016, research D-7); reading Queue
     * Manager entries included since its recorded 9.0 error entry (feature 020, FR-019, research
     * D-20); the server-log reads of feature 021 not yet verified, as on 8.1.
     */
    private static final Entry V9_0 = new Entry(new VersionRange(9, 0, 9, 0),
        with(with(with(ALL_SUPPORTED, Capability.PARTNER_MANAGEMENT, SupportState.UNVERIFIED),
            Capability.TRACE_LOG_READ, SupportState.UNSUPPORTED),
            Capability.WEBSERVICE_LOG_READ, SupportState.UNSUPPORTED),
        Map.of(), false,
        Map.of(Capability.TRACE_LOG_READ, NOT_YET_VERIFIED,
            Capability.WEBSERVICE_LOG_READ, NOT_YET_VERIFIED));

    /**
     * Unknown versions: reading Queue Manager entries and the server logs is refused, not run with
     * a warning.
     */
    private static final CapabilityMatrix PRODUCTION = new CapabilityMatrix(
        List.of(V6_1, V8_1, V9_0),
        with(with(with(READS_UNVERIFIED, Capability.QUEUE_ENTRY_READ, SupportState.UNSUPPORTED),
            Capability.TRACE_LOG_READ, SupportState.UNSUPPORTED),
            Capability.WEBSERVICE_LOG_READ, SupportState.UNSUPPORTED));

    /**
     * The version lines whose stored secret forms are verified as comparable across servers, so
     * that equal fingerprints mean equal values (feature 018, FR-012a, research D-5): 8.1 only.
     */
    public static final Set<MajorMinor> COMPARABLE_SECRET_LINES = Set.of(new MajorMinor(8, 1));

    private final List<Entry> entries;
    private final Map<Capability, SupportState> unknown;

    /**
     * A declaration other than {@link #production()} (e.g. a fictitious range in tests, US3).
     *
     * @param entries ordered; the first entry containing a version decides
     * @param unknown the states for unknown versions and known versions outside every range
     */
    public CapabilityMatrix(List<Entry> entries, Map<Capability, SupportState> unknown) {
        this.entries = List.copyOf(entries);
        this.unknown = checked(unknown);
    }

    /**
     * The production declaration of research D-3: 6.1.x CLI-only with every capability
     * unsupported; 8.1.x and (feature 017, research D-9) 9.0.x all supported, except partner
     * management on 9.0.x, which is unverified (feature 018); any other known version and unknown
     * versions: reads unverified, writes unsupported. Feature 020 (research D-6, D-20): reading
     * Queue Manager entries is supported on 8.1 and 9.0, on any other and on unknown versions
     * unsupported. Feature 021 (research D-6): downloading the trace log and the web service log
     * is unsupported everywhere, on 8.1 and 9.0 with the note "not yet verified".
     */
    public static CapabilityMatrix production() {
        return PRODUCTION;
    }

    /** The state of {@code capability} on {@code version}; empty means the version is unknown. */
    public SupportState state(Optional<InubitVersion> version, Capability capability) {
        Map<Capability, SupportState> states = entry(version).map(Entry::states).orElse(unknown);
        return states.getOrDefault(Objects.requireNonNull(capability, "capability"),
            SupportState.UNSUPPORTED);
    }

    /**
     * The channels {@code capability} uses on {@code version}: the entry's declaration, else the
     * capability's own channels (also for unknown versions).
     */
    public Set<Channel> channels(Optional<InubitVersion> version, Capability capability) {
        Objects.requireNonNull(capability, "capability");
        return entry(version).map(Entry::channels)
            .map(declared -> declared.get(capability))
            .orElse(capability.channels());
    }

    /**
     * The note of the entry containing {@code version} for {@code capability}, e.g. "not yet
     * verified"; empty for an unknown version and a version outside every range.
     */
    public Optional<String> note(Optional<InubitVersion> version, Capability capability) {
        Objects.requireNonNull(capability, "capability");
        return entry(version).map(e -> e.notes().get(capability));
    }

    /**
     * The version lines that declare {@code capability} {@code SUPPORTED} or carry a note for it
     * (supported once verified), in declaration order: "8.1", or "7.8–7.9" for a wider range.
     */
    public List<String> lines(Capability capability) {
        Objects.requireNonNull(capability, "capability");
        return entries.stream()
            .filter(e -> e.states().get(capability) == SupportState.SUPPORTED
                || e.notes().containsKey(capability))
            .map(e -> line(e.range()))
            .toList();
    }

    /** Whether {@code version} is reached through StartCLI only; an unknown version is not. */
    public boolean isCliOnly(Optional<InubitVersion> version) {
        return entry(version).map(Entry::cliOnly).orElse(false);
    }

    /**
     * The state of {@code capability} on a configured version line ({@code V8_1} = 8 and 1,
     * {@code V9_X} = 9 without minor). A line without minor gives no major.minor and is looked up
     * as an unknown version (FR-014a).
     */
    public SupportState stateOfLine(int major, OptionalInt minor, Capability capability) {
        Optional<InubitVersion> version = minor.isPresent()
            ? Optional.of(new InubitVersion(major, minor.getAsInt(), OptionalInt.empty(),
                major + "." + minor.getAsInt()))
            : Optional.empty();
        return state(version, capability);
    }

    /**
     * Whether an entry's range contains {@code version}; a version that only falls back to the
     * declaration for unknown versions is not declared (feature 017).
     */
    public boolean declares(MajorMinor version) {
        return entry(Optional.of(new InubitVersion(version.major(), version.minor(),
            OptionalInt.empty(), version.text()))).isPresent();
    }

    private Optional<Entry> entry(Optional<InubitVersion> version) {
        return version.flatMap(
            v -> entries.stream().filter(e -> e.range().contains(v)).findFirst());
    }

    private static String line(VersionRange range) {
        String from = range.fromMajor() + "." + range.fromMinor();
        String to = range.toMajor() + "." + range.toMinor();
        return from.equals(to) ? from : from + "–" + to;
    }

    private static Map<Capability, SupportState> with(Map<Capability, SupportState> states,
        Capability capability, SupportState state) {
        Map<Capability, SupportState> copy = new EnumMap<>(states);
        copy.put(capability, state);
        return copy;
    }

    private static Map<Capability, SupportState> checked(Map<Capability, SupportState> states) {
        states.forEach((capability, state) -> {
            if (capability.kind() == Capability.Kind.WRITE && state == SupportState.UNVERIFIED) {
                throw new IllegalArgumentException(
                    "UNVERIFIED is only allowed for read capabilities, not " + capability);
            }
        });
        return Map.copyOf(states);
    }

    private static Map<Capability, SupportState> all(SupportState state) {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, state);
        }
        return states;
    }

    private static Map<Capability, SupportState> readsUnverified() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, capability.kind() == Capability.Kind.READ
                ? SupportState.UNVERIFIED : SupportState.UNSUPPORTED);
        }
        return states;
    }
}
