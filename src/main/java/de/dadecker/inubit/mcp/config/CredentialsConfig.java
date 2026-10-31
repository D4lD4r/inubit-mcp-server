package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code credentials} block as written in the YAML, at the top level and (feature 010) on groups
 * and nodes. It configures only where the credentials come from: the prefix of the entry names,
 * the secret source and the Parameter Store names; credential values never appear in the YAML.
 * {@link ConfigLoader} enforces which keys each level may have.
 *
 * @param prefix the profile-wide prefix of the entry names (top level only; feature 011,
 *     research R-6); default {@code ProfileInfo.defaultCredentialPrefix(profile.name)}
 * @param envPrefix the name of {@code prefix} before feature 011 (top level only); deprecated,
 *     same meaning
 * @param source the secret source declared at this level; empty = inherited (research R-6)
 * @param parameters the Parameter Store names declared at this level (groups and nodes only)
 */
public record CredentialsConfig(
    Optional<String> prefix,
    Optional<String> envPrefix,
    Optional<SecretSourceConfig> source,
    Optional<ParameterNames> parameters) {

    public static final CredentialsConfig EMPTY =
        new CredentialsConfig(Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());

    public CredentialsConfig {
        prefix = Optionals.orEmpty(prefix);
        envPrefix = Optionals.orEmpty(envPrefix);
        source = Optionals.orEmpty(source);
        parameters = Optionals.orEmpty(parameters);
    }

    /** The profile-wide prefix as written: {@code prefix}, else {@code envPrefix}. */
    public Optional<String> profilePrefix() {
        return prefix.or(() -> envPrefix);
    }

    /** The key that holds {@link #profilePrefix()}, for messages. */
    public String profilePrefixKey() {
        return prefix.isPresent() || envPrefix.isEmpty() ? "credentials.prefix"
            : "credentials.envPrefix";
    }
}
