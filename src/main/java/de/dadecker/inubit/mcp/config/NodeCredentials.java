package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.Secret;
import java.util.Objects;
import java.util.Optional;

/**
 * Credentials of one server, resolved from environment variables (data-model.md → Credentials).
 * Missing values are empty; {@link CredentialResolution#errors()} explains which variables to set.
 * A node whose secrets come from AWS Parameter Store gets {@link #deferred(NodeId)} credentials at
 * startup: no values, fetched on first use (feature 010, data-model.md → NodeCredentials).
 *
 * @param basicAuthToken {@code base64(username:password)}, present if both are
 * @param deferred       true for a node whose values are fetched on first use; all values empty
 */
public record NodeCredentials(
    NodeId node,
    Optional<SourcedValue<String>> username,
    Optional<SourcedValue<Secret>> password,
    Optional<SourcedValue<Secret>> trustStorePassword,
    Optional<Secret> basicAuthToken,
    boolean deferred) {

    public NodeCredentials {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(trustStorePassword, "trustStorePassword");
        Objects.requireNonNull(basicAuthToken, "basicAuthToken");
        if (deferred && (username.isPresent() || password.isPresent()
            || trustStorePassword.isPresent() || basicAuthToken.isPresent())) {
            throw new IllegalArgumentException("Deferred credentials hold no values");
        }
    }

    /** Credentials resolved at startup (environment variables). */
    public NodeCredentials(NodeId node, Optional<SourcedValue<String>> username,
        Optional<SourcedValue<Secret>> password, Optional<SourcedValue<Secret>> trustStorePassword,
        Optional<Secret> basicAuthToken) {
        this(node, username, password, trustStorePassword, basicAuthToken, false);
    }

    /** The credentials of a node whose values are fetched on first use. */
    public static NodeCredentials deferred(NodeId node) {
        return new NodeCredentials(node, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), true);
    }

    /** True if username and password are both resolved (never for deferred credentials). */
    public boolean complete() {
        return username.isPresent() && password.isPresent();
    }
}
