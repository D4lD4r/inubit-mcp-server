package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Feature 010 (T004, research R-6): the effective secret source of each node (node → group →
 * profile → environment) and its resolved names.
 */
class SecretPlanTest {

    private static final Path HOME = Path.of("/home/test");
    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final NodeId NODE2 = NodeId.parse("dev/node2");

    private static Map<NodeId, SecretPlan> plans(String yaml) {
        return new ConfigLoader(Map.of(), HOME, false).parse(yaml, HOME.resolve("acme.yaml"))
            .config().secretPlans();
    }

    /** Profile {@code acme} with group {@code dev} (settings) and nodes node1 and node2. */
    private static String profile(String top, String group, String node1) {
        return "profile:\n  name: acme\n" + top + """
            groups:
              - name: dev
            """ + group.indent(4) + """
                nodes:
                  - name: node1
                    baseUrl: https://node1.dev.example.test:8443
            """ + node1.indent(8) + """
                  - name: node2
                    baseUrl: https://node2.dev.example.test:8443
            """;
    }

    private static final String AWS_TOP = """
        credentials:
          source:
            type: awsParameterStore
            region: eu-central-1
        """;

    private static final String AWS_GROUP = """
        credentials:
          source:
            type: awsParameterStore
            region: eu-west-1
            awsProfile: test-profile
          parameters:
            username: /inubit/dev/username
            password: /inubit/dev/password
        """;

    @Test
    void withoutAnySourceEveryNodeUsesTheEnvironmentWithTheDefaultPrefix() {
        Map<NodeId, SecretPlan> plans = plans(profile("", "", ""));

        assertThat(plans).containsOnlyKeys(NODE1, NODE2);
        SecretPlan plan = plans.get(NODE1);
        assertThat(plan.source()).isEqualTo(new SecretSourceConfig.Environment(Optional.empty()));
        assertThat(plan.origin()).isEqualTo(SecretPlan.Origin.DEFAULT);
        assertThat(plan.aws()).isFalse();
        assertThat(plan.variables()).contains(new CredentialVariables("INUBIT_ACME", NODE1));
        assertThat(plan.names()).isEmpty();
    }

    @Test
    void theEnvironmentPrefixIsTheSourcePrefixElseEnvPrefixElseTheDefault() {
        String envPrefix = "credentials:\n  envPrefix: INUBIT_LEGACY\n";
        String groupSource = "credentials:\n  source:\n    type: environment\n";
        String nodeSource = "credentials:\n  source:\n    type: environment\n"
            + "    prefix: INUBIT_OWN\n";

        Map<NodeId, SecretPlan> plans = plans(profile(envPrefix, groupSource, nodeSource));

        assertThat(plans.get(NODE1).variables().orElseThrow().prefix()).isEqualTo("INUBIT_OWN");
        assertThat(plans.get(NODE1).origin()).isEqualTo(SecretPlan.Origin.NODE);
        assertThat(plans.get(NODE2).variables().orElseThrow().prefix())
            .isEqualTo("INUBIT_LEGACY");
        assertThat(plans.get(NODE2).origin()).isEqualTo(SecretPlan.Origin.GROUP);
    }

    @Test
    void theProfileSourceAppliesWhereNoGroupOrNodeDeclaresOne() {
        String names = "credentials:\n  parameters:\n    username: /inubit/node1/username\n"
            + "    password: /inubit/node1/password\n";

        SecretPlan plan = plans(profile(AWS_TOP, "", names)).get(NODE1);

        assertThat(plan.source()).isEqualTo(
            new SecretSourceConfig.AwsParameterStore("eu-central-1", Optional.empty()));
        assertThat(plan.origin()).isEqualTo(SecretPlan.Origin.PROFILE);
        assertThat(plan.aws()).isTrue();
        assertThat(plan.variables()).isEmpty();
        assertThat(plan.names().orElseThrow().username()).contains("/inubit/node1/username");
    }

    @Test
    void theGroupSourceReplacesTheProfileSourceAndTheNodeSourceReplacesBoth() {
        String nodeSource = """
            credentials:
              source:
                type: awsParameterStore
                region: us-east-1
              parameters:
                username: /inubit/node1/username
                password: /inubit/node1/password
            """;

        Map<NodeId, SecretPlan> plans = plans(profile(AWS_TOP, AWS_GROUP, nodeSource));

        assertThat(plans.get(NODE1).source()).isEqualTo(
            new SecretSourceConfig.AwsParameterStore("us-east-1", Optional.empty()));
        assertThat(plans.get(NODE1).origin()).isEqualTo(SecretPlan.Origin.NODE);
        // the override replaces the whole source, including the AWS profile
        assertThat(plans.get(NODE2).source()).isEqualTo(
            new SecretSourceConfig.AwsParameterStore("eu-west-1", Optional.of("test-profile")));
        assertThat(plans.get(NODE2).origin()).isEqualTo(SecretPlan.Origin.GROUP);
    }

