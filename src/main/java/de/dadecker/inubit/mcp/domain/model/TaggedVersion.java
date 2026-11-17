package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * One version of a diagram or module that carries a tag (feature 022, research D-15), from the
 * version history: what a tag-list backup records before a tag is removed or deleted.
 *
 * @param kind    whether the object is a diagram or a module
 * @param name    the name of the diagram or module
 * @param version the version that carries the tag
 */
public record TaggedVersion(Kind kind, String name, int version) {

    /** The kind of a tagged object in the version history. */
    public enum Kind {
        WORKFLOW,
        MODULE
    }

    public TaggedVersion {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
    }
}
