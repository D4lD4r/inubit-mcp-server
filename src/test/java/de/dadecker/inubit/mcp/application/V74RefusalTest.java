package de.dadecker.inubit.mcp.application;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.PatchDepth;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TestValidators;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 025 (T010, FR-007, FR-012, FR-013, SC-003, US1): every tool that contacts a node is
 * refused on a node pinned to {@code V7_4}, through the production wiring and its production
 * capability matrix, with {@code UNSUPPORTED_VERSION} and the texts of T009, before any contact.
 * The 7.4 nodes have every setting a tool needs (write, development, end-to-end, the
 * {@code unsupported} opt-ins), so that only the version refuses; {@code legacy74/node1} also has
 * a StartCLI installation and is a deployment target, {@code bare74/node1} has no client at all
 * (and so cannot be a deployment target, which needs one). Their INUBIT endpoint is a
 * TLS listener that counts connections and handshakes, their SOAP endpoint a WireMock without
 * stubs, which is also the INUBIT endpoint of the 6.1 nodes, so that its check of no request
 * covers the 6.1 nodes as well; the StartCLI launcher fails and records every launch. The 8.1 nodes share another
 * WireMock and get every tool announced; {@code legacy74/node2} gives the group calls their 8.1
 * entry. The 6.1 nodes {@code old/node1} (writes enabled) and {@code readonly/node1} (writes
 * disabled) give the outcome a 7.4 write must match (pattern {@link V61RefusalTest}).
 */
