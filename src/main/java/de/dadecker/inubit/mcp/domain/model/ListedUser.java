package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * One user of INUBIT's login list ({@code GET /user/users}, feature 022, US2, research D-20): its
 * login and INUBIT's user type. It has no other field, so the list's e-mail and names cannot be
 * carried (FR-011, SC-005).
 *
 * @param login the login ({@code id})
 * @param type  INUBIT's user type ({@code type}); empty if the list gives none
 */
public record ListedUser(String login, String type) {

    public ListedUser {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(type, "type");
    }
}
