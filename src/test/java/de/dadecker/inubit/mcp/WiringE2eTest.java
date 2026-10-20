package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Feature 010 (T012, FR-006a, FR-011, research R-7): {@code run_e2e_test} through the production
 * wiring. The end-to-end credentials of an AWS node come from their own fetch on the first test,
 * the SOAP client's trust store password from the node's main fetch; environment nodes keep their
 * variables. INUBIT and its SOAP endpoint are one HTTPS WireMock whose certificate is trusted
 * through the password-protected test trust store.
 */
class WiringE2eTest {

    private static final StoreLocation EU = new StoreLocation("eu-central-1", Optional.empty());
    private static final String USERNAME = "/test/dev/username";
    private static final String PASSWORD = "/test/dev/password";
    private static final String TRUST = "/test/dev/truststore-password";
    private static final String E2E_USERNAME = "/test/dev/e2e-username";
    private static final String E2E_PASSWORD = "/test/dev/e2e-password";
    private static final String SERVICE = "/ibis/ws/Service-01";
    private static final Map<String, Object> RUN = Map.of("node", "dev/node1",
        "envelope", "samples/order.xml", "path", SERVICE);

    @TempDir
    Path temp;

    private WireMockServer inubit;
    private final FakeParameterStore store = new FakeParameterStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"));
    private final SecretScrubber scrubber = new SecretScrubber();
    private Wiring wiring;
    private McpTestClient client;

