package de.dadecker.inubit.mcp.domain.model;

/**
 * Whether end-to-end tests may be sent to a node (feature 004, research D-1, D-17;
 * configuration key {@code e2eTests}): freely, after a server-side confirmation, or not at all
 * (the default, and always on production).
 */
public enum E2ePolicy {
    FREE,
    CONFIRM,
    FORBIDDEN
}
