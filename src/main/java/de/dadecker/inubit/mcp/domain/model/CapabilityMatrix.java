package de.dadecker.inubit.mcp.domain.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Declares per INUBIT version range whether each capability is supported, unverified or
 * unsupported (FR-003, research D-3, data-model.md → CapabilityMatrix).
 *
 * <p>A known version is looked up in the first entry whose range contains it; a version outside
 * every range and an unknown version use the declaration for unknown versions. A capability an
 * entry (or the unknown declaration) does not list is {@link SupportState#UNSUPPORTED}.
 * {@link SupportState#UNVERIFIED} is rejected for write capabilities (it means "runs with a
 * warning", which only reads may do).
 */
public final class CapabilityMatrix {

    /** The states of one version range; capabilities not listed are unsupported. */
    public record Entry(VersionRange range, Map<Capability, SupportState> states) {

        public Entry {
            Objects.requireNonNull(range, "range");
            states = checked(states);
        }
    }

    private static final Map<Capability, SupportState> ALL_SUPPORTED = all(SupportState.SUPPORTED);

    /** Reads run with a warning, writes are refused. */
    private static final Map<Capability, SupportState> READS_UNVERIFIED = readsUnverified();

    private static final CapabilityMatrix PRODUCTION = new CapabilityMatrix(
        List.of(new Entry(new VersionRange(8, 1, 8, 1), ALL_SUPPORTED),
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
     * The production declaration of research D-3: 8.1.x all supported; 9.0.x, any other known
     * version and unknown versions: reads unverified, writes unsupported.
     */
    public static CapabilityMatrix production() {
        return PRODUCTION;
    }

    /** The state of {@code capability} on {@code version}; empty means the version is unknown. */
    public SupportState state(Optional<InubitVersion> version, Capability capability) {
        Map<Capability, SupportState> states = version
            .flatMap(v -> entries.stream().filter(e -> e.range().contains(v)).findFirst())
            .map(Entry::states)
            .orElse(unknown);
        return states.getOrDefault(Objects.requireNonNull(capability, "capability"),
            SupportState.UNSUPPORTED);
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
