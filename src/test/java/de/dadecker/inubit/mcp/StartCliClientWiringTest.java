package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigLocation;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Feature 013 (T020, FR-010): the production wiring on the host platform with a StartCLI
 * installation — on Windows {@code startcli.bat}, started through the Windows launch. The
 * StartCLI-backed tools are announced, {@code list_nodes} shows the CLI as available and
 * {@code get_health} reports the client the probe ({@code -v}) found. The substitute StartCLI
 * replays the recorded version output; INUBIT is an HTTPS WireMock.
 */
class StartCliClientWiringTest {

    @TempDir
    Path temp;

    private WireMockServer inubit;
    private final SecretScrubber scrubber = new SecretScrubber();
    private Wiring wiring;
    private McpTestClient client;

    @BeforeEach
    void start() throws IOException {
        inubit = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        stub("/ibis/rest/healthcheck", "healthcheck", "json");
        stub("/ibis/rest/ready", "ready", "json");
        stub("/ibis/rest/system/info", "system_info", "xml");
        stub("/ibis/rest/metrics", "metrics", "json");
        Path cliHome = temp.resolve("client");
        FakeStartCli.replaying(cliHome, "version");
        wire("", """
                    cli:
                      home: %s
                      javaHome: %s
            """.formatted(cliHome, System.getProperty("java.home")));
    }

    /**
     * Wires one {@code AUTO} node {@code dev/node1} with the {@code defaults} lines
     * {@code defaults} and the node lines {@code nodeLines} (both indented as in the profile).
     */
    private void wire(String defaults, String nodeLines) throws IOException {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            """.formatted(temp.resolve("audit"), temp.resolve("workspace")) + defaults + """
            groups:
              - name: dev
                development:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
                    versionLine: AUTO
                    timeout: PT30S
                    tls:
                      trustStore: %s
            """.formatted(inubit.httpsPort(), TestCertificates.get().trustStore()) + nodeLines;
        ProfileConfig config = new ConfigLoader(Map.of(), temp, Platform.windows())
            .parse(yaml, temp.resolve("config.yaml")).config();
        wiring = new Wiring(config, TestSecrets.store(Map.of(
            "INUBIT_ACME_DEV_USERNAME", "test-user",
            "INUBIT_ACME_DEV_PASSWORD", "test-password")), scrubber, ClockProvider.system(),
            Files::exists, Platform.windows(), new SystemProcessLauncher(), System.getenv(),
            FakeParameterStore::new,
            new ConfigLocation(temp.resolve("config"), temp.resolve("config/config.yaml")),
            temp.resolve("home"));
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    private void stub(String path, String fixture, String extension) {
        inubit.stubFor(get(urlPathEqualTo(path))
            .willReturn(RestFixtures.response(fixture, extension)));
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

    @Test
    void theStartCliBackedToolsAreAnnounced() {
        assertThat(wiring.toolHandlers().stream().map(ToolHandler::name).toList())
            .contains("export_artifacts", "export_archives", "import_artifacts");
    }

    @Test
    void listNodesShowsTheCliAsAvailable() {
        JsonNode node = client.callTool("list_nodes", Map.of()).path("structuredContent")
            .path("groups").path(0).path("nodes").path(0);

        assertThat(node.path("cliAvailable").asBoolean()).as(node.toString()).isTrue();
    }

    @Test
    void getHealthReportsTheProbedClient() {
        JsonNode result = client.callTool("get_health", Map.of("target", "dev/node1"),
            Duration.ofSeconds(60));

        JsonNode report = result.path("structuredContent").path("reports").path(0);
        assertThat(report.path("client").path("version").asString()).as(result.toString())
            .isEqualTo("8.1.17");
        assertThat(report.path("client").path("supportedTo").asString()).isEqualTo("8.1.17");
        assertThat(report.path("unavailable").toString()).doesNotContain("client");
    }

    /**
     * Feature 017 (T022, research D-6, D-7): with {@code defaults.cliClients} only, the client is
     * chosen per call by the version: unknown at first (no version-less client, so none is
     * available), then the 8.1 entry for the 8.1.17 that {@code get_health} reads.
     */
    private void wireWithVersionEntries() throws IOException {
        Path client81 = temp.resolve("client-8.1");
        FakeStartCli.replaying(client81, "version");
        wire("""
            defaults:
              cliClients:
                "8.1":
                  home: %s
                  javaHome: %s
                "9.0":
                  home: %s
            """.formatted(client81, System.getProperty("java.home"),
            temp.resolve("client-9.0")), "");
    }

    private JsonNode listedNode() {
        return client.callTool("list_nodes", Map.of()).path("structuredContent")
            .path("groups").path(0).path("nodes").path(0);
    }

    @Test
    void withVersionEntriesGetHealthReportsTheClientOfTheReadVersion() throws IOException {
        wireWithVersionEntries();

        JsonNode result = client.callTool("get_health", Map.of("target", "dev/node1"),
            Duration.ofSeconds(60));

        JsonNode report = result.path("structuredContent").path("reports").path(0);
        assertThat(report.path("version").asString()).as(result.toString()).isEqualTo("8.1.17");
        assertThat(report.path("client").path("version").asString()).as(result.toString())
            .isEqualTo("8.1.17");
        assertThat(report.path("unavailable").toString()).doesNotContain("client");
    }

    @Test
    void withVersionEntriesListNodesShowsTheClientOfTheKnownVersion() throws IOException {
        wireWithVersionEntries();
        JsonNode before = listedNode();
        assertThat(before.path("cliAvailable").asBoolean()).as(before.toString()).isFalse();
        assertThat(before.path("version").path("capabilities").path("usable").toString())
            .doesNotContain("workspace");

        client.callTool("get_health", Map.of("target", "dev/node1"), Duration.ofSeconds(60));

        JsonNode after = listedNode();
        assertThat(after.path("cliAvailable").asBoolean()).as(after.toString()).isTrue();
        assertThat(after.path("version").path("client").path("version").asString())
            .as(after.toString()).isEqualTo("8.1.17");
    }

    @Test
    void withVersionEntriesTheExportToolsAreAnnounced() throws IOException {
        wireWithVersionEntries();

        assertThat(wiring.toolHandlers().stream().map(ToolHandler::name).toList())
            .contains("export_artifacts", "export_archives");
    }
}
