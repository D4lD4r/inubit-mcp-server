package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretSourceConfigTest {

    // --- environment: prefix ---------------------------------------------------------------

    @Test
    void anEnvironmentSourceNeedsNoPrefix() {
        assertThat(new SecretSourceConfig.Environment(Optional.empty()).prefix()).isEmpty();
        assertThat(new SecretSourceConfig.Environment(null).prefix()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "INUBIT_ACME", "X1_2",
        "A123456789012345678901234567890123456789012345678901234567890123"})
    void acceptsPrefixesOfTheEnvPrefixPattern(String prefix) {
        assertThat(new SecretSourceConfig.Environment(Optional.of(prefix)).prefix())
            .contains(prefix);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "inubit", "1ABC", "_ABC", "AB-C", "AB C",
        "A1234567890123456789012345678901234567890123456789012345678901234"})
    void refusesPrefixesOutsideTheEnvPrefixPattern(String prefix) {
        assertThatThrownBy(() -> new SecretSourceConfig.Environment(Optional.of(prefix)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("prefix");
    }

    // --- awsParameterStore: region ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"eu-central-1", "us-east-1", "ap-southeast-2", "us-gov-west-1",
        "eu-south-2"})
    void acceptsRegions(String region) {
        assertThat(new SecretSourceConfig.AwsParameterStore(region, Optional.empty()).region())
            .isEqualTo(region);
        assertThat(SecretSourceConfig.isValidRegion(region)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "eu", "eu-central", "EU-CENTRAL-1", "eu-central-12",
        "eu--central-1", "e-central-1", "eu-central-1 ", "eu_central_1", "eu-central-x"})
    void refusesRegionsOutsideThePattern(String region) {
        assertThat(SecretSourceConfig.isValidRegion(region)).isFalse();
        assertThatThrownBy(() -> new SecretSourceConfig.AwsParameterStore(region,
            Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("region");
    }

    @Test
    void anAwsSourceNeedsARegion() {
        assertThatThrownBy(() -> new SecretSourceConfig.AwsParameterStore(null, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("region");
    }

    // --- awsParameterStore: awsProfile -----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"inubit", "prod-account", "a.b_c+d=e,f@g", "Profile1",
        "a123456789012345678901234567890123456789012345678901234567890123"})
    void acceptsAwsProfiles(String profile) {
        SecretSourceConfig.AwsParameterStore source =
            new SecretSourceConfig.AwsParameterStore("eu-central-1", Optional.of(profile));

        assertThat(source.awsProfile()).contains(profile);
        assertThat(SecretSourceConfig.isValidAwsProfile(profile)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", "a/b", "a:b", "a\nb", "a\"b",
        "a1234567890123456789012345678901234567890123456789012345678901234"})
    void refusesAwsProfilesOutsideThePattern(String profile) {
        assertThat(SecretSourceConfig.isValidAwsProfile(profile)).isFalse();
        assertThatThrownBy(() -> new SecretSourceConfig.AwsParameterStore("eu-central-1",
            Optional.of(profile)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("awsProfile");
    }

    @Test
    void theAwsProfileIsOptional() {
        assertThat(new SecretSourceConfig.AwsParameterStore("eu-central-1", null).awsProfile())
            .isEmpty();
    }

    @Test
    void errorsDoNotEchoTheRefusedValue() {
        assertThatThrownBy(() -> new SecretSourceConfig.AwsParameterStore("eu-central-1",
            Optional.of("test password 1")))
            .hasMessageNotContaining("test password 1");
        assertThatThrownBy(() -> new SecretSourceConfig.AwsParameterStore("test-password-1",
            Optional.empty()))
            .hasMessageNotContaining("test-password-1");
        assertThatThrownBy(() -> new SecretSourceConfig.Environment(Optional.of("test-pw-1")))
            .hasMessageNotContaining("test-pw-1");
    }

    // --- StoreLocation ---------------------------------------------------------------------

    @Test
    void aStoreLocationIsAValueKey() {
        StoreLocation location = new StoreLocation("eu-central-1", Optional.of("inubit"));

        assertThat(location).isEqualTo(new StoreLocation("eu-central-1", Optional.of("inubit")));
        assertThat(location).isNotEqualTo(new StoreLocation("eu-central-1", Optional.empty()));
        assertThat(new StoreLocation("eu-west-1", null).awsProfile()).isEmpty();
    }

    @Test
    void aStoreLocationChecksRegionAndProfile() {
        assertThatThrownBy(() -> new StoreLocation("EU", Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("region");
        assertThatThrownBy(() -> new StoreLocation(null, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("region");
        assertThatThrownBy(() -> new StoreLocation("eu-central-1", Optional.of("a b")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("awsProfile");
    }
}
