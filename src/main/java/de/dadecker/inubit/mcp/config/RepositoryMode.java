package de.dadecker.inubit.mcp.config;

/**
 * {@code development.repository} (feature 008, research D-1): whether the development tools may
 * write repository files of the node. Effective only on a development stage (D-2).
 */
public enum RepositoryMode {
    READ_ONLY,
    WRITE
}
