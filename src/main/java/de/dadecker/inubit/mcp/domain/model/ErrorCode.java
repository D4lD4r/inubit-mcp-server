package de.dadecker.inubit.mcp.domain.model;

/** Error catalogue of {@link ToolError} (FR-027, data-model.md → ToolError). */
public enum ErrorCode {
    TARGET_UNKNOWN,
    UNREACHABLE,
    TIMEOUT,
    TLS_ERROR,
    AUTH_FAILED,
    FORBIDDEN,
    MAINTENANCE_MODE,
    NOT_FOUND,
    INVALID_INPUT,
    CLI_UNAVAILABLE,
    UNEXPECTED_RESPONSE,
    WRITE_DISABLED,
    PRODUCTION_PROTECTED,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_INVALID,
    PRECONDITION_FAILED,
    UNSUPPORTED_VERSION,
    NOT_CONFIGURED,
    INTERNAL
}
