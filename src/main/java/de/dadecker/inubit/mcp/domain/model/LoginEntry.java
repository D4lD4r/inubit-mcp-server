package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One logged-in user (data-model.md → LoginEntry, US2 AS4).
 *
 * @param login the login name
 * @param since the login time as INUBIT shows it; empty if it shows none
 */
public record LoginEntry(String login, Optional<String> since) {

    public LoginEntry {
        Objects.requireNonNull(login, "login");
        since = since == null ? Optional.empty() : since;
    }
}
