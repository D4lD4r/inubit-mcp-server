package de.dadecker.inubit.mcp.config;

import java.net.URI;
import java.util.Optional;

/**
 * {@code e2e} block of a group or node as written in the YAML (feature 004, research D-17): the
 * base address of the node's SOAP endpoints ({@code e2e.soap.baseUrl}), inherited node → group.
 * The test policy itself is the sibling key {@code e2eTests}.
 */
public record E2eConfig(Soap soap) {

    public static final E2eConfig EMPTY = new E2eConfig(Soap.EMPTY);

    public E2eConfig {
        soap = Optionals.orDefault(soap, Soap.EMPTY);
    }

    /** {@code e2e.soap}. */
    public record Soap(Optional<URI> baseUrl) {

        public static final Soap EMPTY = new Soap(Optional.empty());

        public Soap {
            baseUrl = Optionals.orEmpty(baseUrl);
        }
    }
}
