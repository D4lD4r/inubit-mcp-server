package de.dadecker.inubit.mcp.domain.model;

/**
 * Normalized severity of a {@link LogEntry}. INUBIT 8.1 log rows have no severity field; it is
 * derived from the log type's success or status field (data-model.md → LogEntry, severity
 * table). {@link #OTHER} marks rows without such a field or with an unknown value.
 */
public enum Severity {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    OTHER
}
