package de.dadecker.inubit.mcp.domain.model;

/**
 * The outcome of one audit record (data-model.md → AuditRecord).
 *
 * <p>{@link #PENDING} marks the {@code EXECUTE} record that is written and forced to disk
 * <em>before</em> the StartCLI call (research R-14, fail closed); the record with the final
 * outcome ({@link #EXECUTED} or {@link #FAILED}) follows the call with the same {@code auditId}.
 */
public enum AuditOutcome {
    CHALLENGE_ISSUED,
    PENDING,
    EXECUTED,
    REFUSED,
    FAILED
}
