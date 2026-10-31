package de.dadecker.inubit.mcp.domain.model;

/**
 * How a node is reached (research D-13, data-model.md → NodeAccess): over REST and StartCLI, or
 * through StartCLI only (a {@code cliOnly} entry of the {@link CapabilityMatrix}).
 */
public enum NodeAccess {
    REST_AND_CLI,
    CLI_ONLY
}
