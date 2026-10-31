package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Feature 011 (SC-002, research R-15): no INUBIT credential is read from an environment variable.
 * Every name the profile derives ({@link ProfileConfig#credentialNameSet()}) is set in the
 * environment of the production wiring to a distinct value, the credential store double holds
 * other values; a tool run authenticates with the store's values only, and no environment value
 * reaches a request or a result.
 */
@Timeout(60)
class NoEnvironmentCredentialsTest {

    private static final String STORE_USER = "store-user";
    private static final String STORE_PASSWORD = "store-Password-1";

    @TempDir
    Path temp;

    private WireMockServer inubit;
    private TestWiring wiring;
    private McpTestClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        if (inubit != null) {
            inubit.stop();
        }
    }

    @Test
    void noEnvironmentValueIsEverUsed() {
        inubit = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck"))
            .willReturn(RestFixtures.response("healthcheck", "json")));
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
                versionLine: V8_1
                tls:
                  trustStore: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
            TestCertificates.get().trustStore(), inubit.httpsPort());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        Map<String, String> environment = new HashMap<>();
        List<String> environmentValues = new ArrayList<>();
        int i = 0;
        for (String name : config.credentialNameSet()) {
            String value = "env-value-" + (i++) + "-" + name.toLowerCase();
            environment.put(name, value);
            environmentValues.add(value);
        }
        assertThat(environment).containsKeys("INUBIT_ACME_DEV_USERNAME",
            "INUBIT_ACME_DEV_NODE1_PASSWORD", "INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD",
            "INUBIT_ACME_DEV_E2E_PASSWORD");
        environment.put("PATH", "/usr/bin");
        FakeCredentialStore store = new FakeCredentialStore()
            .put("INUBIT_ACME_DEV_USERNAME", STORE_USER)
            .put("INUBIT_ACME_DEV_PASSWORD", STORE_PASSWORD);
        SecretScrubber scrubber = new SecretScrubber();
        wiring = TestWiring.of(config, store, scrubber, path -> false, false,
            spec -> {
                throw new AssertionError("no StartCLI run expected: " + spec.command());
            }, environment);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();

        JsonNode result = client.callTool("get_health",
            Map.of("target", "dev/node1", "includeSystemInfo", true), Duration.ofSeconds(30));

        List<String> authorizations = inubit.getAllServeEvents().stream()
            .map(event -> event.getRequest())
            .filter(request -> request.containsHeader("Authorization"))
            .map((LoggedRequest request) -> request.getHeader("Authorization"))
            .toList();
        assertThat(authorizations).isNotEmpty().containsOnly("Basic " + Base64.getEncoder()
            .encodeToString((STORE_USER + ":" + STORE_PASSWORD)
                .getBytes(StandardCharsets.UTF_8)));
        String requests = inubit.getAllServeEvents().stream()
            .map(event -> event.getRequest().toString()).reduce("", String::concat);
        for (String value : environmentValues) {
            assertThat(requests).doesNotContain(value);
            assertThat(result.toString()).doesNotContain(value);
        }
        assertThat(store.reads()).extracting(FakeCredentialStore.Read::account)
            .contains("INUBIT_ACME_DEV_PASSWORD");
    }
}