class V74RefusalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NODE = "legacy74/node1";
    /** A 7.4 node without any StartCLI client (FR-007: a missing client never changes it). */
    private static final String BARE = "bare74/node1";
    private static final String MIXED_81 = "legacy74/node2";
    private static final String SYSTEM_INFO = "/ibis/rest/system/info";
    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    /** An audit id of no earlier call: the gate refuses before the backup is looked up. */
    private static final String BACKUP_REF = "123e4567-e89b-42d3-a456-426614174000";

    @TempDir
    Path temp;

    @TempDir(factory = GitFreeTempDir.class)
    Path folders;

    private WireMockServer current;
    private WireMockServer legacy;
    private TlsTestServer tls;
    private final FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0)
        .failingToLaunch(new IOException("StartCLI must not be launched in this test"));
    private TestWiring wiring;
    private McpTestClient client;

    private final Logger gateLog = (Logger) LoggerFactory.getLogger(VersionGate.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void start() throws IOException, GeneralSecurityException {
        current = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        current.stubFor(get(urlPathEqualTo(SYSTEM_INFO)).willReturn(RestFixtures.response(
            "system_info", "xml")));
        current.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck"))
            .willReturn(RestFixtures.response("healthcheck", "json")));
        current.stubFor(get(urlPathEqualTo("/ibis/rest/ready"))
            .willReturn(RestFixtures.response("ready", "json")));
        current.stubFor(get(urlPathEqualTo("/ibis/rest/metrics"))
            .willReturn(RestFixtures.response("metrics", "json")));
        current.stubFor(post(urlPathEqualTo(QUEUE_LOG))
            .willReturn(RestFixtures.response("log_queueLog", "json")));
        legacy = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        tls = TlsTestServer.start(TestCertificates.get().localhostKeyStore());
        // the refusal log record (FR-012) at INFO, the production root level; tests run at WARN
        appender.start();
        gateLog.addAppender(appender);
        gateLog.setAdditive(false);
        gateLog.setLevel(Level.INFO);
        startWiring(CapabilityMatrix.production());
    }

    /** The server on {@code matrix}; the configuration the server starts with. */
    private void startWiring(CapabilityMatrix matrix) throws IOException {
        // a real StartCLI script for the 7.4 and 6.1 nodes, so only the version keeps it from
        // running; the 8.1 nodes have a client home that does not exist (the launcher stays unused)
        Path cli74 = script("cli74");
        Path cli61 = script("cli61");
        String yaml = """
            profile:
              name: acme
            auditDirectory: %1$s
            workspace: %2$s
            defaults:
              cliJavaHome: /opt/jdk-17
              inventory:
                owner: jdoe
            groups:
              - name: lab
                versionLine: V8_1
                tls:
                  trustStore: %3$s
                development:
                  enabled: true
                write:
                  enabled: true
                e2eTests: CONFIRM
                e2e:
                  soap:
                    baseUrl: https://localhost:%4$d
                cli:
                  home: %5$s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%4$d
              - name: legacy74
                deploy:
                  from: lab
                tls:
                  trustStore: %3$s
                write:
                  enabled: true
                unsupported:
                  queueEntryRead: true
                  serverLogRead: true
                  nodeStatusRead: true
                  ioLogRead: true
                  nodeConfigRead: true
                  logLevelWrite: true
                nodes:
                  - name: node1
                    versionLine: V7_4
                    baseUrl: https://localhost:%7$d
                    cli:
                      home: %8$s
                    development:
                      enabled: true
                    e2eTests: CONFIRM
                    e2e:
                      soap:
                        baseUrl: https://localhost:%6$d
                  - name: node2
                    versionLine: V8_1
                    baseUrl: https://localhost:%4$d
                    cli:
                      home: %5$s
              - name: bare74
                tls:
                  trustStore: %3$s
                write:
                  enabled: true
                unsupported:
                  queueEntryRead: true
                  serverLogRead: true
                  nodeStatusRead: true
                  ioLogRead: true
                  nodeConfigRead: true
                  logLevelWrite: true
                nodes:
                  - name: node1
                    versionLine: V7_4
                    baseUrl: https://localhost:%7$d
                    development:
                      enabled: true
                    e2eTests: CONFIRM
                    e2e:
                      soap:
                        baseUrl: https://localhost:%6$d
              - name: old
                tls:
                  trustStore: %3$s
                write:
                  enabled: true
                nodes:
                  - name: node1
                    versionLine: V6_1
                    baseUrl: https://localhost:%6$d
                    cli:
                      home: %9$s
              - name: readonly
                tls:
                  trustStore: %3$s
                nodes:
                  - name: node1
                    versionLine: V6_1
                    baseUrl: https://localhost:%6$d
                    cli:
                      home: %9$s
                  - name: node2
                    versionLine: V7_4
                    baseUrl: https://localhost:%7$d
                    cli:
                      home: %8$s
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
            TestCertificates.get().trustStore(), current.httpsPort(), temp.resolve("cli81"),
            legacy.httpsPort(), tls.port(), cli74, cli61);
        LoadedConfig loaded = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml"));
        SecretScrubber scrubber = new SecretScrubber();
        CredentialStore credentials = TestSecrets.store(Map.of(
            "INUBIT_ACME_LAB_USERNAME", "jdoe", "INUBIT_ACME_LAB_PASSWORD", "v74-refusal-pw",
            "INUBIT_ACME_LEGACY74_USERNAME", "jdoe", "INUBIT_ACME_LEGACY74_PASSWORD",
            "v74-refusal-pw",
            "INUBIT_ACME_BARE74_USERNAME", "jdoe", "INUBIT_ACME_BARE74_PASSWORD", "v74-refusal-pw",
            "INUBIT_ACME_OLD_USERNAME", "jdoe", "INUBIT_ACME_OLD_PASSWORD", "v74-refusal-pw",
            "INUBIT_ACME_READONLY_USERNAME", "jdoe", "INUBIT_ACME_READONLY_PASSWORD",
            "v74-refusal-pw"));
        assertThat(TestValidators.of(path -> true, Map.of(), false)
            .validate(loaded).errors()).isEmpty();
        wiring = TestWiring.of(loaded.config(), credentials, scrubber, Files::exists, false,
            launcher, Map.of("PATH", "/usr/bin"), matrix);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    private Path script(String home) throws IOException {
        Path cli = temp.resolve(home);
        Files.createDirectories(cli.resolve("bin"));
        Files.writeString(cli.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        return cli;
    }

    @AfterEach
    void stop() throws IOException, InterruptedException {
        stopWiring();
        current.stop();
        legacy.stop();
        tls.close();
        gateLog.setLevel(null);
        gateLog.setAdditive(true);
        gateLog.detachAppender(appender);
        appender.stop();
    }

    private void stopWiring() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
    }

    /**
     * The message of T009 (research D-6) for {@code capability} on the 7.4 node {@code node}.
     * {@code get_health} asks the gate for the node's monitoring access, which names no tool (as
     * on 6.1, {@link V61RefusalTest}); every other tool's message starts with its name.
     */
    private static String refusal(String tool, String node, Capability capability) {
        return (tool.equals("get_health") ? "" : tool + ": ")
            + "INUBIT 7.4 on " + node + ": " + capability.label()
            + " is not yet verified for INUBIT 7.4"
            + (capability == Capability.INVENTORY
                ? "; modules of INUBIT 7.4 cannot be looked up" : "")
            + "; nothing was sent";
    }

    /**
     * The likely cause of the refusal (contracts/mcp-tools-delta.md): the gate's wording for a
     * read or a write capability.
     */
    private static String likelyCause(Capability capability) {
        return capability.kind() == Capability.Kind.WRITE
            ? "The node runs a version for which no verified write access exists"
            : "This server declares " + capability.label() + " unsupported for that version";
    }

    /** The next step of T009 for {@code capability} on {@code node}. */
    private static String nextStep(String node, Capability capability) {
        return "INUBIT 7.4 is recognised, but no tool is verified on it yet; use the INUBIT"
            + " Workbench for " + capability.label() + " on " + node;
    }

    /**
     * SC-003: no StartCLI run (option and version probes included), no TLS handshake with and no
     * request to a 7.4 node.
     */
    private void assertNothingReachedA74Node() {
        assertThat(launcher.launchCount()).as("StartCLI launches").isZero();
        assertThat(tls.connections()).as("connections to the 7.4 INUBIT endpoint").isZero();
        assertThat(tls.handshakes()).as("TLS handshakes with the 7.4 INUBIT endpoint").isZero();
        legacy.verify(0, anyRequestedFor(anyUrl()));
    }

    /** FR-012: the refusal is logged like the version refusals of feature 009. */
    private void assertRefusalLogged(String node, Capability capability) {
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
            .startsWith(capability.name() + " on " + node + ": REFUSED (UNSUPPORTED_VERSION): ")
            .contains("INUBIT 7.4"));
    }

    /** The refusal of {@code tool} on the 7.4 node {@code node}, as T009 words it. */
    private static void assertRefusal(JsonNode error, String tool, String node,
        Capability capability) {
        assertThat(error.path("code").asString()).as(error.toString())
            .isEqualTo("UNSUPPORTED_VERSION");
        assertThat(error.path("message").asString()).isEqualTo(refusal(tool, node, capability));
        assertThat(error.path("likelyCause").asString()).isEqualTo(likelyCause(capability));
        assertThat(error.path("nextStep").asString()).isEqualTo(nextStep(node, capability));
        assertThat(error.path("node").asString()).isEqualTo(node);
    }

    /**
     * The error of {@code node} in {@code result}: the tool error of a refused call, or the error
     * of the node's entry in the per-node list of the result ({@code reports} of get_health,
     * {@code settings} of get_server_settings, {@code servers} of find_partners, {@code results}
     * of the other per-node reads).
     */
    private static JsonNode errorOf(JsonNode result, String node) {
        if (result.path("isError").asBoolean()) {
            return toolError(result);
        }
        return result.path("structuredContent").valueStream().filter(JsonNode::isArray)
            .flatMap(JsonNode::valueStream)
            .filter(entry -> entry.path("node").asString().equals(node))
            .findFirst().orElseThrow(() -> new AssertionError("no entry of " + node + ": "
                + result)).path("error");
    }

    /** The parsed {@code {"error": …}} of a tool error result. */
    private static JsonNode toolError(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        return JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
    }

    private Path folder(String name) throws IOException {
        return Files.createDirectories(folders.resolve(name));
    }

    /**
     * Every tool registered in {@code Wiring} that contacts a node, except {@code read_io_log}
     * (no line admits it in production, see below) and {@code deploy_release} (a group call, see
     * below): tool, capability, arguments for a node.
     */
    static Stream<Arguments> nodeTools() {
        Stream<Arguments> tools = Stream.of(
            tool("get_health", Capability.MONITORING, node -> Map.of("target", node)),
            tool("find_processes", Capability.PROCESS_DIAGNOSIS, node -> Map.of("target", node)),
            tool("query_logs", Capability.LOG_DIAGNOSIS,
                node -> Map.of("target", node, "logType", "systemLog")),
            tool("read_queue_entry", Capability.QUEUE_ENTRY_READ,
                node -> Map.of("target", node, "processId", "110219899")),
            tool("download_server_log", Capability.TRACE_LOG_READ,
                node -> Map.of("target", node, "log", "trace", "targetFolder", "@folder")),
            tool("get_server_settings", Capability.SERVER_SETTINGS,
                node -> Map.of("target", node)),
            tool("read_users", Capability.USER_DIRECTORY,
                node -> Map.of("target", node, "view", "users")),
            tool("read_model_details", Capability.MODEL_DETAILS,
                node -> Map.of("target", node, "action", "libraries")),
            tool("list_cluster_nodes", Capability.CLUSTER_NODES_READ,
                node -> Map.of("target", node)),
            tool("get_node_config", Capability.NODE_CONFIG_READ, node -> Map.of("target", node)),
            tool("list_inventory", Capability.INVENTORY,
                node -> Map.of("target", node, "kind", "MODULE")),
            tool("get_inventory_item", Capability.INVENTORY,
                node -> Map.of("target", node, "kind", "MODULE", "name", "Workflow-0101")),
            tool("find_partners", Capability.PARTNER_MANAGEMENT,
                node -> Map.of("target", node)),
            tool("export_artifacts", Capability.WORKSPACE_EXPORT,
                node -> Map.of("target", node, "diagramGroups", List.of("grpA"))),
            tool("export_archives", Capability.WORKSPACE_EXPORT,
                node -> Map.of("target", node, "owner", "OWNERS", "diagramGroup", "grpA",
                    "targetFolder", "@folder", "acknowledgeClearTextSecrets", true)),
            tool("restart_process", Capability.PROCESS_CONTROL,
                node -> Map.of("node", node, "processId", "110219899")),
            tool("kill_process", Capability.PROCESS_CONTROL,
                node -> Map.of("node", node, "processId", "110219899")),
            tool("operate_server", Capability.SERVER_OPERATION,
                node -> Map.of("node", node, "action", "setReadiness", "ready", true,
                    "reason", "T010")),
            tool("manage_tags", Capability.TAG_ADMINISTRATION,
                node -> Map.of("node", node, "action", "setActiveTag", "user", "jdoe",
                    "tag", "release-1", "reason", "T010")),
            tool("set_temporary_log_level", Capability.LOG_LEVEL_WRITE,
                node -> Map.of("node", node, "loggers", List.of("com.inubit.ibis.server"),
                    "level", "DEBUG")),
            tool("reset_temporary_log_level", Capability.LOG_LEVEL_WRITE,
                node -> Map.of("node", node)),
            tool("import_artifacts", Capability.DEVELOPMENT_WRITE,
                node -> Map.of("node", node, "diagramGroup", "grpA", "reason", "T010")),
            tool("restore_backup", Capability.DEVELOPMENT_WRITE,
                node -> Map.of("node", node, "backupRef", BACKUP_REF, "reason", "T010")),
            tool("set_active", Capability.DEVELOPMENT_WRITE,
                node -> Map.of("node", node, "diagramGroup", "grpA", "workflow",
                    "Workflow-0001", "active", false, "reason", "T010")),
            tool("tag_artifacts", Capability.DEVELOPMENT_WRITE,
                node -> Map.of("node", node, "diagramGroups", List.of("grpA"),
                    "tag", "release-1", "reason", "T010")),
            tool("run_e2e_test", Capability.E2E_TEST,
                node -> Map.of("node", node, "envelope", "samples/order.xml",
                    "path", "/ibis/ws/Service-01")));
        // FR-007: the refusal comes before the client lookup, so the node without a client gets
        // the same refusal
        return tools.flatMap(tool -> Stream.of(NODE, BARE).map(node -> {
            Object[] values = tool.get();
            return Arguments.of(node, values[0], values[1], values[2]);
        }));
    }

    private static Arguments tool(String name, Capability capability,
        Function<String, Map<String, Object>> arguments) {
        return Arguments.of(name, capability, arguments);
    }

    /**
     * SC-003: the cases of this test cover every announced tool except the four that contact no
     * node; {@code read_io_log} has its own case below.
     */
    @Test
    void theCasesCoverEveryAnnouncedToolThatContactsANode() {
        Set<String> names = client.listTools().path("tools").valueStream()
            .map(tool -> tool.path("name").asString()).collect(Collectors.toSet());

        assertThat(names).isEqualTo(Stream.concat(nodeTools().map(a -> (String) a.get()[1]),
                Stream.of("deploy_release", "list_nodes", "check_artifacts", "read_knowledge",
                    "record_knowledge"))
            .collect(Collectors.toSet()));
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("nodeTools")
    void aToolOnA74NodeIsRefusedBeforeAnyContact(String node, String tool,
        Capability capability, Function<String, Map<String, Object>> arguments)
        throws IOException {
        Map<String, Object> call = new HashMap<>(arguments.apply(node));
        if ("@folder".equals(call.get("targetFolder"))) {
            call.put("targetFolder", folder(tool).toString());
        }

        JsonNode result = client.callTool(tool, call);

        assertRefusal(errorOf(result, node), tool, node, capability);
        assertRefusalLogged(node, capability);
        assertNothingReachedA74Node();
    }

    /**
     * {@code read_io_log}: the production matrix admits reading the I/O log on no line, so the
     * tool is announced only with a matrix that admits it on 8.1; that matrix repeats the 7.4
     * entry of the production matrix (FR-006), which is not accessible from outside.
     */
    @Test
    void readIoLogOnA74NodeIsRefusedBeforeAnyContact() throws IOException {
        stopWiring();
        startWiring(admittingIoLogOnEightOne());

        for (String node : List.of(NODE, BARE)) {
            JsonNode result = client.callTool("read_io_log", Map.of("target", node,
                "workflow", "Workflow-0001", "processId", "110219899"));

            assertRefusal(errorOf(result, node), "read_io_log", node, Capability.IO_LOG_READ);
            assertRefusalLogged(node, Capability.IO_LOG_READ);
        }
        assertNothingReachedA74Node();
    }

    /** The 7.4 entry of the production matrix, and 8.1 with every capability supported. */
    private static CapabilityMatrix admittingIoLogOnEightOne() {
        Map<Capability, SupportState> all = new EnumMap<>(Capability.class);
        Map<Capability, String> notYetVerified = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            all.put(capability, SupportState.SUPPORTED);
            notYetVerified.put(capability, "not yet verified");
        }
        return new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(7, 4, 7, 4), Map.of(),
                Map.of(Capability.MONITORING, Set.of(Channel.CLI),
                    Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)),
                true, notYetVerified, PatchDepth.FOUR),
            new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), all)), Map.of());
    }

    @Test
    void deployReleaseIntoAGroupWithA74NodeIsRefusedNamingThatNode() {
        JsonNode result = client.callTool("deploy_release", Map.of("target", "legacy74",
            "tag", "release-1"));

        JsonNode error = toolError(result);
        assertThat(error.path("code").asString()).isEqualTo("UNSUPPORTED_VERSION");
        assertThat(error.path("message").asString()).startsWith("deploy_release: refused"
                + " nodes: " + NODE + "; deployment from lab into legacy74 refused")
            .contains(NODE + " INUBIT 7.4 (refused: INUBIT 7.4 on " + NODE + ": deployment is not"
                + " yet verified for INUBIT 7.4)")
            .endsWith("; nothing was sent");
        assertThat(error.path("nextStep").asString())
            .startsWith(nextStep(NODE, Capability.DEPLOYMENT));
        assertRefusalLogged(NODE, Capability.DEPLOYMENT);
        assertNothingReachedA74Node();
        assertThat(current.findAll(anyRequestedFor(anyUrl()))).as("no 8.1 node read either")
            .isEmpty();
    }

    /** FR-007: in a call addressing several nodes the refusal is per node; 8.1 is read as before. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("groupReads")
    void aGroupOf81And74AnswersThe81NodeAndRefusesThe74NodeInItsOwnEntry(String tool,
        Capability capability) {
        JsonNode result = client.callTool(tool, Map.of("target", "legacy74"));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(errorOf(result, MIXED_81).isMissingNode()).as(result.toString()).isTrue();
        assertRefusal(errorOf(result, NODE), tool, NODE, capability);
        assertThat(current.findAll(anyRequestedFor(anyUrl()))).as("reads of " + MIXED_81)
            .isNotEmpty();
        assertNothingReachedA74Node();
    }

    static Stream<Arguments> groupReads() {
        return Stream.of(Arguments.of("get_health", Capability.MONITORING),
            Arguments.of("find_processes", Capability.PROCESS_DIAGNOSIS));
    }

    /**
     * FR-007: a write on 7.4 has the outcome of the same write on 6.1, with writes enabled
     * ({@code old/node1}) and with writes disabled ({@code readonly}), the order of the write
     * checks and the version check unchanged.
     */
    @Test
    void aWriteOnA74NodeHasTheOutcomeOfTheSameWriteOnA61Node() {
        JsonNode enabled61 = toolError(client.callTool("restart_process",
            Map.of("node", "old/node1", "processId", "110219899")));
        JsonNode enabled74 = toolError(client.callTool("restart_process",
            Map.of("node", NODE, "processId", "110219899")));
        JsonNode disabled61 = toolError(client.callTool("restart_process",
            Map.of("node", "readonly/node1", "processId", "110219899")));
        JsonNode disabled74 = toolError(client.callTool("restart_process",
            Map.of("node", "readonly/node2", "processId", "110219899")));

        assertThat(enabled74.path("code").asString()).isEqualTo(enabled61.path("code").asString())
            .isEqualTo("UNSUPPORTED_VERSION");
        assertThat(disabled74.path("code").asString())
            .isEqualTo(disabled61.path("code").asString()).isNotEqualTo("UNSUPPORTED_VERSION");
        assertThat(disabled74.path("message").asString()).isEqualTo(disabled61.path("message")
            .asString().replace("readonly/node1", "readonly/node2"));
        assertNothingReachedA74Node();
    }
}
