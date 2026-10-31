package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Feature 011 T017 (FR-011, research R-13, quickstart Q15, Q27): the credential store part of
 * {@code --check-config}.
 */
class EntryCheckTest {

    private static final Path HOME = Path.of("/home/jdoe");
    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final NodeId DEV2 = NodeId.parse("dev/node2");

    private final FakeCredentialStore store = new FakeCredentialStore();

    private static List<EffectiveNodeConfig> nodes(String devExtra, String nodeExtra) {
        String yaml = """
            profile:
              name: acme
            groups:
              - name: dev
            %s
                nodes:
                  - name: node1
                    baseUrl: https://inubit-dev-1.example.test:8443
            %s
                  - name: node2
                    baseUrl: https://inubit-dev-2.example.test:8443
              - name: prod
                credentials:
                  source: { type: awsParameterStore, region: eu-central-1 }
                  parameters:
                    username: /inubit/prod/username
                    password: /inubit/prod/password
                nodes:
                  - name: node1
                    baseUrl: https://inubit-prod.example.test:8443
            """.formatted(devExtra.indent(4).stripTrailing(), nodeExtra.indent(8).stripTrailing());
        return new ConfigLoader(Map.of(), HOME, false).parse(yaml, HOME.resolve("acme.yaml"))
            .config().effectiveNodes();
    }

    private EntryCheck.Result run(List<EffectiveNodeConfig> nodes) {
        return EntryCheck.run(nodes, store, Duration.ofSeconds(5));
    }

    private static final String E2E_ALLOWED = """
        development:
          enabled: true
        e2eTests: FREE
        e2e:
          soap:
            baseUrl: https://inubit-dev.example.test:8443
        """;

    @Test
    void foundEntriesAreOkAndASharedGroupEntryIsReadOnce() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1");

        EntryCheck.Result result = run(nodes("", ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.line(DEV1, "username")).hasValueSatisfying(line -> {
            assertThat(line.shown()).isEqualTo("INUBIT_ACME_DEV_USERNAME");
            assertThat(line.status()).isEqualTo(EntryCheck.Status.OK);
        });
        assertThat(result.line(DEV2, "password").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.OK);
        assertThat(result.line(NodeId.parse("prod/node1"), "username")).isEmpty();
        // Q27: each distinct account once per run, also when two nodes share the group entry
        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .doesNotHaveDuplicates()
            .contains("INUBIT_ACME_DEV_USERNAME", "INUBIT_ACME_DEV_NODE1_USERNAME",
                "INUBIT_ACME_DEV_NODE2_USERNAME");
    }

    @Test
    void aMissingRequiredEntryIsAnErrorNamingBothEntriesAndTheStore() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe");

        EntryCheck.Result result = run(nodes("", ""));

