package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * One version of a diagram or module that carries a tag, from the version history, as the live
 * check of feature 022 T055 ({@code ManageTagsLiveTestSupport}) derives it. Test code only: tag
 * removal is excluded after T055, so no production code records tagged versions (research D-18).
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
