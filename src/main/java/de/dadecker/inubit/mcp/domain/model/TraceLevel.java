package de.dadecker.inubit.mcp.domain.model;

/**
 * The server-wide trace levels of {@code trace -l <level>} (feature 022, research D-22): the vendor
 * list {@code error|warn|info|debug}. 8.1.17 has no {@code trace --help} to record it from (T054);
 * {@code debug} and {@code info} were recorded with {@code trace -l} and as {@code TracingLevel}
 * of {@code /system/info}.
 */
public enum TraceLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG
}
