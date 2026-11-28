package de.dadecker.inubit.mcp.application;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TestValidators;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 012 (T009, SC-003, FR-007, US3): every tool on a node pinned to {@code V6_1}, through
 * the production wiring and its production capability matrix, is refused with
 * {@code UNSUPPORTED_VERSION} before any contact, except {@code get_health} and
 * {@code find_processes}, which run through StartCLI with the unverified warning since feature
 * 025 (T035, FR-036). The 6.1 node {@code old/node2} has write,
 * development, deploy and end-to-end settings and a StartCLI installation, so that only the
 * version refuses; its INUBIT and SOAP endpoint is an HTTPS WireMock without stubs, and the
 * StartCLI launcher records and fails every launch, unless a test replays 6.1 sessions
 * ({@link #sessions}). The 8.1 nodes {@code lab/node1} and
 * {@code old/node1} share another WireMock; they get every tool announced and give
 * {@code query_logs} on the group {@code old} its 8.1 entry (US3-3).
 */
class V61RefusalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NODE = "old/node2";
    private static final String SYSTEM_LOG = "/ibis/rest/log/systemLog";
    /** An audit id of no earlier call: the gate refuses before the backup is looked up. */
    private static final String BACKUP_REF = "123e4567-e89b-42d3-a456-426614174000";
    private static final String CAPABLE = "use the Workbench, or another node with a different"
        + " INUBIT version";

    @TempDir
    Path temp;

    private WireMockServer current;
    private WireMockServer legacy;
    private final List<ProcessLauncher.LaunchSpec> launches = new CopyOnWriteArrayList<>();
    /**
     * The 6.1 StartCLI sessions of a test that runs health or processes; while null every launch
     * fails. Otherwise {@code -h} replays the reconstructed 6.1 help, {@code -v} answers as a
     * 6.1.0 client and every other launch is a session.
     */
    private FakeProcessLauncher sessions;
    private TestWiring wiring;
    private McpTestClient client;

    @BeforeEach
    void start() throws IOException {
        current = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        current.stubFor(post(urlPathEqualTo(SYSTEM_LOG))
            .willReturn(RestFixtures.response("log_systemLog_filtered", "json")));
        legacy = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        // a real StartCLI script for the 6.1 node, so only the version keeps it from running;
        // the 8.1 nodes have none (no client probe, the launcher stays unused)
        Path cli61 = temp.resolve("cli61");
        Files.createDirectories(cli61.resolve("bin"));
        Files.writeString(cli61.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        // a Java 8 runtime for the 6.1 client, which takes the PKCS12 trust store through
        // JAVA_TOOL_OPTIONS (it has no trust-store option)
        Path java61 = Files.createDirectories(temp.resolve("java61"));
        Files.writeString(java61.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
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
              - name: old
                deploy:
                  from: lab
                tls:
                  trustStore: %3$s
                write:
                  enabled: true
                nodes:
                  - name: node1
                    versionLine: V8_1
                    baseUrl: https://localhost:%4$d
                    cli:
                      home: %5$s
                  - name: node2
                    versionLine: V6_1
                    baseUrl: https://localhost:%6$d
                    cli:
                      home: %7$s
                      javaHome: %8$s
                    development:
                      enabled: true
                    e2eTests: CONFIRM
                    e2e:
                      soap:
                        baseUrl: https://localhost:%6$d
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
            TestCertificates.get().trustStore(), current.httpsPort(), temp.resolve("cli81"),
            legacy.httpsPort(), cli61, java61);
        LoadedConfig loaded = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml"));
        ProfileConfig config = loaded.config();
        SecretScrubber scrubber = new SecretScrubber();
        CredentialStore credentials = TestSecrets.store(Map.of(
            "INUBIT_ACME_LAB_USERNAME", "jdoe", "INUBIT_ACME_LAB_PASSWORD", "v61-refusal-pw",
            "INUBIT_ACME_OLD_USERNAME", "jdoe", "INUBIT_ACME_OLD_PASSWORD", "v61-refusal-pw"));
        // a configuration the server starts with: only the version keeps the tools from running
        assertThat(TestValidators.of(path -> true, Map.of(), false)
            .validate(loaded).errors()).isEmpty();
        FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
        FakeProcessLauncher clientVersion = FakeProcessLauncher.of("CLI 6.1.0\nSupported inubit"
            + " Process Engine versions from 4.0.1 to 6.1.0.\n", "", 0);
        ProcessLauncher launcher = spec -> {
            launches.add(spec);
            if (sessions == null) {
                throw new IOException("StartCLI must not be launched in this test");
            }
            return switch (spec.startCliArguments().size() == 1
                ? spec.startCliArguments().get(0) : "") {
                case "-h" -> help.launch(spec);
                case "-v" -> clientVersion.launch(spec);
                default -> sessions.launch(spec);
            };
        };
        wiring = TestWiring.of(config, credentials, scrubber, Files::exists, false, launcher,
            Map.of("PATH", "/usr/bin"));
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        current.stop();
        legacy.stop();
    }

    /** The refusal of a 6.1 node, as the gate words it for {@code capability}. */
    private static String refusal(String capability, String step) {
        return "INUBIT 6.1 (versionLine V6_1) on " + NODE + " does not support " + capability
            + "; " + step + "; nothing was sent";
    }

    /** SC-003: no StartCLI run, no REST or SOAP request on the 6.1 node. */
    private void assertNothingReachedThe61Node() {
        assertThat(launches).as("StartCLI launches").isEmpty();
        assertThat(legacy.findAll(anyRequestedFor(anyUrl()))).as("requests to " + NODE)
            .isEmpty();
    }

    private static void assertRefusal(JsonNode error, String message, String nextStep) {
        assertThat(error.path("code").asString()).isEqualTo("UNSUPPORTED_VERSION");
        assertThat(error.path("message").asString()).isEqualTo(message);
        assertThat(error.path("nextStep").asString()).isEqualTo(nextStep);
        assertThat(error.path("node").asString()).isEqualTo(NODE);
    }

    private static String capitalized(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    @Test
    void everyToolOfTheTestIsAnnounced() {
        List<String> names = client.listTools().path("tools").valueStream()
            .map(tool -> tool.path("name").asString()).toList();

        assertThat(names).contains("get_health", "find_processes", "query_logs",
            "list_inventory", "get_inventory_item", "export_artifacts", "export_archives",
            "restart_process", "kill_process", "import_artifacts", "restore_backup", "set_active",
            "tag_artifacts", "deploy_release", "run_e2e_test");
    }

    /** Feature 025 (FR-036): the unverified warning of {@code capability} on the 6.1 node. */
    private static String unverified(String capability) {
        return "INUBIT 6.1 on " + NODE + " is not verified for " + capability
            + "; results may be incomplete";
    }

    /** No REST or SOAP request on the 6.1 node: StartCLI only (TLS handshake aside). */
    private void assertNoRequestToThe61Node() {
        assertThat(legacy.findAll(anyRequestedFor(anyUrl()))).as("requests to " + NODE)
            .isEmpty();
    }

    @Test
    void getHealthReadsThe61NodeThroughStartCliWithTheWarning() {
        // feature 025 (T035, FR-036): was the refusal of feature 012
        sessions = FakeProcessLauncher.replaying("v6_1", "health_session_ok");

        JsonNode result = client.callTool("get_health", Map.of("target", NODE));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode report = result.path("structuredContent").path("reports").get(0);
        assertThat(report.path("node").asString()).isEqualTo(NODE);
        assertThat(report.has("error")).as(report.toString()).isFalse();
        assertThat(report.path("reachable").asBoolean()).isTrue();
        assertThat(report.path("warnings")).extracting(JsonNode::asString)
            .contains(unverified("monitoring"));
        assertThat(sessions.launchCount()).as("one session").isEqualTo(1);
        assertNoRequestToThe61Node();
    }

    @Test
    void findProcessesListsThe61NodeThroughStartCliWithTheWarningAndTheVersionNote() {
        // feature 025 (T035, FR-036): was the refusal of feature 012
        sessions = FakeProcessLauncher.replaying("v6_1", "ps_session_all");

        JsonNode result = client.callTool("find_processes", Map.of("target", NODE));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode entry = result.path("structuredContent").path("results").get(0);
        assertThat(entry.path("node").asString()).isEqualTo(NODE);
        assertThat(entry.has("error")).as(entry.toString()).isFalse();
        assertThat(entry.path("page").path("total").asLong()).isEqualTo(9);
        assertThat(entry.path("warnings")).extracting(JsonNode::asString).containsExactly(
            unverified("process diagnosis"), "logs of INUBIT 6.1 cannot be read with query_logs;"
                + " read them in the Workbench");
        assertThat(sessions.launchCount()).as("one session").isEqualTo(1);
        assertNoRequestToThe61Node();
    }

    /** The read tools with one result entry per node: tool, arguments, expected message. */
    static Stream<Arguments> perNodeReads() {
        String inventory = "modules of INUBIT 6.1 cannot be looked up; " + CAPABLE;
        return Stream.of(
            Arguments.of("query_logs", Map.of("target", NODE, "logType", "systemLog"),
                refusal("log diagnosis", "read the logs of INUBIT 6.1 in the Workbench"),
                "read the logs of INUBIT 6.1 in the Workbench"),
            Arguments.of("list_inventory", Map.of("target", NODE, "kind", "MODULE"),
                refusal("inventory", inventory), CAPABLE),
            Arguments.of("get_inventory_item", Map.of("target", NODE, "kind", "MODULE",
                "name", "Workflow-0101"), refusal("inventory", inventory), CAPABLE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("perNodeReads")
    void aReadIsRefusedInTheResultEntryOfThe61Node(String tool, Map<String, Object> arguments,
        String message, String nextStep) {
        JsonNode result = client.callTool(tool, arguments);

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode entry = result.path("structuredContent").path("results").get(0);
        assertThat(entry.path("node").asString()).isEqualTo(NODE);
        assertThat(entry.has("page")).isFalse();
        assertRefusal(entry.path("error"), tool + ": " + message, capitalized(nextStep));
        assertNothingReachedThe61Node();
    }

    /** The tools that refuse the whole call: tool, arguments, expected message, next step. */
    static Stream<Arguments> wholeCallRefusals() {
        String write = "use the Workbench";
        return Stream.of(
            Arguments.of("export_artifacts", Map.of("target", NODE,
                "diagramGroups", List.of("grpA")),
                refusal("workspace export", "use the Workbench export"),
                "use the Workbench export"),
            Arguments.of("restart_process", Map.of("node", NODE, "processId", "110219899"),
                refusal("process control", write), write),
            Arguments.of("kill_process", Map.of("node", NODE, "processId", "110219899"),
                refusal("process control", write), write),
            Arguments.of("import_artifacts", Map.of("node", NODE, "diagramGroup", "grpA",
                "reason", "T009"), refusal("development writes", write), write),
            Arguments.of("restore_backup", Map.of("node", NODE, "backupRef", BACKUP_REF,
                "reason", "T009"), refusal("development writes", write), write),
            Arguments.of("set_active", Map.of("node", NODE, "diagramGroup", "grpA",
                "workflow", "Workflow-0001", "active", false, "reason", "T009"),
                refusal("development writes", write), write),
            Arguments.of("tag_artifacts", Map.of("node", NODE, "diagramGroups", List.of("grpA"),
                "tag", "release-1", "reason", "T009"), refusal("development writes", write),
                write),
            Arguments.of("run_e2e_test", Map.of("node", NODE, "envelope", "samples/order.xml",
                "path", "/ibis/ws/Service-01"), refusal("end-to-end test", write), write));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wholeCallRefusals")
    void aCallOnThe61NodeIsRefusedBeforeAnyContact(String tool, Map<String, Object> arguments,
        String message, String nextStep) {
        JsonNode result = client.callTool(tool, arguments);

        assertRefusal(toolError(result), tool + ": " + message, capitalized(nextStep));
        assertNothingReachedThe61Node();
    }

    /**
     * Feature 015 merged with 012: {@code export_archives} needs {@code workspace_export} like
     * {@code export_artifacts}, so it refuses a 6.1 node before any contact and writes nothing.
     */
    @Test
    void exportArchivesOnThe61NodeIsRefusedBeforeAnyContact(
        @TempDir(factory = GitFreeTempDir.class) Path archives) throws IOException {
        JsonNode result = client.callTool("export_archives", Map.of("target", NODE,
            "owner", "OWNERS", "diagramGroup", "grpA", "targetFolder", archives.toString(),
            "acknowledgeClearTextSecrets", true));

        assertRefusal(toolError(result), "export_archives: "
            + refusal("workspace export", "use the Workbench export"),
            capitalized("use the Workbench export"));
        assertNothingReachedThe61Node();
        try (Stream<Path> written = Files.list(archives)) {
            assertThat(written).as("archives written").isEmpty();
        }
    }

    @Test
    void deployReleaseIntoAGroupWithA61NodeIsRefusedNamingThatNode() {
        JsonNode result = client.callTool("deploy_release", Map.of("target", "old",
            "tag", "release-1"));

        JsonNode error = toolError(result);
        assertThat(error.path("code").asString()).isEqualTo("UNSUPPORTED_VERSION");
        assertThat(error.path("message").asString()).startsWith("deploy_release: refused"
                + " nodes: " + NODE + "; deployment from lab into old refused")
            .contains("Targets: old/node1 INUBIT 8.1, " + NODE + " INUBIT 6.1 (refused: "
                + refusal("deployment", "use the Workbench").replace("; nothing was sent", ")"))
            .endsWith("; nothing was sent");
        assertThat(error.path("nextStep").asString()).startsWith("Use the Workbench; ");
        assertNothingReachedThe61Node();
        assertThat(current.findAll(anyRequestedFor(anyUrl()))).as("no 8.1 node read either")
            .isEmpty();
    }

    @Test
    void queryLogsOnAGroupOf81And61ReturnsTheLogsAndTheRefusalPerNode() {
        // US3-3 (FR-007): the refusal is per node, the 8.1 node is read as before
        JsonNode result = client.callTool("query_logs", Map.of("target", "old",
            "logType", "systemLog"));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode results = result.path("structuredContent").path("results");
        assertThat(results).hasSize(2);
        JsonNode read = results.get(0);
        assertThat(read.path("node").asString()).isEqualTo("old/node1");
        assertThat(read.has("error")).isFalse();
        assertThat(read.path("page").path("items")).isNotEmpty();
        JsonNode refused = results.get(1);
        assertThat(refused.path("node").asString()).isEqualTo(NODE);
        assertThat(refused.has("page")).isFalse();
        assertRefusal(refused.path("error"), "query_logs: " + refusal("log diagnosis",
                "read the logs of INUBIT 6.1 in the Workbench"),
            "Read the logs of INUBIT 6.1 in the Workbench");
        assertThat(current.findAll(postRequestedFor(urlPathEqualTo(SYSTEM_LOG)))).hasSize(1);
        assertNothingReachedThe61Node();
    }

    /** The parsed {@code {"error": …}} of a tool error result. */
    private static JsonNode toolError(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        return JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
    }
}
