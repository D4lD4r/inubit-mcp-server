package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * The key of a coverage list row (feature 022, data-model.md → CoverageEntry): the channel and the
 * entry as the vendor writes it, e.g. {@code CLI} + {@code license -td} or {@code REST} +
 * {@code DELETE /diagrams/{name}}.
 */
public record CoverageKey(Channel channel, String entry) {

    public CoverageKey {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(entry, "entry");
    }
}
