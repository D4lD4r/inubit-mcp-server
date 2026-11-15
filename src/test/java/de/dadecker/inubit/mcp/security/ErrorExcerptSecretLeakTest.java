package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Hardening of the shared error excerpts (Constitution II): free-text secrets the
 * {@link SecretScrubber} does not know ({@link TestSecrets#FREE_TEXT_SECRETS}: {@code password=},
 * {@code pwd=}, a {@code Bearer} token, JDBC URLs with a password parameter and with user-info) in
 * a failed StartCLI script run, in a {@code 2-NOK} line quoted in the error message and in
 * INUBIT's REST error bodies reach the tool error masked. Driven over MCP on the production wiring
 * ({@link TestWiring}): {@code export_archives} with {@code content: workflows} on a scripted
 * StartCLI that fails, and {@code list_inventory} against error bodies in HTML, XML, the 9.0 JSON
 * form and plain text. No value occurs in the results, the log output (our loggers at TRACE) or
 * the audit files. {@code get_server_settings} is covered by
 * {@link ServerSettingsSecretLeakTest#freeTextSecretsInTheErrorExcerptsAreMasked}.
 */
@Timeout(120)
class ErrorExcerptSecretLeakTest {

    private static final String USER = "jdoe";
    private static final String MODELS = "/ibis/rest/model/models";

    @TempDir
    Path temp;

    private final String password = "excerpt-leak-pw-0001";
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private volatile String cliStdout = "";
    private volatile String cliStderr = "";
    private Level previousLevel;
    private WireMockServer server;
    private TestWiring wiring;
    private McpTestClient client;

    @BeforeEach
    void setUp() throws IOException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);

        server = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        server.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        Path cliHome = temp.resolve("cli");
        FakeStartCli.install(cliHome);
        String yaml = """
            profile:
              name: acme
            auditDirectory: '%s'
            groups:
              - name: dev
                cli:
                  home: '%s'
                  javaHome: '%s'
                inventory:
                  owner: OWNERS
                tls:
                  trustStore: '%s'
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
                    versionLine: V8_1
            """.formatted(temp.resolve("audit"), cliHome, System.getProperty("java.home"),
            TestCertificates.get().trustStore(), server.httpsPort());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        wiring = TestWiring.of(config, TestSecrets.store(Map.of("INUBIT_ACME_DEV_USERNAME", USER,
                "INUBIT_ACME_DEV_PASSWORD", password)), scrubber, Files::exists,
            Platform.windows(), startCli(), Map.of("PATH", Platform.searchPath()));
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
        if (server != null) {
            server.stop();
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    /**
     * The scripted StartCLI: {@code -v} answers the recorded client version; every other call
     * fails with exit code 1 and {@link #cliStdout} and {@link #cliStderr}.
     */
    private ProcessLauncher startCli() {
        return spec -> {
            if (!spec.startCliArguments().contains("--execCommand")) {
                return FakeProcessLauncher.replaying("version").launch(spec);
            }
            return FakeProcessLauncher.of(cliStdout, cliStderr, 1).launch(spec);
        };
    }

    private Map<String, Object> exportOfWorkflows() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", "dev");
        arguments.put("content", "workflows");
        arguments.put("diagramGroup", "GRP-01");
        arguments.put("targetFolder", temp.resolve("archives").toString());
        arguments.put("acknowledgeClearTextSecrets", true);
        return arguments;
    }

    @Test
    void aFailedExportOfWorkflowsMasksTheSecretsOfItsExcerpt() throws IOException {
        // an answer no rule knows, the secrets on stdout and in a log record on stderr
        cliStdout = "Odd answer " + TestSecrets.FREE_TEXT_SECRETS + "\n";
        cliStderr = "ERROR 08:00:01,234 [main] Export failed: " + TestSecrets.FREE_TEXT_SECRETS
            + "\n";

        JsonNode result = client.callTool("export_archives", exportOfWorkflows(),
            Duration.ofSeconds(60));

        assertMaskedExcerpt(result);
        String audit = auditText();
        assertThat(audit).contains("FAILED");
        assertNoLeak(result.toString(), audit);
    }

    /** A {@code 2-NOK} line quoted in the error message has its secrets masked. */
    @Test
    void aNokLineQuotedInTheMessageOfAFailedExportIsMasked() throws IOException {
        cliStdout = "1-OK: Login.\n2-NOK: Workflow group not found: GRP-01 "
            + TestSecrets.FREE_TEXT_SECRETS + "\n";
        cliStderr = "";

        JsonNode result = client.callTool("export_archives", exportOfWorkflows(),
            Duration.ofSeconds(60));

        String text = result.path("content").get(0).path("text").asString();
        assertThat(text).contains("\"code\":\"NOT_FOUND\"",
            "Workflow group not found: GRP-01 password=${secret:data.password}");
        String audit = auditText();
        assertThat(audit).contains("FAILED");
        assertNoLeak(result.toString(), audit);
    }

    /**
     * INUBIT's error bodies, the 9.0 JSON {@code message} text included, reach the error only as
     * the masked excerpt, never in the message.
     */
    @Test
    void restErrorBodiesOfAnInventoryReadAreMaskedInTheExcerpt() throws IOException {
        String planted = "Odd " + TestSecrets.FREE_TEXT_SECRETS;
        List<JsonNode> results = Stream.of(
                aResponse().withStatus(500).withHeader("Content-Type", "text/html")
                    .withBody("<html><body><h1>Error</h1><p>" + planted + "</p></body></html>"),
                aResponse().withStatus(400).withHeader("Content-Type", "application/xml")
                    .withBody("<error><message>" + planted + "</message></error>"),
                aResponse().withStatus(502).withHeader("Content-Type", "application/json")
                    .withBody("{\"statusCode\": 502, \"message\": \"" + planted + "\"}"),
                aResponse().withStatus(500).withHeader("Content-Type", "text/plain")
                    .withBody(planted))
            .map(answer -> {
                server.stubFor(any(urlPathEqualTo(MODELS)).willReturn(answer));
                return client.callTool("list_inventory", Map.of("target", "dev/node1", "kind",
                    "DIAGRAM"), Duration.ofSeconds(60));
            }).toList();

        results.forEach(ErrorExcerptSecretLeakTest::assertMaskedExcerpt);
        assertNoLeak(results.toString(), auditText());
    }

    /** The result carries an error whose excerpt holds the planted secrets masked. */
    private static void assertMaskedExcerpt(JsonNode result) {
        String text = result.path("content").get(0).path("text").asString();
        assertThat(text).as("the excerpt is passed on")
            .contains("\"excerpt\"", "password=${secret:data.password}",
                "Bearer ${secret:authorization}");
    }

    private void assertNoLeak(String results, String audit) {
        String log = logText();
        assertThat(log).as("log lines were captured").isNotBlank();
        TestSecrets.assertNoLeak(results);
        TestSecrets.assertNoLeak(log);
        TestSecrets.assertNoLeak(audit);
        assertThat(results + log + audit).doesNotContain(password);
    }

    private String auditText() throws IOException {
        Path audit = temp.resolve("audit");
        if (!Files.isDirectory(audit)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.walk(audit)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                text.append(Files.readString(file, StandardCharsets.ISO_8859_1)).append('\n');
            }
        }
        return text.toString();
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }
}
