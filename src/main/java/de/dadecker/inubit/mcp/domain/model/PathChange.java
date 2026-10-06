package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * One changed file of a history entry (data-model.md → History).
 *
 * @param path workspace-relative, {@code /}-separated (e.g.
 *             {@code dev/OWNERS/workflows/GRP-01/Workflow-0001.xml})
 */
public record PathChange(String path, Kind kind) {

    /** How the file changed. */
    public enum Kind {
        ADDED,
        MODIFIED,
        DELETED
    }

    public PathChange {
        requireWorkspacePath(path, "path");
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * Checks a workspace-relative path: not blank, not absolute, {@code /} as separator.
     *
     * @throws IllegalArgumentException naming {@code field}
     */
    static void requireWorkspacePath(String path, String field) {
        Objects.requireNonNull(path, field);
        if (path.isBlank() || path.startsWith("/") || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(field
                + " must be a workspace-relative path with '/' as separator");
        }
    }
}
