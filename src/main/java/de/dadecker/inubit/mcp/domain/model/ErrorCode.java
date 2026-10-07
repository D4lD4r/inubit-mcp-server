package de.dadecker.inubit.mcp.domain.model;

/**
 * Error catalogue of {@link ToolError} (FR-027, data-model.md → ToolError).
 *
 * <p>Feature 004 (research D-19) adds the codes of the development tools:
 * {@link #NOT_DEVELOPMENT} (the node is not a development stage), {@link #CONFLICT} (the server
 * changed since the base export, or a workflow is in Workbench edit mode),
 * {@link #SECRET_UNRESOLVED} (a secret placeholder has no value on the target),
 * {@link #IMPORT_FAILED} (StartCLI's import failed or its protocol did not match),
 * {@link #VERIFY_MISMATCH} (the re-export differs from the intended state) and
 * {@link #E2E_FORBIDDEN} (end-to-end tests are not allowed on the node).
 *
 * <p>Feature 005 (research D-15) adds the codes of the deployments along the stage chain:
 * {@link #CHAIN_VIOLATION}, {@link #SOURCE_INCONSISTENT} and {@link #DEPLOY_LOCKED}.
 */
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
    NOT_DEVELOPMENT,
    CONFLICT,
    SECRET_UNRESOLVED,
    IMPORT_FAILED,
    VERIFY_MISMATCH,
    E2E_FORBIDDEN,
    /**
     * The target does not receive deployments from the requested source: it has no
     * {@code deploy} record, or a target was named that is not a group of the stage chain
     * (feature 005, research D-2).
     */
    CHAIN_VIOLATION,
    /**
     * The nodes of the source group do not hold the same release: the rendered tag exports
     * differ (feature 005, research D-4).
     */
    SOURCE_INCONSISTENT,
    /**
     * Another deployment into the same target group is running, in this or another server
     * process of the profile (feature 005, research D-11).
     */
    DEPLOY_LOCKED,
    INTERNAL
}
