package de.dadecker.inubit.mcp.domain.model;

/**
 * The outcome of one audit record (data-model.md → AuditRecord).
 *
 * <p>{@link #PENDING} marks the {@code EXECUTE} record that is written and forced to disk
 * <em>before</em> the StartCLI call (research R-14, fail closed); the record with the final
 * outcome ({@link #EXECUTED} or {@link #FAILED}) follows the call with the same {@code auditId}.
 * {@link #PACKAGED} (feature 005, research D-10): the package of a package-only node was written;
 * nothing was sent to it.
 */
public enum AuditOutcome {
    CHALLENGE_ISSUED,
    PENDING,
    EXECUTED,
    REFUSED,
    FAILED,
    PACKAGED
}
