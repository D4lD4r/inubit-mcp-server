package de.dadecker.inubit.mcp.mcp.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.NodeSecrets;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.HealthService;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.SecretPlan;
import de.dadecker.inubit.mcp.config.SecretSourceConfig;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T056: {@code get_health} over MCP with the real adapters against WireMock (contracts/mcp-tools.md
 * §2, quickstart V2/V3): {@code dev/node1} is healthy, both {@code qa} servers are in maintenance
 * mode, not ready and unlicensed for metrics, and {@code staging/bogus} is unreachable.
 *
 * <p>Feature 011 (T028, US1): {@code get_health} on an INUBIT 6.1 node through the production
 * wiring, see {@link #startWith61}.
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
    /** The baseUrl of the 6.1 node: plain HTTP, without stubs, must never be asked. */
    private static WireMockServer legacy;

    private SecretScrubber scrubber;
    /** Feature 009: nodes with a StartCLI installation (none by default) and their probe. */
    private Set<NodeId> withClient = Set.of();
    private ClientVersionPort probe = (node, timeout) -> {
        throw new AssertionError("no node has a StartCLI installation");
    };
    private AdapterGatewayFactory gateways;
    private McpTestClient client;
    /** Feature 011: the production wiring of {@link #startWith61}, if started. */
    private TestWiring wiring;

    @TempDir
    Path temp;

    @BeforeAll
    static void start() {
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        integration = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        other = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        legacy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        legacy.start();
    }

    @AfterAll
    static void stop() {
        dev.stop();
        integration.stop();
        other.stop();
        legacy.stop();
    }

    @BeforeEach
    void setUp() {
        dev.resetAll();
        integration.resetAll();
        other.resetAll();
        legacy.resetAll();
        stub(dev, "/ibis/rest/healthcheck", "healthcheck", "json");
        stub(dev, "/ibis/rest/ready", "ready", "json");
        stub(dev, "/ibis/rest/system/info", "system_info", "xml");
        stub(dev, "/ibis/rest/metrics", "metrics", "json");
        stub(integration, "/ibis/rest/healthcheck", "healthcheck_maintenance", "json");
        stub(integration, "/ibis/rest/ready", "ready_not_ready", "json");
        stub(integration, "/ibis/rest/system/info", "system_info", "xml");
        stub(integration, "/ibis/rest/metrics", "metrics_unlicensed", "json");

        start(List.of(
            server("dev/node1", "https://localhost:" + dev.httpsPort(), VersionSetting.AUTO),
            server("qa/node1", "https://localhost:" + integration.httpsPort(),
                VersionSetting.V8_1),
            server("qa/node2", "https://localhost:" + integration.httpsPort(),
                VersionSetting.V8_1),
            server("staging/bogus", "https://127.0.0.1:9", VersionSetting.V8_1)));
    }

    private void start(List<EffectiveNodeConfig> servers) {
        start(servers, scrubber -> Map.of());
    }

    /**
     * @param awsSecrets the {@link NodeSecrets} of the nodes whose credentials come from AWS
     *     Parameter Store (feature 010), built with the scrubber of this start
     */
    private void start(List<EffectiveNodeConfig> servers,
        Function<SecretScrubber, Map<NodeId, NodeSecrets>> awsSecrets) {
        if (client != null) {
            client.close();
            gateways.close();
        }
        List<NodeId> ids = servers.stream().map(EffectiveNodeConfig::id).toList();
        scrubber = new SecretScrubber();
        Map<NodeId, EffectiveNodeConfig> byId = servers.stream().collect(Collectors.toMap(
            EffectiveNodeConfig::id, Function.identity()));
        NodeVersions versions = new NodeVersions(Clock.systemUTC(),
            id -> byId.get(id).versionLine(), id -> byId.get(id).inventory().cacheTtl());
        Map<NodeId, NodeSecrets> secrets = new HashMap<>(TestSecrets.environment(
            new CredentialResolver(Map.of(
                "INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", PASSWORD,
                "INUBIT_QA_USERNAME", "jdoe", "INUBIT_QA_PASSWORD", PASSWORD,
                "INUBIT_STAGING_USERNAME", "jdoe", "INUBIT_STAGING_PASSWORD", PASSWORD), scrubber,
                "INUBIT")
            .resolve(ids)));
        secrets.putAll(awsSecrets.apply(scrubber));
        gateways = new AdapterGatewayFactory(servers, secrets, scrubber, Clock.systemUTC(),
            new CliRunner(new SystemProcessLauncher(), Map.of(), false,
                new CliResources("acme")), versions, PortSetRegistry.v81Default());
        Map<NodeId, Duration> timeouts = servers.stream().collect(Collectors.toMap(
            EffectiveNodeConfig::id, EffectiveNodeConfig::timeout));
        HealthService health = new HealthService(gateways, new TargetResolver(ids),
            new FanOut(), timeouts::get, Clock.systemUTC(),
            gateWithoutClients(versions, gateways, CapabilityMatrix.production()), versions, probe,
            withClient::contains);
        client = McpTestClient.start(List.of(new GetHealthTool(health)), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
        if (gateways != null) {
            gateways.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        client = null;
    }

    private static EffectiveNodeConfig server(String id, String baseUrl, VersionSetting line) {
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
            VersionSetting.AUTO)));

        long start = System.nanoTime();
        JsonNode result = call(Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.plusSeconds(1));
        JsonNode report = result.path("structuredContent").path("reports").path(0);
        assertThat(report.path("reachable").asBoolean()).as(report.toString()).isTrue();
        assertThat(report.path("ready").asBoolean()).isTrue();
        assertThat(report.path("version").asString()).isEqualTo("8.1.17");
        assertThat(report.has("load")).as(report.toString()).isTrue();
        assertThat(report.path("warnings")).as("no version warning on 8.1.17").isEmpty();
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
            server("dev/node1", "https://localhost:" + dev.httpsPort(), VersionSetting.AUTO),
            server("staging/hang", "https://localhost:" + other.httpsPort(), VersionSetting.AUTO)));

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

    // --- feature 010 T014: an AWS node's first fetch within the health budget (R-9) ---------

    @Test
    void anAwsNodeWhoseFirstFetchOutlivesTheBudgetIsATimeoutUntilTheFetchHasEnded() throws InterruptedException {
        stub(other, "/ibis/rest/healthcheck", "healthcheck", "json");
        stub(other, "/ibis/rest/ready", "ready", "json");
        stub(other, "/ibis/rest/system/info", "system_info", "xml");
        stub(other, "/ibis/rest/metrics", "metrics", "json");
        NodeId aws = NodeId.parse("staging/aws");
        StoreLocation location = new StoreLocation("eu-central-1", Optional.empty());
        // the store answers after timeout + 1 s, within its own 10 s bound
        FakeParameterStore store = new FakeParameterStore()
            .put(location, "/test/staging/aws/username", "jdoe")
            .put(location, "/test/staging/aws/password", PASSWORD)
            .delay(location, TIMEOUT.plusSeconds(1));
        start(List.of(
            server("dev/node1", "https://localhost:" + dev.httpsPort(), VersionSetting.AUTO),
            server(aws.value(), "https://localhost:" + other.httpsPort(), VersionSetting.V8_1)),
            scrubber -> Map.of(aws, NodeSecrets.aws(new SecretPlan(aws,
                new SecretSourceConfig.AwsParameterStore(location.region(),
                    location.awsProfile()), SecretPlan.Origin.NODE, Optional.empty(),
                Optional.of(new ParameterNames(Optional.of("/test/staging/aws/username"),
                    Optional.of("/test/staging/aws/password"), Optional.empty(),
                    Optional.empty(), Optional.empty()))), store, scrubber,
                Clock.systemUTC())));

        long start = System.nanoTime();
        JsonNode first = call(Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).as("SC-002").isLessThan(TIMEOUT.plusSeconds(1));
        assertMatchesOutputSchema(first);
        assertThat(byServer(first).get("dev/node1").path("reachable").asBoolean()).isTrue();
        JsonNode pending = byServer(first).get("staging/aws");
        assertThat(pending.path("reachable").asBoolean(true)).isFalse();
        assertThat(pending.path("error").path("code").asString()).isEqualTo("TIMEOUT");
        assertThat(pending.path("error").path("message").asString()).isEqualTo("staging/aws:"
            + " credentials not yet available (still fetching from AWS Parameter Store); call"
            + " get_health again shortly");
        assertThat(pending.path("error").path("node").asString()).isEqualTo("staging/aws");
        assertThat(other.getAllServeEvents()).as("nothing is sent to that node").isEmpty();

        long fetchDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!gateways.credentialsHeld(aws) && System.nanoTime() < fetchDeadline) {
            Thread.sleep(20);
        }
        assertThat(gateways.credentialsHeld(aws)).as("the fetch continued").isTrue();
        JsonNode next = call(Map.of());

        assertMatchesOutputSchema(next);
        JsonNode report = byServer(next).get("staging/aws");
        assertThat(report.path("reachable").asBoolean()).as(report.toString()).isTrue();
        assertThat(report.path("version").asString()).isEqualTo("8.1.17");
        assertThat(report.has("error")).isFalse();
        assertThat(store.calls()).as("one fetch").isEqualTo(1);
        assertThat(next.toString()).doesNotContain(PASSWORD);
    }

    // --- feature 009: the StartCLI client probe (FR-002) -------------------------------------

    @Test
    void aHangingClientProbeIsNotYetDetectedWithinTimeoutPlusOneSecond() {
        CountDownLatch never = new CountDownLatch(1);
        withClient = Set.of(NodeId.parse("dev/node1"));
        probe = (node, timeout) -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("interrupted");
        };
        start(List.of(
            server("dev/node1", "https://localhost:" + dev.httpsPort(), VersionSetting.AUTO),
            server("qa/node1", "https://localhost:" + integration.httpsPort(),
                VersionSetting.V8_1)));

        long start = System.nanoTime();
        JsonNode result = call(Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).as("SC-002 with a hanging probe").isLessThan(TIMEOUT.plusSeconds(1));
        assertMatchesOutputSchema(result);
        JsonNode reports = result.path("structuredContent").path("reports");
        JsonNode probed = reports.path(0);
        assertThat(probed.path("version").asString()).isEqualTo("8.1.17");
        assertThat(probed.has("load")).isTrue();
        assertThat(probed.has("client")).isFalse();
        assertThat(probed.path("unavailable")).singleElement().satisfies(part -> {
            assertThat(part.path("part").asString()).isEqualTo("client");
            assertThat(part.path("reason").asString())
                .startsWith("TIMEOUT: not yet detected (timeout)");
        });
        assertThat(reports.path(1).path("client").path("state").asString())
            .isEqualTo("not_available");
    }

    @Test
    void aProbedClientIsReportedWithItsRangeAndComparison() {
        withClient = Set.of(NodeId.parse("dev/node1"));
        probe = (node, timeout) -> new ClientVersion(InubitVersion.parse("8.1.17").orElseThrow(),
            InubitVersion.parse("4.0.1"), InubitVersion.parse("8.1.17"));
        start(List.of(
            server("dev/node1", "https://localhost:" + dev.httpsPort(), VersionSetting.AUTO)));

        JsonNode result = call(Map.of());

        assertMatchesOutputSchema(result);
        JsonNode report = result.path("structuredContent").path("reports").path(0);
        assertThat(report.path("client")).isEqualTo(JSON.readTree("{\"version\":\"8.1.17\","
            + "\"supportedFrom\":\"4.0.1\",\"supportedTo\":\"8.1.17\","
            + "\"comparison\":\"match\"}"));
        assertThat(report.path("unavailable")).isEmpty();
        assertThat(report.path("warnings")).isEmpty();
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
            VersionSetting.AUTO)));

        JsonNode first = call(Map.of()).path("structuredContent").path("reports").path(0);
        JsonNode second = call(Map.of()).path("structuredContent").path("reports").path(0);

        assertThat(authenticatedRequests(other)).as("one failed login per 60 s").isEqualTo(1);
        for (JsonNode report : List.of(first, second)) {
            assertThat(report.path("reachable").asBoolean()).isTrue();
            assertThat(report.path("ready").asBoolean()).isTrue();
            assertThat(report.path("unavailable")).extracting(part -> part.path("reason")
                .asString().substring(0, 12)).containsOnly("AUTH_FAILED:");
            assertThat(report.path("warnings")).hasSize(1);
            assertThat(report.path("warnings").path(0).asString())
                .matches("the INUBIT version of staging/locked is unknown \\(AUTH_FAILED at"
                    + " \\S+\\); results are unverified");
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

    // --- feature 011, T028: get_health on a 6.1 node end to end (US1) -----------------------

    private static final String LEGACY_81 = "legacy/node1";
    private static final String LEGACY_61 = "legacy/node2";
    /** 8.1 as in production, 6.1 CLI-only with monitoring supported (as after T040). */
    private static final CapabilityMatrix WITH_61 = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
            Map.of(Capability.MONITORING, SupportState.SUPPORTED),
            Map.of(Capability.MONITORING, Set.of(Channel.CLI)), true),
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Arrays.stream(Capability.values()).collect(Collectors.toMap(
                capability -> capability, capability -> SupportState.SUPPORTED)))),
        Map.of(Capability.MONITORING, SupportState.UNVERIFIED));

    private final FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
    private final FakeProcessLauncher clientVersion = FakeProcessLauncher.of("CLI 6.1.0\n"
        + "Supported inubit Process Engine versions from 4.0.1 to 6.1.0.\n", "", 0);
    /** Every StartCLI session of the 6.1 node. */
    private FakeProcessLauncher session;

    /**
     * Replaces the server of {@link #setUp} by the production wiring with {@link #WITH_61}: the
     * group {@code legacy} has the 8.1 node {@code legacy/node1} on the {@code dev} WireMock and
     * the 6.1 node {@code legacy/node2} on {@link #legacy}, read through a fake StartCLI whose
     * sessions replay the reconstructed fixture {@code sessionCase} (T022); {@code -h} replays
     * the reconstructed help, {@code -v} answers as a 6.1.0 client.
     *
     * @param withStartCli whether the 6.1 node has a StartCLI installation (US1-4)
     */
    private void startWith61(String sessionCase, boolean withStartCli) throws IOException {
        client.close();
        gateways.close();
        gateways = null;
        session = FakeProcessLauncher.replaying("v6_1", sessionCase);
        Path cliHome = Files.createDirectories(temp.resolve("cli/bin")).getParent();
        Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        Path javaHome = Files.createDirectories(temp.resolve("java"));
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
        String yaml = """
            profile:
              name: acme
            groups:
              - name: legacy
                nodes:
                  - name: node1
                    versionLine: V8_1
                    baseUrl: https://localhost:%1$d
                    tls:
                      trustStore: "%2$s"
                  - name: node2
                    versionLine: V6_1
                    baseUrl: http://localhost:%3$d
                    allowInsecureHttp: true
            """.formatted(dev.httpsPort(), TestCertificates.get().trustStore(), legacy.port())
            + (withStartCli ? """
                    cli:
                      home: "%1$s"
                      javaHome: "%2$s"
            """.formatted(cliHome, javaHome) : "");
        Map<String, String> environment = Map.of("INUBIT_ACME_LEGACY_USERNAME", "jdoe",
            "INUBIT_ACME_LEGACY_PASSWORD", PASSWORD);
        LoadedConfig loaded = new ConfigLoader(environment, temp, false)
            .parse(yaml, temp.resolve("config.yaml"));
        scrubber = new SecretScrubber();
        CredentialResolution credentials = new CredentialResolver(environment, scrubber,
            loaded.config().credentialPrefix()).resolve(loaded.config().nodeIds());
        assertThat(ConfigValidator.forSystem().validate(loaded, credentials).errors())
            .isEmpty();
        ProcessLauncher launcher = spec -> switch (spec.command().size() == 2
            ? spec.command().get(1) : "") {
            case "-h" -> help.launch(spec);
            case "-v" -> clientVersion.launch(spec);
            default -> session.launch(spec);
        };
        wiring = TestWiring.of(loaded.config(), credentials, scrubber, Files::exists, false,
            launcher, Map.of("PATH", "/usr/bin"), WITH_61);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    /** The parts of contracts/mcp-tools-delta.md that INUBIT 6.1 does not have. */
    private static final String NOT_ON_61 = "["
        + "{\"part\":\"ready\",\"reason\":\"NOT_AVAILABLE_ON_6_1: INUBIT 6.1 has no readiness"
        + " check\"},"
        + "{\"part\":\"threads\",\"reason\":\"NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no"
        + " thread figures\"},"
        + "{\"part\":\"blockingQueue\",\"reason\":\"NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no"
        + " blocking queue\"}]";

    /**
     * US1-1: the report of a 6.1 node as in contracts/mcp-tools-delta.md, in the variant whose
     * StartCLI version is the client's (FR-006; {@code V61HealthAdapter} until T037): no
     * {@code version}, a warning with the value labelled as the client's.
     */
    private static void assertContract61Report(JsonNode report) {
        assertThat(report.path("node").asString()).isEqualTo(LEGACY_61);
        assertThat(report.path("group").asString()).isEqualTo("legacy");
        assertThat(Instant.parse(report.path("checkedAt").asString())).isNotNull();
        assertThat(report.path("reachable").asBoolean()).as(report.toString()).isTrue();
        assertThat(report.has("version")).as("the client's version is not reported").isFalse();
        // memory only; the figures are JSON numbers (schema "number", as on 8.1)
        JsonNode load = report.path("load");
        assertThat(load.propertyNames()).containsExactlyInAnyOrder("usedMemoryMb",
            "freeMemoryMb", "maxMemoryMb", "memoryUsedPercent");
        assertThat(load.path("usedMemoryMb").asDouble()).isEqualTo(1210);
        assertThat(load.path("freeMemoryMb").asDouble()).isEqualTo(838);
        assertThat(load.path("maxMemoryMb").asDouble()).isEqualTo(2048);
        assertThat(load.path("memoryUsedPercent").asDouble()).isEqualTo(59.1);
        assertThat(report.path("processCounts")).isEqualTo(JSON.readTree("{\"error\":3,"
            + "\"processing\":0,\"waiting\":12,\"queued\":0}"));
        // without a server version (the pin V6_1 has no patch) the client is not comparable;
        // the contract's "match" needs the server version of the other variant
        assertThat(report.path("client")).isEqualTo(JSON.readTree("{\"version\":\"6.1.0\","
            + "\"supportedFrom\":\"4.0.1\",\"supportedTo\":\"6.1.0\","
            + "\"comparison\":\"not_comparable\"}"));
        assertThat(report.path("unavailable")).isEqualTo(JSON.readTree(NOT_ON_61));
        assertThat(report.has("ready")).isFalse();
        assertThat(report.has("readyMessage")).isFalse();
        assertThat(report.has("systemInfo")).isFalse();
        assertThat(report.has("error")).isFalse();
        assertThat(report.path("warnings")).extracting(JsonNode::asString).containsExactly(
            "the version 6.1.0.89 read on legacy/node2 is the StartCLI client's; the server"
                + " version cannot be read");
    }

    @Test
    void a61NodeIsReportedFromOneStartCliSessionAsInTheContractWithoutRest() throws IOException {
        startWith61("health_session_ok", true);

        JsonNode result = call(Map.of("target", LEGACY_61));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertMatchesOutputSchema(result);
        JsonNode report = result.path("structuredContent").path("reports").path(0);
        assertThat(result.path("structuredContent").path("reports")).hasSize(1);
        assertContract61Report(report);
        assertThat(report.path("status").asString()).isEqualTo("OK");
        assertThat(report.path("maintenanceMode").asBoolean(true)).isFalse();
        assertThat(session.launchCount()).as("one StartCLI session").isEqualTo(1);
        assertThat(legacy.getAllServeEvents()).as("US1-3: no REST request to a 6.1 node")
            .isEmpty();
        assertThat(dev.getAllServeEvents()).as("the 8.1 node was not addressed").isEmpty();
        assertThat(result.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void aNegativeAliveCheckOnA61NodeIsErrorWithTheOtherPartsStillReported() throws IOException {
        startWith61("health_session_alive_failed", true);

        JsonNode result = call(Map.of("target", LEGACY_61));

        assertMatchesOutputSchema(result);
        JsonNode report = result.path("structuredContent").path("reports").path(0);
        assertContract61Report(report);
        assertThat(report.path("status").asString()).as("US1-2").isEqualTo("ERROR");
        assertThat(report.path("maintenanceMode").asBoolean(true)).isFalse();
        assertThat(legacy.getAllServeEvents()).isEmpty();
    }

    @Test
    void aGroupOfAn81AndA61NodeReportsEachByItsOwnChannelWithinTheOverviewBudget()
        throws IOException {
        startWith61("health_session_ok", true);

        long start = System.nanoTime();
        JsonNode result = call(Map.of("target", "legacy"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).as("US1-5: the overview budget").isLessThan(Duration.ofSeconds(10));
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertMatchesOutputSchema(result);
        JsonNode reports = result.path("structuredContent").path("reports");
        assertThat(reports).extracting(report -> report.path("node").asString())
            .containsExactly(LEGACY_81, LEGACY_61);
        JsonNode current = reports.get(0);
        assertThat(current.path("reachable").asBoolean()).isTrue();
        assertThat(current.path("status").asString()).isEqualTo("OK");
        assertThat(current.path("ready").asBoolean()).isTrue();
        assertThat(current.path("version").asString()).isEqualTo("8.1.17");
        assertThat(current.path("load").path("maxThreads").asLong()).isEqualTo(401);
        assertThat(current.has("processCounts")).isFalse();
        assertThat(current.path("unavailable")).isEmpty();
        assertThat(current.path("client").path("state").asString()).isEqualTo("not_available");
        assertContract61Report(reports.get(1));
        assertThat(reports.get(1).path("status").asString()).isEqualTo("OK");
        assertThat(dev.getAllServeEvents()).as("the 8.1 node is read over REST").isNotEmpty();
        assertThat(legacy.getAllServeEvents()).as("no REST request to the 6.1 node").isEmpty();
        assertThat(session.launchCount()).isEqualTo(1);
    }

    @Test
    void a61NodeWithoutStartCliIsCliUnavailableWhileThe81NodeIsReported() throws IOException {
        startWith61("health_session_ok", false);

        JsonNode result = call(Map.of("target", "legacy"));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertMatchesOutputSchema(result);
        Map<String, JsonNode> reports = byServer(result);
        assertThat(reports.get(LEGACY_81).path("version").asString()).isEqualTo("8.1.17");
        assertThat(reports.get(LEGACY_81).has("error")).isFalse();
        JsonNode unavailable = reports.get(LEGACY_61);
        assertThat(unavailable.path("status").asString()).as(unavailable.toString())
            .isEqualTo("UNKNOWN");
        assertThat(unavailable.path("error").path("code").asString())
            .isEqualTo("CLI_UNAVAILABLE");
        assertThat(unavailable.path("error").path("node").asString()).isEqualTo(LEGACY_61);
        assertThat(unavailable.path("error").path("nextStep").asString())
            .as("US1-4: names the missing client").contains("INUBIT client");
        assertThat(unavailable.path("client").path("state").asString())
            .isEqualTo("not_available");
        assertThat(help.launchCount() + clientVersion.launchCount() + session.launchCount())
            .as("no StartCLI launch").isZero();
        assertThat(legacy.getAllServeEvents()).isEmpty();
    }
}
