package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Feature 011 (research R-8, quickstart Q23, review T013-T015 r2): a rejected login inside a
 * fan-out on a node whose credentials are held starts a re-read that no allowance covers. The
 * branch ends in {@code TIMEOUT}, the re-read runs to its end, and the next call logs in with the
 * new value — through the production wiring, the credential guard and a credential store double.
 */
@Timeout(60)
class RotationInsideAFanOutTest {

    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    private static final String USER = "rotation-user";
    private static final String OLD_PASSWORD = "old-Password-1";
    private static final String NEW_PASSWORD = "new-Password-2";
    /** Longer than the fan-out deadline of a node with timeout PT1S (3 × 1 s + 1 s grace). */
    private static final Duration REREAD = Duration.ofSeconds(6);

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

    private static String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString((USER + ":" + password)
            .getBytes(StandardCharsets.UTF_8));
    }

    private void answer(String password, int status) {
        inubit.stubFor(post(urlPathEqualTo(QUEUE_LOG))
            .withHeader("Authorization", equalTo(basic(password)))
            .willReturn(status == 200
                ? aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("{\"queueLog\":{\"total\":0,\"success\":true,\"count\":0}}")
                : aResponse().withStatus(status).withHeader("Content-Type", "text/html")
                    .withBody("<html>" + status + "</html>")));
    }

    @Test
    void theBranchTimesOutDuringTheReReadAndTheNextCallUsesTheNewValue() throws Exception {
        inubit = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck"))
            .willReturn(RestFixtures.response("healthcheck", "json")));
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        answer(OLD_PASSWORD, 200);
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
                    timeout: PT1S
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
            TestCertificates.get().trustStore(), inubit.httpsPort());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        FakeCredentialStore store = new FakeCredentialStore()
            .put("INUBIT_ACME_DEV_USERNAME", USER)
            .put("INUBIT_ACME_DEV_PASSWORD", OLD_PASSWORD);
        SecretScrubber scrubber = new SecretScrubber();
        wiring = TestWiring.of(config, store, scrubber, path -> false, false);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
        Map<String, Object> request = Map.of("target", "dev/node1");

        JsonNode held = client.callTool("find_processes", request, Duration.ofSeconds(20));
        assertThat(held.toString()).doesNotContain("TIMEOUT", "AUTH_FAILED");

        // the password is rotated: INUBIT rejects the old one, the store holds the new one and
        // its read takes longer than the branch deadline
        store.put("INUBIT_ACME_DEV_PASSWORD", NEW_PASSWORD)
            .delay("INUBIT_ACME_DEV_NODE1_PASSWORD", REREAD);
        answer(OLD_PASSWORD, 401);
        answer(NEW_PASSWORD, 200);
        long start = System.nanoTime();

        JsonNode rotating = client.callTool("find_processes", request, Duration.ofSeconds(20));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(REREAD);
        assertThat(rotating.toString()).contains("TIMEOUT").doesNotContain(OLD_PASSWORD,
            NEW_PASSWORD);

        // the re-read ends in the background; then the next call uses the new value
        Thread.sleep(REREAD.plusSeconds(1));
        store.delay("INUBIT_ACME_DEV_NODE1_PASSWORD", Duration.ZERO);
        JsonNode rotated = client.callTool("find_processes", request, Duration.ofSeconds(20));

        assertThat(rotated.toString()).doesNotContain("TIMEOUT", "AUTH_FAILED");
        assertThat(inubit.getAllServeEvents().get(0).getRequest().getHeader("Authorization"))
            .as("the newest request").isEqualTo(basic(NEW_PASSWORD));
    }
}
