package de.dadecker.inubit.mcp.mcp.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliClientSelector;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.NodeOverview;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
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
 * T055, 002-T005, 002-T013: {@code list_nodes} over MCP (contracts/mcp-tools.md §1, FR-003).
 * 009-T020: the {@code version} block per node (FR-004, research D-9, 009
 * contracts/mcp-tools-delta.md), read from {@link NodeVersions} at call time; the nodes point to
 * a WireMock server that must never see a request from {@code list_nodes}.
 */
@Timeout(60)
class ListNodesToolTest {

    private static final String DESCRIPTION = "[acme] List the configured INUBIT groups and"
        + " their nodes (ids `<group>/<node>`, e.g. `test/node1`), with production"
        + " classification, whether write actions are enabled, and per node the INUBIT version"
        + " and StartCLI client as last known, the support status and the usable capabilities."
        + " No node is contacted: for a version `not_yet_detected` call `get_health`. Call this"
        + " first to learn valid `target` and `node` values.";
    private static final ProfileInfo CUSTOM = new ProfileInfo("acme", Optional.of("ACME test"),
        new Terminology("Umgebung", "Umgebungen", "Knoten", "Knoten"), "INUBIT_ACME_SECRETPFX");
    private static final Path CLI_HOME = Path.of("/opt/inubit/client");
    private static final Predicate<Path> EXISTS =
        path -> path.equals(CLI_HOME.resolve("bin").resolve("startcli.sh"));

    private static List<EffectiveNodeConfig> config() {
        return List.of(
            TestNodeConfig.node().id("dev/inubit01")
                .baseUrl("https://inubit01.dev.example.test:8443")
                .write(true, false, ConfirmationMode.CLIENT).cliHome(CLI_HOME)
                .versionLine(VersionSetting.AUTO).build(),
            TestNodeConfig.node().id("dev/inubit02")
                .baseUrl("https://inubit02.dev.example.test:8443")
                .development(new EffectiveNodeConfig.Development(true, ConfirmationMode.SERVER,
                    E2ePolicy.FORBIDDEN, Optional.empty(), true))
                .versionLine(VersionSetting.parse("8.1.17").orElseThrow()).build(),
            TestNodeConfig.node().id("prod/inubit01")
                .baseUrl("https://inubit01.prod.example.test:8443").production(true)
                .write(true, false, ConfirmationMode.SERVER).cliHome(Path.of("/missing"))
                .build(),
            TestNodeConfig.node().id("prod/inubit02")
                .baseUrl("https://inubit02.prod.example.test:8443").production(true)
                .write(true, true, ConfirmationMode.SERVER).versionLine(VersionSetting.V9_X)
                .build());
    }

    private static McpTestClient client() {
        return client(McpTestClient.TEST_PROFILE);
    }

    private static McpTestClient client(ProfileInfo profile) {
        return client(profile, EXISTS, false);
    }

    private static McpTestClient client(ProfileInfo profile, Predicate<Path> exists,
        boolean windows) {
        List<EffectiveNodeConfig> config = config();
        NodeVersions versions = new NodeVersions(Clock.systemUTC(),
            id -> config.stream().filter(node -> node.id().equals(id)).findFirst().orElseThrow()
                .versionLine(), id -> java.time.Duration.ofMinutes(10));
        List<NodeSummary> summaries = config.stream()
            .map(node -> node.summary(exists, windows)).toList();
        NodeOverview overview = new NodeOverview(summaries, versions,
            CapabilityMatrix.production(), (id, withClient) -> READS,
            TestClientSelection.installed(summaries));
        McpTestClient client = McpTestClient.start(profile, List.of(new ListNodesTool(profile,
            overview::nodes)));
        client.initialize();
        return client;
    }

