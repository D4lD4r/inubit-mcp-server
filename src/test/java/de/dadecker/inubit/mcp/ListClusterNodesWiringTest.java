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
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodesResult;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 024 T023 (FR-002, FR-010, research D-6): {@code list_cluster_nodes} through the
 * production wiring with {@link TraceMatrix#admittingEightOne()}, a test matrix that admits the
 * cluster's node list on 8.1 (the production matrix does not before T025). A group without
 * {@code unsupported.nodeStatusRead} is refused before any request or launch; an opted-in group
 * lists the cluster through the INUBIT client helper, a fake replaying the
 * {@code node-tools/v8_1/cluster-nodes/ok} case.
 */
@Timeout(60)
class ListClusterNodesWiringTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The nonce in the reconstructed frames; the fake helper answers with the request's nonce. */
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    @Test
    void aGroupWithoutTheOptInIsRefusedBeforeAnyRequestOrLaunch() throws IOException {
        // group prod opts in, so the tool is announced; group test does not
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
                  - name: prod
                    versionLine: V8_1
                    unsupported:
                      nodeStatusRead: true
                    cli:
                      home: %s
                    nodes:
                      - name: node1
                        baseUrl: https://localhost:2
                """.formatted(temp.resolve("audit"), temp.resolve("workspace"),
                temp.resolve("client"), inubit.httpsPort(), temp.resolve("client"));
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
                TraceMatrix.admittingEightOne())) {
                ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
                    () -> listTool(wiring).handle(Map.of("target", "test/node1")));

                assertThat(refused).as("refusal").isNotNull();
                ToolError error = refused.error();
                assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
                assertThat(error.node()).contains(NodeId.parse("test/node1"));
                assertThat(error.message()).contains("unsupported.nodeStatusRead is not set");
                assertThat(error.nextStep()).contains("unsupported.nodeStatusRead: true");
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
    void anOptedInGroupListsTheClusterThroughTheWiredServer() throws Exception {
        List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

        try (TestWiring wiring = optedInWiring(helper(requests));
            McpTestClient mcp = McpTestClient.start(wiring.toolHandlers(), new SecretScrubber())) {
            mcp.initialize();
            JsonNode response = mcp.callTool("list_cluster_nodes", Map.of("target", "test/node1"),
                Duration.ofSeconds(30));

            assertMatchesOutputSchema(response);
            JsonNode content = response.path("structuredContent");
            assertThat(content.path("node").asString()).isEqualTo("test/node1");
            assertThat(content.path("inubitNode").asString()).isEqualTo("node1");
            assertThat(content.path("total").asInt()).isEqualTo(2);
            assertThat(content.path("nodes")).extracting(node -> node.path("name").asString())
                .containsExactly("node1", "node2");
            assertThat(content.path("nodes")).extracting(node -> node.path("status").asString())
                .containsExactly("ACTIVE", "UNKNOWN");
            assertThat(content.path("warnings")).extracting(JsonNode::asString)
                .contains(ClusterNodesResult.WARNING);
            TestSecrets.assertNoLeak(response.toString());
        }
        assertThat(requests).singleElement().satisfies(request -> assertThat(request)
            .containsEntry("op", "clusterNodes").containsEntry("details", "")
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", "http://node1.example.test:8000/ibis/servlet/IBISSoapServlet"));
    }

    /**
     * The production wiring with {@link TraceMatrix#admittingEightOne()} for group {@code test}
     * (8.1, {@code unsupported.nodeStatusRead: true}, a client home with {@code lib/ibis.jar} and
     * this JVM as {@code javaHome}), StartCLI and the helper launched by {@code launcher}.
     */
    private TestWiring optedInWiring(ProcessLauncher launcher) throws IOException {
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
                  nodeStatusRead: true
                cli:
                  home: %s
                  javaHome: %s
                nodes:
                  - name: node1
                    baseUrl: http://node1.example.test:8000
                    allowInsecureHttp: true
            """.formatted(temp.resolve("audit"), temp.resolve("workspace"), client,
            System.getProperty("java.home"));
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        return TestWiring.of(config, TestSecrets.store(Map.of(
                "INUBIT_ACME_TEST_USERNAME", "jdoe",
                "INUBIT_ACME_TEST_PASSWORD", TestSecrets.CONFIGURED_PASSWORD)),
            new SecretScrubber(), Files::exists, Platform.windows(), launcher,
            Map.of("PATH", Platform.searchPath()), TraceMatrix.admittingEightOne());
    }

    private static ToolHandler listTool(TestWiring wiring) {
        return wiring.toolHandlers().stream()
            .filter(handler -> handler.name().equals("list_cluster_nodes")).findFirst()
            .orElseThrow(() -> new AssertionError("list_cluster_nodes is announced"));
    }

    /**
     * StartCLI and the INUBIT client helper: a helper run records its request without the
     * password in {@code requests} and answers with the {@code ok} frames carrying the request's
     * nonce; every other run (the client version probe) replays {@code version}.
     */
    private static ProcessLauncher helper(List<Map<String, String>> requests) {
        FakeProcessLauncher helper = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = new HashMap<>();
            for (String line : new String(request, StandardCharsets.US_ASCII).split("\n")) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    fields.put(line.substring(0, equals),
                        URLDecoder.decode(line.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
            String nonce = fields.get("nonce");
            fields.remove("password");
            requests.add(fields);
            return new FakeProcessLauncher.Answer(fixture("ok.frames").replace(FIXTURE_NONCE,
                nonce), Integer.parseInt(fixture("ok.exit").strip()));
        });
        return spec -> spec.command().getLast().equals(HelperInstallation.MAIN_CLASS)
            ? helper.launch(spec) : FakeProcessLauncher.replaying("version").launch(spec);
    }

    private static String fixture(String name) {
        return new String(FakeProcessLauncher.resource("node-tools/v8_1/cluster-nodes/" + name),
            StandardCharsets.UTF_8);
    }

    private static void assertMatchesOutputSchema(JsonNode response) {
        assertThat(response.path("isError").asBoolean()).as(response.toString()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = JSON.readValue(SchemaResources.forClasspath()
            .load("schemas/list_cluster_nodes.output.json"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = JSON.treeToValue(response.path("structuredContent"),
            Map.class);
        JsonSchemaValidator.ValidationResponse validation =
            new DefaultJsonSchemaValidator().validate(schema, content);
        assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
    }
}
