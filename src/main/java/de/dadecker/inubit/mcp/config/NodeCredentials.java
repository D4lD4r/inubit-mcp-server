package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.Secret;
import java.util.Objects;
import java.util.Optional;

/**
 * Credentials of one server as read from its store (data-model.md → Credentials; feature 011:
 * built only inside {@code NodeSecrets} from read values, never at startup).
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

    /** True if username and password are both present. */
    public boolean complete() {
        return username.isPresent() && password.isPresent();
    }
}
