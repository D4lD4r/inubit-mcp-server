package de.dadecker.inubit.mcp.domain.model;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The effective development settings of one node (feature 004, research D-1, data-model.md →
 * DevelopmentPolicy), as the development guard and the writing services need them.
 *
 * @param production      the node's group is classified {@code production: true} (never
 *                        together with {@code enabled} after the startup validation)
 * @param enabled         {@code development.enabled}
 * @param confirmation    {@code development.confirmation}: two-step on the server (default) or
 *                        left to the client
 * @param confirmationTtl how long a confirmation code is valid
 * @param e2eTests        {@code e2eTests}
 * @param soapBaseUrl     {@code e2e.soap.baseUrl}, if set
 */
public record DevelopmentPolicy(NodeId node, boolean production, boolean enabled,
    WritePolicy.Confirmation confirmation, Duration confirmationTtl, E2ePolicy e2eTests,
    Optional<URI> soapBaseUrl) {

    public DevelopmentPolicy {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(confirmationTtl, "confirmationTtl");
        Objects.requireNonNull(e2eTests, "e2eTests");
        soapBaseUrl = soapBaseUrl == null ? Optional.empty() : soapBaseUrl;
    }
}
