package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One check-in of a diagram or module (data-model.md → InventoryDetail {@code versions}), from
 * {@code versionHistory.xml} of the CLI export with history (research R-11, S-6b).
 *
 * @param version        {@code versionNode}
 * @param checkinAt      {@code DateTime} ({@code dd.MM.yyyy HH:mm:ss}, local time
 *                       {@code Europe/Berlin}); absent if missing or unparseable
 * @param tags           the <em>current</em> tag assignment of this version (tags moved later
 *                       appear only on the version they are on now, S-6b)
 */
public record VersionEntry(int version, Optional<String> checkinUser, Optional<Instant> checkinAt,
    Optional<String> checkinComment, Optional<String> userComment, List<String> tags) {

    public VersionEntry {
        checkinUser = checkinUser == null ? Optional.empty() : checkinUser;
        checkinAt = checkinAt == null ? Optional.empty() : checkinAt;
        checkinComment = checkinComment == null ? Optional.empty() : checkinComment;
        userComment = userComment == null ? Optional.empty() : userComment;
        tags = List.copyOf(tags);
    }
}
