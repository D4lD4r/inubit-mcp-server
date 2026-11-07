package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of {@code defaults.cliClients} (feature 017, research D-1): the StartCLI client
 * directory for one INUBIT major.minor and, optionally, its JDK. Both paths use the binding of
 * {@code cliHome}/{@code cliJavaHome}.
 */
public record CliClient(Path home, Optional<Path> javaHome) {

    public CliClient {
        Objects.requireNonNull(home, "home");
        javaHome = Optionals.orEmpty(javaHome);
    }
}
