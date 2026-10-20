package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where the credentials of a node come from (feature 010, data-model.md → SecretSourceConfig,
 * research R-3). Errors name the field and its rule, never the refused value.
 */
public sealed interface SecretSourceConfig {

    /** AWS region, e.g. {@code eu-central-1}. */
    Pattern REGION_PATTERN = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d$");

    /** Name of a profile in the AWS configuration files. */
    Pattern AWS_PROFILE_PATTERN = Pattern.compile("^[A-Za-z0-9_.+=,@-]{1,64}$");

    /** Returns whether {@code region} matches {@link #REGION_PATTERN}. */
    static boolean isValidRegion(String region) {
        return region != null && REGION_PATTERN.matcher(region).matches();
    }

    /** Returns whether {@code profile} matches {@link #AWS_PROFILE_PATTERN}. */
    static boolean isValidAwsProfile(String profile) {
        return profile != null && AWS_PROFILE_PATTERN.matcher(profile).matches();
    }

    /** Throws if {@code region} or {@code awsProfile} breaks its pattern. */
    static void checkLocation(String region, Optional<String> awsProfile) {
        if (!isValidRegion(region)) {
            throw new IllegalArgumentException("Invalid region: expected "
                + REGION_PATTERN.pattern());
        }
        if (awsProfile.isPresent() && !isValidAwsProfile(awsProfile.get())) {
            throw new IllegalArgumentException("Invalid awsProfile: expected "
                + AWS_PROFILE_PATTERN.pattern());
        }
    }

    /**
     * Environment variables.
     *
     * @param prefix the variable prefix; empty = {@code credentials.envPrefix} or the default
     */
    record Environment(Optional<String> prefix) implements SecretSourceConfig {

        public Environment {
            prefix = Optionals.orEmpty(prefix);
            if (prefix.isPresent() && !ProfileInfo.isValidPrefix(prefix.get())) {
                throw new IllegalArgumentException("Invalid prefix: expected "
                    + ProfileInfo.PREFIX_PATTERN.pattern());
            }
        }
    }

    /**
     * AWS Systems Manager Parameter Store.
     *
     * @param region the AWS region (required)
     * @param awsProfile the profile in the AWS configuration files; empty = the default chain
     */
    record AwsParameterStore(String region, Optional<String> awsProfile)
        implements SecretSourceConfig {

        public AwsParameterStore {
            awsProfile = Optionals.orEmpty(awsProfile);
            checkLocation(region, awsProfile);
        }
    }
}