        assertThat(result.line(DEV1, "password")).hasValueSatisfying(line -> {
            assertThat(line.shown()).isEqualTo(
                "INUBIT_ACME_DEV_NODE1_PASSWORD | INUBIT_ACME_DEV_PASSWORD");
            assertThat(line.status()).isEqualTo(EntryCheck.Status.NOT_FOUND);
        });
        assertThat(result.errors()).contains("dev/node1: credential store entry"
            + " INUBIT_ACME_DEV_NODE1_PASSWORD (or INUBIT_ACME_DEV_PASSWORD) in the macOS"
            + " Keychain for the password: not found");
    }

    @Test
    void anInvalidOrDeniedEntryIsAnErrorWithoutTheValue() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jd:oe-Secret")
            .fail("INUBIT_ACME_DEV_NODE1_PASSWORD", FailureKind.ACCESS_DENIED, "exit 128")
            .put("INUBIT_ACME_DEV_PASSWORD", "abc");

        EntryCheck.Result result = run(nodes("", ""));

        assertThat(result.line(DEV1, "username").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.INVALID_VALUE);
        assertThat(result.line(DEV1, "password").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.ACCESS_DENIED);
        assertThat(result.line(DEV2, "password").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.INVALID_VALUE);
        assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains(
            "INUBIT_ACME_DEV_USERNAME", "invalid value (the username must not contain ':'"))
            .anySatisfy(error -> assertThat(error).contains("INUBIT_ACME_DEV_NODE1_PASSWORD",
                "access denied"))
            .allSatisfy(error -> assertThat(error).doesNotContain("jd:oe-Secret", "abc ("));
    }

    @Test
    void anUnavailableStoreIsOneWarningAndTheRestIsNotChecked() {
        store.fail("INUBIT_ACME_DEV_NODE1_USERNAME", FailureKind.STORE_NOT_AVAILABLE,
            "secret-tool is not installed");

        EntryCheck.Result result = run(nodes("", ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).singleElement().asString().contains("macOS Keychain",
            "dev/node1, dev/node2", "resolvability not checked", "could not be asked")
            .doesNotContain("secret-tool is not installed");
        assertThat(result.line(DEV2, "password").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.NOT_CHECKED);
        assertThat(store.reads()).hasSize(1);
    }

    @Test
    void anUnansweredDialogIsNotCheckedNotAnError() {
        store.fail("INUBIT_ACME_DEV_NODE1_USERNAME", FailureKind.TIMEOUT, "no answer");

        EntryCheck.Result result = run(nodes("", ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).singleElement().asString().contains("access dialog");
    }

    @Test
    void aTrustStorePasswordEntryWithACliIsAnErrorAndWithoutATrustStoreAWarning() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1")
            .put("INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "ts-Pw-2");

        EntryCheck.Result result = run(nodes("", """
            tls:
              trustStore: /tmp/ts.p12
            cli:
              home: /opt/inubit/client
            """));

        assertThat(result.errors()).singleElement().asString().contains("dev/node1",
            "INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "CLI");
        assertThat(result.warnings()).singleElement().asString().contains("dev/node2",
            "INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "ignored", "tls.trustStore");
        assertThat(result.line(DEV1, "trustStorePassword")).isPresent();
    }

    @Test
    void endToEndEntriesOverPlainHttpAreAnError() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1")
            .put("INUBIT_ACME_DEV_E2E_USERNAME", "e2e-user")
            .put("INUBIT_ACME_DEV_E2E_PASSWORD", "e2e-Pw-3");

        EntryCheck.Result result = run(nodes("""
            development:
              enabled: true
            e2eTests: FREE
            e2e:
              soap:
                baseUrl: http://inubit-dev.example.test:8080
            """, ""));

        assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains("dev/node1",
            "http://", "INUBIT_ACME_DEV_E2E_USERNAME"));
        assertThat(result.line(DEV1, "e2eUsername").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.OK);
    }

    @Test
    void onlyOneEndToEndEntryIsAnError() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1")
            .put("INUBIT_ACME_DEV_E2E_USERNAME", "e2e-user");

        assertThat(run(nodes(E2E_ALLOWED, "")).errors()).anySatisfy(error -> assertThat(error)
            .contains("dev/node1", "INUBIT_ACME_DEV_NODE1_E2E_PASSWORD", "both"));
    }

    @Test
    void withoutCredentialStoreNodesNothingIsRead() {
        List<EffectiveNodeConfig> awsOnly = nodes("", "").stream()
            .filter(node -> node.secretPlan().aws()).toList();

        EntryCheck.Result result = run(awsOnly);

        assertThat(store.reads()).isEmpty();
        assertThat(result.errors()).isEmpty();
        assertThat(result.store()).isEqualTo(StoreKind.KEYCHAIN);
    }

    // --- review T016-T017 r1 ---

    @Test
    void theBoundIsSixtySecondsPerNode() {
        assertThat(EntryCheck.TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void eachNodeHasItsOwnDeadlineAndEachReadGetsTheTimeLeft() {
        store.put("INUBIT_ACME_DEV_NODE1_USERNAME", "jdoe")
            .delay("INUBIT_ACME_DEV_NODE1_USERNAME", Duration.ofMillis(150))
            .put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1")
            .put("INUBIT_ACME_DEV_NODE2_USERNAME", "jdoe2");

        EntryCheck.Result result = EntryCheck.run(nodes("", ""), store, Duration.ofMillis(400));

        assertThat(result.warnings()).isEmpty();
        List<FakeCredentialStore.Read> reads = store.reads();
        Duration afterSlowRead = reads.stream()
            .filter(read -> read.account().equals("INUBIT_ACME_DEV_NODE1_PASSWORD"))
            .findFirst().orElseThrow().timeout();
        Duration node2First = reads.stream()
            .filter(read -> read.account().equals("INUBIT_ACME_DEV_NODE2_USERNAME"))
            .findFirst().orElseThrow().timeout();
        // node1's later read got only what was left; node2 starts with a fresh 400 ms
        assertThat(afterSlowRead).isLessThanOrEqualTo(Duration.ofMillis(400 - 150));
        assertThat(node2First).isGreaterThan(afterSlowRead)
            .isLessThanOrEqualTo(Duration.ofMillis(400));
    }

    @Test
    void aNodeWhoseBudgetRunsOutIsNotCheckedAndStopsTheStore() {
        store.put("INUBIT_ACME_DEV_NODE1_USERNAME", "jdoe")
            .delay("INUBIT_ACME_DEV_NODE1_USERNAME", Duration.ofMillis(250))
            .put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1");

        EntryCheck.Result result = EntryCheck.run(nodes("", ""), store, Duration.ofMillis(200));

        assertThat(result.line(DEV1, "password").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.NOT_CHECKED);
        assertThat(result.line(DEV2, "username").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.NOT_CHECKED);
        assertThat(result.warnings()).singleElement().asString().contains("dev/node1",
            "dev/node2", "access dialog");
        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .noneMatch(account -> account.contains("NODE2"));
    }

    @Test
    void theFirstTimeoutStopsTheStore() {
        store.fail("INUBIT_ACME_DEV_NODE1_USERNAME", FailureKind.TIMEOUT, "no answer");

        EntryCheck.Result result = run(nodes("", ""));

        assertThat(store.reads()).hasSize(1);
        assertThat(result.line(DEV1, "password").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.NOT_CHECKED);
        assertThat(result.line(DEV2, "username").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.NOT_CHECKED);
        assertThat(result.line(DEV2, "password").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.NOT_CHECKED);
    }

    @Test
    void theWarningNamesOnlyNodesThatWereNotFullyRead() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1")
            .fail("INUBIT_ACME_DEV_NODE2_USERNAME", FailureKind.TIMEOUT, "no answer");

        EntryCheck.Result result = run(nodes("", ""));

        assertThat(result.line(DEV1, "username").orElseThrow().status())
            .isEqualTo(EntryCheck.Status.OK);
        assertThat(result.warnings()).singleElement().asString().contains("dev/node2")
            .doesNotContain("dev/node1");
    }

    @Test
    void theWarningCarriesAFixedReasonNotTheToolOutput() {
        store.fail("INUBIT_ACME_DEV_NODE1_USERNAME", FailureKind.STORE_NOT_AVAILABLE,
            "exit 1: secret-tool: Cannot autolaunch D-Bus without X11 $DISPLAY");
        FakeCredentialStore linux = new FakeCredentialStore(StoreKind.SECRET_SERVICE);
        linux.fail("INUBIT_ACME_DEV_NODE1_USERNAME", FailureKind.STORE_NOT_AVAILABLE,
            "exit 1: secret-tool: Cannot autolaunch D-Bus without X11 $DISPLAY");

        EntryCheck.Result result = EntryCheck.run(nodes("", ""), linux, Duration.ofSeconds(5));

        assertThat(result.warnings()).singleElement().asString().contains("Secret Service",
            "secret-tool is not installed or no Secret Service")
            .doesNotContain("exit 1", "autolaunch", "$DISPLAY");
    }

    @Test
    void withoutASupportedStoreTheWarningSaysSo() {
        FakeCredentialStore none = new FakeCredentialStore(StoreKind.NONE);
        none.fail("INUBIT_ACME_DEV_NODE1_USERNAME", FailureKind.STORE_NOT_AVAILABLE,
            "no supported credential store on FreeBSD");

        EntryCheck.Result result = EntryCheck.run(nodes("", ""), none, Duration.ofSeconds(5));

        assertThat(result.warnings()).singleElement().asString()
            .startsWith("No supported credential store on this system for dev/node1, dev/node2");
    }

    @Test
    void theNodeEntryIsReadBeforeTheGroupEntry() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1");

        run(nodes("", ""));

        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .containsSubsequence("INUBIT_ACME_DEV_NODE1_USERNAME", "INUBIT_ACME_DEV_USERNAME");
    }

    @Test
    void anInvalidOrDeniedNodeEntryDoesNotFallBackButAnEmptyOneDoes() {
        store.put("INUBIT_ACME_DEV_NODE1_USERNAME", "jd:oe")
            .put("INUBIT_ACME_DEV_USERNAME", "jdoe")
            .fail("INUBIT_ACME_DEV_NODE1_PASSWORD", FailureKind.ACCESS_DENIED, "exit 128")
            .put("INUBIT_ACME_DEV_NODE2_PASSWORD", "")
            .put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1");

        EntryCheck.Result result = run(nodes("", ""));

        assertThat(result.line(DEV1, "username").orElseThrow()).isEqualTo(
            new EntryCheck.Line("INUBIT_ACME_DEV_NODE1_USERNAME", EntryCheck.Status.INVALID_VALUE));
        assertThat(result.line(DEV1, "password").orElseThrow()).isEqualTo(
            new EntryCheck.Line("INUBIT_ACME_DEV_NODE1_PASSWORD", EntryCheck.Status.ACCESS_DENIED));
        assertThat(result.line(DEV2, "password").orElseThrow()).isEqualTo(
            new EntryCheck.Line("INUBIT_ACME_DEV_PASSWORD", EntryCheck.Status.OK));
    }

    @Test
    void absentOptionalKindsAreHidden() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1");

        EntryCheck.Result result = run(nodes(E2E_ALLOWED, ""));

        assertThat(result.line(DEV1, "trustStorePassword")).isEmpty();
        assertThat(result.line(DEV1, "e2eUsername")).isEmpty();
        assertThat(result.line(DEV1, "e2ePassword")).isEmpty();
    }

    @Test
    void oneOfTwoEndToEndEntriesIsNoErrorOnceTheStoreIsUnavailable() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1")
            .put("INUBIT_ACME_DEV_NODE1_E2E_USERNAME", "e2e-user")
            .fail("INUBIT_ACME_DEV_NODE1_E2E_PASSWORD", FailureKind.TIMEOUT, "no answer");

        EntryCheck.Result result = run(nodes(E2E_ALLOWED, ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).singleElement().asString().contains("access dialog");
    }

    @Test
    void endToEndEntriesAreNotReadWhileEndToEndTestsAreForbidden() {
        store.put("INUBIT_ACME_DEV_USERNAME", "jdoe").put("INUBIT_ACME_DEV_PASSWORD", "group-Pw-1")
            .put("INUBIT_ACME_DEV_E2E_USERNAME", "e2e-user");

        EntryCheck.Result result = run(nodes("""
            e2e:
              soap:
                baseUrl: http://inubit-dev.example.test:8080
            """, ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.line(DEV1, "e2eUsername")).isEmpty();
        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .noneMatch(account -> account.contains("E2E"));
    }
}
