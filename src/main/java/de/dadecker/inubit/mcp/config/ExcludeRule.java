package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * One entry of {@code deploy.exclude} as written in the YAML (feature 005, research D-2): exactly
 * one of a diagram group name, a glob on workflow and module names, or a glob on repository paths
 * ({@code *} one segment, {@code **} any). Not validated here: {@link ConfigValidator} reports an
 * entry with no key, several keys or a blank value.
 */
public record ExcludeRule(Optional<String> diagramGroup, Optional<String> name,
    Optional<String> repositoryPath) {

    public ExcludeRule {
        diagramGroup = Optionals.orEmpty(diagramGroup);
        name = Optionals.orEmpty(name);
        repositoryPath = Optionals.orEmpty(repositoryPath);
    }
}
