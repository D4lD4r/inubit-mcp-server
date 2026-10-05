package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.ScrubbingJsonEncoder;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T117 / SC-006 end to end: unique fake passwords are seeded through the injected environment;
 * every tool is driven through {@link McpTestClient} on the production wiring, including the
 * error paths auth failure, unreachable, timeout and unexpected output (REST and StartCLI). No
 * seed and no {@code base64(user:seed)} may occur in the protocol stdout, the operational log
 * (encoded by the production {@link ScrubbingJsonEncoder}), the audit files, any
 * {@code ToolError} excerpt, or any StartCLI argument array or environment.
 *
 * <p>The nodes: {@code dev/node1} answers normally (write-enabled, server-side confirmation)
 * but echoes the secrets in an unparseable log response and in unrecognized StartCLI output;
 * {@code qa/node1} rejects every login (HTTP 401, CLI {@code LoginFailure}) and is
 * write-enabled with client confirmation; {@code qa/node2} is unreachable; {@code test/node1}
 * answers too late.
 */
@Timeout(120)
class NoSecretLeakTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String USER = "leak-user";
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");
    private static final String QUEUE_LOG = "/ibis/rest/log/queueLog";
    private static final String PID = "110219899";

    @TempDir
    Path temp;

    private final String devSeed = "dev-seed-" + UUID.randomUUID();
    private final String qaSeed = "qa-seed-" + UUID.randomUUID();
    private final String testSeed = "test-seed-" + UUID.randomUUID();
    private final List<FakeProcessLauncher.FakeProcess> launches = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream logs = new ByteArrayOutputStream();

    private WireMockServer dev;
    private WireMockServer integration;
    private WireMockServer slow;
    private OutputStreamAppender<ILoggingEvent> appender;
    private Level previousLevel;
    private Level previousSdkLevel;
    private TestWiring wiring;
    private McpTestClient client;

    private List<String> forbidden() {
        List<String> values = new ArrayList<>();
        for (String seed : List.of(devSeed, qaSeed, testSeed)) {
            values.add(seed);
            values.add(Base64.getEncoder().encodeToString((USER + ":" + seed)
                .getBytes(StandardCharsets.UTF_8)));
        }
        return values;
    }

    @BeforeEach
    void setUp() throws IOException {
        captureLogs();
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        integration = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        slow = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        stubEnt();
        integration.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(401)
            .withBody("<html>401 " + USER + ":" + qaSeed + "</html>")));
        integration.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck")).atPriority(1)
            .willReturn(RestFixtures.response("healthcheck", "json")));
        // the REST login is accepted once, so that the first CLI export reaches StartCLI and
        // fails there with LoginFailure; afterwards the guard answers AUTH_FAILED from cache
        integration.stubFor(get(urlPathEqualTo("/ibis/rest/system/info")).atPriority(1)
            .willReturn(RestFixtures.response("system_info", "xml")));
        slow.stubFor(any(anyUrl()).willReturn(RestFixtures.response("healthcheck", "json")
            .withFixedDelay(2_500)));

        Path cliHome = Files.createDirectories(temp.resolve("cli/bin")).getParent();
        Files.writeString(cliHome.resolve("bin/startcli.sh"), "#!/bin/sh\n");
        String yaml = """
            profile:
              name: acme
            auditDirectory: %1$s
            defaults:
              cliHome: %2$s
              cliJavaHome: /opt/jdk-17
              timeout: PT0.5S
              cliTimeout: PT5S
              inventory:
                owner: INTEGRATION
            groups:
              - name: dev
                versionLine: V8_1
                tls:
                  trustStore: %3$s
                write:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%4$d
              - name: qa
                versionLine: V8_1
                tls:
                  trustStore: %3$s
                write:
                  enabled: true
                  confirmation: CLIENT
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%5$d
                  - name: node2
                    baseUrl: https://127.0.0.1:9
              - name: test
                versionLine: V8_1
                tls:
                  trustStore: %3$s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%6$d
            """.formatted(temp.resolve("audit"), cliHome, TestCertificates.get().trustStore(),
            dev.httpsPort(), integration.httpsPort(), slow.httpsPort());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("INUBIT_ACME_DEV_USERNAME", USER);
        environment.put("INUBIT_ACME_DEV_PASSWORD", devSeed);
        environment.put("INUBIT_ACME_QA_USERNAME", USER);
        environment.put("INUBIT_ACME_QA_PASSWORD", qaSeed);
        environment.put("INUBIT_ACME_TEST_USERNAME", USER);
        environment.put("INUBIT_ACME_TEST_PASSWORD", testSeed);
        SecretScrubber scrubber = SecretScrubber.global();
        Map<String, String> parent = new LinkedHashMap<>(environment);
        parent.put("PATH", "/usr/bin");
        wiring = TestWiring.of(config, new CredentialResolver(environment, scrubber,
            config.credentialPrefix())
                .resolve(config.nodeIds()), scrubber, Files::exists, false, startCli(),
            parent);
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        for (WireMockServer server : new WireMockServer[] {dev, integration, slow}) {
            if (server != null) {
                server.stop();
            }
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
        context.getLogger("io.modelcontextprotocol").setLevel(previousSdkLevel);
    }

    /** The production encoder on every log event; our loggers at TRACE. */
    private void captureLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // configured as in logback.xml
        ScrubbingJsonEncoder encoder = new ScrubbingJsonEncoder();
        encoder.setWithFormattedMessage(true);
        encoder.setWithMessage(false);
        encoder.setWithArguments(false);
        encoder.setWithContext(false);
        encoder.setWithNanoseconds(false);
        encoder.setContext(context);
        encoder.start();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(logs);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);
        Logger sdk = context.getLogger("io.modelcontextprotocol");
        previousSdkLevel = sdk.getLevel();
        sdk.setLevel(Level.DEBUG);
    }

    private void stubEnt() {
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/healthcheck"))
            .willReturn(RestFixtures.response("healthcheck", "json")));
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/ready"))
            .willReturn(RestFixtures.response("ready", "json")));
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/metrics"))
            .willReturn(RestFixtures.response("metrics", "json")));
        dev.stubFor(post(urlPathEqualTo(QUEUE_LOG)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json").withBody("""
                {"queueLog":{"total":1,"success":true,"count":1,"row":[{"owner":"OWNERS",
                "moduleName":"M","workflowName":"W","globalPId":110219899,
                "startTime":1790861373734,"workflowId":110219899,
                "status":{"level":0,"content":"Error"}}]}}""")));
        String echo = "user " + USER + " password " + devSeed + " basic " + Base64.getEncoder()
            .encodeToString((USER + ":" + devSeed).getBytes(StandardCharsets.UTF_8));
        // unparseable answer that echoes the credentials: UNEXPECTED_RESPONSE
        dev.stubFor(post(urlPathEqualTo("/ibis/rest/log/systemLog")).willReturn(aResponse()
            .withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("not json: " + echo)));
        // 503 without maintenance flag that echoes the credentials: an error with an excerpt
        dev.stubFor(post(urlPathEqualTo("/ibis/rest/log/connectionLog")).willReturn(aResponse()
            .withStatus(503).withHeader("Content-Type", "text/plain")
            .withBody("Service Unavailable for " + echo)));
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/model/models"))
            .willReturn(RestFixtures.response("model_models_owner", "xml")));
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/model/modelByName/Workflow-0101"))
            .willReturn(RestFixtures.response("modelByName_sample", "xml")));
        dev.stubFor(get(urlPathEqualTo("/ibis/rest/model/export/Workflow-0101"))
            .willReturn(aResponse().withStatus(200)
                .withBody(RestFixtures.bytes("model_export_sample.zip"))));
    }

    /**
     * Fake StartCLI per server and command: {@code qa} rejects the login; on {@code dev} the
     * exports write the recorded ZIPs, {@code kill} succeeds and {@code processErrorStart}
     * prints unrecognized output that echoes the password.
     */
    private ProcessLauncher startCli() {
        byte[] modules = FakeProcessLauncher.fixture("export_modules_sample.zip");
        byte[] history = FakeProcessLauncher.fixture("export_history_sample.zip");
        return spec -> {
            List<String> command = spec.command();
            String exec = command.get(command.indexOf("--execCommand") + 1);
            String url = command.get(command.size() - 1);
            FakeProcessLauncher launcher;
            if (url.contains(":" + integration.httpsPort() + "/")) {
                launcher = FakeProcessLauncher.replaying("login_failed");
            } else if (exec.startsWith("export")) {
                launcher = FakeProcessLauncher.replaying("export_history_sample").onLaunch(s -> {
                    Matcher matcher = EXPORT_FILE.matcher(exec);
                    if (matcher.find()) {
                        try {
                            Files.write(Path.of(matcher.group(1)),
                                exec.contains("--exportModule") ? modules : history);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
            } else if (exec.startsWith("kill")) {
                launcher = FakeProcessLauncher.replaying("kill_ok");
            } else {
                launcher = FakeProcessLauncher.of("JAVA_HOME is set\nPassword: \n"
                    + "ERROR 08:00:00,000 [main      ] CLI unexpected: login " + USER + " with "
                    + devSeed + "\nsomething happened, maybe\n", "", 0);
            }
            FakeProcessLauncher.FakeProcess process =
                (FakeProcessLauncher.FakeProcess) launcher.launch(spec);
            launches.add(process);
            return process;
        };
    }

    private JsonNode call(String tool, Map<String, Object> arguments) {
        return client.callTool(tool, arguments, Duration.ofSeconds(30));
    }

    /** The {@code ToolError}s of a result: the whole-call error or per-server errors. */
    private static List<JsonNode> errors(JsonNode result) {
        List<JsonNode> errors = new ArrayList<>();
        if (result.path("isError").asBoolean()) {
            String text = result.path("content").get(0).path("text").asString();
            if (text.startsWith("{")) {
                errors.add(JSON.readTree(text).path("error"));
            }
            return errors;
        }
        collect(result.path("structuredContent"), errors);
        return errors;
    }

    private static void collect(JsonNode node, List<JsonNode> errors) {
        if (node.isObject()) {
            if (node.has("error") && node.path("error").has("code")) {
                errors.add(node.path("error"));
            }
            node.properties().forEach(property -> collect(property.getValue(), errors));
        } else if (node.isArray()) {
            node.forEach(element -> collect(element, errors));
        }
    }

    private String auditText() throws IOException {
        Path directory = temp.resolve("audit");
        if (!Files.isDirectory(directory)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                text.append(Files.readString(file));
            }
        }
        return text.toString();
    }

    @Test
    void noSecretLeavesTheServerThroughAnyChannel() throws IOException {
        List<JsonNode> results = new ArrayList<>();
        // first on qa: REST login accepted, then StartCLI rejects the login (CLI auth failure)
        results.add(call("list_inventory", Map.of("target", "qa", "kind", "MODULE")));
        results.add(call("list_nodes", Map.of()));
        results.add(call("get_health", Map.of()));
        results.add(call("find_processes", Map.of("target", "dev")));
        results.add(call("find_processes", Map.of("target", "qa")));
        results.add(call("find_processes", Map.of("target", "test")));
        results.add(call("query_logs", Map.of("target", "dev/node1", "logType", "systemLog")));
        results.add(call("query_logs", Map.of("target", "dev/node1", "logType",
            "connectionLog")));
        results.add(call("query_logs", Map.of("target", "qa", "logType", "systemLog")));
        results.add(call("list_inventory", Map.of("target", "dev/node1", "kind", "DIAGRAM")));
        results.add(call("list_inventory", Map.of("target", "dev/node1", "kind", "MODULE")));
        results.add(call("get_inventory_item", Map.of("target", "dev/node1",
            "kind", "DIAGRAM", "name", "Workflow-0101")));
        JsonNode preview = call("restart_process", Map.of("node", "dev/node1",
            "processId", PID, "reason", "leak test " + devSeed));
        results.add(preview);
        JsonNode restarted = call("restart_process", Map.of("node", "dev/node1",
            "processId", PID, "confirmationCode", preview.path("structuredContent")
                .path("challenge").path("confirmationCode").asString()));
        results.add(restarted);
        JsonNode killPreview = call("kill_process", Map.of("node", "dev/node1",
            "processId", PID));
        results.add(killPreview);
        JsonNode killed = call("kill_process", Map.of("node", "dev/node1", "processId", PID,
            "confirmationCode", killPreview.path("structuredContent").path("challenge")
                .path("confirmationCode").asString()));
        results.add(killed);
        results.add(call("kill_process", Map.of("node", "qa/node1", "processId", PID)));
        results.add(call("kill_process", Map.of("node", "qa/node2", "processId", PID)));
        results.add(call("restart_process", Map.of("node", "test/node1", "processId", PID)));

        // the error paths were really taken, and the secrets were really in play
        List<String> codes = results.stream().flatMap(result -> errors(result).stream())
            .map(error -> error.path("code").asString()).toList();
        assertThat(codes).contains("AUTH_FAILED", "UNREACHABLE", "TIMEOUT",
            "UNEXPECTED_RESPONSE", "WRITE_DISABLED");
        assertThat(restarted.path("structuredContent").path("result").path("outcome")
            .asString()).as("unrecognized StartCLI output").isEqualTo("FAILED");
        assertThat(killed.path("structuredContent").path("result").path("outcome")
            .asString()).isEqualTo("EXECUTED");
        assertThat(launches).isNotEmpty().anySatisfy(process -> assertThat(new String(
            process.stdinBytes(), StandardCharsets.UTF_8)).contains(devSeed));
        assertThat(launches).anySatisfy(process -> assertThat(new String(
            process.stdinBytes(), StandardCharsets.UTF_8)).contains(qaSeed));
        assertThat(dev.findAll(anyRequestedFor(anyUrl())
            .withHeader("Authorization", matching("Basic .+")))).isNotEmpty();
        assertThat(integration.findAll(anyRequestedFor(anyUrl())
            .withHeader("Authorization", matching("Basic .+")))).isNotEmpty();

        String protocol = client.rawOutput();
        String log = logs.toString(StandardCharsets.UTF_8);
        String audit = auditText();
        List<String> excerpts = results.stream().flatMap(result -> errors(result).stream())
            .filter(error -> error.has("excerpt")).map(error -> error.path("excerpt")
                .asString()).toList();
        List<String> arguments = new ArrayList<>();
        launches.forEach(process -> {
            arguments.addAll(process.spec().command());
            arguments.addAll(process.spec().environment().values());
        });
        String failedMessage = restarted.path("structuredContent").path("result")
            .path("message").asString();

        assertThat(excerpts).as("the unexpected answers produced excerpts").isNotEmpty();
        assertThat(audit).as("write calls were audited").contains("CHALLENGE_ISSUED")
            .contains("REFUSED").contains("EXECUTED").contains("FAILED");
        assertThat(log).as("write calls are logged by the service")
            .contains("ProcessControlService").contains("restart_process 110219899 on dev")
            .contains("kill_process 110219899 on dev");
        for (String secret : forbidden()) {
            assertThat(protocol).as("protocol stdout").doesNotContain(secret);
            assertThat(log).as("operational log").doesNotContain(secret);
            assertThat(audit).as("audit files").doesNotContain(secret);
            assertThat(excerpts).as("ToolError excerpts")
                .allSatisfy(excerpt -> assertThat(excerpt).doesNotContain(secret));
            assertThat(failedMessage).as("failed result").doesNotContain(secret);
            assertThat(arguments).as("StartCLI arguments and environment")
                .allSatisfy(argument -> assertThat(argument).doesNotContain(secret));
        }
    }
}
