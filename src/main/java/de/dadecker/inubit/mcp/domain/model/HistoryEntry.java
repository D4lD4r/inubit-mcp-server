package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One entry of the workspace history (data-model.md → History; a git commit, research D-1).
 *
 * @param commit  the abbreviated or full commit id (lower-case hex, 7–40 characters)
 * @param changes the changed files, an immutable copy
 */
public record HistoryEntry(String commit, String message, List<PathChange> changes) {

    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{7,40}$");

    public HistoryEntry {
        Objects.requireNonNull(commit, "commit");
        if (!COMMIT.matcher(commit).matches()) {
            throw new IllegalArgumentException("commit must be 7 to 40 lower-case hex digits");
        }
        Objects.requireNonNull(message, "message");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
}
