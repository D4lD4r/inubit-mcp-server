package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * Region and AWS profile of a Parameter Store access; the key of the cached store client
 * (feature 010, data-model.md → StoreLocation).
 *
 * @param awsProfile empty = the default AWS credential chain
 */
public record StoreLocation(String region, Optional<String> awsProfile) {

    public StoreLocation {
        awsProfile = Optionals.orEmpty(awsProfile);
        SecretSourceConfig.checkLocation(region, awsProfile);
    }
}
