package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
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
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * (feature 019's {@code ProfileSecrets}) to the service.
 */
@Timeout(60)
class DownloadServerLogWiringTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
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
