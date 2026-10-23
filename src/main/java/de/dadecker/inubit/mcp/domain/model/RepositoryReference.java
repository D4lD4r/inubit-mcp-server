package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * A reference from a workspace file to a repository file (research D-3, data-model.md).
 *
 * @param from the referring file
 * @param to   the full repository path, e.g. {@code /Root/jdoe/xsd/msg.xsd}, {@code %XX} escapes
 *             decoded
 * @param kind how the file names it
 */
public record RepositoryReference(WorkspacePath from, String to, Kind kind) {

    /** How a file names a repository file. */
    public enum Kind {
        /** An {@code inubitrepository:} URI. */
        URI,
        /**
         * A relative {@code href}/{@code schemaLocation} of an import or include in a repository
         * file, resolved against that file's repository path.
         */
        RELATIVE
    }

    public RepositoryReference {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
    }
}
