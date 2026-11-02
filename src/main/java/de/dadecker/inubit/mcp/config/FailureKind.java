package de.dadecker.inubit.mcp.config;

/**
 * Why a secret read failed, for one name or for the whole call (feature 010, data-model.md →
 * ParameterStore port; mapped to error codes per research R-11). Feature 011 (research R-2) adds
 * the credential store kinds {@link #STORE_NOT_AVAILABLE} and {@link #TIMEOUT}.
 */
public enum FailureKind {
    NOT_FOUND,
    ACCESS_DENIED,
    UNREACHABLE,
    INVALID_VALUE,
    UNKNOWN_PROFILE,
    NO_CREDENTIALS,
    /** No usable credential store on this machine (feature 011). */
    STORE_NOT_AVAILABLE,
    /** The credential store did not answer in time, e.g. an unanswered access dialog. */
    TIMEOUT
}
