package de.dadecker.inubit.mcp.domain.model;

import java.net.URI;
import java.time.Duration;
import java.util.List;
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
 * @param repositoryWrite the effective {@code development.repository} of feature 008
 *                        (research D-2): repository files of the node may be written
 * @param warnings        feature 009 (research D-5): the warnings of the version gate's
 *                        admission, for the result of the call (empty in the configuration)
 */
public record DevelopmentPolicy(NodeId node, boolean production, boolean enabled,
    WritePolicy.Confirmation confirmation, Duration confirmationTtl, E2ePolicy e2eTests,
    Optional<URI> soapBaseUrl, boolean repositoryWrite, List<String> warnings) {

    public DevelopmentPolicy {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(confirmationTtl, "confirmationTtl");
        Objects.requireNonNull(e2eTests, "e2eTests");
        soapBaseUrl = soapBaseUrl == null ? Optional.empty() : soapBaseUrl;
        warnings = List.copyOf(warnings);
    }

    /** The settings as configured, without warnings. */
    public DevelopmentPolicy(NodeId node, boolean production, boolean enabled,
        WritePolicy.Confirmation confirmation, Duration confirmationTtl, E2ePolicy e2eTests,
        Optional<URI> soapBaseUrl, boolean repositoryWrite) {
        this(node, production, enabled, confirmation, confirmationTtl, e2eTests, soapBaseUrl,
            repositoryWrite, List.of());
    }
}
