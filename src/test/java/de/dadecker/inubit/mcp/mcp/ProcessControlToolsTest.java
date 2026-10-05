package de.dadecker.inubit.mcp.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.ConfirmationRegistry;
import de.dadecker.inubit.mcp.application.ProcessControlService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.VersionLine;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.tools.KillProcessTool;
import de.dadecker.inubit.mcp.mcp.tools.RestartProcessTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 * T105: {@code restart_process} and {@code kill_process} over MCP (contracts/mcp-tools.md §7–8,
 * Story 4 / AS 6, AS 7, quickstart V9 – V13): registration only with effective write access,
 * annotations, input schema, and the two-step flow end to end with WireMock (state reads) and a
 * fake StartCLI.
 */
@Timeout(60)
class ProcessControlToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    private static final String PID = "110219899";
    private static final String PASSWORD = "process-control-tool-pw";
    private static final String RESTART_DESCRIPTION = "[acme] Restart ONE process instance that"
        + " is in ERROR state on ONE INUBIT node. Changes production data flow. On nodes with"
        + " `confirmationMode` `SERVER` (the default), the first call only returns a preview and"
        + " a confirmationCode; call again with the code to execute.";
    private static final String KILL_DESCRIPTION = "[acme] Delete (kill) ONE process instance on"
        + " ONE INUBIT node. Irreversible. Same two-step confirmation as restart_process.";

    private static WireMockServer dev;

    @TempDir
    Path temp;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeAll
    static void start() {
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        dev.stop();
    }

    @BeforeEach
    void reset() {
        dev.resetAll();
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    // --- registration (Story 4 / AS 6) ---------------------------------------------------------

    private List<String> toolNames(boolean devWriteEnabled) {
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            groups:
              - name: dev
                write:
                  enabled: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
              - name: prod
                production: true
                write:
                  enabled: true
                nodes:
                  - name: inubit01
                    baseUrl: https://localhost:2
            """.formatted(temp.resolve("audit"), devWriteEnabled);
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        TestWiring wiring = TestWiring.of(config, new CredentialResolver(Map.of(
            "INUBIT_ACME_DEV_USERNAME", "jdoe", "INUBIT_ACME_DEV_PASSWORD", PASSWORD,
            "INUBIT_ACME_PROD_USERNAME", "jdoe", "INUBIT_ACME_PROD_PASSWORD", PASSWORD), scrubber,
            config.credentialPrefix())
            .resolve(config.nodeIds()), scrubber, path -> false, false);
        closeables.add(wiring);
        McpTestClient client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        closeables.add(client);
        client.initialize();
        List<String> names = new ArrayList<>();
        client.listTools().path("tools").forEach(tool -> names.add(tool.path("name").asString()));
        return names;
    }

    @Test
    void withoutEffectiveWriteAccessTheWriteToolsAreNotOffered() {
        // dev: write disabled (default); prod: write.enabled but no productionOptIn
        assertThat(toolNames(false)).containsExactlyInAnyOrder("list_nodes", "get_health",
            "find_processes", "query_logs", "list_inventory", "get_inventory_item");
    }

    @Test
    void withWriteAccessOnEntBothWriteToolsAreOffered() {
        assertThat(toolNames(true)).contains("restart_process", "kill_process").hasSize(8);
    }

    @Test
    void noAuditFileIsWrittenWithoutAWriteCall() {
        toolNames(true);

        assertThat(Files.exists(temp.resolve("audit"))).isFalse();
    }

    // --- the tools with fakes ------------------------------------------------------------------

    /** The write tools on {@code dev/node1} (WireMock + fake StartCLI), audit in {@code temp}. */
    private McpTestClient client(FakeProcessLauncher launcher, ConfirmationMode confirmation)
        throws IOException {
        Path cliHome = Files.createDirectories(temp.resolve("cli/bin")).getParent();
        Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        EffectiveNodeConfig server = TestNodeConfig.node().id("dev/node1")
            .baseUrl("https://localhost:" + dev.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).versionLine(VersionLine.V8_1)
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .write(true, false, confirmation).build();
        SecretScrubber scrubber = new SecretScrubber();
        List<NodeId> ids = List.of(server.id());
        AdapterGatewayFactory gateways = new AdapterGatewayFactory(List.of(server),
            new CredentialResolver(Map.of("INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD",
                PASSWORD), scrubber, "INUBIT").resolve(ids), scrubber, Clock.systemUTC(),
            new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false, new CliResources("acme")));
        closeables.add(gateways);
        Map<NodeId, WritePolicy> policies = Map.of(server.id(), new WritePolicy(server.id(),
            false, true, false, confirmation == ConfirmationMode.SERVER
                ? WritePolicy.Confirmation.SERVER : WritePolicy.Confirmation.CLIENT,
            Duration.ofMinutes(5), Optional.of("jdoe")));
        Function<NodeId, WritePolicy> policyOf = policies::get;
        ProcessControlService service = new ProcessControlService(
            new WriteGuard(new TargetResolver(ids), policyOf, gateways), policyOf, gateways,
            new ConfirmationRegistry(Clock.systemUTC()),
            new AuditLog(temp.resolve("audit"), scrubber), Clock.systemUTC(), UUID::randomUUID,
            "acme");
        McpTestClient client = McpTestClient.start(List.of(new RestartProcessTool(service),
            new KillProcessTool(service)), scrubber);
        closeables.add(client);
        client.initialize();
        return client;
    }

    private static String queueLog(boolean withRow) {
        if (!withRow) {
            return "{\"queueLog\":{\"total\":0,\"success\":true,\"count\":0}}";
        }
        return """
            {"queueLog":{"total":1,"success":true,"count":1,"row":[{"owner":"OWNERS",
            "moduleType":"Workflow Connector","moduleName":"Module-0036(23379903)",
            "workflowName":"Workflow-0424","priority":"normal","node":"ip-192-0-2-1",
            "globalPId":110219899,"startTime":1790861373734,"tag":"","workflowId":110219899,
            "status":{"level":0,"content":"Error"}}]}}""";
    }

    /** Successive state reads: answers in order, the last one repeats. */
    private static void stubReads(boolean... withRow) {
        String state = Scenario.STARTED;
        for (int i = 0; i < withRow.length; i++) {
            String next = i + 1 < withRow.length ? "read-" + (i + 1) : state;
            dev.stubFor(post(urlPathEqualTo(QUEUE_LOG)).inScenario("reads")
                .whenScenarioStateIs(state).withRequestBody(containing(PID))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(queueLog(withRow[i])))
                .willSetStateTo(next));
            state = next;
        }
    }

    private List<JsonNode> auditRecords() throws IOException {
        List<JsonNode> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(temp.resolve("audit"))) {
            for (Path file : files.sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    records.add(JSON.readTree(line));
                }
            }
        }
        return records;
    }

    private static JsonNode tool(McpTestClient client, String name) {
        for (JsonNode tool : client.listTools().path("tools")) {
            if (tool.path("name").asString().equals(name)) {
                return tool;
            }
        }
        throw new AssertionError("tool " + name + " not listed");
    }

    private static JsonNode toolError(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        assertThat(result.has("structuredContent")).isFalse();
        return JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
    }

    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(JSON.readTree(result.path("content").get(0).path("text").asString()))
            .isEqualTo(result.path("structuredContent"));
        return result.path("structuredContent");
    }

    @Test
    void bothToolsAreDestructiveNonIdempotentAndNotReadOnlyWithTheContractDescriptions()
        throws IOException {
        McpTestClient client = client(FakeProcessLauncher.replaying("processErrorStart_ok"),
            ConfirmationMode.SERVER);

        for (String name : List.of("restart_process", "kill_process")) {
            JsonNode annotations = tool(client, name).path("annotations");
            assertThat(annotations.path("destructiveHint").asBoolean()).as(name).isTrue();
            assertThat(annotations.path("idempotentHint").asBoolean()).as(name).isFalse();
            assertThat(annotations.path("readOnlyHint").asBoolean()).as(name).isFalse();
            assertThat(annotations.path("openWorldHint").asBoolean()).as(name).isTrue();
        }
        assertThat(tool(client, "restart_process").path("description").asString())
            .isEqualTo(RESTART_DESCRIPTION);
        assertThat(tool(client, "kill_process").path("description").asString())
            .isEqualTo(KILL_DESCRIPTION);
    }

    @Test
    void theInputSchemaFollowsTheContract() throws IOException {
        McpTestClient client = client(FakeProcessLauncher.replaying("processErrorStart_ok"),
            ConfirmationMode.SERVER);

        for (String name : List.of("restart_process", "kill_process")) {
            JsonNode schema = tool(client, name).path("inputSchema");
            JsonNode properties = schema.path("properties");
            assertThat(schema.path("required")).extracting(JsonNode::asString)
                .containsExactlyInAnyOrder("node", "processId");
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(properties.path("node").path("pattern").asString())
                .isEqualTo("^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$");
            assertThat(properties.path("processId").path("pattern").asString())
                .isEqualTo("^[1-9][0-9]{0,18}$");
            assertThat(properties.path("confirmationCode").path("pattern").asString())
                .isEqualTo("^[A-Za-z0-9_-]{22}$");
            assertThat(properties.path("reason").path("maxLength").asInt()).isEqualTo(500);
            assertThat(names(properties)).containsExactlyInAnyOrder("node", "processId",
                "confirmationCode", "reason");
        }
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void theSchemaRejectsAStageAUuidAndABadCodeBeforeTheTool() throws IOException {
        McpTestClient client = client(FakeProcessLauncher.replaying("processErrorStart_ok"),
            ConfirmationMode.SERVER);

        List<Map<String, Object>> invalid = List.of(
            Map.of("node", "dev", "processId", PID),
            Map.of("node", "dev/node1", "processId", "7b0c4f1e-3a52-4d5e-9a40-1f2e3d4c5b6a"),
            Map.of("node", "dev/node1", "processId", "0" + PID),
            Map.of("node", "dev/node1", "processId", PID, "confirmationCode", "short"),
            Map.of("node", "dev/node1", "processId", PID, "reason", "x".repeat(501)),
            Map.of("node", "dev/node1", "processId", PID, "force", true));
        for (Map<String, Object> arguments : invalid) {
            JsonNode result = client.callTool("restart_process", arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
        }
        assertThat(dev.findAll(postRequestedFor(urlPathEqualTo(QUEUE_LOG)))).isEmpty();
        assertThat(Files.exists(temp.resolve("audit"))).isFalse();
    }

    @Test
    void theTwoStepRestartWorksEndToEnd() throws IOException {
        stubReads(true, true, false);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("processErrorStart_ok");
        McpTestClient client = client(launcher, ConfirmationMode.SERVER);

        JsonNode first = structured(client.callTool("restart_process", Map.of(
            "node", "dev/node1", "processId", PID, "reason", "mapping fixed")));
        JsonNode challenge = first.path("challenge");
        String code = challenge.path("confirmationCode").asString();
        assertThat(first.has("result")).isFalse();
        assertThat(code).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(challenge.path("preview").path("node").asString()).isEqualTo("dev/node1");
        assertThat(challenge.path("preview").path("action").asString()).isEqualTo("RESTART");
        assertThat(challenge.path("preview").path("processId").asString()).isEqualTo(PID);
        assertThat(challenge.path("preview").path("workflow").asString())
            .isEqualTo("Workflow-0424");
        assertThat(challenge.path("preview").path("state").asString()).isEqualTo("ERROR");
        assertThat(challenge.path("expiresAt").asString()).endsWith("Z");
        assertThat(launcher.launchCount()).isZero();

        JsonNode second = structured(client.callTool("restart_process", Map.of(
            "node", "dev/node1", "processId", PID, "confirmationCode", code)));
        JsonNode result = second.path("result");

        assertThat(second.has("challenge")).isFalse();
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("stateBefore").asString()).isEqualTo("ERROR");
        assertThat(result.path("stateAfter").asString()).isEqualTo("NOT_IN_QUEUE");
        assertThat(launcher.launchCount()).isEqualTo(1);
        List<String> command = launcher.last().spec().command();
        assertThat(command.get(command.indexOf("--execCommand") + 1))
            .isEqualTo("processErrorStart " + PID);
        List<JsonNode> records = auditRecords();
        assertThat(records).extracting(record -> record.path("outcome").asString())
            .containsExactly("CHALLENGE_ISSUED", "PENDING", "EXECUTED");
        assertThat(records).allSatisfy(record -> {
            assertThat(record.path("mcpClient").asString()).isEqualTo("mcp-test-client/1.0");
            assertThat(record.path("account").asString()).isEqualTo("jdoe");
            assertThat(record.path("capability").asString()).isEqualTo("restart_process");
        });
        assertThat(records.get(0).path("inputs").path("reason").asString())
            .isEqualTo("mapping fixed");
        assertThat(records.get(2).path("auditId").asString())
            .isEqualTo(result.path("auditId").asString());
        assertThat(records.toString()).doesNotContain(code).doesNotContain(PASSWORD);

        JsonNode reused = toolError(client.callTool("restart_process", Map.of(
            "node", "dev/node1", "processId", PID, "confirmationCode", code)));
        assertThat(reused.path("code").asString()).isIn("CONFIRMATION_INVALID", "NOT_FOUND");
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void killWithClientConfirmationExecutesOnTheFirstCall() throws IOException {
        stubReads(true, false);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("kill_ok");
        McpTestClient client = client(launcher, ConfirmationMode.CLIENT);

        JsonNode result = structured(client.callTool("kill_process", Map.of(
            "node", "dev/node1", "processId", PID))).path("result");

        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("action").asString()).isEqualTo("KILL");
        assertThat(result.path("message").asString()).isEqualTo("Process 110190387 killed.");
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(auditRecords()).extracting(record -> record.path("outcome").asString())
            .containsExactly("PENDING", "EXECUTED");
    }

    @Test
    void anUnknownProcessIsNotFoundAndAudited() throws IOException {
        stubReads(false);
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("kill_ok");
        McpTestClient client = client(launcher, ConfirmationMode.SERVER);

        JsonNode error = toolError(client.callTool("kill_process", Map.of(
            "node", "dev/node1", "processId", PID)));

        assertThat(error.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.path("node").asString()).isEqualTo("dev/node1");
        assertThat(launcher.launchCount()).isZero();
        assertThat(auditRecords()).extracting(record -> record.path("outcome").asString())
            .containsExactly("REFUSED");
    }

    @Test
    void theOutputsMatchTheOutputSchemas() throws IOException {
        stubReads(true, true, false);
        McpTestClient client = client(FakeProcessLauncher.replaying("processErrorStart_ok"),
            ConfirmationMode.SERVER);

        // the SDK validates structuredContent against the output schema; an invalid result would
        // come back as an error
        JsonNode first = client.callTool("restart_process", Map.of("node", "dev/node1",
            "processId", PID));
        JsonNode second = client.callTool("restart_process", Map.of("node", "dev/node1",
            "processId", PID, "confirmationCode",
            structured(first).path("challenge").path("confirmationCode").asString()));

        assertThat(names(structured(first))).containsExactly("challenge");
        assertThat(names(structured(second))).containsExactly("result");
        assertThat(names(structured(second).path("result")).stream()
            .collect(Collectors.toSet())).containsExactlyInAnyOrder("node", "action",
                "processId", "outcome", "stateBefore", "stateAfter", "message", "auditId");
    }
}
