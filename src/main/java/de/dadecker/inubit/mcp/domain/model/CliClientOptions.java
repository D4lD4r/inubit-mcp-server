package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * What the local StartCLI client of a 6.1 node offers (feature 011, research D-8, data-model.md →
 * CliClientOptions), read locally from its help output and its Java runtime. An option that could
 * not be determined counts as absent (fail closed, FR-012a).
 *
 * @param trustStoreFilePath          the client has its own trust-store option
 *                                    ({@code --trustStoreFilePath})
 * @param disableHostNameVerification the client has its own option to skip the host-name check
 *                                    ({@code --disableHostNameVerification})
 * @param execCommand                 the client runs single commands ({@code --execCommand})
 * @param javaFeatureVersion          the feature version of the client's Java runtime (8 for
 *                                    {@code 1.8.0_292}, 17 for {@code 17.0.2}); empty if unreadable
 */
public record CliClientOptions(boolean trustStoreFilePath, boolean disableHostNameVerification,
    boolean execCommand, Optional<Integer> javaFeatureVersion) {

    public CliClientOptions {
        Objects.requireNonNull(javaFeatureVersion, "javaFeatureVersion");
    }
}
