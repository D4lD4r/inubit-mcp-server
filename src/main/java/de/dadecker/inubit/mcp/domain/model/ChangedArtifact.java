package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One artifact of a {@link ChangeSet} (data-model.md → ChangedArtifact).
 *
 * @param paths its current workspace files (workspace-relative, sorted)
 * @param base  the last server state of its own files ({@code MODIFIED}); empty for a
 *              {@code NEW} or {@code EXISTING} artifact, whose base is the scope's (review M4)
 */
public record ChangedArtifact(ArtifactRef ref, Kind kind, List<String> paths,
    Optional<String> base) {

    /** Created by the import, or an existing artifact that changed. */
    public enum Kind {
        NEW,
        MODIFIED,
        /**
         * New in the workspace (no server state), but on the server already with other content,
         * e.g. created by an import that was rolled back (0.4.2): the import updates it as a new
         * version.
         */
        EXISTING
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

    /** True if the artifact is on the server before the import ({@code MODIFIED}, {@code EXISTING}). */
    public boolean onServer() {
        return kind != Kind.NEW;
    }

    /** This new artifact as one the server has already ({@link Kind#EXISTING}). */
    public ChangedArtifact existing() {
        return new ChangedArtifact(ref, Kind.EXISTING, paths, base);
    }

    /** The artifact's name. */
    public String name() {
        return ref.name();
    }
}
