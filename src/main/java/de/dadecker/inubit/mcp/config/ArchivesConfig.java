package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code archives} block of a group as written in the YAML (feature 015, research R-15). It exists
 * only on groups: the opt-in is a decision per stage, so {@code archives} on a node or in
 * {@code defaults} is an unknown key.
 *
 * @param productionOptIn allows {@code export_archives} (archives with clear-text secrets) on a
 *                        group with {@code production: true}; independent of {@code write.*}
 *                        (FR-014); built-in {@code false}
 */
public record ArchivesConfig(Optional<Boolean> productionOptIn) {

    public static final ArchivesConfig EMPTY = new ArchivesConfig(Optional.empty());

    public ArchivesConfig {
        productionOptIn = Optionals.orEmpty(productionOptIn);
    }
}
