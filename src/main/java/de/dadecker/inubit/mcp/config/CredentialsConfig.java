package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code credentials} block as written in the YAML. It configures only how the credential
 * environment variables are named; credential values never appear in the YAML.
 *
 * @param envPrefix the prefix of the credential variables; default
 *     {@code ProfileInfo.defaultCredentialPrefix(profile.name)}
 */
public record CredentialsConfig(Optional<String> envPrefix) {

    public static final CredentialsConfig EMPTY = new CredentialsConfig(Optional.empty());

    public CredentialsConfig {
        envPrefix = Optionals.orEmpty(envPrefix);
    }
}
