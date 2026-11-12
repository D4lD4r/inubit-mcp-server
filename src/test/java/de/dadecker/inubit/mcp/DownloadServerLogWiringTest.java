package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TraceMatrix;
import de.dadecker.inubit.mcp.testing.WebServiceMatrix;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 021 T031 (FR-002, FR-010, research D-11, D-17): {@code download_server_log} through the
 * production wiring with a matrix that admits the trace log on 8.1. StartCLI is a
 * {@link FakeProcessLauncher} replaying the {@code v8_1/trace/ok} case and writing a log that
 * contains the password of {@code prod/node1}, a node the call never contacts: the file in the
 * folder keeps it, the excerpt masks it, because the wiring passes the profile's credentials
 * (feature 019's {@code ProfileSecrets}) to the service. T043 (FR-002, US2-1 to US2-3): the web
 * service log needs the group's {@code unsupported.serverLogRead} and then runs through the INUBIT
 * client helper, a fake replaying the {@code v8_1/webservice/ok} case.
 */
@Timeout(60)
class DownloadServerLogWiringTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The nonce in the recorded frames; the fake helper answers with the request's nonce. */
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final Pattern DOWNLOAD = Pattern.compile("--download '([^']+)'");
    private static final String PROD_PASSWORD = "Prod-Only-pw-7731";
    private static final String LOG = """
        2026-11-12 08:00:00,000 INFO  [acme.Engine] started
        2026-11-12 08:00:01,000 ERROR [acme.Connector] login with %s failed
        """.formatted(PROD_PASSWORD);

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    @Test
    void aTraceDownloadWritesTheFileAndMasksAnotherNodesPasswordInTheExcerpt() throws Exception {
        Path client = temp.resolve("client");
        FakeStartCli.install(client);
        Path folder = Files.createDirectories(temp.resolve("logs"));
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: test
                versionLine: V8_1
                cli:
                  home: %s
                  javaHome: %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
              - name: prod
                versionLine: V8_1
                nodes:
                  - name: node1
                    baseUrl: https://localhost:2
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"), client,
            System.getProperty("java.home"));
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();

        try (TestWiring wiring = TestWiring.of(config, TestSecrets.store(Map.of(
                "INUBIT_ACME_TEST_USERNAME", "jdoe",
                "INUBIT_ACME_TEST_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
                "INUBIT_ACME_PROD_USERNAME", "jdoe",
                "INUBIT_ACME_PROD_PASSWORD", PROD_PASSWORD)), scrubber, Files::exists, false,
                startCli(), Map.of("PATH", Platform.searchPath()),
                TraceMatrix.admittingEightOne());
            McpTestClient mcp = McpTestClient.start(wiring.toolHandlers(), scrubber)) {
            ToolHandler tool = wiring.toolHandlers().stream()
                .filter(handler -> handler.name().equals("download_server_log")).findFirst()
                .orElseThrow(() -> new AssertionError("download_server_log is announced"));

            // the tool's result before the result mapper scrubs anything
            JsonNode result = ResultJson.mapper().valueToTree(tool.handle(Map.of("target",
                "test/node1", "log", "trace", "targetFolder", folder.toString())));

            Path file = Path.of(result.path("file").asString());
            assertThat(file.getParent()).isEqualTo(folder.toRealPath());
            assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("the file keeps the content").isEqualTo(LOG);
            assertThat(result.path("sizeBytes").asLong())
                .isEqualTo(LOG.getBytes(StandardCharsets.UTF_8).length);
            assertThat(result.path("excerpt").path("kind").asString())
                .as("all credentials known: not withheld").isEqualTo("text");
            assertThat(result.path("excerpt").path("lines")).extracting(line -> line.path("text")
                .asString()).containsExactly("2026-11-12 08:00:00,000 INFO  [acme.Engine] started",
                    "2026-11-12 08:00:01,000 ERROR [acme.Connector] login with *** failed");

            // the same call over MCP: the result as the contract describes it
            mcp.initialize();
            JsonNode response = mcp.callTool("download_server_log", Map.of("target",
                "test/node1", "log", "trace", "targetFolder", folder.toString()),
                Duration.ofSeconds(30));
            assertMatchesOutputSchema(response);
            JsonNode content = response.path("structuredContent");
            assertThat(content.path("node").asString()).isEqualTo("test/node1");
            assertThat(content.path("log").asString()).isEqualTo("trace");
            assertThat(content.has("inubitNode")).isFalse();
            assertThat(Path.of(content.path("file").asString()).getFileName().toString())
                .matches("test-node1-trace-\\d{8}-\\d{6}(-2)?\\.log");
            assertThat(content.path("excerpt").path("kind").asString()).isEqualTo("text");
            assertThat(content.path("warnings")).extracting(JsonNode::asString)
                .contains("The log file contains clear-text content");
            assertThat(response.toString()).doesNotContain(PROD_PASSWORD);
            TestSecrets.assertNoLeak(response.toString());
        }
    }

    // --- T043: the web service log (FR-002, US2-1 to US2-3, research D-5, D-17) ---------------

    @Test
    void aWebServiceCallWithoutTheOptInIsRefusedBeforeAnyRequestOrLaunch() throws IOException {
        // US2-3: the production matrix announces the tool for the 8.1 trace log
        Path folder = Files.createDirectories(temp.resolve("logs"));
        WireMockServer inubit = TestCertificates.httpsWireMock(
            TestCertificates.get().localhostKeyStore());
        AtomicInteger launches = new AtomicInteger();
        try {
            String yaml = """
                profile:
                  name: acme
                auditDirectory: %s
                workspace: %s
                groups:
                  - name: test
                    versionLine: V8_1
                    cli:
                      home: %s
                    nodes:
                      - name: node1
                        baseUrl: https://localhost:%d
                """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
                temp.resolve("client"), inubit.httpsPort());
            ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
                .parse(yaml, temp.resolve("config.yaml")).config();
            ProcessLauncher launcher = spec -> {
                launches.incrementAndGet();
                throw new AssertionError("nothing is launched without the opt-in");
            };

            try (TestWiring wiring = TestWiring.of(config, TestSecrets.store(Map.of(
                    "INUBIT_ACME_TEST_USERNAME", "jdoe",
                    "INUBIT_ACME_TEST_PASSWORD", TestSecrets.CONFIGURED_PASSWORD)),
                new SecretScrubber(), path -> true, false, launcher, Map.of(),
                CapabilityMatrix.production())) {
                ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
                    () -> downloadTool(wiring).handle(Map.of("target", "test/node1", "log",
                        "webservice", "targetFolder", folder.toString())));

                assertThat(refused).as("refusal").isNotNull();
                ToolError error = refused.error();
                assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
                assertThat(error.node()).contains(NodeId.parse("test/node1"));
                assertThat(error.message()).contains("unsupported.serverLogRead is not set");
                assertThat(error.nextStep()).contains("unsupported.serverLogRead: true");
                TestSecrets.assertNoLeak(error.toString());
            }
            assertThat(inubit.findAll(anyRequestedFor(anyUrl()))).as("nothing was sent")
                .isEmpty();
        } finally {
            inubit.stop();
        }
        assertThat(launches).as("no launch").hasValue(0);
    }

    @Test
    void anOptedInSingleNodeWritesTheWebServiceLogAndNamesItsInubitNode() throws Exception {
        // US2-1, with a matrix that admits the 8.1 web service log (T046 flips production)
        Path folder = Files.createDirectories(temp.resolve("logs"));
        List<String> urls = new CopyOnWriteArrayList<>();

        try (TestWiring wiring = webServiceWiring("""
                  - name: node1
                    baseUrl: http://node1.example.test:8000
                    allowInsecureHttp: true
            """, helper("node1", urls));
            McpTestClient mcp = McpTestClient.start(wiring.toolHandlers(), new SecretScrubber())) {
            mcp.initialize();
            JsonNode response = mcp.callTool("download_server_log", Map.of("target",
                "test/node1", "log", "webservice", "targetFolder", folder.toString()),
                Duration.ofSeconds(30));

            assertMatchesOutputSchema(response);
            JsonNode content = response.path("structuredContent");
            assertThat(content.path("node").asString()).isEqualTo("test/node1");
            assertThat(content.path("log").asString()).isEqualTo("webservice");
            assertThat(content.path("inubitNode").asString()).isEqualTo("node1");
            Path file = Path.of(content.path("file").asString());
            assertThat(file.getParent()).isEqualTo(folder.toRealPath());
            assertThat(file.getFileName().toString())
                .matches("test-node1-webservice-\\d{8}-\\d{6}(-2)?\\.log");
            assertThat(file).hasBinaryContent(webServiceFixture("ok.log"));
            assertThat(content.path("warnings")).extracting(JsonNode::asString).contains(
                "Read through an unsupported, undocumented INUBIT interface; diagnosis only",
                "The log file contains clear-text content");
            TestSecrets.assertNoLeak(response.toString());
        }
        assertThat(urls).containsExactly(
            "http://node1.example.test:8000/ibis/servlet/IBISSoapServlet");
    }

    @Test
    void aClusterNodeNamesTheInubitNodeTheHelperReports() throws Exception {
        // US2-2, research D-5: the log of the node the configured address reaches, named by it;
        // the helper's name differs from the configured one, so it cannot be a copy of that
        Path folder = Files.createDirectories(temp.resolve("logs"));
        List<String> urls = new CopyOnWriteArrayList<>();

        try (TestWiring wiring = webServiceWiring("""
                  - name: node1
                    baseUrl: http://node1.example.test:8000
                    allowInsecureHttp: true
                  - name: node2
                    baseUrl: http://node2.example.test:8000
                    allowInsecureHttp: true
            """, helper("inubit-b", urls))) {
            JsonNode result = ResultJson.mapper().valueToTree(downloadTool(wiring).handle(Map.of(
                "target", "test/node2", "log", "webservice", "targetFolder", folder.toString())));

            assertThat(result.path("node").asString()).isEqualTo("test/node2");
            assertThat(result.path("inubitNode").asString()).isEqualTo("inubit-b");
            assertThat(Path.of(result.path("file").asString()))
                .hasBinaryContent(webServiceFixture("ok.log"));
        }
        assertThat(urls).as("the configured address of test/node2").containsExactly(
            "http://node2.example.test:8000/ibis/servlet/IBISSoapServlet");
    }

    /**
     * The production wiring with {@link WebServiceMatrix#admittingEightOneWithoutTrace()} for
     * group {@code test} (8.1, {@code unsupported.serverLogRead: true}, a client home with
     * {@code lib/ibis.jar} and this JVM as {@code javaHome}) with the node entries
     * {@code nodes}, StartCLI and the helper launched by {@code launcher}.
     */
    private TestWiring webServiceWiring(String nodes, ProcessLauncher launcher)
        throws IOException {
        Path client = temp.resolve("client");
        FakeStartCli.install(client);
        Files.createDirectories(client.resolve("lib").resolve("shared"));
        Files.createDirectories(client.resolve("conf"));
        Files.createFile(client.resolve("lib").resolve("ibis.jar"));
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: test
                versionLine: V8_1
                unsupported:
                  serverLogRead: true
                cli:
                  home: %s
                  javaHome: %s
                nodes:
            %s""".formatted(temp.resolve("audit"), temp.resolve("workspace"), client,
            System.getProperty("java.home"), nodes);
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        return TestWiring.of(config, TestSecrets.store(Map.of(
                "INUBIT_ACME_TEST_USERNAME", "jdoe",
                "INUBIT_ACME_TEST_PASSWORD", TestSecrets.CONFIGURED_PASSWORD)),
            new SecretScrubber(), Files::exists, false, launcher,
            Map.of("PATH", Platform.searchPath()),
            WebServiceMatrix.admittingEightOneWithoutTrace());
    }

    private static ToolHandler downloadTool(TestWiring wiring) {
        return wiring.toolHandlers().stream()
            .filter(handler -> handler.name().equals("download_server_log")).findFirst()
            .orElseThrow(() -> new AssertionError("download_server_log is announced"));
    }

    /**
     * StartCLI and the INUBIT client helper: a helper run records the request's {@code url} in
     * {@code urls}, writes the {@code v8_1/webservice/ok} log to its {@code logOut} and answers
     * with the case's frames naming INUBIT node {@code inubitNode}; every other run (the client
     * version probe) replays {@code version}.
     */
    private static ProcessLauncher helper(String inubitNode, List<String> urls) {
        FakeProcessLauncher helper = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = new HashMap<>();
            for (String line : new String(request, StandardCharsets.US_ASCII).split("\n")) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    fields.put(line.substring(0, equals),
                        URLDecoder.decode(line.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
            urls.add(fields.get("url"));
            try {
                Files.write(Path.of(fields.get("logOut")), webServiceFixture("ok.log"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String frames = new String(webServiceFixture("ok.frames"), StandardCharsets.UTF_8)
                .replace(FIXTURE_NONCE, fields.get("nonce"))
                .replace(" node ok node1", " node ok " + inubitNode);
            return new FakeProcessLauncher.Answer(frames, Integer.parseInt(
                new String(webServiceFixture("ok.exit"), StandardCharsets.UTF_8).strip()));
        });
        return spec -> spec.command().getLast().equals(HelperInstallation.MAIN_CLASS)
            ? helper.launch(spec) : FakeProcessLauncher.replaying("version").launch(spec);
    }

    private static byte[] webServiceFixture(String name) {
        return FakeProcessLauncher.resource("server-log/v8_1/webservice/" + name);
    }

    /**
     * StartCLI: a trace download replays {@code v8_1/trace/ok} and writes {@link #LOG} to the
     * download path; every other run (the client version probe) replays {@code version}.
     */
    private static ProcessLauncher startCli() {
        return spec -> {
            List<String> arguments = spec.startCliArguments();
            if (!arguments.contains("--execCommand")) {
                return FakeProcessLauncher.replaying("version").launch(spec);
            }
            Matcher download = DOWNLOAD.matcher(
                arguments.get(arguments.indexOf("--execCommand") + 1));
            assertThat(download.find()).as("a trace download: %s", arguments).isTrue();
            try {
                Files.writeString(Path.of(download.group(1)), LOG, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return FakeProcessLauncher.of(fixture("ok.stdout"), fixture("ok.stderr"),
                Integer.parseInt(fixture("ok.exit").strip())).launch(spec);
        };
    }

    private static String fixture(String name) {
        return new String(FakeProcessLauncher.resource("server-log/v8_1/trace/" + name),
            StandardCharsets.UTF_8);
    }

    private static void assertMatchesOutputSchema(JsonNode response) {
        assertThat(response.path("isError").asBoolean()).as(response.toString()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/download_server_log.output.json"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = JSON.treeToValue(response.path("structuredContent"),
            Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, content);
        assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
    }
}
