package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import java.util.Objects;
import java.util.Set;

/**
 * One part of a multi-part read (feature 022, research D-2 "Gate per call"): read over one
 * channel by sending {@code entries}. {@link VersionGate#admitParts} admits it or reports it
 * under {@code unavailable}.
 *
 * @param name one of {@link UnavailablePart#PARTS}, the name it is reported under
 */
public record Part(String name, Channel channel, Set<CoverageKey> entries) {

    public Part {
        if (!UnavailablePart.PARTS.contains(name)) {
            throw new IllegalArgumentException("unknown part " + Names.quote(name));
        }
        Objects.requireNonNull(channel, "channel");
        entries = Set.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Part " + name + " sends no entry");
        }
        if (entries.stream().anyMatch(key -> key.channel() != channel)) {
            throw new IllegalArgumentException("Part " + name + " sends an entry that is not on "
                + channel);
        }
    }
}
