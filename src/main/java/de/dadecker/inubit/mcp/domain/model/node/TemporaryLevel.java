package de.dadecker.inubit.mcp.domain.model.node;

/**
 * The levels a logger may be raised to for a while (FR-041, research D-11). Only these names
 * reach StartCLI, so the fallback of the server's level parsing is never used.
 */
public enum TemporaryLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
