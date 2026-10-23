package de.dadecker.inubit.mcp.config;

/**
 * Why a Parameter Store read failed, for one name or for the whole call (feature 010,
 * data-model.md → ParameterStore port; mapped to error codes per research R-11).
 */
public enum FailureKind {
    NOT_FOUND,
    ACCESS_DENIED,
    UNREACHABLE,
    INVALID_VALUE,
    UNKNOWN_PROFILE,
    NO_CREDENTIALS
}
