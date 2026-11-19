package de.dadecker.inubit.mcp.domain.model;

/**
 * How many version parts the client/server patch comparison looks at, declared per version range
 * (feature 025 research D-3, FR-003).
 */
public enum PatchDepth {
    /** major.minor.patch; a fourth part is ignored (every line except 7.4). */
    THREE,
    /** major.minor.patch, then the fourth part (build) when the first three are equal (7.4). */
    FOUR
}
