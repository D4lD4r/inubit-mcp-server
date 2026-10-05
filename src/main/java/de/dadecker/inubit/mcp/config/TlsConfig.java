package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.Optional;

/** {@code tls} block as written in the YAML; empty values are inherited. */
public record TlsConfig(
    Optional<Path> trustStore,
    Optional<Boolean> disableHostnameVerification,
    Optional<String> pinnedCertificateSha256) {

    public static final TlsConfig EMPTY =
        new TlsConfig(Optional.empty(), Optional.empty(), Optional.empty());

    public TlsConfig {
        trustStore = Optionals.orEmpty(trustStore);
        disableHostnameVerification = Optionals.orEmpty(disableHostnameVerification);
        pinnedCertificateSha256 = Optionals.orEmpty(pinnedCertificateSha256);
    }
}
