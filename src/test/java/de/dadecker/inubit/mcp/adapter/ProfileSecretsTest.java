package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.SecretPlan;
import de.dadecker.inubit.mcp.config.SecretSourceConfig;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SecretSpan;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets.Pending;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets.Readiness;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Feature 019 T015 (research D-7, option A): the profile's configured credentials, read for all
 * nodes in parallel, ready only when every read is settled.
 */
class ProfileSecretsTest {

    private static final Duration LONG = Duration.ofSeconds(10);
    private static final NodeId CS_NODE = NodeId.parse("dev/node1");
    private static final NodeId AWS_NODE = NodeId.parse("dev/node2");
    private static final String GROUP_USER = "INUBIT_ACME_DEV_USERNAME";
    private static final String GROUP_PW = "INUBIT_ACME_DEV_PASSWORD";
    private static final String NODE_USER = "INUBIT_ACME_DEV_NODE1_USERNAME";
    private static final StoreLocation EU =
        new StoreLocation("eu-central-1", Optional.of("test-profile"));
    private static final String AWS_USER = "/test/dev/node2/username";
    private static final String AWS_PW = "/test/dev/node2/password";

    private final FakeCredentialStore credentialStore = new FakeCredentialStore();
    private final FakeParameterStore parameterStore = new FakeParameterStore();
    private final SecretScrubber scrubber = new SecretScrubber();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T08:00:00Z"));

    @AfterEach
    void release() throws InterruptedException {
        parameterStore.release(Duration.ofSeconds(2));
    }

    private NodeSecrets credentialStoreNode() {
        return new NodeSecrets(TestNodeConfig.node().id(CS_NODE.value())
            .credentialPrefix("INUBIT_ACME").build(), credentialStore, scrubber, clock, LONG);
    }

    private NodeSecrets awsNode() {
        return new NodeSecrets(new SecretPlan(AWS_NODE,
            new SecretSourceConfig.AwsParameterStore(EU.region(), EU.awsProfile()),
            SecretPlan.Origin.NODE, Optional.empty(), Optional.of(new ParameterNames(
                Optional.of(AWS_USER), Optional.of(AWS_PW), Optional.empty(), Optional.empty(),
                Optional.empty()))), parameterStore, scrubber, clock, Duration.ofSeconds(2));
    }

    private ProfileSecrets profile() {
        return new ProfileSecrets(Map.of(CS_NODE, credentialStoreNode(), AWS_NODE, awsNode()),
            scrubber);
    }

    private void putAll() {
        credentialStore.put(GROUP_USER, "jdoe").put(GROUP_PW, "group-Pw-2");
        parameterStore.put(EU, AWS_USER, "aws-user").put(EU, AWS_PW, "aws-Pw-9");
    }

    private static Duration elapsed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    @Test
    void aProfileWithoutNodesIsReadyAtOnce() {
        ProfileSecrets profile = new ProfileSecrets(Map.of(), scrubber);

        assertThat(profile.prepare(LONG)).isEqualTo(new Readiness.Ready());
        assertThat(profile.timeout()).isEqualTo(Duration.ZERO);
    }

    @Test
    void allHeldIsReadyAndEveryPasswordIsFound() {
        putAll();
        ProfileSecrets profile = profile();

        assertThat(profile.prepare(LONG)).isEqualTo(new Readiness.Ready());

        String text = "a group-Pw-2 b aws-Pw-9";
        assertThat(profile.occurrences(text)).containsExactly(new SecretSpan(2, 12),
            new SecretSpan(15, 23));
    }

    @Test
    void nothingConfiguredIsReady() {
        assertThat(profile().prepare(LONG)).isEqualTo(new Readiness.Ready());
    }

    @Test
    void oneWaitingNodeIsNotReadyWithinTheBound() {
        putAll();
        credentialStore.delay(NODE_USER, Duration.ofSeconds(2));
        ProfileSecrets profile = profile();

        Readiness[] readiness = new Readiness[1];
        Duration waited = elapsed(() -> readiness[0] = profile.prepare(Duration.ofMillis(200)));

        assertThat(readiness[0]).isEqualTo(new Readiness.NotReady(
            List.of(new Pending("dev/node1", "macOS Keychain", "waiting"))));
        assertThat(waited).isLessThan(Duration.ofMillis(1500));
        assertThat(profile.prepare(LONG)).as("the read went on").isEqualTo(new Readiness.Ready());
    }

    @Test
    void anOfflineAwsNodeIsNotReadyAtOnceDuringTheBackOff() {
        credentialStore.put(GROUP_USER, "jdoe").put(GROUP_PW, "group-Pw-2");
        parameterStore.failCall(EU, FailureKind.UNREACHABLE, "no route to host");
        ProfileSecrets profile = profile();
        Readiness offline = new Readiness.NotReady(
            List.of(new Pending("dev/node2", "AWS Parameter Store", "unreachable")));

        assertThat(profile.prepare(LONG)).isEqualTo(offline);
        assertThat(profile.prepare(LONG)).isEqualTo(offline);
        assertThat(parameterStore.calls()).isEqualTo(1);
    }

    @Test
    void aFailedEndToEndReadIsPendingWithItsKind() {
        putAll();
        credentialStore.put("INUBIT_ACME_DEV_E2E_PASSWORD", "e2e-Pw-4");

        assertThat(profile().prepare(LONG)).isEqualTo(new Readiness.NotReady(
            List.of(new Pending("dev/node1", "macOS Keychain", "not_found"))));
    }

    @Test
    void pendingNodesAreInNodeOrder() {
        credentialStore.fail(NODE_USER, FailureKind.ACCESS_DENIED, "denied");
        parameterStore.failCall(EU, FailureKind.NO_CREDENTIALS, "expired");

        assertThat(profile().prepare(LONG)).isEqualTo(new Readiness.NotReady(List.of(
            new Pending("dev/node1", "macOS Keychain", "access_denied"),
            new Pending("dev/node2", "AWS Parameter Store", "no_credentials"))));
    }

    @Test
    void theNodesAreReadInParallel() {
        putAll();
        credentialStore.delay(NODE_USER, Duration.ofMillis(600));
        parameterStore.delay(EU, Duration.ofMillis(600));
        ProfileSecrets profile = profile();

        Duration waited = elapsed(() -> assertThat(profile.prepare(LONG))
            .isEqualTo(new Readiness.Ready()));

        assertThat(waited).isLessThan(Duration.ofMillis(1100));
    }

    @Test
    void theTimeoutIsTheLongestOfTheNodes() {
        assertThat(profile().timeout()).isEqualTo(LONG);
    }
}
