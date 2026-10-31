package de.dadecker.inubit.mcp.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.PortSet;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.tools.FindProcessesTool;
import de.dadecker.inubit.mcp.mcp.tools.GetHealthTool;
import de.dadecker.inubit.mcp.mcp.tools.ListInventoryTool;
import de.dadecker.inubit.mcp.mcp.tools.ListNodesTool;
import de.dadecker.inubit.mcp.mcp.tools.QueryLogsTool;
import de.dadecker.inubit.mcp.mcp.tools.RestartProcessTool;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * T025 (US3, FR-013, FR-014, SC-004): a new INUBIT version needs only a matrix entry and a port
 * set. A test-only {@link CapabilityMatrix} (the production declaration plus a fictitious 7.9
 * range with {@code DIAGNOSIS} and {@code PROCESS_CONTROL} only) and a test-only {@link PortSet}
 * registered for 7.9 are wired with the unchanged production services and tools.
 *
 * <p>{@code dev/node1} runs INUBIT 7.9.3, {@code qa/node1} 8.1.17 and {@code qa/node2} 9.0.2, each
 * on its own HTTPS WireMock server and detected ({@code versionLine: AUTO}). Every node allows
 * every capability by configuration (write enabled, client confirmation), so only the version
 * decides. No StartCLI is run: the launcher fails on every launch.
 *
 * <p>The diagnosis tools validate their query on the node's ports before the version gate
 * admits (and detects); on the first call for {@code dev/node1} that validation runs on the
 * default (8.1) port set, which validates offline; the call itself then runs on the 7.9 port set.
 */
@Timeout(60)
class CapabilityExtensionTest {

    private static final NodeId NODE_79 = NodeId.parse("dev/node1");
    private static final NodeId NODE_81 = NodeId.parse("qa/node1");
    private static final NodeId NODE_90 = NodeId.parse("qa/node2");
    private static final String SYSTEM_INFO = "/ibis/rest/system/info";
    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    private static final String PASSWORD = "capability-extension-test-pw";
    private static final String PROCESS_ID = "7900001";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final VersionRange V7_9 = new VersionRange(7, 9, 7, 9);
    /** The production declaration plus the fictitious 7.9 range with a subset of capabilities. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), production("8.1.17")),
        new CapabilityMatrix.Entry(new VersionRange(9, 0, 9, 0), production("9.0.2")),
        new CapabilityMatrix.Entry(V7_9, Map.of(Capability.DIAGNOSIS, SupportState.SUPPORTED,
            Capability.PROCESS_CONTROL, SupportState.SUPPORTED))),
        production(null));

    private static WireMockServer on79;
    private static WireMockServer on81;
    private static WireMockServer on90;

    @TempDir
    Path temp;

    private final SecretScrubber scrubber = new SecretScrubber();
    /** Every call on the 7.9 port set, e.g. {@code find}, {@code restart 7900001}. */
    private final List<String> calls79 = new CopyOnWriteArrayList<>();
    /** The nodes the 7.9 port set was built for. */
    private final List<NodeId> built79 = new CopyOnWriteArrayList<>();
    private NodeVersions versions;
    private AdapterGatewayFactory gateways;
    private McpTestClient client;

