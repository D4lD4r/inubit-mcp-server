package de.dadecker.inubit.mcp.domain.model;

/**
 * Whether a capability runs on an INUBIT version (FR-003, data-model.md → SupportState).
 * {@link #UNVERIFIED} is only meaningful for read capabilities: they run with the 8.1 adapters and
 * an "unverified version" warning.
 */
public enum SupportState {
    SUPPORTED,
    UNVERIFIED,
    UNSUPPORTED
}
