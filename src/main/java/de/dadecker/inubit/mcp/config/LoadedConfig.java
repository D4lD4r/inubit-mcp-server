package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A parsed configuration file.
 *
 * @param credentialKeys paths of credential keys found (and stripped) in the YAML, e.g.
 *     {@code groups[0].nodes[0].password}; their values are never kept
 */
public record LoadedConfig(Path source, ProfileConfig config, List<String> credentialKeys,
    List<String> urlProblems) {

    public LoadedConfig {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(config, "config");
        credentialKeys = List.copyOf(credentialKeys);
        urlProblems = List.copyOf(urlProblems);
    }
}
