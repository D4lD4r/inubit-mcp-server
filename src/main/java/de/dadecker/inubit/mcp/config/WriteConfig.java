package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/** {@code write} block as written in the YAML; empty values are inherited. */
public record WriteConfig(
    Optional<Boolean> enabled,
    Optional<Boolean> productionOptIn,
    Optional<ConfirmationMode> confirmation) {

    public static final WriteConfig EMPTY =
        new WriteConfig(Optional.empty(), Optional.empty(), Optional.empty());

    public WriteConfig {
        enabled = Optionals.orEmpty(enabled);
        productionOptIn = Optionals.orEmpty(productionOptIn);
        confirmation = Optionals.orEmpty(confirmation);
    }
}
