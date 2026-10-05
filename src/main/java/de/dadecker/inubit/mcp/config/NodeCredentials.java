package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.Secret;
import java.util.Objects;
import java.util.Optional;

/**
 * Credentials of one server, resolved from environment variables (data-model.md → Credentials).
 * Missing values are empty; {@link CredentialResolution#errors()} explains which variables to set.
 *
 * @param basicAuthToken {@code base64(username:password)}, present if both are
 */
public record NodeCredentials(
    NodeId node,
    Optional<SourcedValue<String>> username,
    Optional<SourcedValue<Secret>> password,
    Optional<SourcedValue<Secret>> trustStorePassword,
    Optional<Secret> basicAuthToken) {

    public NodeCredentials {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(trustStorePassword, "trustStorePassword");
        Objects.requireNonNull(basicAuthToken, "basicAuthToken");
    }

    /** True if username and password are both resolved. */
    public boolean complete() {
        return username.isPresent() && password.isPresent();
    }
}
