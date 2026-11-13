package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One user of a user listing (data-model.md → UserSummary, US2). It has no e-mail, name or other
 * personal field by construction, so a listing cannot carry one (FR-011, US2 AS5).
 *
 * @param login            the login name
 * @param group            the user's group
 * @param additionalGroups the additional groups
 * @param roles            the roles
 * @param accountState     the account state INUBIT reports (e.g. locked); empty if it reports
 *                         none
 */
public record UserSummary(String login, String group, List<String> additionalGroups,
    List<String> roles, Optional<String> accountState) {

    public UserSummary {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(group, "group");
        additionalGroups = List.copyOf(additionalGroups);
        roles = List.copyOf(roles);
        accountState = accountState == null ? Optional.empty() : accountState;
    }
}
