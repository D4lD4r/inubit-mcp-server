package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code updateCheck} block (feature 016, contracts/configuration-delta.md): {@code enabled:
 * false} turns the check for a newer release off; absent means on.
 */
public record UpdateCheckConfig(Optional<Boolean> enabled) {

    public static final UpdateCheckConfig DEFAULT = new UpdateCheckConfig(Optional.empty());

    public UpdateCheckConfig {
        enabled = Optionals.orEmpty(enabled);
    }

    /** Whether the profile leaves the check on. */
    public boolean on() {
        return enabled.orElse(true);
    }
}
