package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.infra.Secret;
import java.util.Objects;

/**
 * The optional basic authentication of the SOAP end-to-end tests of a node (feature 004, research
 * D-25 H8): username and password with the name they were read from (feature 011: moved from
 * {@code CredentialResolver}).
 */
public record E2eCredentials(SourcedValue<String> username, SourcedValue<Secret> password) {

    public E2eCredentials {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
    }
}
