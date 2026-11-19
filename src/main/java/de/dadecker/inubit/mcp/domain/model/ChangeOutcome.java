package de.dadecker.inubit.mcp.domain.model;

/**
 * The outcome of a confirmed change (feature 022, data-model.md → ChangeResult). Refusals before
 * execution are tool errors, not outcomes.
 */
public enum ChangeOutcome {
    /** INUBIT accepted the change. */
    EXECUTED,
    /** The requested state was already in place or the affected set was empty; nothing was sent. */
    NOTHING_TO_DO,
    /** INUBIT did not accept the change, or sending it failed. */
    FAILED
}
