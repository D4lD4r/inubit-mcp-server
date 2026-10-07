package de.dadecker.inubit.mcp.domain.model;

/**
 * How a target group of the stage chain receives a release (feature 005, research D-2, D-10).
 */
public enum DeployMode {
    /** The server imports the release node by node (the default). */
    EXECUTE,
    /**
     * The server sends nothing to the group: it writes owner-only import packages that an
     * operator applies (production).
     */
    PACKAGE_ONLY
}
