package de.dadecker.inubit.mcp.domain.model;

/**
 * Whether a capability runs on an INUBIT version (FR-003, data-model.md → SupportState).
 * {@link #UNVERIFIED} is only meaningful for read capabilities: they run with an "unverified
 * version" warning and the adapters of the version's entry, the 8.1 adapters for a version without
 * own adapters and the StartCLI adapters on a CLI-only version (feature 025, FR-030).
 */
public enum SupportState {
    SUPPORTED,
    UNVERIFIED,
    UNSUPPORTED
}
