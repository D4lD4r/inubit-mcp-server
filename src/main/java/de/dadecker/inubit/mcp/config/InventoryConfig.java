package de.dadecker.inubit.mcp.config;

import java.time.Duration;
import java.util.Optional;

/** {@code inventory} block as written in the YAML; empty values are inherited. */
public record InventoryConfig(Optional<String> owner, Optional<Duration> cacheTtl) {

    public static final InventoryConfig EMPTY =
        new InventoryConfig(Optional.empty(), Optional.empty());

    public InventoryConfig {
        owner = Optionals.orEmpty(owner);
        cacheTtl = Optionals.orEmpty(cacheTtl);
    }
}