    @BeforeEach
    void start() throws IOException {
        inubit = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        inubit.stubFor(post(urlPathEqualTo(SERVICE)).willReturn(aResponse().withStatus(200)
            .withBody("<ok/>")));
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("samples"));
        Files.writeString(workspace.resolve("samples/order.xml"), "<Envelope/>");
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        inubit.stop();
    }

    /** {@code credentials}: the dev group's {@code credentials} section, or empty. */
    private void startServer(String credentials, Map<String, String> environment) {
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
                development:
                  enabled: true
                e2eTests: FREE
                e2e:
                  soap:
                    baseUrl: https://localhost:%d
            %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
                    versionLine: V8_1
                    tls:
                      trustStore: %s
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"), inubit.httpsPort(),
            credentials.indent(4).stripTrailing(), inubit.httpsPort(),
            TestCertificates.get().protectedTrustStore());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        wiring = new Wiring(config, new CredentialResolver(environment, scrubber,
                config.terminology().effectiveOrDefault(), config.credentialPrefix(),
                config.secretPlans()).resolve(config.nodeIds()), scrubber,
            new ClockProvider(clock), path -> false, false, new SystemProcessLauncher(),
            environment, () -> store);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    private void startAwsServer() {
        startServer("""
            credentials:
              source:
                type: awsParameterStore
                region: eu-central-1
              parameters:
                username: %s
                password: %s
                trustStorePassword: %s
                e2eUsername: %s
                e2ePassword: %s
            """.formatted(USERNAME, PASSWORD, TRUST, E2E_USERNAME, E2E_PASSWORD), Map.of());
    }

    private void putMainParameters() {
        store.put(EU, USERNAME, "test-user").put(EU, PASSWORD, "test-password")
            .put(EU, TRUST, TestCertificates.PASSWORD);
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private List<List<String>> requestedNames() {
        return store.requests().stream().map(FakeParameterStore.Request::names).toList();
    }

    private static void assertSucceeded(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(result.path("structuredContent").path("result").path("status").asInt())
            .as(result.toString()).isEqualTo(200);
    }

    @Test
    void theEndToEndCredentialsOfAnAwsNodeAreFetchedOnlyWhenAnEndToEndTestRuns() {
        putMainParameters();
        store.put(EU, E2E_USERNAME, "test-e2e-user").put(EU, E2E_PASSWORD, "test-e2e-password");
        startAwsServer();

        client.callTool("list_nodes", Map.of());
        assertThat(store.calls()).as("nothing at startup or for list_nodes").isZero();

        client.callTool("query_logs", Map.of("target", "dev/node1", "logType", "systemLog"));
        assertThat(requestedNames()).as("another tool fetches the main parameters only")
            .containsExactly(List.of(USERNAME, PASSWORD, TRUST));

        assertSucceeded(client.callTool("run_e2e_test", RUN));
        assertSucceeded(client.callTool("run_e2e_test", RUN));

        assertThat(requestedNames()).as("one end-to-end fetch, held for the second test")
            .containsExactly(List.of(USERNAME, PASSWORD, TRUST),
                List.of(E2E_USERNAME, E2E_PASSWORD));
        // TLS with the trust store password from the store, Basic auth with the e2e values
        inubit.verify(2, postRequestedFor(urlPathEqualTo(SERVICE))
            .withHeader("Authorization", equalTo(basic("test-e2e-user", "test-e2e-password"))));
    }

    @Test
    void aFailedEndToEndFetchNamesTheParametersAndIsRetriedAfterTheBackOff() {
        putMainParameters();
        store.put(EU, E2E_USERNAME, "test-e2e-user")
            .fail(EU, E2E_PASSWORD, FailureKind.ACCESS_DENIED, "test detail");
        startAwsServer();

        JsonNode failed = client.callTool("run_e2e_test", RUN);

        assertThat(failed.path("isError").asBoolean()).as(failed.toString()).isTrue();
        assertThat(failed.toString()).contains("AUTH_FAILED", "dev/node1",
            "end-to-end test credentials", "access denied for " + E2E_PASSWORD)
            .doesNotContain("test-e2e-user", "test detail");
        assertThat(inubit.findAll(postRequestedFor(urlPathEqualTo(SERVICE))))
            .as("nothing was sent").isEmpty();

        clock.advance(Duration.ofSeconds(29));
        JsonNode within = client.callTool("run_e2e_test", RUN);

        assertThat(within.path("isError").asBoolean()).isTrue();
        assertThat(within.toString()).contains("access denied for " + E2E_PASSWORD);
        assertThat(store.calls()).as("main fetch plus one e2e fetch, no new one within 30 s")
            .isEqualTo(2);

        clock.advance(Duration.ofSeconds(1));
        store.put(EU, E2E_PASSWORD, "test-e2e-password");

        assertSucceeded(client.callTool("run_e2e_test", RUN));
        assertThat(store.calls()).isEqualTo(3);
        inubit.verify(1, postRequestedFor(urlPathEqualTo(SERVICE))
            .withHeader("Authorization", equalTo(basic("test-e2e-user", "test-e2e-password"))));
    }

    @Test
    void aFailedMainFetchFailsTheTestBeforeTheEndToEndFetchAndIsNotCached() {
        store.put(EU, USERNAME, "test-user")
            .fail(EU, PASSWORD, FailureKind.NOT_FOUND, "test detail")
            .put(EU, TRUST, TestCertificates.PASSWORD)
            .put(EU, E2E_USERNAME, "test-e2e-user").put(EU, E2E_PASSWORD, "test-e2e-password");
        startAwsServer();

        JsonNode failed = client.callTool("run_e2e_test", RUN);

        assertThat(failed.path("isError").asBoolean()).as(failed.toString()).isTrue();
        assertThat(failed.toString()).contains("AUTH_FAILED", "not found for " + PASSWORD);
        assertThat(requestedNames()).containsExactly(List.of(USERNAME, PASSWORD, TRUST));

        clock.advance(Duration.ofSeconds(30));
        store.put(EU, PASSWORD, "test-password");

        assertSucceeded(client.callTool("run_e2e_test", RUN));
    }

    @Test
    void anEnvironmentNodeKeepsItsVariablesAndNeverTouchesTheStore() {
        startServer("", Map.of(
            "INUBIT_ACME_DEV_USERNAME", "test-user",
            "INUBIT_ACME_DEV_PASSWORD", "test-password",
            "INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", TestCertificates.PASSWORD,
            "INUBIT_ACME_DEV_E2E_USERNAME", "test-env-e2e-user",
            "INUBIT_ACME_DEV_E2E_PASSWORD", "test-env-e2e-password"));

        assertSucceeded(client.callTool("run_e2e_test", RUN));

        assertThat(store.calls()).isZero();
        inubit.verify(1, postRequestedFor(urlPathEqualTo(SERVICE)).withHeader("Authorization",
            equalTo(basic("test-env-e2e-user", "test-env-e2e-password"))));
    }
}
