package de.dadecker.inubit.mcp.domain.model.node;

import java.util.Arrays;
import java.util.Optional;

/** The status of a cluster member (research D-7). */
public enum NodeStatus {
    ACTIVE,
    MAINTENANCE,
    OFFLINE,
    /** The answer carries no status or one not listed above. */
    UNKNOWN;

    /**
     * The status for the text INUBIT returned: {@code ACTIVE}, {@code MAINTENANCE} and
     * {@code OFFLINE} by their exact name, anything else (also no text) {@link #UNKNOWN}.
     */
    public static NodeStatus of(Optional<String> raw) {
        return raw.flatMap(text -> Arrays.stream(values())
                .filter(status -> status != UNKNOWN && status.name().equals(text))
                .findFirst())
            .orElse(UNKNOWN);
    }
}
