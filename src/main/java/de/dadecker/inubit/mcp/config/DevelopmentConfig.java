package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code development} block as written in the YAML (feature 004, research D-1); empty values are
 * inherited node → group → {@code defaults} → built-in ({@code enabled: false},
 * {@code confirmation: SERVER}).
 */
public record DevelopmentConfig(Optional<Boolean> enabled,
    Optional<ConfirmationMode> confirmation) {

    public static final DevelopmentConfig EMPTY =
        new DevelopmentConfig(Optional.empty(), Optional.empty());

    public DevelopmentConfig {
        enabled = Optionals.orEmpty(enabled);
        confirmation = Optionals.orEmpty(confirmation);
    }
}
