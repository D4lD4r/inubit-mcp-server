package de.dadecker.inubit.mcp.domain.model;

/**
 * Whether an INUBIT owner is a user or a user group (feature 004, research D-21, D-25): StartCLI
 * imports address them with {@code --importUser} or {@code --importUserGroup}. Configured per
 * owner in the profile ({@code owners.<name>}) or found in INUBIT's user list.
 */
public enum OwnerKind {
    USER,
    USER_GROUP
}
