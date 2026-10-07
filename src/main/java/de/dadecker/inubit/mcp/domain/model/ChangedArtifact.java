package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One artifact of a {@link ChangeSet} (data-model.md → ChangedArtifact).
 *
 * @param paths its current workspace files (workspace-relative, sorted)
 * @param base  the last server state of its own files ({@code MODIFIED}); empty for a
 *              {@code NEW} artifact, whose base is the scope's (review M4)
 */
public record ChangedArtifact(ArtifactRef ref, Kind kind, List<String> paths,
    Optional<String> base) {

    /** Created by the import, or an existing artifact that changed. */
    public enum Kind {
        NEW,
        MODIFIED
    }

    public ChangedArtifact {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(kind, "kind");
        paths = List.copyOf(paths);
        base = base == null ? Optional.empty() : base;
        if (kind == Kind.MODIFIED && base.isEmpty()) {
            throw new IllegalArgumentException("A modified artifact has a base");
        }
    }

    /** The artifact's name. */
    public String name() {
        return ref.name();
    }
}