    @Test
    void aNodeNameWinsPerKindAndTheGroupNamesFillTheOtherKinds() {
        String names = "credentials:\n  parameters:\n    password: /inubit/node1/password\n"
            + "    trustStorePassword: /inubit/node1/ts-pass\n";

        Map<NodeId, SecretPlan> plans = plans(profile("", AWS_GROUP, names));

        ParameterNames node1 = plans.get(NODE1).names().orElseThrow();
        assertThat(node1.username()).contains("/inubit/dev/username");
        assertThat(node1.password()).contains("/inubit/node1/password");
        assertThat(node1.trustStorePassword()).contains("/inubit/node1/ts-pass");
        assertThat(node1.e2eUsername()).isEmpty();
        ParameterNames node2 = plans.get(NODE2).names().orElseThrow();
        assertThat(node2.username()).contains("/inubit/dev/username");
        assertThat(node2.password()).contains("/inubit/dev/password");
        assertThat(node2.trustStorePassword()).isEmpty();
    }

    @Test
    void aNodeWithItsOwnSourceDoesNotInheritTheGroupNames() {
        String nodeSource = """
            credentials:
              source:
                type: awsParameterStore
                region: eu-west-1
                awsProfile: test-profile
              parameters:
                password: /inubit/node1/password
            """;

        ParameterNames names = plans(profile("", AWS_GROUP, nodeSource)).get(NODE1).names()
            .orElseThrow();

        // even an identical source declared on the node is its own source
        assertThat(names.username()).isEmpty();
        assertThat(names.password()).contains("/inubit/node1/password");
    }

    @Test
    void anEnvironmentOverrideInAnAwsGroupHasVariablesAndNoNames() {
        String nodeSource = "credentials:\n  source:\n    type: environment\n"
            + "  parameters:\n    password: /inubit/node1/password\n";

        SecretPlan plan = plans(profile("", AWS_GROUP, nodeSource)).get(NODE1);

        assertThat(plan.aws()).isFalse();
        assertThat(plan.variables()).contains(new CredentialVariables("INUBIT_ACME", NODE1));
        assertThat(plan.names()).isEmpty();
    }

    @Test
    void theEndToEndPairIsResolvedPerKindLikeTheOtherNames() {
        String group = AWS_GROUP + "    e2eUsername: /inubit/dev/e2e-user\n";
        String node1 = "credentials:\n  parameters:\n    e2ePassword: /inubit/node1/e2e-pass\n";

        Map<NodeId, SecretPlan> plans = plans(profile("", group, node1));

        ParameterNames names = plans.get(NODE1).names().orElseThrow();
        assertThat(names.e2eUsername()).contains("/inubit/dev/e2e-user");
        assertThat(names.e2ePassword()).contains("/inubit/node1/e2e-pass");
        assertThat(plans.get(NODE2).names().orElseThrow().e2ePassword()).isEmpty();
    }

    @Test
    void theEffectiveNodeCarriesItsPlanAndItsVariables() {
        String nodeSource = "credentials:\n  source:\n    type: environment\n"
            + "    prefix: INUBIT_OWN\n";
        ProfileConfig config = new ConfigLoader(Map.of(), HOME, false)
            .parse(profile("", "", nodeSource), HOME.resolve("acme.yaml")).config();

        EffectiveNodeConfig node1 = config.effectiveNodes().get(0);

        assertThat(node1.secretPlan()).isEqualTo(config.secretPlans().get(NODE1));
        assertThat(node1.credentialVariables()).isEqualTo(
            new CredentialVariables("INUBIT_OWN", NODE1));
        assertThat(config.effectiveNodes().get(1).credentialVariables()).isEqualTo(
            new CredentialVariables("INUBIT_ACME", NODE2));
    }

    @Test
    void anAwsNodeKeepsTheProfileVariablesForMessagesThatStillNameVariables() {
        ProfileConfig config = new ConfigLoader(Map.of(), HOME, false)
            .parse(profile("", AWS_GROUP, ""), HOME.resolve("acme.yaml")).config();

        assertThat(config.effectiveNodes().get(0).credentialVariables()).isEqualTo(
            new CredentialVariables("INUBIT_ACME", NODE1));
    }

    @Test
    void aPlanMatchesItsSource() {
        SecretSourceConfig environment = new SecretSourceConfig.Environment(Optional.empty());
        SecretSourceConfig aws =
            new SecretSourceConfig.AwsParameterStore("eu-central-1", Optional.empty());
        Optional<CredentialVariables> variables =
            Optional.of(new CredentialVariables("INUBIT", NODE1));

        assertThatIllegalArgumentException().isThrownBy(() -> new SecretPlan(NODE1, environment,
            SecretPlan.Origin.DEFAULT, Optional.empty(), Optional.of(ParameterNames.EMPTY)));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretPlan(NODE1, aws,
            SecretPlan.Origin.NODE, variables, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretPlan(NODE2, environment,
            SecretPlan.Origin.DEFAULT, variables, Optional.empty()));
    }
}
