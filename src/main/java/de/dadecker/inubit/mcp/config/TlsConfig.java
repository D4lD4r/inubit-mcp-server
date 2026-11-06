package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code tls} block as written in the YAML; empty values are inherited. {@code pinPerServer} is
 * not inherited: on a stage it marks a stage whose servers each carry their own pin (feature 014).
 */
public record TlsConfig(
    Optional<Path> trustStore,
    Optional<Boolean> disableHostnameVerification,
    Optional<String> pinnedCertificateSha256,
    Optional<Boolean> pinPerServer) {

    public static final TlsConfig EMPTY =
        new TlsConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public TlsConfig {
        trustStore = Optionals.orEmpty(trustStore);
        disableHostnameVerification = Optionals.orEmpty(disableHostnameVerification);
        pinnedCertificateSha256 = Optionals.orEmpty(pinnedCertificateSha256);
        pinPerServer = Optionals.orEmpty(pinPerServer);
    }

    /** True if this block marks its stage as one with a pin per server. */
    public boolean marksStage() {
        return pinPerServer.orElse(false);
    }
}
