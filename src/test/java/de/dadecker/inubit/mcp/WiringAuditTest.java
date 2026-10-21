package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.ImportHarness;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 010 (T020, research R-16, Constitution VII): the INUBIT account in the audit records
 * and in the check-in comment of an import, through the production wiring. An AWS node's records
 * carry the username fetched from Parameter Store, also when the fetch happens during the audited
 * call; a record written before any fetch carries {@code (Parameter Store <username parameter>)};
 * an environment node carries its startup username. INUBIT is an HTTPS WireMock (REST) plus the
 * scripted StartCLI and fake server of {@link ImportHarness}.
 */
@Timeout(120)
class WiringAuditTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final StoreLocation EU = new StoreLocation("eu-central-1", Optional.empty());
    private static final String USERNAME = "/test/dev/username";
    private static final String PASSWORD = "/test/dev/password";
    private static final String AWS_USER = "aws-audit-user";
    private static final String ENV_USER = "env-audit-user";
    private static final String PLACEHOLDER = "(Parameter Store " + USERNAME + ")";
    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    private static final String PID = "110219899";

    @TempDir
    Path temp;

    private WireMockServer inubit;
    private ImportHarness harness;
    private final FakeParameterStore store = new FakeParameterStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"));
    private final SecretScrubber scrubber = new SecretScrubber();
    private Wiring wiring;
    private McpTestClient client;

    @BeforeEach
    void start() throws IOException {
        inubit = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck"))
            .willReturn(RestFixtures.response("healthcheck", "json")));
        inubit.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        inubit.stubFor(post(urlPathEqualTo(QUEUE_LOG)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json").withBody("""
                {"queueLog":{"total":1,"success":true,"count":1,"row":[{"owner":"OWNERS",
                "moduleName":"M","workflowName":"W","globalPId":110219899,
                "startTime":1790861373734,"workflowId":110219899,
                "status":{"level":0,"content":"Error"}}]}}""")));
        harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
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

    /** {@code credentials}: the dev group's {@code credentials} section, or empty. */
    private void startServer(String credentials, Map<String, String> variables) {
        String yaml = """
            profile:
              name: acme
            auditDirectory: %1$s
            workspace: %2$s
            defaults:
              cliHome: %3$s
              cliJavaHome: /opt/jdk-17
              inventory:
                owner: jdoe
            groups:
              - name: dev
                versionLine: V8_1
                development:
                  enabled: true
                  confirmation: CLIENT
                write:
                  enabled: true
                  confirmation: CLIENT
                tls:
                  trustStore: %4$s
            %5$s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%6$d
                  - name: node2
                    baseUrl: https://localhost:%6$d
                    write:
                      enabled: false
            """.formatted(temp.resolve("audit"), harness.root, harness.cliHome,
            TestCertificates.get().trustStore(), credentials.indent(4).stripTrailing(),
            inubit.httpsPort());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        Map<String, String> environment = new HashMap<>(variables);
        // the workspace history runs the system git
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin"));
        wiring = new Wiring(config, new CredentialResolver(environment, scrubber,
                config.terminology().effectiveOrDefault(), config.credentialPrefix(),
                config.secretPlans()).resolve(config.nodeIds()), scrubber,
            new ClockProvider(clock), Files::exists, false, startCli(), environment,
            () -> store);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    /** The scripted StartCLI of the harness; the client version probe answers 8.1.17. */
    private ProcessLauncher startCli() {
        return spec -> spec.command().contains("--execCommand") ? harness.cli.launch(spec)
            : FakeProcessLauncher.replaying("version").launch(spec);
    }

    private void startAwsServer() {
        store.put(EU, USERNAME, AWS_USER).put(EU, PASSWORD, "test-password-1");
        startServer("""
            credentials:
              source:
                type: awsParameterStore
                region: eu-central-1
              parameters:
                username: %s
                password: %s
            """.formatted(USERNAME, PASSWORD), Map.of());
    }

    private void startEnvironmentServer() {
        startServer("", Map.of("INUBIT_ACME_DEV_USERNAME", ENV_USER,
            "INUBIT_ACME_DEV_PASSWORD", "test-password-2"));
    }

    private JsonNode call(String tool, Map<String, Object> arguments) {
        return client.callTool(tool, arguments, Duration.ofSeconds(60));
    }

    private JsonNode restart() {
        harness.cli.expect("processErrorStart " + PID).replying("processErrorStart_ok");
        return call("restart_process", Map.of("node", "dev/node1", "processId", PID,
            "reason", "audit test"));
    }

    private JsonNode importGroup() {
        // the module list of the owner (inventory) is a module export of the fake server
        harness.cli.expect("export --exportModule '' --exportModuleGroup ''"
            + " --exportModuleUser 'jdoe'").then(spec -> write(
                ScriptedProcessLauncher.exportFile(spec), harness.inubit.exportWorkflowGroup()))
            .replying(ImportHarness.MODULE_OK, "", 0);
        harness.exportGroup().importApplied().exportGroup();
        return call("import_artifacts", Map.of("node", "dev/node1", "diagramGroup", "GRP-01",
            "reason", "audit test"));
    }

    private static void write(Path file, byte[] content) {
        try {
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertExecuted(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(result.path("structuredContent").path("result").path("outcome").asString())
            .as(result.toString()).isEqualTo("EXECUTED");
    }

    /** The audit records of {@code capability}, in order. */
    private List<JsonNode> audit(String capability) throws IOException {
        List<JsonNode> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(temp.resolve("audit"))) {
            for (Path file : files.sorted().toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    JsonNode record = JSON.readTree(line);
                    if (record.path("capability").asString().equals(capability)) {
                        records.add(record);
                    }
                }
            }
        }
        return records;
    }

    private static List<String> accounts(List<JsonNode> records) {
        return records.stream().map(record -> record.path("account").asString()).toList();
    }

    /** The workflow file of every archive the fake server received. */
    private String importedWorkflows() {
        StringBuilder text = new StringBuilder();
        harness.inubit.imported.forEach(zip -> text.append(new String(ArtifactFixtures
            .entries(zip).get("workflow/workflow.xml"), StandardCharsets.UTF_8)));
        return text.toString();
    }

    @Test
    void anExecutedRestartOnAnAwsNodeCarriesTheUsernameFetchedDuringTheCall() throws IOException {
        startAwsServer();

        JsonNode restarted = restart();

        assertExecuted(restarted);
        assertThat(store.calls()).as("the state read fetched the secrets").isEqualTo(1);
        List<JsonNode> records = audit("restart_process");
        assertThat(records).extracting(record -> record.path("outcome").asString())
            .containsExactly("PENDING", "EXECUTED");
        assertThat(accounts(records)).containsOnly(AWS_USER);
        assertThat(records.toString()).doesNotContain("test-password-1");
    }

    @Test
    void anImportOnAnAwsNodeCarriesTheFetchedUsernameInItsAuditAndCheckInComment()
        throws IOException {
        startAwsServer();

        assertExecuted(importGroup());

        harness.cli.verifyComplete();
        List<JsonNode> records = audit("import_artifacts");
        assertThat(records).extracting(record -> record.path("outcome").asString())
            .containsExactly("PENDING", "EXECUTED");
        assertThat(accounts(records)).containsOnly(AWS_USER);
        assertThat(importedWorkflows()).contains("Deploying User: " + AWS_USER + "@@@")
            .doesNotContain(PLACEHOLDER, "Deploying User: unknown");
        assertThat(records.toString()).doesNotContain("test-password-1");
    }

    @Test
    void aRefusalBeforeAnyFetchCarriesTheParameterNameAndALaterOneTheFetchedUsername()
        throws IOException {
        startAwsServer();
        Map<String, Object> readOnly = Map.of("node", "dev/node2", "processId", PID);

        JsonNode refused = call("restart_process", readOnly);

        assertThat(refused.toString()).as("refused").contains("WRITE_DISABLED");
        assertThat(store.calls()).as("nothing was fetched").isZero();
        assertThat(accounts(audit("restart_process"))).containsExactly(PLACEHOLDER);

        call("find_processes", Map.of("target", "dev/node2"));
        assertThat(store.calls()).as("the read fetched the secrets of node2").isEqualTo(1);
        call("restart_process", readOnly);

        assertThat(accounts(audit("restart_process"))).containsExactly(PLACEHOLDER, AWS_USER);
    }

    @Test
    void anEnvironmentNodeCarriesItsStartupUsername() throws IOException {
        startEnvironmentServer();

        JsonNode refused = call("restart_process", Map.of("node", "dev/node2",
            "processId", PID));
        assertThat(refused.toString()).as("refused").contains("WRITE_DISABLED");
        assertExecuted(restart());
        assertExecuted(importGroup());

        assertThat(store.calls()).isZero();
        assertThat(accounts(audit("restart_process"))).containsExactly(ENV_USER, ENV_USER,
            ENV_USER);
        assertThat(accounts(audit("import_artifacts"))).containsOnly(ENV_USER);
        assertThat(importedWorkflows()).contains("Deploying User: " + ENV_USER + "@@@");
    }
}
