package de.dadecker.inubit.mcp.mcp.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.HealthService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.VersionLine;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T056: {@code get_health} over MCP with the real adapters against WireMock (contracts/mcp-tools.md
 * §2, quickstart V2/V3): {@code dev/node1} is healthy, both {@code qa} servers are in maintenance
 * mode, not ready and unlicensed for metrics, and {@code staging/bogus} is unreachable.
 */
@Timeout(60)
class GetHealthToolTest {

    private static final String DESCRIPTION = "[acme] Check whether the INUBIT nodes are"
        + " reachable and ready: maintenance mode, version, memory, threads, and blocking queue."
        + " `target` takes the id of one node (`<group>/<node>`) or of one group (all its nodes);"
        + " omit it for all nodes.";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String PASSWORD = "get-health-test-pw";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static WireMockServer dev;
    private static WireMockServer integration;
    private static WireMockServer other;

    private SecretScrubber scrubber;
    private AdapterGatewayFactory gateways;
    private McpTestClient client;

    @BeforeAll
    static void start() {
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        integration = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        other = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        dev.stop();
        integration.stop();
        other.stop();
    }

    @BeforeEach
    void setUp() {
        dev.resetAll();
        integration.resetAll();
        other.resetAll();
        stub(dev, "/ibis/rest/healthcheck", "healthcheck", "json");
        stub(dev, "/ibis/rest/ready", "ready", "json");
        stub(dev, "/ibis/rest/system/info", "system_info", "xml");
        stub(dev, "/ibis/rest/metrics", "metrics", "json");
        stub(integration, "/ibis/rest/healthcheck", "healthcheck_maintenance", "json");
        stub(integration, "/ibis/rest/ready", "ready_not_ready", "json");
        stub(integration, "/ibis/rest/system/info", "system_info", "xml");
        stub(integration, "/ibis/rest/metrics", "metrics_unlicensed", "json");

        start(List.of(
            server("dev/node1", "https://localhost:" + dev.httpsPort(), VersionLine.AUTO),
            server("qa/node1", "https://localhost:" + integration.httpsPort(),
                VersionLine.V8_1),
            server("qa/node2", "https://localhost:" + integration.httpsPort(),
                VersionLine.V8_1),
            server("staging/bogus", "https://127.0.0.1:9", VersionLine.V8_1)));
    }

    private void start(List<EffectiveNodeConfig> servers) {
        if (client != null) {
            client.close();
            gateways.close();
        }
        List<NodeId> ids = servers.stream().map(EffectiveNodeConfig::id).toList();
        scrubber = new SecretScrubber();
        gateways = new AdapterGatewayFactory(servers, new CredentialResolver(Map.of(
            "INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", PASSWORD,
            "INUBIT_QA_USERNAME", "jdoe", "INUBIT_QA_PASSWORD", PASSWORD,
            "INUBIT_STAGING_USERNAME", "jdoe", "INUBIT_STAGING_PASSWORD", PASSWORD), scrubber,
            "INUBIT")
            .resolve(ids), scrubber, Clock.systemUTC(),
            new CliRunner(new SystemProcessLauncher(), Map.of(), false,
                new CliResources("acme")));
        Map<NodeId, Duration> timeouts = servers.stream().collect(Collectors.toMap(
            EffectiveNodeConfig::id, EffectiveNodeConfig::timeout));
        HealthService health = new HealthService(gateways, new TargetResolver(ids),
            new FanOut(), timeouts::get, Clock.systemUTC());
        client = McpTestClient.start(List.of(new GetHealthTool(health)), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
        gateways.close();
        client = null;
    }

    private static EffectiveNodeConfig server(String id, String baseUrl, VersionLine line) {
        return TestNodeConfig.node().id(id).baseUrl(baseUrl)
            .trustStore(TestCertificates.get().trustStore()).timeout(TIMEOUT).versionLine(line)
            .build();
    }

    private static void stub(WireMockServer server, String path, String fixture,
        String extension) {
        stub(server, path, fixture, extension, 0);
    }

    private static void stub(WireMockServer server, String path, String fixture,
        String extension, int delayMillis) {
        server.stubFor(get(urlPathEqualTo(path))
            .willReturn(RestFixtures.response(fixture, extension).withFixedDelay(delayMillis)));
    }

    private static int authenticatedRequests(WireMockServer server) {
        return server.findAll(com.github.tomakehurst.wiremock.client.WireMock
            .getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
            .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock
                .matching("Basic .+"))).size();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("get_health", arguments);
    }

