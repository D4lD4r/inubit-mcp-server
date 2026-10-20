package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code credentials} block as written in the YAML, at the top level and (feature 010) on groups
 * and nodes. It configures only where the credentials come from: the environment variable prefix,
 * the secret source and the Parameter Store names; credential values never appear in the YAML.
 * {@link ConfigLoader} enforces which keys each level may have.
 *
 * @param envPrefix the prefix of the credential variables (top level only); default
 *     {@code ProfileInfo.defaultCredentialPrefix(profile.name)}
 * @param source the secret source declared at this level; empty = inherited (research R-6)
 * @param parameters the Parameter Store names declared at this level (groups and nodes only)
 */
public record CredentialsConfig(
    Optional<String> envPrefix,
    Optional<SecretSourceConfig> source,
    Optional<ParameterNames> parameters) {

    public static final CredentialsConfig EMPTY =
        new CredentialsConfig(Optional.empty(), Optional.empty(), Optional.empty());

    public CredentialsConfig {
        envPrefix = Optionals.orEmpty(envPrefix);
        source = Optionals.orEmpty(source);
        parameters = Optionals.orEmpty(parameters);
    }
}