    /** The production states of {@code version} ({@code null}: an unknown version). */
    private static Map<Capability, SupportState> production(String version) {
        Optional<InubitVersion> parsed = version == null ? Optional.empty()
            : InubitVersion.parse(version);
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, CapabilityMatrix.production().state(parsed, capability));
        }
        return states;
    }

    @BeforeAll
    static void start() {
        on79 = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        on81 = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        on90 = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        on79.stop();
        on81.stop();
        on90.stop();
    }

    @BeforeEach
    void setUp() {
        stubServer(on79, "7.9.3");
        stubServer(on81, "8.1.17");
        stubServer(on90, "9.0.2");
        List<EffectiveNodeConfig> servers = List.of(server(NODE_79, on79),
            server(NODE_81, on81), server(NODE_90, on90));
        List<NodeId> ids = servers.stream().map(EffectiveNodeConfig::id).toList();
        Map<NodeId, EffectiveNodeConfig> byId = servers.stream().collect(Collectors.toMap(
            EffectiveNodeConfig::id, Function.identity()));
        Clock clock = Clock.systemUTC();
        versions = new NodeVersions(clock, id -> byId.get(id).versionLine(),
            id -> byId.get(id).inventory().cacheTtl());
        PortSetRegistry portSets = PortSetRegistry.v81Default().register(V7_9, node -> {
            built79.add(node.server().id());
            return portSet79();
        });
        gateways = new AdapterGatewayFactory(servers, TestSecrets.credentialStore(servers, Map.of(
            "INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", PASSWORD,
            "INUBIT_QA_USERNAME", "jdoe", "INUBIT_QA_PASSWORD", PASSWORD), scrubber), scrubber,
            clock, new CliRunner(spec -> {
                throw new AssertionError("no StartCLI run expected: " + spec.command());
            }, Map.of(), false, new CliResources("acme")), versions, portSets);
        VersionGate gate = gateWithoutClients(versions, gateways, MATRIX);
        TargetResolver targets = new TargetResolver(ids);
        Function<NodeId, Duration> timeouts = id -> byId.get(id).timeout();
        DiagnosisService diagnosis = new DiagnosisService(gateways, targets, new FanOut(),
            ResultLimiter.withDefaults(), timeouts, id -> byId.get(id).hangingThreshold(), clock,
            ResultJson::size, gate, SecretTiming.NONE);
        InventoryService inventory = new InventoryService(gateways, targets, new FanOut(),
            ResultLimiter.withDefaults(), new InventoryCache(clock,
                id -> byId.get(id).inventory().cacheTtl()), id -> byId.get(id).inventory().owner(),
            timeouts, id -> byId.get(id).cliExportTimeout(), id -> byId.get(id).cliTimeout(),
            CliRunner.KILL_GRACE.multipliedBy(2), ResultJson::size, Terminology.DEFAULT, gate,
            SecretTiming.NONE);
        HealthService health = new HealthService(gateways, targets, new FanOut(), timeouts,
            clock, gate, versions, (node, timeout) -> {
                throw new AssertionError("no client probe expected for " + node);
            }, node -> false);
        Map<NodeId, WritePolicy> policies = new HashMap<>();
        ids.forEach(id -> policies.put(id, new WritePolicy(id, false, true, false,
            WritePolicy.Confirmation.CLIENT, Duration.ofMinutes(5), Optional.of("jdoe"))));
        ProcessControlService processControl = new ProcessControlService(
            new WriteGuard(targets, policies::get, gateways, gate), policies::get, gateways,
            new ConfirmationRegistry(clock), new AuditLog(temp.resolve("audit"), scrubber), clock,
            UUID::randomUUID, "acme");
        NodeOverview overview = new NodeOverview(servers.stream()
            .map(server -> server.summary(path -> false, false)).toList(), versions, MATRIX,
            id -> EnumSet.allOf(Capability.class));
        client = McpTestClient.start(List.of(
            new ListNodesTool(McpTestClient.TEST_PROFILE, overview::nodes),
            new GetHealthTool(health), new FindProcessesTool(diagnosis),
            new QueryLogsTool(diagnosis), new ListInventoryTool(inventory),
            new RestartProcessTool(processControl)), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
        gateways.close();
        for (WireMockServer server : List.of(on79, on81, on90)) {
            server.resetAll();
        }
    }

    private static EffectiveNodeConfig server(NodeId id, WireMockServer wireMock) {
        return TestNodeConfig.node().id(id.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(2))
            .inventoryOwner("OWNERS").write(true, false, ConfirmationMode.CLIENT)
            .versionLine(VersionSetting.AUTO).build();
    }

    /** The REST answers of INUBIT {@code version}: health, system info and the queue log. */
    private static void stubServer(WireMockServer server, String version) {
        server.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml;charset=UTF-8")
            .withBody(RestFixtures.text("system_info.xml")
                .replace("value=\"8.1.17\"", "value=\"" + version + "\""))));
        server.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck"))
            .willReturn(RestFixtures.response("healthcheck", "json")));
        server.stubFor(get(urlPathEqualTo("/ibis/rest/ready"))
            .willReturn(RestFixtures.response("ready", "json")));
        server.stubFor(get(urlPathEqualTo("/ibis/rest/metrics"))
            .willReturn(RestFixtures.response("metrics", "json")));
        server.stubFor(post(urlPathEqualTo(QUEUE_LOG))
            .willReturn(RestFixtures.response("log_queueLog", "json")));
    }

    // --- the test-only 7.9 port set ----------------------------------------------------------

    /** Serves the diagnosis and process control from memory; every other port is an error. */
    private PortSet portSet79() {
        ProcessInstance failed = new ProcessInstance(NODE_79, PROCESS_ID, Optional.empty(),
            ProcessState.ERROR, "Error", Instant.parse("2026-10-01T08:00:00Z"), Duration.ZERO,
            false, Optional.of("Workflow-79"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        ProcessQueryPort processes = new ProcessQueryPort() {
            @Override
            public void validate(ProcessQuery query) {
            }

            @Override
            public Page<ProcessInstance> find(ProcessQuery query) {
                calls79.add("find");
                return new Page<>(List.of(failed), 0, 1, OptionalLong.of(1), false, false,
                    OptionalInt.empty());
            }

            @Override
            public ProcessRows findByProcessId(String processId, Instant now,
                Duration hangingThreshold) {
                calls79.add("findByProcessId " + processId);
                return new ProcessRows(List.of(failed), 1);
            }
        };
        LogPort logs = new LogPort() {
            @Override
            public void validate(LogQuery query) {
            }

            @Override
            public Page<LogEntry> query(LogQuery query) {
                calls79.add("query");
                return new Page<>(List.of(new LogEntry(NODE_79, LogType.SYSTEM_LOG,
                    Optional.empty(), Severity.ERROR, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("logged by the 7.9 port set"),
                    Map.of())), 0, 1, OptionalLong.of(1), false, false, OptionalInt.empty());
            }
        };
        ProcessControlPort processControl = new ProcessControlPort() {
            @Override
            public void checkAvailable() {
            }

            @Override
            public String restart(String processId) {
                calls79.add("restart " + processId);
                return "restarted by the 7.9 port set";
            }

            @Override
            public String kill(String processId) {
                throw new AssertionError("kill is not part of this test");
            }
        };
        return new PortSet(processes, logs, undeclared(InventoryPort.class), processControl,
            undeclared(ArtifactPort.class), undeclared(ImportPort.class),
            undeclared(TagPort.class));
    }

    /** A port of a capability 7.9 does not declare: the gate must refuse before it is used. */
    private static <T> T undeclared(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                throw new AssertionError(type.getSimpleName() + "." + method.getName()
                    + " called on INUBIT 7.9");
            }));
    }

    // --- helpers -----------------------------------------------------------------------------

    private JsonNode call(String tool, Map<String, Object> arguments) {
        return client.callTool(tool, arguments);
    }

    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("structuredContent");
    }

    /** The {@code {"error": …}} of a tool error result. */
    private static JsonNode toolError(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        return JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
    }

    /** The error of the single node of a fan-out tool's result. */
    private static JsonNode nodeError(JsonNode result) {
        JsonNode results = structured(result).path("results");
        assertThat(results).hasSize(1);
        return results.get(0).path("error");
    }

    /** The {@code get_health} report of {@code node}. */
    private JsonNode health(NodeId node) {
        JsonNode reports = structured(call("get_health", Map.of("target", node.value())))
            .path("reports");
        assertThat(reports).hasSize(1);
        return reports.get(0);
    }

    /** The {@code list_nodes} version blocks by node id. */
    private Map<String, JsonNode> listVersions() {
        Map<String, JsonNode> byNode = new HashMap<>();
        structured(call("list_nodes", Map.of())).path("groups").forEach(group -> group
            .path("nodes").forEach(node -> byNode.put(node.path("id").asString(),
                node.path("version"))));
        return byNode;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asString()));
        return texts;
    }

    private static List<String> notUsable(JsonNode version) {
        List<String> names = new ArrayList<>();
        version.path("capabilities").path("notUsable")
            .forEach(item -> names.add(item.path("capability").asString()));
        return names;
    }

    /** The paths requested from {@code server}, oldest first. */
    private static List<String> requests(WireMockServer server) {
        List<ServeEvent> events = new ArrayList<>(server.getAllServeEvents());
        Collections.reverse(events);
        return events.stream().map(event -> event.getRequest().getMethod() + " "
            + event.getRequest().getUrl().replaceFirst("\\?.*", "")).toList();
    }

    // --- the fictitious version --------------------------------------------------------------

    @Test
    void aNodeOfTheFictitiousRangeRunsTheDeclaredToolsThroughItsPortSet() {
        JsonNode processes = structured(call("find_processes",
            Map.of("target", "dev/node1", "states", List.of("ERROR")))).path("results").get(0);
        assertThat(processes.has("warnings")).as("7.9 declares diagnosis supported").isFalse();
        assertThat(processes.path("page").path("items").get(0).path("processId").asString())
            .isEqualTo(PROCESS_ID);

        JsonNode logs = structured(call("query_logs",
            Map.of("target", "dev/node1", "logType", "systemLog"))).path("results").get(0);
        assertThat(logs.has("warnings")).isFalse();
        assertThat(logs.path("page").path("items").get(0).path("message").asString())
            .isEqualTo("logged by the 7.9 port set");

        JsonNode restart = structured(call("restart_process",
            Map.of("node", "dev/node1", "processId", PROCESS_ID))).path("result");
        assertThat(restart.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(restart.path("message").asString()).isEqualTo("restarted by the 7.9 port set");

        assertThat(calls79).containsExactly("find", "query",
            "findByProcessId " + PROCESS_ID, "restart " + PROCESS_ID,
            "findByProcessId " + PROCESS_ID);
        assertThat(built79).as("built once, after the detection").containsExactly(NODE_79);
        assertThat(requests(on79)).as("only the gate's detection reaches the node")
            .containsExactly("GET " + SYSTEM_INFO);
    }

    @Test
    void theUndeclaredToolsAreRefusedAndGetHealthOnlyWarns() {
        for (String kind : List.of("DIAGRAM", "MODULE")) {
            JsonNode error = nodeError(call("list_inventory",
                Map.of("target", "dev/node1", "kind", kind)));
            assertThat(error.path("code").asString()).as(kind).isEqualTo("UNSUPPORTED_VERSION");
            assertThat(error.path("message").asString()).as(kind).isEqualTo("list_inventory:"
                + " INUBIT 7.9.3 on dev/node1 does not support inventory yet; nothing was sent");
        }

        JsonNode report = health(NODE_79);
        assertThat(report.path("status").asString()).isEqualTo("OK");
        assertThat(texts(report.path("warnings"))).containsExactly("INUBIT 7.9.3 on dev/node1"
            + " is not supported for monitoring; results may be incomplete");

        assertThat(calls79).isEmpty();
        List<String> requests = requests(on79);
        assertThat(requests.get(0)).as("the gate's detection; no inventory request")
            .isEqualTo("GET " + SYSTEM_INFO);
        assertThat(requests.subList(1, requests.size())).as("get_health's own reads")
            .containsExactlyInAnyOrder("GET /ibis/rest/healthcheck", "GET " + SYSTEM_INFO,
                "GET /ibis/rest/ready", "GET /ibis/rest/metrics");
    }

    @Test
    void listNodesShowsExactlyTheDeclaredCapabilitiesAsUsable() {
        // US3-2
        health(NODE_79);

        JsonNode version = listVersions().get("dev/node1");

        assertThat(version.path("server").path("version").asString()).isEqualTo("7.9.3");
        assertThat(version.path("status").asString()).isEqualTo("partially_supported");
        assertThat(texts(version.path("capabilities").path("usable")))
            .containsExactly("diagnosis", "process_control");
        assertThat(notUsable(version)).containsExactly("monitoring", "inventory",
            "workspace_export", "development_writes", "deployment", "e2e_test");
    }

    // --- the declared versions are unchanged -------------------------------------------------

    @Test
    void anEightOneNodeIsUnchanged() {
        JsonNode processes = structured(call("find_processes",
            Map.of("target", "qa/node1", "states", List.of("ERROR")))).path("results").get(0);
        assertThat(processes.has("warnings")).isFalse();
        assertThat(processes.path("page").path("items")).isNotEmpty();
        on81.verify(1, postRequestedFor(urlPathEqualTo(QUEUE_LOG)));
        assertThat(texts(health(NODE_81).path("warnings"))).isEmpty();

        JsonNode version = listVersions().get("qa/node1");
        assertThat(version.path("status").asString()).isEqualTo("supported");
        assertThat(texts(version.path("capabilities").path("usable"))).containsExactly(
            "monitoring", "diagnosis", "inventory", "workspace_export", "development_writes",
            "deployment", "e2e_test", "process_control");
        assertThat(built79).isEmpty();
        assertThat(calls79).isEmpty();
    }

    @Test
    void aNineZeroNodeBehavesAsInUs2() {
        // US3-1: REST reads run with a warning on the 8.1 adapters, writes are refused
        String unverified = "INUBIT 9.0.2 on qa/node2 is not verified for diagnosis; results may"
            + " be incomplete";
        JsonNode processes = structured(call("find_processes",
            Map.of("target", "qa/node2", "states", List.of("ERROR")))).path("results").get(0);
        assertThat(texts(processes.path("warnings"))).containsExactly(unverified);
        on90.verify(1, postRequestedFor(urlPathEqualTo(QUEUE_LOG)));

        JsonNode refused = toolError(call("restart_process",
            Map.of("node", "qa/node2", "processId", PROCESS_ID)));
        assertThat(refused.path("code").asString()).isEqualTo("UNSUPPORTED_VERSION");
        assertThat(refused.path("message").asString())
            .contains("INUBIT 9.0.2 on qa/node2 does not support process control yet");

        // US3-1: a StartCLI-backed read with an 8.1 client is refused (FR-011)
        versions.clientProbed(NODE_90, new ClientVersion(InubitVersion.parse("8.1.17")
            .orElseThrow(), InubitVersion.parse("4.0.1"), InubitVersion.parse("8.1.17")));
        JsonNode modules = nodeError(call("list_inventory",
            Map.of("target", "qa/node2", "kind", "MODULE")));
        assertThat(modules.path("code").asString()).isEqualTo("UNSUPPORTED_VERSION");
        assertThat(modules.path("message").asString()).contains("StartCLI client 8.1.17 of"
            + " qa/node2 supports INUBIT 4.0.1 to 8.1.17, which excludes INUBIT 9.0.2");

        JsonNode version = listVersions().get("qa/node2");
        assertThat(version.path("status").asString()).isEqualTo("partially_supported");
        assertThat(texts(version.path("capabilities").path("usable")))
            .containsExactly("monitoring", "diagnosis", "inventory");
        assertThat(notUsable(version)).containsExactly("inventory (StartCLI parts)",
            "workspace_export", "development_writes", "deployment", "e2e_test",
            "process_control");
        assertThat(requests(on90)).as("only the detection and the REST read")
            .containsExactly("GET " + SYSTEM_INFO, "POST " + QUEUE_LOG);
        assertThat(built79).isEmpty();
    }
}