    private static Map<String, JsonNode> byServer(JsonNode result) {
        return java.util.stream.StreamSupport.stream(
                result.path("structuredContent").path("reports").spliterator(), false)
            .collect(Collectors.toMap(report -> report.path("node").asString(),
                Function.identity()));
    }

    private static void assertMatchesOutputSchema(JsonNode result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/get_health.output.json"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = JSON.treeToValue(result.path("structuredContent"),
            Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, content);
        assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
    }

    @Test
    void theToolIsListedReadOnlyIdempotentAndOpenWorld() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("get_health");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean()).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
        JsonNode input = tool.path("inputSchema");
        assertThat(input.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(input.path("properties").propertyNames())
            .containsExactlyInAnyOrder("target", "includeSystemInfo");
    }

    @Test
    void withoutTargetEveryServerIsReportedInConfigOrderWithinTimeoutPlusOneSecond() {
        long start = System.nanoTime();
        JsonNode result = call(Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(elapsed).as("SC-002").isLessThan(TIMEOUT.plusSeconds(1));
        assertMatchesOutputSchema(result);
        JsonNode reports = result.path("structuredContent").path("reports");
        assertThat(reports).extracting(report -> report.path("node").asString())
            .containsExactly("dev/node1", "qa/node1", "qa/node2", "staging/bogus");
        assertThat(JSON.readTree(result.path("content").get(0).path("text").asString()))
            .isEqualTo(result.path("structuredContent"));

        JsonNode healthy = reports.get(0);
        assertThat(healthy.path("group").asString()).isEqualTo("dev");
        assertThat(healthy.path("reachable").asBoolean()).isTrue();
        assertThat(healthy.path("status").asString()).isEqualTo("OK");
        assertThat(healthy.path("ready").asBoolean()).isTrue();
        assertThat(healthy.path("readyMessage").asString()).isEqualTo("Ready to serve.");
        assertThat(healthy.path("maintenanceMode").asBoolean(true)).isFalse();
        assertThat(healthy.path("version").asString()).isEqualTo("8.1.17");
        assertThat(healthy.path("checkedAt").asString()).isEqualTo("2026-10-01T13:58:45Z");
        assertThat(healthy.path("load").path("memoryUsedPercent").asDouble()).isEqualTo(59.7);
        assertThat(healthy.path("load").path("maxThreads").asLong()).isEqualTo(401);
        assertThat(healthy.path("load").path("blockingQueuePercent").asDouble()).isZero();
        assertThat(healthy.path("unavailable")).isEmpty();
        assertThat(healthy.path("warnings")).isEmpty();
        assertThat(healthy.has("error")).isFalse();
        assertThat(healthy.has("systemInfo")).isFalse();

        JsonNode maintenance = reports.get(1);
        assertThat(maintenance.path("reachable").asBoolean()).isTrue();
        assertThat(maintenance.path("maintenanceMode").asBoolean()).isTrue();
        assertThat(maintenance.path("warnings").get(0).asString())
            .contains("maintenance mode", "not processing normally");
        assertThat(maintenance.path("ready").asBoolean(true)).isFalse();
        assertThat(maintenance.path("readyMessage").asString()).isEqualTo("Not ready to serve.");
        assertThat(maintenance.path("version").asString()).isEqualTo("8.1.17");
        assertThat(maintenance.has("load")).isFalse();
        assertThat(maintenance.path("unavailable")).singleElement().satisfies(part -> {
            assertThat(part.path("part").asString()).isEqualTo("load");
            assertThat(part.path("reason").asString())
                .startsWith("METRICS_NOT_LICENSED: ").contains("HTTP 403");
            assertThat(part.path("likelyCause").asString()).contains("license");
            assertThat(part.path("nextStep").asString()).isNotBlank();
        });

        JsonNode bogus = reports.get(3);
        assertThat(bogus.path("reachable").asBoolean(true)).isFalse();
        assertThat(bogus.path("status").asString()).isEqualTo("UNKNOWN");
        assertThat(bogus.path("error").path("code").asString()).isEqualTo("UNREACHABLE");
        assertThat(bogus.path("error").path("node").asString()).isEqualTo("staging/bogus");
        assertThat(bogus.path("error").path("nextStep").asString()).isNotBlank();
    }

    // --- Phase 3 review H1, m3, M2: real adapters, slow and hanging servers ----------------

    @Test
    void aSlowAutoServerIsReachableOnTheFirstCall() {
        // every call takes 0.6 x timeout; the version detection must not eat the deadline
        int delay = (int) (TIMEOUT.toMillis() * 6 / 10);
        stub(other, "/ibis/rest/healthcheck", "healthcheck", "json", delay);
        stub(other, "/ibis/rest/ready", "ready", "json", delay);
        stub(other, "/ibis/rest/system/info", "system_info", "xml", delay);
        stub(other, "/ibis/rest/metrics", "metrics", "json", 0);
        start(List.of(server("staging/slow", "https://localhost:" + other.httpsPort(),
            VersionLine.AUTO)));

        long start = System.nanoTime();
        JsonNode result = call(Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.plusSeconds(1));
        JsonNode report = result.path("structuredContent").path("reports").path(0);
        assertThat(report.path("reachable").asBoolean()).as(report.toString()).isTrue();
        assertThat(report.path("ready").asBoolean()).isTrue();
        assertThat(report.path("version").asString()).isEqualTo("8.1.17");
        assertThat(report.has("load")).as(report.toString()).isTrue();
        assertThat(report.path("warnings").toString()).doesNotContain("could not be detected");
        assertThat(other.findAll(com.github.tomakehurst.wiremock.client.WireMock
            .getRequestedFor(urlPathEqualTo("/ibis/rest/system/info"))))
            .as("the health call's /system/info is the detection").hasSize(1);
    }

    @Test
    void aHangingServerIsATimeoutWhileTheOthersAreReportedWithinTimeoutPlusOneSecond() {
        int hang = (int) TIMEOUT.plusSeconds(3).toMillis();
        for (String path : List.of("/ibis/rest/healthcheck", "/ibis/rest/ready",
            "/ibis/rest/system/info", "/ibis/rest/metrics")) {
            other.stubFor(get(urlPathEqualTo(path)).willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withFixedDelay(hang).withBody("{}")));
        }
        start(List.of(
            server("dev/node1", "https://localhost:" + dev.httpsPort(), VersionLine.AUTO),
            server("staging/hang", "https://localhost:" + other.httpsPort(), VersionLine.AUTO)));

        long start = System.nanoTime();
        JsonNode result = call(Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).as("SC-002").isLessThan(TIMEOUT.plusSeconds(1));
        assertMatchesOutputSchema(result);
        JsonNode reports = result.path("structuredContent").path("reports");
        assertThat(reports.path(0).path("reachable").asBoolean()).isTrue();
        assertThat(reports.path(0).path("version").asString()).isEqualTo("8.1.17");
        JsonNode hanging = reports.path(1);
        assertThat(hanging.path("reachable").asBoolean(true)).isFalse();
        assertThat(hanging.path("error").path("code").asString()).isEqualTo("TIMEOUT");
    }

    @Test
    void wrongCredentialsCostOneFailedLoginAcrossCalls() {
        stub(other, "/ibis/rest/healthcheck", "healthcheck", "json");
        stub(other, "/ibis/rest/ready", "ready", "json");
        for (String path : List.of("/ibis/rest/system/info", "/ibis/rest/metrics")) {
            other.stubFor(get(urlPathEqualTo(path)).willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(401)
                    .withHeader("Content-Type", "text/html").withBody("<html>401</html>")));
        }
        start(List.of(server("staging/locked", "https://localhost:" + other.httpsPort(),
            VersionLine.AUTO)));

        JsonNode first = call(Map.of()).path("structuredContent").path("reports").path(0);
        JsonNode second = call(Map.of()).path("structuredContent").path("reports").path(0);

        assertThat(authenticatedRequests(other)).as("one failed login per 60 s").isEqualTo(1);
        for (JsonNode report : List.of(first, second)) {
            assertThat(report.path("reachable").asBoolean()).isTrue();
            assertThat(report.path("ready").asBoolean()).isTrue();
            assertThat(report.path("unavailable")).extracting(part -> part.path("reason")
                .asString().substring(0, 12)).containsOnly("AUTH_FAILED:");
            assertThat(report.path("warnings").toString())
                .contains("could not be detected (AUTH_FAILED)");
        }
        assertThat(second.path("unavailable").path(0).path("reason").asString())
            .contains("avoid account lockout");
        assertThat(second.path("unavailable").path(0).path("nextStep").asString())
            .contains("INUBIT_STAGING_LOCKED_PASSWORD", "restart the MCP client");
    }

    @Test
    void aStageReportsEachOfItsServers() {
        JsonNode result = call(Map.of("target", "qa"));

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(byServer(result)).containsOnlyKeys("qa/node1", "qa/node2");
        assertThat(result.path("structuredContent").path("reports"))
            .extracting(report -> report.path("node").asString())
            .containsExactly("qa/node1", "qa/node2");
    }

    @Test
    void aServerWithSystemInfo() {
        JsonNode result = call(Map.of("target", "dev/node1", "includeSystemInfo", true));

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertMatchesOutputSchema(result);
        JsonNode report = result.path("structuredContent").path("reports").path(0);
        JsonNode info = report.path("systemInfo");
        assertThat(info.path("version").asString()).isEqualTo("8.1.17");
        assertThat(info.path("tracingEnabled").asBoolean()).isTrue();
        assertThat(info.path("schedulerThreads").asInt()).isEqualTo(401);
        assertThat(info.path("maxHeap").asString()).isEqualTo("9216 MB");
        assertThat(info.path("raw").path("FreeMemory").asString()).isEqualTo("2548");
    }

    @Test
    void anUnknownTargetIsEnvironmentUnknownListingTheIds() {
        JsonNode result = call(Map.of("target", "staging/nope"));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.has("structuredContent")).isFalse();
        JsonNode error = JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
        assertThat(error.path("code").asString()).isEqualTo("TARGET_UNKNOWN");
        assertThat(error.path("message").asString()).contains("dev", "dev/node1", "qa",
            "qa/node1", "qa/node2", "staging", "staging/bogus");
        assertThat(dev.getAllServeEvents()).as("no server was contacted").isEmpty();
    }

    @Test
    void schemaViolationsAreRejectedBeforeTheToolRuns() {
        JsonNode unknownProperty = call(Map.of("target", "dev", "verbose", true));
        JsonNode badPattern = call(Map.of("target", "DEV/Inubit 1"));
        JsonNode wrongType = call(Map.of("includeSystemInfo", "yes"));

        for (JsonNode result : List.of(unknownProperty, badPattern, wrongType)) {
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
            assertThat(result.path("content").get(0).path("text").asString())
                .doesNotContain("\"code\"");
        }
        assertThat(dev.getAllServeEvents()).isEmpty();
        assertThat(integration.getAllServeEvents()).isEmpty();
    }

    @Test
    void noCredentialReachesTheResult() {
        JsonNode result = call(Map.of("includeSystemInfo", true));

        assertThat(result.toString()).doesNotContain(PASSWORD, "jdoe", "Authorization");
    }
}
