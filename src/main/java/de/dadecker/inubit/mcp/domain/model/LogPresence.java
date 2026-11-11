package de.dadecker.inubit.mcp.domain.model;

/** Whether a downloaded server log is known to exist on the server (feature 021, D-8). */
public enum LogPresence {
    PRESENT,
    /** Only for a 0-byte answer of a channel that cannot tell a missing file from an empty one. */
    UNKNOWN
}
