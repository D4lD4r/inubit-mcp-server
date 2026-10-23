package de.dadecker.inubit.mcp.domain.model;

/**
 * Whether the credentials of a node are available for a call (feature 010, research R-9,
 * data-model.md → CredentialState); carries no value.
 */
public enum CredentialState {
    /** The credentials are held; the node can be called. */
    READY,
    /** The first fetch from AWS Parameter Store is still running; it continues. */
    PENDING
}
