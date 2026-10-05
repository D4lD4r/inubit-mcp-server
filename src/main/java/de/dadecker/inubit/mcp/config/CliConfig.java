package de.dadecker.inubit.mcp.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/** {@code cli} block as written in the YAML; empty values are inherited. */
public record CliConfig(Optional<URI> url, Optional<Path> home, Optional<Path> javaHome) {

    public static final CliConfig EMPTY =
        new CliConfig(Optional.empty(), Optional.empty(), Optional.empty());

    public CliConfig {
        url = Optionals.orEmpty(url);
        home = Optionals.orEmpty(home);
        javaHome = Optionals.orEmpty(javaHome);
    }
}
