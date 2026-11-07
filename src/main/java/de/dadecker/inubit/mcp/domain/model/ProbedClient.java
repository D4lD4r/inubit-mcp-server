package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * The result of a client check: the key of the client that was actually launched, chosen at
 * launch time, and the version it reported (feature 017, research D-5).
 */
public record ProbedClient(ClientKey key, ClientVersion version) {

    public ProbedClient {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(version, "version");
    }
}
