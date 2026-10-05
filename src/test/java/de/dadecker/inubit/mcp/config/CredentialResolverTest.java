package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CredentialResolverTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final NodeId QA2 = NodeId.parse("qa/node2");

    private final Map<String, String> env = new HashMap<>();
    private final SecretScrubber scrubber = new SecretScrubber();

    private static final NodeId TEST_NODE1 = NodeId.parse("test/node1");

    /** With the explicit prefix {@code INUBIT}, the variable names of feature 001. */
    private CredentialResolution resolve(NodeId... servers) {
        return resolve("INUBIT", servers);
    }

    private CredentialResolution resolve(String prefix, NodeId... servers) {
        return new CredentialResolver(env, scrubber, prefix).resolve(List.of(servers));
    }

    @Test
    void derivesVariableNamesByUppercasingAndReplacingNonAlphanumerics() {
        CredentialVariables variables =
            new CredentialVariables("INUBIT", NodeId.parse("a-b/c-1"));

        assertThat(variables.groupVariable("USERNAME")).isEqualTo("INUBIT_A_B_USERNAME");
        assertThat(variables.nodeVariable("USERNAME")).isEqualTo("INUBIT_A_B_C_1_USERNAME");
        assertThat(new CredentialVariables("INUBIT", DEV).nodeVariable("PASSWORD"))
            .isEqualTo("INUBIT_DEV_NODE1_PASSWORD");
    }

    // --- 002 research D-6: the effective prefix ------------------------------------------------

    @Test
    void theVariableNamesStartWithTheGivenPrefix() {
        CredentialVariables variables = new CredentialVariables("INUBIT_ACME", TEST_NODE1);

        assertThat(variables.candidates("PASSWORD")).containsExactly(
            "INUBIT_ACME_TEST_NODE1_PASSWORD", "INUBIT_ACME_TEST_PASSWORD");
        assertThat(variables.either("TRUSTSTORE_PASSWORD")).isEqualTo(
            "INUBIT_ACME_TEST_NODE1_TRUSTSTORE_PASSWORD (or INUBIT_ACME_TEST_TRUSTSTORE_PASSWORD)");
    }

    @Test
    void theDefaultPrefixOfAProfileGivesItsOwnVariablesNodeSpecificFirst() {
        env.put("INUBIT_ACME_TEST_USERNAME", "acme-user");
        env.put("INUBIT_ACME_TEST_NODE1_PASSWORD", "acme-node-pw");
        env.put("INUBIT_ACME_TEST_PASSWORD", "acme-group-pw");

        NodeCredentials nodeSpecific = resolve("INUBIT_ACME", TEST_NODE1).credentials(TEST_NODE1);
        env.remove("INUBIT_ACME_TEST_NODE1_PASSWORD");
        NodeCredentials groupWide = resolve("INUBIT_ACME", TEST_NODE1).credentials(TEST_NODE1);

        assertThat(nodeSpecific.username())
            .contains(new SourcedValue<>("acme-user", "INUBIT_ACME_TEST_USERNAME"));
        assertThat(nodeSpecific.password()).contains(new SourcedValue<>(
            Secret.of("acme-node-pw"), "INUBIT_ACME_TEST_NODE1_PASSWORD"));
        assertThat(groupWide.password()).contains(new SourcedValue<>(
            Secret.of("acme-group-pw"), "INUBIT_ACME_TEST_PASSWORD"));
    }

    @Test
    void theExplicitPrefixInubitGivesTheVariableNamesOfFeature001() {
        env.put("INUBIT_TEST_USERNAME", "user");
        env.put("INUBIT_TEST_NODE1_PASSWORD", "node-pw");

        NodeCredentials credentials = resolve("INUBIT", TEST_NODE1).credentials(TEST_NODE1);

        assertThat(credentials.username().orElseThrow().sourceVariable())
            .isEqualTo("INUBIT_TEST_USERNAME");
        assertThat(credentials.password().orElseThrow().sourceVariable())
            .isEqualTo("INUBIT_TEST_NODE1_PASSWORD");
    }

    @Test
    void aVariableOfAnotherPrefixIsNeverUsedAndTheErrorsNameTheOwnVariables() {
        env.put("INUBIT_GLOBEX_TEST_USERNAME", "globex-user");
        env.put("INUBIT_GLOBEX_TEST_NODE1_PASSWORD", "globex-pw");
        env.put("INUBIT_TEST_USERNAME", "legacy-user");
        env.put("INUBIT_TEST_PASSWORD", "legacy-pw");

        CredentialResolution resolution = resolve("INUBIT_ACME", TEST_NODE1);

        assertThat(resolution.credentials(TEST_NODE1).username()).isEmpty();
        assertThat(resolution.credentials(TEST_NODE1).password()).isEmpty();
        assertThat(resolution.errors()).hasSize(2);
        assertThat(resolution.errors()).anySatisfy(e -> assertThat(e).contains(
            "INUBIT_ACME_TEST_NODE1_USERNAME", "INUBIT_ACME_TEST_USERNAME"));
        assertThat(resolution.errors()).anySatisfy(e -> assertThat(e).contains(
            "INUBIT_ACME_TEST_NODE1_PASSWORD", "INUBIT_ACME_TEST_PASSWORD"));
        assertThat(String.join("\n", resolution.errors()))
            .doesNotContain("GLOBEX", "globex", "legacy");
    }

    @Test
    void collisionsAreDetectedAndNamedWithTheEffectivePrefix() {
        env.put("INUBIT_ACME_A_B_C_USERNAME", "u");
        env.put("INUBIT_ACME_A_B_C_PASSWORD", "p-secret");

        CredentialResolution resolution =
            resolve("INUBIT_ACME", NodeId.parse("a-b/c"), NodeId.parse("a/b-c"));

        assertThat(resolution.errors()).singleElement().asString()
            .contains("a-b/c", "a/b-c", "INUBIT_ACME_A_B_C_USERNAME")
            .doesNotContain("p-secret");
    }

    @Test
    void variablesOfAnotherKnownProfileAreNotReportedAsUnmatched() {
        // 002 US2 review P2: INUBIT_ACME_2_… starts with INUBIT_ACME_ but belongs to acme-2
        env.put("INUBIT_ACME_TEST_USERNAME", "u");
        env.put("INUBIT_ACME_TEST_PASSWORD", "pw-value");
        env.put("INUBIT_ACME_2_DEV_PASSWORD", "other-profile-pw");
        env.put("INUBIT_ACME_DEV_PASSWORD", "typo-pw");

        CredentialResolution resolution = new CredentialResolver(env, scrubber, "INUBIT_ACME")
            .resolve(List.of(TEST_NODE1), Set.of("INUBIT_ACME_2_DEV_USERNAME",
                "INUBIT_ACME_2_DEV_PASSWORD"));

        assertThat(resolution.warnings()).singleElement().asString()
            .contains("INUBIT_ACME_DEV_PASSWORD").doesNotContain("INUBIT_ACME_2");
    }

    @Test
    void onlyUnmatchedPasswordVariablesUnderTheEffectivePrefixAreWarnedAbout() {
        env.put("INUBIT_ACME_TEST_USERNAME", "u");
        env.put("INUBIT_ACME_TEST_PASSWORD", "pw-value");
        env.put("INUBIT_ACME_TEST_NODE2_PASSWORD", "typo-secret");
        // another profile's and the 001 scheme's variables are not this profile's business
        env.put("INUBIT_GLOBEX_TEST_PASSWORD", "globex-secret");
        env.put("INUBIT_ACMEX_TEST_PASSWORD", "acmex-secret");
        env.put("INUBIT_TEST_PASSWORD", "legacy-secret");

        CredentialResolution resolution = resolve("INUBIT_ACME", TEST_NODE1);

        assertThat(resolution.errors()).isEmpty();
        assertThat(resolution.warnings()).singleElement().asString()
            .contains("INUBIT_ACME_TEST_NODE2_PASSWORD").doesNotContain("typo-secret");
    }

    @Test
    void serverSpecificVariablesWinOverStageWideOnes() {
        env.put("INUBIT_QA_USERNAME", "stage-user");
        env.put("INUBIT_QA_PASSWORD", "stage-pass");
        env.put("INUBIT_QA_NODE2_USERNAME", "server-user");
        env.put("INUBIT_QA_NODE2_PASSWORD", "server-pass");

        CredentialResolution resolution = resolve(QA1, QA2);

        NodeCredentials qa1 = resolution.credentials(QA1);
        NodeCredentials qa2 = resolution.credentials(QA2);
        assertThat(qa1.username())
            .contains(new SourcedValue<>("stage-user", "INUBIT_QA_USERNAME"));
        assertThat(qa1.password()).contains(
            new SourcedValue<>(Secret.of("stage-pass"), "INUBIT_QA_PASSWORD"));
        assertThat(qa2.username()).contains(
            new SourcedValue<>("server-user", "INUBIT_QA_NODE2_USERNAME"));
        assertThat(qa2.password()).contains(
            new SourcedValue<>(Secret.of("server-pass"), "INUBIT_QA_NODE2_PASSWORD"));
        assertThat(resolution.errors()).isEmpty();
        assertThat(resolution.warnings()).isEmpty();
    }

    @Test
    void usernameAndPasswordAreResolvedIndependently() {
        env.put("INUBIT_QA_USERNAME", "stage-user");
        env.put("INUBIT_QA_NODE2_PASSWORD", "server-pass");

        NodeCredentials credentials = resolve(QA2).credentials(QA2);

        assertThat(credentials.username()).hasValueSatisfying(
            u -> assertThat(u.sourceVariable()).isEqualTo("INUBIT_QA_USERNAME"));
        assertThat(credentials.password()).hasValueSatisfying(
            p -> assertThat(p.sourceVariable()).isEqualTo("INUBIT_QA_NODE2_PASSWORD"));
        assertThat(credentials.complete()).isTrue();
    }

    @Test
    void emptyValuesCountAsUnset() {
        env.put("INUBIT_DEV_NODE1_USERNAME", "");
        env.put("INUBIT_DEV_USERNAME", "jdoe");
        env.put("INUBIT_DEV_PASSWORD", "pw-123");

        NodeCredentials credentials = resolve(DEV).credentials(DEV);

        assertThat(credentials.username())
            .contains(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME"));
    }

    @Test
    void missingValuesNameTheExpectedVariablesButNoValues() {
        env.put("INUBIT_QA_USERNAME", "jdoe");
        env.put("INUBIT_DEV_PASSWORD", "unrelated-secret");

        CredentialResolution resolution = resolve(QA1);

        assertThat(resolution.credentials(QA1).password()).isEmpty();
        assertThat(resolution.credentials(QA1).complete()).isFalse();
        assertThat(resolution.errors()).singleElement().satisfies(error -> {
            assertThat(error).contains("qa/node1", "password", "INUBIT_QA_NODE1_PASSWORD",
                "INUBIT_QA_PASSWORD");
            assertThat(error).doesNotContain("jdoe", "unrelated-secret");
        });
    }

    @Test
    void missingUsernameAndPasswordAreBothReported() {
        CredentialResolution resolution = resolve(DEV);

        assertThat(resolution.errors()).hasSize(2);
        assertThat(resolution.errors()).anySatisfy(e -> assertThat(e)
            .contains("username", "INUBIT_DEV_NODE1_USERNAME", "INUBIT_DEV_USERNAME"));
        assertThat(resolution.errors()).anySatisfy(e -> assertThat(e)
            .contains("password", "INUBIT_DEV_NODE1_PASSWORD", "INUBIT_DEV_PASSWORD"));
    }

    @Test
    void collidingServerVariableNamesAreAnError() {
        NodeId first = NodeId.parse("a-b/c");
        NodeId second = NodeId.parse("a/b-c");
        env.put("INUBIT_A_B_C_USERNAME", "u");
        env.put("INUBIT_A_B_C_PASSWORD", "p-secret");

        CredentialResolution resolution = resolve(first, second);

        assertThat(resolution.errors()).singleElement().satisfies(error -> {
            assertThat(error).contains("a-b/c", "a/b-c", "INUBIT_A_B_C_USERNAME");
            assertThat(error).doesNotContain("p-secret");
        });
    }

    @Test
    void serverVariableCollidingWithAStageWideVariableIsAnError() {
        // INUBIT_A_B_USERNAME is server a/b's own variable and stage a-b's stage-wide variable
        NodeId serverAB = NodeId.parse("a/b");
        NodeId stageAB = NodeId.parse("a-b/x");
        env.put("INUBIT_A_USERNAME", "u");
        env.put("INUBIT_A_PASSWORD", "p");
        env.put("INUBIT_A_B_USERNAME", "u");
        env.put("INUBIT_A_B_PASSWORD", "p");

        CredentialResolution resolution = resolve(serverAB, stageAB);

        assertThat(resolution.errors()).singleElement().satisfies(error ->
            assertThat(error).contains("a/b", "a-b", "INUBIT_A_B_USERNAME"));
    }

    @Test
    void trustStoreSuffixCollisionIsAnError() {
        // server a/truststore's password variable equals stage a's trust-store password variable
        NodeId truststore = NodeId.parse("a/truststore");
        env.put("INUBIT_A_USERNAME", "u");
        env.put("INUBIT_A_PASSWORD", "p");

        CredentialResolution resolution = resolve(truststore);

        assertThat(resolution.errors()).singleElement().satisfies(error ->
            assertThat(error).contains("INUBIT_A_TRUSTSTORE_PASSWORD"));
    }

    @Test
    void sourceVariableNamesAreRecorded() {
        env.put("INUBIT_DEV_USERNAME", "jdoe");
        env.put("INUBIT_DEV_NODE1_PASSWORD", "pw");
        env.put("INUBIT_DEV_TRUSTSTORE_PASSWORD", "ts-pw");

        NodeCredentials credentials = resolve(DEV).credentials(DEV);

        assertThat(credentials.username().orElseThrow().sourceVariable())
            .isEqualTo("INUBIT_DEV_USERNAME");
        assertThat(credentials.password().orElseThrow().sourceVariable())
            .isEqualTo("INUBIT_DEV_NODE1_PASSWORD");
        assertThat(credentials.trustStorePassword().orElseThrow().sourceVariable())
            .isEqualTo("INUBIT_DEV_TRUSTSTORE_PASSWORD");
    }

    @Test
    void trustStorePasswordIsOptionalAndServerSpecificWins() {
        env.put("INUBIT_QA_USERNAME", "jdoe");
        env.put("INUBIT_QA_PASSWORD", "pw");
        env.put("INUBIT_QA_TRUSTSTORE_PASSWORD", "stage-ts");
        env.put("INUBIT_QA_NODE2_TRUSTSTORE_PASSWORD", "server-ts");

        CredentialResolution resolution = resolve(QA2, DEV);

        assertThat(resolution.credentials(QA2).trustStorePassword()).contains(new SourcedValue<>(
            Secret.of("server-ts"), "INUBIT_QA_NODE2_TRUSTSTORE_PASSWORD"));
        assertThat(resolution.credentials(DEV).trustStorePassword()).isEmpty();
    }

    @Test
    void unmatchedPasswordVariableProducesAWarningWithoutItsValue() {
        env.put("INUBIT_DEV_USERNAME", "jdoe");
        env.put("INUBIT_DEV_PASSWORD", "pw");
        env.put("INUBIT_DEV_INUBTI_PASSWORD", "typo-secret");
        env.put("INUBIT_PROD_TRUSTSTORE_PASSWORD", "other-secret");
        env.put("INUBIT_PROD_USERNAME", "not-a-password-variable");
        env.put("UNRELATED_PASSWORD", "not-inubit");

        CredentialResolution resolution = resolve(DEV);

        assertThat(resolution.errors()).isEmpty();
        assertThat(resolution.warnings()).hasSize(2);
        assertThat(resolution.warnings()).anySatisfy(w -> assertThat(w)
            .contains("INUBIT_DEV_INUBTI_PASSWORD"));
        assertThat(resolution.warnings()).anySatisfy(w -> assertThat(w)
            .contains("INUBIT_PROD_TRUSTSTORE_PASSWORD"));
        assertThat(String.join("\n", resolution.warnings()))
            .doesNotContain("typo-secret", "other-secret");
    }

    @Test
    void passwordsTokensAndTrustStorePasswordsAreRegisteredWithTheScrubber() {
        env.put("INUBIT_DEV_USERNAME", "jdoe");
        env.put("INUBIT_DEV_PASSWORD", "hunter2");
        env.put("INUBIT_DEV_TRUSTSTORE_PASSWORD", "ts-secret");
        String token = Base64.getEncoder()
            .encodeToString("jdoe:hunter2".getBytes(StandardCharsets.UTF_8));

        NodeCredentials credentials = resolve(DEV).credentials(DEV);

        assertThat(credentials.basicAuthToken()).contains(Secret.of(token));
        assertThat(scrubber.scrub("hunter2 " + token + " ts-secret")).isEqualTo("*** *** ***");
        // the username is not a secret (FR-025)
        assertThat(scrubber.scrub("jdoe")).isEqualTo("jdoe");
    }

    @Test
    void noSecretAppearsInToString() {
        env.put("INUBIT_DEV_USERNAME", "jdoe");
        env.put("INUBIT_DEV_PASSWORD", "hunter2");
        env.put("INUBIT_DEV_TRUSTSTORE_PASSWORD", "ts-secret");

        CredentialResolution resolution = resolve(DEV);

        String token = Base64.getEncoder()
            .encodeToString("jdoe:hunter2".getBytes(StandardCharsets.UTF_8));
        assertThat(resolution.toString()).doesNotContain("hunter2", "ts-secret", token);
    }

    @Test
    void credentialsAreKeptInServerOrder() {
        env.put("INUBIT_QA_USERNAME", "u");
        env.put("INUBIT_QA_PASSWORD", "p");
        env.put("INUBIT_DEV_USERNAME", "u");
        env.put("INUBIT_DEV_PASSWORD", "p");

        assertThat(resolve(QA2, DEV, QA1).all()).extracting(NodeCredentials::node)
            .containsExactly(QA2, DEV, QA1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"j:doe", "-jdoe", "jd\u0000oe",
        "jdoe\n", "jd\toe"})
    void unsafeUsernamesAreErrorsNamingOnlyTheVariable(String username) {
        env.put("INUBIT_DEV_USERNAME", username);
        env.put("INUBIT_DEV_PASSWORD", "pw-value");

        CredentialResolution resolution = resolve(DEV);

        assertThat(resolution.errors()).singleElement().satisfies(e -> assertThat(e)
            .contains("dev/node1", "INUBIT_DEV_USERNAME").doesNotContain(username));
    }

    @Test
    void ordinaryUsernamesAreAccepted() {
        env.put("INUBIT_DEV_USERNAME", "j.doe-admin@acme");
        env.put("INUBIT_DEV_PASSWORD", "pw-value");

        assertThat(resolve(DEV).errors()).isEmpty();
    }
}
