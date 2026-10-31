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
 * only (feature 011, research D-3).
 */
public final class CapabilityMatrix {

    /**
     * The states of one version range; capabilities not listed are unsupported. A capability
     * without declared {@code channels} uses its own channels.
     */
    public record Entry(VersionRange range, Map<Capability, SupportState> states,
        Map<Capability, Set<Channel>> channels, boolean cliOnly) {

        public Entry {
            Objects.requireNonNull(range, "range");
            states = checked(states);
            Map<Capability, Set<Channel>> copy = new EnumMap<>(Capability.class);
            channels.forEach((capability, declared) -> copy.put(capability, Set.copyOf(declared)));
            channels = Map.copyOf(copy);
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
            Capability.PROCESS_DIAGNOSIS, SupportState.UNSUPPORTED),
        Map.of(Capability.MONITORING, Set.of(Channel.CLI),
            Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)),
        true);

    private static final CapabilityMatrix PRODUCTION = new CapabilityMatrix(
        List.of(V6_1, new Entry(new VersionRange(8, 1, 8, 1), ALL_SUPPORTED),
            new Entry(new VersionRange(9, 0, 9, 0), READS_UNVERIFIED)),
        READS_UNVERIFIED);

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
     * unsupported; 8.1.x all supported; 9.0.x, any other known version and unknown versions: reads
     * unverified, writes unsupported.
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

    private Optional<Entry> entry(Optional<InubitVersion> version) {
        return version.flatMap(
            v -> entries.stream().filter(e -> e.range().contains(v)).findFirst());
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
