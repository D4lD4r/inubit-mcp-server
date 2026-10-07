package de.dadecker.inubit.mcp.domain.model;

/**
 * The class of one release artifact on one target node (feature 005, FR-010, research D-5).
 */
public enum ArtifactClass {
    /** Not on the target: imported. */
    NEW,
    /** Equal reviewed content: not imported (no new version, SC-003). */
    UNCHANGED,
    /** A workflow that differs only inside {@code StyleSheet} elements: imported. */
    LAYOUT_ONLY,
    /** Any other difference: imported. */
    CHANGED,
    /** Matches a {@code deploy.exclude} rule, is key material or outside the owner's area. */
    EXCLUDED,
    /** In a release diagram group on the target but not in the release: never touched. */
    ONLY_ON_TARGET;

    /** True if the class is imported by a deployment. */
    public boolean deployed() {
        return this == NEW || this == LAYOUT_ONLY || this == CHANGED;
    }
}
