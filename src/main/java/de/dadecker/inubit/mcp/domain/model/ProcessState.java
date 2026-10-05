package de.dadecker.inubit.mcp.domain.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The normalized state of a Queue Manager entry (data-model.md → ProcessInstance, state table,
 * spike S-4). All Queue Manager entries are non-final in INUBIT's sense (finished instances leave
 * the queue); {@link #NON_FINAL} holds the states in which an instance is still expected to
 * progress on its own and that therefore count for {@code hanging} (FR-010). {@link #ERROR}
 * waits for a restart or kill and is never hanging; {@link #OTHER} is unknown and never hanging.
 */
public enum ProcessState {
    ERROR,
    ACTIVE,
    WAITING,
    QUEUED,
    OTHER;

    /** The states that count for {@code hanging} (FR-010). */
    public static final Set<ProcessState> NON_FINAL =
        Set.copyOf(EnumSet.of(ACTIVE, WAITING, QUEUED));

    private static final Map<String, ProcessState> BY_RAW = Map.of(
        "Error", ERROR,
        "Waiting", WAITING,
        "Retry", WAITING,
        "Queued", QUEUED,
        "Processing", ACTIVE);

    /** The INUBIT {@code status} values of this state, in the order of the state table. */
    public List<String> rawValues() {
        return switch (this) {
            case ERROR -> List.of("Error");
            case WAITING -> List.of("Waiting", "Retry");
            case QUEUED -> List.of("Queued");
            case ACTIVE -> List.of("Processing");
            case OTHER -> List.of();
        };
    }

    /** The state of a raw INUBIT value (case-sensitive, as INUBIT); unknown values are OTHER. */
    public static ProcessState fromRaw(String rawState) {
        return rawState == null ? OTHER : BY_RAW.getOrDefault(rawState, OTHER);
    }

    /** True if an instance in this state is hanging once it exceeds the threshold. */
    public boolean nonFinal() {
        return NON_FINAL.contains(this);
    }
}