    @Test
    void theToolIsListedReadOnlyIdempotentAndClosedWorld() {
        try (McpTestClient client = client()) {
            JsonNode tool = client.listTools().path("tools").get(0);

            assertThat(tool.path("name").asString()).isEqualTo("list_nodes");
            assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
            JsonNode annotations = tool.path("annotations");
            assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
            assertThat(annotations.path("destructiveHint").asBoolean()).isFalse();
            assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
            assertThat(annotations.path("openWorldHint").asBoolean(true)).isFalse();
            assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean(true))
                .isFalse();
        }
    }

    @Test
    void groupsAndNodesAreListedInConfigOrderWithTheEffectiveWriteFlag() {
        try (McpTestClient client = client()) {
            JsonNode result = client.callTool("list_nodes", Map.of());

            assertThat(result.path("isError").asBoolean()).isFalse();
            JsonNode groups = result.path("structuredContent").path("groups");
            assertThat(groups).hasSize(2);
            assertThat(groups.get(0).path("name").asString()).isEqualTo("dev");
            assertThat(groups.get(0).path("production").asBoolean()).isFalse();
            assertThat(groups.get(1).path("name").asString()).isEqualTo("prod");
            assertThat(groups.get(1).path("production").asBoolean()).isTrue();

            JsonNode dev1 = groups.get(0).path("nodes").get(0);
            assertThat(dev1.path("id").asString()).isEqualTo("dev/inubit01");
            assertThat(dev1.path("group").asString()).isEqualTo("dev");
            assertThat(dev1.path("node").asString()).isEqualTo("inubit01");
            assertThat(dev1.path("production").asBoolean()).isFalse();
            assertThat(dev1.path("writeEnabled").asBoolean()).isTrue();
            assertThat(dev1.path("confirmationMode").asString()).isEqualTo("CLIENT");
            assertThat(dev1.path("versionLine").asString()).isEqualTo("AUTO");
            assertThat(dev1.path("cliAvailable").asBoolean()).isTrue();

            JsonNode dev2 = groups.get(0).path("nodes").get(1);
            assertThat(dev2.path("id").asString()).isEqualTo("dev/inubit02");
            assertThat(dev2.path("writeEnabled").asBoolean()).as("write disabled").isFalse();
            // 0.4.2: the development tools (import) do not depend on write.enabled
            assertThat(dev2.path("developmentEnabled").asBoolean()).as("development stage")
                .isTrue();
            assertThat(dev1.path("developmentEnabled").asBoolean()).isFalse();
            assertThat(dev2.path("repositoryWrite").asBoolean()).as("008 repository WRITE")
                .isTrue();
            assertThat(dev1.path("repositoryWrite").asBoolean()).isFalse();
            assertThat(dev2.path("cliAvailable").asBoolean()).as("no CLI home").isFalse();
            assertThat(dev2.path("versionLine").asString()).as("009: the configured pin")
                .isEqualTo("8.1.17");

            JsonNode prod1 = groups.get(1).path("nodes").get(0);
            assertThat(prod1.path("writeEnabled").asBoolean())
                .as("production without opt-in").isFalse();
            assertThat(prod1.path("cliAvailable").asBoolean()).as("startcli missing").isFalse();

            JsonNode prod2 = groups.get(1).path("nodes").get(1);
            assertThat(prod2.path("writeEnabled").asBoolean()).as("production with opt-in")
                .isTrue();
            assertThat(prod2.path("versionLine").asString()).isEqualTo("V9_X");
            assertThat(prod2.path("production").asBoolean()).isTrue();
        }
    }

    /**
     * Feature 013 (T020, FR-010): on Windows a node whose CLI home holds {@code startcli.bat} is
     * listed with {@code cliAvailable}; {@code startcli.sh} alone is no installation there.
     */
    @Test
    void onWindowsTheBatchInstallationMakesTheCliAvailable() {
        Predicate<Path> batch =
            path -> path.equals(CLI_HOME.resolve("bin").resolve("startcli.bat"));
        for (Map.Entry<Predicate<Path>, Boolean> installation
            : Map.of(batch, true, EXISTS, false).entrySet()) {
            try (McpTestClient client =
                client(McpTestClient.TEST_PROFILE, installation.getKey(), true)) {
                JsonNode nodes = client.callTool("list_nodes", Map.of())
                    .path("structuredContent").path("groups").get(0).path("nodes");

                assertThat(nodes.get(0).path("cliAvailable").asBoolean())
                    .as(installation.getValue() ? "startcli.bat" : "startcli.sh on Windows")
                    .isEqualTo(installation.getValue());
                assertThat(nodes.get(1).path("cliAvailable").asBoolean()).as("no CLI home")
                    .isFalse();
            }
        }
    }

    @Test
    void theResultHasNoUrlsUsernamesOrCredentials() {
        try (McpTestClient client = client()) {
            JsonNode result = client.callTool("list_nodes", Map.of());

            String text = result.path("content").get(0).path("text").asString();
            assertThat(text).isNotBlank();
            assertThat(text + result.path("structuredContent"))
                .doesNotContain("https://", "example.test", "baseUrl", "url", "username",
                    "password", "Password", "credential", "trustStore");
            JsonNode node = result.path("structuredContent").path("groups").path(0)
                .path("nodes").path(0);
            assertThat(node.propertyNames()).containsExactly("id", "group", "node",
                "production", "writeEnabled", "developmentEnabled", "confirmationMode",
                "repositoryWrite", "versionLine", "cliAvailable", "startCli", "version");
        }
    }

    @Test
    void theResultNamesTheProfileAndItsTerminology() {
        try (McpTestClient client = client(CUSTOM)) {
            JsonNode result = client.callTool("list_nodes", Map.of()).path("structuredContent");

            assertThat(result.propertyNames()).as("002-T013, contracts/mcp-tools-delta.md")
                .containsExactly("profile", "terminology", "groups");
            assertThat(result.path("profile").propertyNames())
                .containsExactly("name", "description");
            assertThat(result.path("profile").path("name").asString()).isEqualTo("acme");
            assertThat(result.path("profile").path("description").asString())
                .isEqualTo("ACME test");
            JsonNode terminology = result.path("terminology");
            assertThat(terminology.propertyNames()).containsExactly("group", "node");
            assertThat(terminology.path("group").propertyNames())
                .containsExactly("singular", "plural");
            assertThat(terminology.path("group").path("singular").asString())
                .isEqualTo("Umgebung");
            assertThat(terminology.path("group").path("plural").asString())
                .isEqualTo("Umgebungen");
            assertThat(terminology.path("node").path("singular").asString())
                .isEqualTo("Knoten");
            assertThat(terminology.path("node").path("plural").asString()).isEqualTo("Knoten");
        }
    }

    @Test
    void theCredentialPrefixIsNeverPartOfTheResult() {
        try (McpTestClient client = client(CUSTOM)) {
            JsonNode result = client.callTool("list_nodes", Map.of());

            assertThat(result.path("isError").asBoolean()).isFalse();
            assertThat(result.path("content").get(0).path("text").asString()
                + result.path("structuredContent"))
                .as("002 review G6").doesNotContain("SECRETPFX", "INUBIT_ACME", "credential",
                    "Prefix", "prefix");
            assertThat(result.path("structuredContent").path("profile").propertyNames())
                .doesNotContain("credentialPrefix", "terminology");
        }
    }

    @Test
    void aProfileWithoutDescriptionHasNoDescriptionField() {
        try (McpTestClient client = client()) {
            JsonNode profile = client.callTool("list_nodes", Map.of()).path("structuredContent")
                .path("profile");

            assertThat(profile.propertyNames()).containsExactly("name");
            assertThat(profile.path("name").asString()).isEqualTo("acme");
        }
    }

    @Test
    void theDefaultTerminologyIsListedWithoutATerminologySection() {
        try (McpTestClient client = client()) {
            JsonNode terminology = client.callTool("list_nodes", Map.of())
                .path("structuredContent").path("terminology");

            assertThat(terminology.path("group").path("singular").asString()).isEqualTo("group");
            assertThat(terminology.path("group").path("plural").asString()).isEqualTo("groups");
            assertThat(terminology.path("node").path("singular").asString()).isEqualTo("node");
            assertThat(terminology.path("node").path("plural").asString()).isEqualTo("nodes");
        }
    }

    @Test
    void unknownArgumentsAreRejected() {
        try (McpTestClient client = client()) {
            JsonNode result = client.callTool("list_nodes", Map.of("target", "dev"));

            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
        }
    }

    // --- 009-T020: the version block (FR-004, research D-9) --------------------------------

    private static final Set<Capability> READS =
        EnumSet.of(Capability.MONITORING, Capability.PROCESS_DIAGNOSIS,
            Capability.LOG_DIAGNOSIS, Capability.INVENTORY);
    /** dev/node1: a development stage with write access and StartCLI. */
    private static final Set<Capability> DEVELOPMENT = EnumSet.of(Capability.MONITORING,
        Capability.PROCESS_DIAGNOSIS, Capability.LOG_DIAGNOSIS, Capability.INVENTORY,
        Capability.WORKSPACE_EXPORT, Capability.DEVELOPMENT_WRITE, Capability.PROCESS_CONTROL);
    private static final Instant NOW = Instant.parse("2026-10-18T09:12:03Z");
    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final NodeId DEV2 = NodeId.parse("dev/node2");
    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final NodeId QA3 = NodeId.parse("qa/node3");
    private static final NodeId QA4 = NodeId.parse("qa/node4");
    private static final String PASSWORD = "list-nodes-test-pw";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static WireMockServer wireMock;
    /** The clock of {@link #versions}; at {@link #NOW} unless a test advances it. */
    private MutableClock clock;
    private NodeVersions versions;
    private AdapterGatewayFactory gateways;
    private McpTestClient versionClient;
    /** The summaries and allowed capabilities of the nodes, for an overview with another matrix. */
    private List<NodeSummary> summaries;
    private Map<NodeId, Set<Capability>> allowed;

    @TempDir
    Path temp;

    @BeforeAll
    static void startWireMock() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    /**
     * dev/node1 ({@code AUTO}, StartCLI, {@link #DEVELOPMENT}), dev/node2 ({@code AUTO}, no
     * StartCLI, reads), qa/node1 ({@code V8_1}, StartCLI, {@link #DEVELOPMENT}), qa/node2
     * (pinned {@code 8.1.17}, no StartCLI, reads) and qa/node3 ({@code V9_X}, StartCLI,
     * {@link #DEVELOPMENT}), all at the WireMock server; feature 012: qa/node4 ({@code V6_1},
     * StartCLI, {@link #DEVELOPMENT}).
     */
    @BeforeEach
    void startVersionClient() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        String baseUrl = "https://localhost:" + wireMock.httpsPort();
        List<EffectiveNodeConfig> nodes = List.of(
            versionNode("dev/node1", baseUrl, VersionSetting.AUTO).cliHome(CLI_HOME).build(),
            versionNode("dev/node2", baseUrl, VersionSetting.AUTO).build(),
            versionNode("qa/node1", baseUrl, VersionSetting.V8_1).cliHome(CLI_HOME).build(),
            versionNode("qa/node2", baseUrl, VersionSetting.parse("8.1.17").orElseThrow())
                .build(),
            versionNode("qa/node3", baseUrl, VersionSetting.V9_X).cliHome(CLI_HOME).build(),
            versionNode("qa/node4", baseUrl, VersionSetting.V6_1).cliHome(CLI_HOME).build());
        Map<NodeId, EffectiveNodeConfig> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));
        clock = new MutableClock(NOW);
        versions = new NodeVersions(clock,
            id -> byId.get(id).versionLine(), id -> byId.get(id).inventory().cacheTtl());
        SecretScrubber scrubber = new SecretScrubber();
        gateways = new AdapterGatewayFactory(nodes, TestSecrets.credentialStore(nodes, Map.of(
            "INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", PASSWORD,
            "INUBIT_QA_USERNAME", "jdoe", "INUBIT_QA_PASSWORD", PASSWORD), scrubber), scrubber,
            Clock.systemUTC(),
            TestCliRunners.forHost(noStartCli(new AtomicInteger()), Map.of(),
                new CliResources("acme",
                    PrivateFiles.forHost())), versions, PortSetRegistry.v81Default());
        allowed = Map.of(DEV1, DEVELOPMENT, DEV2, READS, QA1, DEVELOPMENT,
            NodeId.parse("qa/node2"), READS, QA3, DEVELOPMENT, QA4, DEVELOPMENT);
        summaries = nodes.stream().map(node -> node.summary(EXISTS, false)).toList();
        NodeOverview overview = new NodeOverview(summaries, versions,
            CapabilityMatrix.production(), (id, withClient) -> allowed.get(id),
            TestClientSelection.installed(summaries));
        versionClient = McpTestClient.start(McpTestClient.TEST_PROFILE, List.of(
            new ListNodesTool(McpTestClient.TEST_PROFILE, overview::nodes)));
        versionClient.initialize();
    }

    @AfterEach
    void stopVersionClient() {
        versionClient.close();
        gateways.close();
    }

    private static TestNodeConfig versionNode(String id, String baseUrl,
        VersionSetting setting) {
        return TestNodeConfig.node().id(id).baseUrl(baseUrl)
            .trustStore(TestCertificates.get().trustStore()).versionLine(setting);
    }

    /** A StartCLI launcher that counts and refuses every run. */
    private static ProcessLauncher noStartCli(AtomicInteger runs) {
        return spec -> {
            runs.incrementAndGet();
            throw new AssertionError("list_nodes must not run StartCLI: " + spec);
        };
    }

    private static ClientVersion clientVersion(String version, String from, String to) {
        return new ClientVersion(InubitVersion.parse(version).orElseThrow(),
            InubitVersion.parse(from), InubitVersion.parse(to));
    }

    /** Calls list_nodes, checks the output schema and that no node was contacted. */
    private Map<String, JsonNode> listVersions() {
        int before = requests();
        JsonNode result = versionClient.callTool("list_nodes", Map.of());
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(requests()).as("FR-004: list_nodes contacts no node").isEqualTo(before);
        assertMatchesOutputSchema(result);
        Map<String, JsonNode> byNode = new HashMap<>();
        result.path("structuredContent").path("groups").forEach(group -> group.path("nodes")
            .forEach(node -> byNode.put(node.path("id").asString(), node.path("version"))));
        return byNode;
    }

    private static int requests() {
        return wireMock.findAll(anyRequestedFor(anyUrl())).size();
    }

    private static List<String> usable(JsonNode version) {
        return texts(version.path("capabilities").path("usable"));
    }

    private static List<String> notUsable(JsonNode version) {
        List<String> names = new ArrayList<>();
        version.path("capabilities").path("notUsable")
            .forEach(item -> names.add(item.path("capability").asString()));
        return names;
    }

    private static String reason(JsonNode version, String capability) {
        return StreamSupport.stream(version.path("capabilities").path("notUsable").spliterator(),
                false)
            .filter(item -> item.path("capability").asString().equals(capability))
            .findFirst().orElseThrow().path("reason").asString();
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asString()));
        return texts;
    }

    private static void assertMatchesOutputSchema(JsonNode result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/list_nodes.output.json"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = JSON.treeToValue(result.path("structuredContent"),
            Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, content);
        assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
    }

    @Test
    void anUndetectedAutoNodeIsNotYetDetectedWithoutAnyRequest() {
        // US1-2
        JsonNode version = listVersions().get("dev/node1");

        assertThat(requests()).isZero();
        assertThat(version.path("setting").asString()).isEqualTo("AUTO");
        assertThat(version.path("server").propertyNames()).containsExactly("state");
        assertThat(version.path("server").path("state").asString())
            .isEqualTo("not_yet_detected");
        assertThat(version.path("client").propertyNames()).containsExactly("state");
        assertThat(version.path("client").path("state").asString())
            .isEqualTo("not_yet_detected");
        assertThat(version.path("status").asString()).isEqualTo("unknown");
        assertThat(usable(version)).as("reads run and detect first")
            .containsExactly("monitoring", "process_diagnosis", "log_diagnosis", "inventory",
                "workspace_export");
        assertThat(notUsable(version)).containsExactly("development_writes", "process_control");
        assertThat(reason(version, "development_writes"))
            .contains("INUBIT version of dev/node1 is unknown (not yet detected)", "get_health");
        assertThat(version.has("warnings")).isFalse();
    }

    @Test
    void afterADetectionTheNodeIsSupportedWithItsClient() {
        // US1-1: the detection request is the only one; list_nodes adds none
        gateways.detect(DEV1);
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE,
            clientVersion("8.1.17", "4.0.1", "8.1.17")));
        assertThat(requests()).isEqualTo(1);

        JsonNode version = listVersions().get("dev/node1");

        assertThat(version.path("server").path("version").asString()).isEqualTo("8.1.17");
        assertThat(version.path("server").path("source").asString()).isEqualTo("detected");
        assertThat(version.path("server").path("at").asString()).isEqualTo(NOW.toString());
        assertThat(version.path("client").propertyNames()).containsExactly("version",
            "supportedFrom", "supportedTo", "comparison");
        assertThat(version.path("client").path("version").asString()).isEqualTo("8.1.17");
        assertThat(version.path("client").path("supportedFrom").asString()).isEqualTo("4.0.1");
        assertThat(version.path("client").path("supportedTo").asString()).isEqualTo("8.1.17");
        assertThat(version.path("client").path("comparison").asString()).isEqualTo("match");
        assertThat(version.path("status").asString()).isEqualTo("supported");
        assertThat(usable(version)).containsExactly("monitoring", "process_diagnosis",
            "log_diagnosis", "inventory", "workspace_export", "development_writes",
            "process_control");
        assertThat(notUsable(version)).isEmpty();
        assertThat(version.has("warnings")).isFalse();
    }

    @Test
    void aPinnedNodeShowsItsPinWithoutDetection() {
        JsonNode version = listVersions().get("qa/node2");

        assertThat(version.path("setting").asString()).isEqualTo("8.1.17");
        assertThat(version.path("server").propertyNames()).containsExactly("version", "source");
        assertThat(version.path("server").path("version").asString()).isEqualTo("8.1.17");
        assertThat(version.path("server").path("source").asString()).isEqualTo("pinned");
        assertThat(version.path("status").asString()).isEqualTo("supported");
    }

    @Test
    void aNineOneNodeWithAnEightOneClientIsPartiallySupported() {
        // US1-5: REST reads usable (unverified), StartCLI-backed and write capabilities not
        versions.detected(DEV1, InubitVersion.parse("9.1.0").orElseThrow());
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE,
            clientVersion("8.1.17", "4.0.1", "8.1.17")));

        JsonNode version = listVersions().get("dev/node1");

        assertThat(version.path("server").path("version").asString()).isEqualTo("9.1.0");
        assertThat(version.path("client").path("comparison").asString()).isEqualTo("mismatch");
        assertThat(version.path("status").asString()).isEqualTo("partially_supported");
        assertThat(usable(version)).containsExactly("monitoring", "process_diagnosis",
            "log_diagnosis", "inventory");
        assertThat(notUsable(version)).containsExactly("inventory (StartCLI parts)",
            "workspace_export", "development_writes", "process_control");
        String excluded = "StartCLI client 8.1.17 of dev/node1 supports INUBIT 4.0.1 to 8.1.17,"
            + " which excludes INUBIT 9.1.0";
        assertThat(reason(version, "inventory (StartCLI parts)")).contains(excluded);
        assertThat(reason(version, "workspace_export")).contains(excluded);
        assertThat(reason(version, "development_writes"))
            .contains("INUBIT 9.1.0 on dev/node1 does not support development writes yet");
        assertThat(reason(version, "process_control"))
            .contains("does not support process control yet");
    }

    @Test
    void aFailedDetectionIsUnknownWithReasonAndTime() {
        // US1-3
        versions.detectionFailed(DEV2, ErrorCode.FORBIDDEN);

        JsonNode version = listVersions().get("dev/node2");

        assertThat(version.path("server").propertyNames()).containsExactly("state", "reason",
            "at");
        assertThat(version.path("server").path("state").asString()).isEqualTo("unknown");
        assertThat(version.path("server").path("reason").asString()).isEqualTo("FORBIDDEN");
        assertThat(version.path("server").path("at").asString()).isEqualTo(NOW.toString());
        assertThat(version.path("status").asString()).isEqualTo("unknown");
        assertThat(usable(version)).containsExactly("monitoring", "process_diagnosis",
            "log_diagnosis", "inventory");
    }

    @Test
    void aPinContradictedByGetHealthIsWarnedAndWritesAreNotUsable() {
        // FR-012: V8_1 pinned, get_health observed 9.0.7
        versions.observed(QA1, InubitVersion.parse("9.0.7").orElseThrow());

        JsonNode version = listVersions().get("qa/node1");

        assertThat(version.path("setting").asString()).isEqualTo("V8_1");
        assertThat(version.path("server").path("version").asString()).isEqualTo("9.0.7");
        assertThat(version.path("server").path("source").asString()).isEqualTo("observed");
        assertThat(texts(version.path("warnings"))).singleElement().asString()
            .contains("versionLine V8_1 of qa/node1 contradicts INUBIT 9.0.7");
        assertThat(notUsable(version)).contains("development_writes", "process_control");
        assertThat(reason(version, "development_writes"))
            .contains("versionLine V8_1 of qa/node1 contradicts INUBIT 9.0.7");
        assertThat(version.path("status").asString()).isEqualTo("partially_supported");
    }

    @Test
    void aNodeWithoutStartCliHasNoClient() {
        versions.detected(DEV2, InubitVersion.parse("8.1.17").orElseThrow());

        JsonNode version = listVersions().get("dev/node2");

        assertThat(version.path("client").propertyNames()).containsExactly("state");
        assertThat(version.path("client").path("state").asString()).isEqualTo("not_available");
        assertThat(version.path("status").asString()).isEqualTo("supported");
    }

    @Test
    void aClientWithAnotherPatchIsAMismatchAndStartCliCapabilitiesStayUsable() {
        // US1-4
        versions.detected(DEV1, InubitVersion.parse("8.1.18").orElseThrow());
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE,
            clientVersion("8.1.17", "4.0.1", "8.1.17")));

        JsonNode version = listVersions().get("dev/node1");

        assertThat(version.path("client").path("version").asString()).isEqualTo("8.1.17");
        assertThat(version.path("client").path("comparison").asString()).isEqualTo("mismatch");
        assertThat(usable(version)).contains("inventory", "workspace_export",
            "development_writes", "process_control");
        assertThat(notUsable(version)).isEmpty();
        assertThat(texts(version.path("warnings"))).singleElement().asString()
            .contains("StartCLI client 8.1.17", "INUBIT 8.1.18", "mismatch",
                "StartCLI-backed tools still run");
        assertThat(version.path("status").asString()).isEqualTo("supported");
    }

    @Test
    void aFailedClientProbeIsShownWithReasonAndTime() {
        versions.detected(DEV1, InubitVersion.parse("8.1.17").orElseThrow());
        versions.clientProbeFailed(DEV1, ClientKey.NONE, ErrorCode.TIMEOUT);
        versions.clientProbeFailed(QA1, ClientKey.NONE, ErrorCode.CLI_UNAVAILABLE);

        Map<String, JsonNode> byNode = listVersions();

        JsonNode timedOut = byNode.get("dev/node1").path("client");
        assertThat(timedOut.propertyNames()).containsExactly("state", "reason", "at");
        assertThat(timedOut.path("state").asString()).as("US1-6").isEqualTo("not_yet_detected");
        assertThat(timedOut.path("reason").asString()).isEqualTo("TIMEOUT");
        assertThat(timedOut.path("at").asString()).isEqualTo(NOW.toString());
        JsonNode failed = byNode.get("qa/node1").path("client");
        assertThat(failed.path("state").asString()).isEqualTo("unknown");
        assertThat(failed.path("reason").asString()).isEqualTo("CLI_UNAVAILABLE");
    }

    @Test
    void timesAreShownToTheSecondLikeTheGateTexts() {
        // production NodeVersions read Clock.systemUTC(), which has sub-second precision
        clock.advance(Duration.ofNanos(123_456_789));
        versions.detected(DEV1, InubitVersion.parse("8.1.17").orElseThrow());
        versions.clientProbeFailed(DEV1, ClientKey.NONE, ErrorCode.TIMEOUT);
        versions.detectionFailed(DEV2, ErrorCode.FORBIDDEN);
        versions.observed(QA1, InubitVersion.parse("8.1.17").orElseThrow());
        versions.clientProbeFailed(QA1, ClientKey.NONE, ErrorCode.CLI_UNAVAILABLE);

        Map<String, JsonNode> byNode = listVersions();

        assertThat(byNode.get("dev/node1").path("server").path("at").asString())
            .as("detected").isEqualTo(NOW.toString());
        assertThat(byNode.get("dev/node1").path("client").path("at").asString())
            .as("client probe timed out").isEqualTo(NOW.toString());
        assertThat(byNode.get("dev/node2").path("server").path("at").asString())
            .as("detection failed").isEqualTo(NOW.toString());
        assertThat(byNode.get("qa/node1").path("server").path("at").asString())
            .as("observed").isEqualTo(NOW.toString());
        assertThat(byNode.get("qa/node1").path("client").path("at").asString())
            .as("client probe failed").isEqualTo(NOW.toString());
    }

    @Test
    void aV9xNodeIsPartiallySupportedAndAnEightOneClientIsRefusedByItsLine() {
        // V9_X gives no major.minor: reads run unverified, writes are unsupported (FR-012), and
        // the 8.1 client's range excludes the whole 9.x line (FR-011, research D-8)
        versions.clientProbed(QA3, new ProbedClient(ClientKey.NONE,
            clientVersion("8.1.17", "4.0.1", "8.1.17")));

        JsonNode version = listVersions().get("qa/node3");

        assertThat(version.path("setting").asString()).isEqualTo("V9_X");
        assertThat(version.path("server").propertyNames()).containsExactly("version", "source");
        assertThat(version.path("server").path("version").asString()).isEqualTo("9.x");
        assertThat(version.path("server").path("source").asString()).isEqualTo("pinned");
        assertThat(version.path("client").path("version").asString()).isEqualTo("8.1.17");
        assertThat(version.path("client").path("comparison").asString())
            .isEqualTo("not_comparable");
        assertThat(version.path("status").asString()).isEqualTo("partially_supported");
        assertThat(usable(version)).containsExactly("monitoring", "process_diagnosis",
            "log_diagnosis", "inventory");
        assertThat(notUsable(version)).containsExactly("inventory (StartCLI parts)",
            "workspace_export", "development_writes", "process_control");
        String excluded = "StartCLI client 8.1.17 of qa/node3 supports INUBIT 4.0.1 to 8.1.17,"
            + " which excludes INUBIT 9.x (versionLine V9_X)";
        assertThat(reason(version, "inventory (StartCLI parts)")).isEqualTo(excluded);
        assertThat(reason(version, "workspace_export")).isEqualTo(excluded);
        assertThat(reason(version, "development_writes")).isEqualTo("INUBIT 9.x (versionLine"
            + " V9_X) on qa/node3 does not support development writes yet");
        assertThat(reason(version, "process_control")).isEqualTo("INUBIT 9.x (versionLine"
            + " V9_X) on qa/node3 does not support process control yet");
    }

    @Test
    void noVersionFieldCarriesAUrlUsernameOrPassword() {
        // FR-016, across the states of the tests above
        versions.detected(DEV1, InubitVersion.parse("9.1.0").orElseThrow());
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE,
            clientVersion("8.1.17", "4.0.1", "8.1.17")));
        versions.detectionFailed(DEV2, ErrorCode.AUTH_FAILED);
        versions.observed(QA1, InubitVersion.parse("9.0.7").orElseThrow());
        versions.clientProbeFailed(QA1, ClientKey.NONE, ErrorCode.TIMEOUT);

        String text = listVersions().values().toString();

        assertThat(text).doesNotContain("https://", "localhost", String.valueOf(
            wireMock.httpsPort()), "baseUrl", "jdoe", "username", PASSWORD, "password",
            CLI_HOME.toString());
    }

    // --- 012-T013: a 6.1 node (US3-5, research D-13) ---------------------------------------

    @Test
    void aV61NodeIsCliOnlyAndNoCapabilityIsUsableWithoutContactingIt() {
        // US3-5: the production matrix declares every capability of 6.1 unsupported; listVersions
        // fails on any request, and the launcher on any StartCLI run
        JsonNode result = versionClient.callTool("list_nodes", Map.of());
        JsonNode node = StreamSupport.stream(result.path("structuredContent").path("groups")
                .spliterator(), false)
            .flatMap(group -> StreamSupport.stream(group.path("nodes").spliterator(), false))
            .filter(item -> item.path("id").asString().equals("qa/node4"))
            .findFirst().orElseThrow();
        assertThat(node.path("versionLine").asString()).isEqualTo("V6_1");

        JsonNode version = listVersions().get("qa/node4");

        assertThat(requests()).isZero();
        assertThat(version.path("setting").asString()).isEqualTo("V6_1");
        assertThat(version.path("access").asString()).isEqualTo("cli_only");
        assertThat(version.path("server").path("version").asString()).isEqualTo("6.1");
        assertThat(version.path("server").path("source").asString()).isEqualTo("pinned");
        assertThat(version.path("status").asString()).isEqualTo("unsupported");
        assertThat(usable(version)).isEmpty();
        assertThat(notUsable(version)).containsExactly("monitoring", "process_diagnosis",
            "log_diagnosis", "inventory", "workspace_export", "development_writes",
            "process_control");
        assertThat(reason(version, "monitoring")).isEqualTo("INUBIT 6.1 (versionLine V6_1) on"
            + " qa/node4 does not support monitoring");
        assertThat(reason(version, "log_diagnosis")).isEqualTo("INUBIT 6.1 (versionLine V6_1)"
            + " on qa/node4 does not support log diagnosis");
        assertThat(reason(version, "process_control")).isEqualTo("INUBIT 6.1 (versionLine V6_1)"
            + " on qa/node4 does not support process control");
    }

    @Test
    void nodesOfOtherVersionsAreReachedOverRestAndCli() {
        // research D-13: access comes from the matrix entry of the pin; AUTO is REST-based
        versions.detected(DEV1, InubitVersion.parse("8.1.17").orElseThrow());

        Map<String, JsonNode> byNode = listVersions();

        assertThat(byNode.get("dev/node1").path("access").asString()).isEqualTo("rest_and_cli");
        assertThat(byNode.get("dev/node2").path("access").asString()).as("AUTO, not detected")
            .isEqualTo("rest_and_cli");
        assertThat(byNode.get("qa/node1").path("access").asString()).as("V8_1")
            .isEqualTo("rest_and_cli");
        assertThat(byNode.get("qa/node3").path("access").asString()).as("V9_X")
            .isEqualTo("rest_and_cli");
        assertThat(usable(byNode.get("qa/node1"))).as("US3-5: the split diagnosis names")
            .contains("process_diagnosis", "log_diagnosis").doesNotContain("diagnosis");
    }

    @Test
    void theUsableCapabilitiesOfAV61NodeAreThoseOfItsMatrixEntry() {
        // a 6.1 entry with monitoring and process diagnosis supported over StartCLI (the flip of
        // research D-15): exactly those are usable, as the gate admits them
        CapabilityMatrix flipped = new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
                new VersionRange(6, 1, 6, 1),
                Map.of(Capability.MONITORING, SupportState.SUPPORTED,
                    Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED),
                Map.of(Capability.MONITORING, Set.of(Channel.CLI),
                    Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)), true)), Map.of());

        NodeSummary.Version version = version(flipped, QA4);

        assertThat(version.access()).isEqualTo("cli_only");
        assertThat(version.status()).isEqualTo("partially_supported");
        assertThat(version.capabilities().usable()).containsExactly("monitoring",
            "process_diagnosis");
        assertThat(version.capabilities().notUsable()).extracting(NodeSummary.NotUsable::capability)
            .containsExactly("log_diagnosis", "inventory", "workspace_export",
                "development_writes", "process_control");
    }

    @Test
    void aClientThatExcludes61MakesTheStartCliCapabilitiesOfTheEntryNotUsable() {
        // FR-011: on 6.1 monitoring runs over StartCLI (the entry's channels), so a client
        // whose range excludes 6.1 refuses it
        CapabilityMatrix flipped = new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
                new VersionRange(6, 1, 6, 1), Map.of(Capability.MONITORING, SupportState.SUPPORTED),
                Map.of(Capability.MONITORING, Set.of(Channel.CLI)), true)), Map.of());
        versions.clientProbed(QA4, new ProbedClient(ClientKey.NONE,
            clientVersion("9.0.7", "8.1.0", "9.0.7")));

        NodeSummary.Version version = version(flipped, QA4);

        assertThat(version.capabilities().usable()).isEmpty();
        assertThat(version.capabilities().notUsable()).first().isEqualTo(
            new NodeSummary.NotUsable("monitoring", "StartCLI client 9.0.7 of qa/node4 supports"
                + " INUBIT 8.1.0 to 9.0.7, which excludes INUBIT 6.1"));
    }

    @Test
    void aContradictedV61PinLeavesNoCapabilityUsable() {
        // US3-6: the gate refuses every tool but get_health while the 6.1 pin is contradicted,
        // and judges monitoring on the pin
        versions.observed(QA4, InubitVersion.parse("8.1.17").orElseThrow());

        JsonNode version = listVersions().get("qa/node4");

        assertThat(version.path("access").asString()).isEqualTo("cli_only");
        assertThat(version.path("server").path("version").asString()).isEqualTo("8.1.17");
        assertThat(version.path("server").path("source").asString()).isEqualTo("observed");
        assertThat(usable(version)).isEmpty();
        assertThat(version.path("status").asString()).isEqualTo("unsupported");
        assertThat(reason(version, "monitoring")).isEqualTo("INUBIT 6.1 (versionLine V6_1) on"
            + " qa/node4 does not support monitoring");
        assertThat(reason(version, "process_diagnosis")).startsWith("versionLine V6_1 of"
            + " qa/node4 contradicts INUBIT 8.1.17 observed by get_health at");
        assertThat(reason(version, "development_writes")).startsWith("versionLine V6_1 of"
            + " qa/node4 contradicts INUBIT 8.1.17");
    }

    /** The version block of {@code node} in an overview with {@code matrix}. */
    private NodeSummary.Version version(CapabilityMatrix matrix, NodeId node) {
        return new NodeOverview(summaries, versions, matrix, (id, withClient) -> allowed.get(id),
            TestClientSelection.installed(summaries)).nodes().stream()
            .filter(summary -> summary.id().equals(node)).findFirst().orElseThrow()
            .version().orElseThrow();
    }

    @Test
    void theProductionWiringListsVersionsWithoutContactingANodeOrRunningStartCli() {
        // the capabilities a node's configuration allows come from Wiring: dev is a
        // development stage without write access, test receives deployments
        String baseUrl = "https://localhost:" + wireMock.httpsPort();
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
                development:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: %s
                    cli:
                      home: %s
                      javaHome: /opt/jdk17
              - name: test
                deploy:
                  from: dev
                  mode: PACKAGE_ONLY
                nodes:
                  - name: node1
                    baseUrl: %s
                    versionLine: V8_1
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"), baseUrl, CLI_HOME,
            baseUrl);
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        AtomicInteger runs = new AtomicInteger();
        try (TestWiring wiring = TestWiring.of(config, TestSecrets.store(Map.of(
                "INUBIT_ACME_DEV_USERNAME", "jdoe", "INUBIT_ACME_DEV_PASSWORD", PASSWORD,
                "INUBIT_ACME_TEST_USERNAME", "jdoe", "INUBIT_ACME_TEST_PASSWORD", PASSWORD)),
                scrubber, path -> true, false, noStartCli(runs), Map.of());
            McpTestClient wired = McpTestClient.start(wiring.toolHandlers(), scrubber)) {
            wired.initialize();

            JsonNode result = wired.callTool("list_nodes", Map.of());

            assertThat(requests()).as("FR-004: no request to any node").isZero();
            assertThat(runs.get()).as("FR-004: no StartCLI run").isZero();
            assertMatchesOutputSchema(result);
            JsonNode groups = result.path("structuredContent").path("groups");
            JsonNode dev = groups.path(0).path("nodes").path(0).path("version");
            assertThat(dev.path("server").path("state").asString())
                .isEqualTo("not_yet_detected");
            assertThat(dev.path("client").path("state").asString())
                .isEqualTo("not_yet_detected");
            assertThat(usable(dev)).containsExactly("monitoring", "process_diagnosis",
                "log_diagnosis", "inventory", "workspace_export");
            assertThat(notUsable(dev)).as("no write access: no process control")
                .containsExactly("development_writes");
            JsonNode test = groups.path(1).path("nodes").path(0).path("version");
            assertThat(test.path("server").path("source").asString()).isEqualTo("pinned");
            assertThat(test.path("client").path("state").asString()).isEqualTo("not_available");
            assertThat(test.path("status").asString()).isEqualTo("supported");
            assertThat(usable(test)).containsExactly("monitoring", "process_diagnosis",
                "log_diagnosis", "inventory", "deployment");
        }
    }

    // --- feature 017 T026: startCli (research D-18, FR-015) ------------------------------------

    @Test
    void eachNodeNamesItsStartCliChoiceAsOfTheCallWithoutAUrl() {
        // pinned (version entry, base URL, final), detected and undetected AUTO nodes
        Path client90 = Path.of("/opt/acme/client-9.0");
        String baseUrl = "https://localhost:" + wireMock.httpsPort();
        List<EffectiveNodeConfig> nodes = List.of(
            versionNode("dev/node1", baseUrl, VersionSetting.parse("9.0.7").orElseThrow())
                .cliClient("9.0", client90, Optional.empty()).build(),
            versionNode("dev/node2", baseUrl, VersionSetting.AUTO)
                .cliClient("9.0", client90, Optional.empty()).defaultCliHome(CLI_HOME).build(),
            versionNode("dev/node3", baseUrl, VersionSetting.AUTO)
                .cliClient("9.0", client90, Optional.empty()).defaultCliHome(CLI_HOME).build(),
            versionNode("qa/node1", baseUrl, VersionSetting.V8_1).cliHome(CLI_HOME)
                .cliUrl(baseUrl + "/cli").build());
        Map<NodeId, EffectiveNodeConfig> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));
        NodeVersions known = new NodeVersions(clock, id -> byId.get(id).versionLine(),
            id -> Duration.ofMinutes(10));
        known.detected(NodeId.parse("dev/node3"), InubitVersion.parse("9.0.7").orElseThrow());
        List<NodeSummary> listed = nodes.stream().map(node -> node.summary(EXISTS, false))
            .toList();
        CliClientSelector selector = new CliClientSelector(TestCliRunners.forHost(
            noStartCli(new AtomicInteger()), Map.of(), new CliResources("acme",
                PrivateFiles.forHost())), byId::get, EXISTS, false);
        NodeOverview overview = new NodeOverview(listed, known, CapabilityMatrix.production(),
            (id, withClient) -> READS, selector);

        try (McpTestClient mcp = McpTestClient.start(McpTestClient.TEST_PROFILE, List.of(
            new ListNodesTool(McpTestClient.TEST_PROFILE, overview::nodes)))) {
            mcp.initialize();
            JsonNode result = mcp.callTool("list_nodes", Map.of());

            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
            assertMatchesOutputSchema(result);
            Map<String, JsonNode> byNode = new HashMap<>();
            result.path("structuredContent").path("groups").forEach(group -> group.path("nodes")
                .forEach(node -> byNode.put(node.path("id").asString(), node.path("startCli"))));
            JsonNode pinned = byNode.get("dev/node1");
            assertThat(pinned.propertyNames()).as("the model's finalChoice is JSON \"final\"")
                .containsExactly("clientDirectory", "source", "versionEntry", "address", "final");
            assertThat(pinned.path("clientDirectory").asString()).isEqualTo(client90.toString());
            assertThat(pinned.path("source").asString()).isEqualTo("version_entry");
            assertThat(pinned.path("versionEntry").asString()).isEqualTo("9.0");
            assertThat(pinned.path("address").asString()).isEqualTo("base_url");
            assertThat(pinned.path("final").asBoolean()).isTrue();
            JsonNode undetected = byNode.get("dev/node2");
            assertThat(undetected.propertyNames()).containsExactly("clientDirectory", "source",
                "address", "final");
            assertThat(undetected.path("clientDirectory").asString())
                .isEqualTo(CLI_HOME.toString());
            assertThat(undetected.path("source").asString()).isEqualTo("defaults");
            assertThat(undetected.path("address").asString()).isEqualTo("servlet_path");
            assertThat(undetected.path("final").asBoolean()).as("chosen at runtime").isFalse();
            JsonNode detected = byNode.get("dev/node3");
            assertThat(detected.path("source").asString()).isEqualTo("version_entry");
            assertThat(detected.path("address").asString()).isEqualTo("base_url");
            assertThat(detected.path("final").asBoolean()).isTrue();
            JsonNode configured = byNode.get("qa/node1");
            assertThat(configured.path("source").asString()).isEqualTo("node");
            assertThat(configured.path("address").asString()).isEqualTo("configured");
            assertThat(configured.path("final").asBoolean()).isTrue();
            assertThat(result.path("structuredContent").toString())
                .doesNotContain("://", "localhost", "finalChoice");
        }
        assertThat(requests()).as("no node is contacted").isZero();
    }

    @Test
    void theClientDirectoryIsTheOnlyPlaceTheCliHomeAppears() {
        // review of stage 7 (N3): FR-015 / D-18 show startCli.clientDirectory; nothing else
        // names the home, and no URL, username, credential or trust store appears
        List<EffectiveNodeConfig> config = config();
        Map<NodeId, EffectiveNodeConfig> byId = new HashMap<>();
        config.forEach(node -> byId.put(node.id(), node));
        NodeVersions known = new NodeVersions(clock, id -> byId.get(id).versionLine(),
            id -> Duration.ofMinutes(10));
        List<NodeSummary> summaries = config.stream().map(node -> node.summary(EXISTS, false))
            .toList();
        CliClientSelector selector = new CliClientSelector(TestCliRunners.forHost(
            noStartCli(new AtomicInteger()), Map.of(), new CliResources("acme",
                PrivateFiles.forHost())), byId::get, EXISTS, false);
        NodeOverview overview = new NodeOverview(summaries, known, CapabilityMatrix.production(),
            (id, withClient) -> READS, selector);

        try (McpTestClient mcp = McpTestClient.start(McpTestClient.TEST_PROFILE, List.of(
            new ListNodesTool(McpTestClient.TEST_PROFILE, overview::nodes)))) {
            mcp.initialize();
            JsonNode result = mcp.callTool("list_nodes", Map.of());

            assertMatchesOutputSchema(result);
            JsonNode first = result.path("structuredContent").path("groups").path(0)
                .path("nodes").path(0);
            assertThat(first.path("startCli").path("clientDirectory").asString())
                .isEqualTo(CLI_HOME.toString());
            String field = "\"clientDirectory\":\"" + CLI_HOME + "\"";
            String text = result.path("content").get(0).path("text").asString();
            String structured = result.path("structuredContent").toString();
            assertThat(structured).contains(field);
            assertThat(text.replace(field, "") + structured.replace(field, ""))
                .doesNotContain(CLI_HOME.toString(), "://", "example.test", "baseUrl",
                    "username", "password", "Password", "credential", "trustStore");
        }
    }

    @Test
    void theModelNamesTheChoiceFinalChoice() {
        NodeSummary.StartCli startCli = new NodeSummary.StartCli(Optional.empty(), "none",
            Optional.empty(), "servlet_path", false);

        assertThat(startCli.finalChoice()).isFalse();
        assertThat(ResultJson.mapper().valueToTree(startCli).propertyNames())
            .containsExactly("source", "address", "final");
    }
}
