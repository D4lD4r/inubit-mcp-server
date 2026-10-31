package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Feature 010 (T021, research R-15): the names of a node's secrets in messages, per source.
 * Environment texts are those of features 002–009, byte for byte.
 */
class SecretLabelsTest {

    private static final NodeId NODE1 = NodeId.parse("dev/node1");

    private static SecretLabels environment() {
        return SecretLabels.of(new SecretPlan(NODE1,
            new SecretSourceConfig.SystemStore(Optional.empty()), SecretPlan.Origin.DEFAULT,
            Optional.of(new CredentialNames("INUBIT_ACME", NODE1)), Optional.empty()));
    }

    private static SecretLabels aws(Optional<String> awsProfile, ParameterNames names) {
        return SecretLabels.of(new SecretPlan(NODE1,
            new SecretSourceConfig.AwsParameterStore("eu-central-1", awsProfile),
            SecretPlan.Origin.NODE, Optional.empty(), Optional.of(names)));
    }

    private static final ParameterNames ALL = new ParameterNames(
        Optional.of("/inubit/dev/node1/username"), Optional.of("/inubit/dev/node1/password"),
        Optional.of("/inubit/dev/node1/truststore-password"),
        Optional.of("/inubit/dev/node1/e2e-username"),
        Optional.of("/inubit/dev/node1/e2e-password"));

    @Test
    void environmentLabelsAreTheNodeAndGroupVariablesAsBefore() {
        SecretLabels labels = environment();

        assertThat(labels.node()).isEqualTo(NODE1);
        assertThat(labels.label(CredentialNames.TRUSTSTORE_PASSWORD)).isEqualTo(
            "INUBIT_ACME_DEV_NODE1_TRUSTSTORE_PASSWORD (or INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD)");
        assertThat(labels.label(CredentialNames.E2E_USERNAME)).isEqualTo(
            "INUBIT_ACME_DEV_NODE1_E2E_USERNAME (or INUBIT_ACME_DEV_E2E_USERNAME)");
        assertThat(labels.fixCredentials()).isEqualTo("Fix INUBIT_ACME_DEV_NODE1_PASSWORD (or"
            + " INUBIT_ACME_DEV_PASSWORD) and _USERNAME, then restart the MCP client from a shell"
            + " with the new values");
    }

    @Test
    void awsLabelsNameTheParameterWithRegionAndProfile() {
        SecretLabels labels = aws(Optional.of("inubit"), ALL);

        assertThat(labels.node()).isEqualTo(NODE1);
        assertThat(labels.label(CredentialNames.USERNAME)).isEqualTo(
            "Parameter Store /inubit/dev/node1/username (eu-central-1, profile inubit)");
        assertThat(labels.label(CredentialNames.PASSWORD)).isEqualTo(
            "Parameter Store /inubit/dev/node1/password (eu-central-1, profile inubit)");
        assertThat(labels.label(CredentialNames.TRUSTSTORE_PASSWORD)).isEqualTo(
            "Parameter Store /inubit/dev/node1/truststore-password (eu-central-1, profile inubit)");
        assertThat(labels.label(CredentialNames.E2E_USERNAME)).isEqualTo(
            "Parameter Store /inubit/dev/node1/e2e-username (eu-central-1, profile inubit)");
        assertThat(labels.label(CredentialNames.E2E_PASSWORD)).isEqualTo(
            "Parameter Store /inubit/dev/node1/e2e-password (eu-central-1, profile inubit)");
    }

    @Test
    void awsFixCredentialsAsksToCorrectTheParametersWithoutRestart() {
        // contracts/mcp-tools-delta.md: rejected credentials of an AWS node (FR-008)
        assertThat(aws(Optional.empty(), ALL).fixCredentials()).isEqualTo("Correct the parameter"
            + " value(s) /inubit/dev/node1/username, /inubit/dev/node1/password in Parameter"
            + " Store (eu-central-1); no restart is needed");
    }

    @Test
    void anUnconfiguredAwsNameIsShownAsItsSetting() {
        SecretLabels labels = aws(Optional.empty(), new ParameterNames(Optional.empty(),
            Optional.of("/inubit/dev/node1/password"), Optional.empty(), Optional.empty(),
            Optional.empty()));

        // optional kind: named by its setting, not as an error
        assertThat(labels.label(CredentialNames.TRUSTSTORE_PASSWORD)).isEqualTo(
            "credentials.parameters.trustStorePassword (Parameter Store, eu-central-1)");
        assertThat(labels.trustStorePasswordMissing()).isEqualTo("no trust store password"
            + " parameter is configured (credentials.parameters.trustStorePassword)");
        assertThat(labels.supplyTrustStorePassword()).isEqualTo("store its password in"
            + " Parameter Store (eu-central-1) and set credentials.parameters.trustStorePassword"
            + " to its name");
        // required kind (ConfigValidator reports its absence as an error)
        assertThat(labels.fixCredentials()).contains(
            "credentials.parameters.username (not configured), /inubit/dev/node1/password");
    }

    @Test
    void trustStorePasswordPhrasesPerSource() {
        assertThat(environment().trustStorePasswordMissing()).isEqualTo("neither"
            + " INUBIT_ACME_DEV_NODE1_TRUSTSTORE_PASSWORD (or INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD)"
            + " is set");
        assertThat(environment().supplyTrustStorePassword()).isEqualTo("set"
            + " INUBIT_ACME_DEV_NODE1_TRUSTSTORE_PASSWORD (or INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD)");

        SecretLabels aws = aws(Optional.of("inubit"), ALL);
        assertThat(aws.trustStorePasswordMissing()).isEqualTo("no trust store password is set in"
            + " Parameter Store /inubit/dev/node1/truststore-password (eu-central-1, profile"
            + " inubit)");
        assertThat(aws.supplyTrustStorePassword()).isEqualTo("store its password in Parameter"
            + " Store /inubit/dev/node1/truststore-password (eu-central-1, profile inubit)");
    }
}
