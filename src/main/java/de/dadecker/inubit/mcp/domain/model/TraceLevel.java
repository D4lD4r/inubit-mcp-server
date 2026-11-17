package de.dadecker.inubit.mcp.domain.model;

/**
 * The server-wide trace levels of {@code trace -l <level>} (feature 022, research D-22). The list
 * is the reconstructed vendor list {@code error|warn|info|debug} until the live check T054 (L-3)
 * records it from {@code trace --help}.
 */
public enum TraceLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG
}
