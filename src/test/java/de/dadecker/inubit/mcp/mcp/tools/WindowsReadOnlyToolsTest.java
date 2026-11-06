package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.InventoryService;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Feature 013 (T021, FR-001, FR-006): the read-only StartCLI tools ({@code list_inventory},
 * {@code get_inventory_item}, {@code export_artifacts}) through the real launch of the host
 * platform — on Windows {@code startcli.bat} through the command interpreter. The substitute
 * StartCLI writes the recorded export ZIPs of {@code fixtures/v8_1/cli/} and replays the recorded
 * output with CRLF line ends, as the client prints it on Windows. The results equal those of the
 * simulated launch with the unchanged macOS recordings, and no temporary data remains.
 */
@Timeout(120)
class WindowsReadOnlyToolsTest {

    private static WireMockServer dev;
    private static WireMockServer integration;

    private static final java.util.regex.Pattern EXPORT_FILE =
        java.util.regex.Pattern.compile("--exportFile '([^']+)'");

    private static final String GRP_A = "/fixtures/v8_1/artifacts/grp-a.zip";

    @org.junit.jupiter.api.io.TempDir
    Path temp;

    @BeforeAll
    static void start() {
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        integration = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        dev.stop();
        integration.stop();
    }

    @BeforeEach
    void setUp() {
        dev.resetAll();
        integration.resetAll();
        InventoryToolFixture.stubInventory(dev);
        InventoryToolFixture.stubInventory(integration);
    }

    @Test
    void listInventoryOfTheModulesEqualsTheMacOsResult() throws IOException {
        assertSameResult(ListInventoryTool::new, "list_inventory",
            Map.of("target", "dev/node1", "kind", "MODULE", "limit", 100));
    }

    @Test
    void getInventoryItemWithItsHistoryEqualsTheMacOsResult() throws IOException {
        assertSameResult(GetInventoryItemTool::new, "get_inventory_item",
            Map.of("target", "dev/node1", "kind", "DIAGRAM", "name", "Workflow-0101"));
    }

    @Test
    void exportArtifactsOfADiagramGroupEqualsTheMacOsResult() throws Exception {
        dev.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
            com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                "/ibis/rest/system/info")).willReturn(
                    de.dadecker.inubit.mcp.adapter.rest.RestFixtures.response("system_info",
                        "xml")));
        List<Path> before = exportDirectories();

        Path launchedRoot = Files.createDirectories(temp.resolve("launched"));
        JsonNode launched = exportArtifacts(launchedRoot, true);
        JsonNode simulated = exportArtifacts(Files.createDirectories(temp.resolve("simulated")),
            false);

        DiagnosisToolFixture.assertMatchesOutputSchema("export_artifacts", launched);
        assertThat(launched.path("isError").asBoolean()).as(launched.toString()).isFalse();
        assertThat(withoutCommit(launched)).isEqualTo(withoutCommit(simulated));
        assertThat(launched.path("structuredContent").path("unchanged").asBoolean()).isFalse();
        assertThat(launchedRoot.resolve("workspace/dev/jdoe/workflows/GRP-01"))
            .isDirectory();
        assertThat(exportDirectories()).as("no temporary data remains").isEqualTo(before);
    }

    /**
     * {@code export_artifacts} of GRP-01 on {@code dev/node1} below {@code root}: through the
     * real launch the substitute writes {@code grp-a.zip} and replays the output of
     * {@code export_release} with
     * CRLF; simulated, a fake launcher does the same with the recorded LF output.
     */
    private static JsonNode exportArtifacts(Path root, boolean throughTheLaunch)
        throws Exception {
        Path cliHome = root.resolve("cli");
        if (throughTheLaunch) {
            de.dadecker.inubit.mcp.testing.FakeStartCli.install(cliHome, Map.of(
                de.dadecker.inubit.mcp.testing.ArgvRecorder.REPLAY, "export_release",
                de.dadecker.inubit.mcp.testing.ArgvRecorder.EXPORT_FIXTURE, GRP_A,
                de.dadecker.inubit.mcp.testing.ArgvRecorder.CRLF, "true"));
        } else {
            de.dadecker.inubit.mcp.testing.FakeStartCli.install(cliHome);
        }
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        String yaml = """
            profile:
              name: acme
            workspace: '%s'
            groups:
              - name: dev
                cli:
                  home: '%s'
                  javaHome: '%s'
                inventory:
                  owner: jdoe
                tls:
                  trustStore: '%s'
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
                    versionLine: V8_1
            """.formatted(workspace, cliHome, System.getProperty("java.home"),
            TestCertificates.get().trustStore(), dev.httpsPort());
        var config = new de.dadecker.inubit.mcp.config.ConfigLoader(Map.of(), root, false)
            .parse(yaml, root.resolve("acme.yaml")).config();
        var scrubber = new de.dadecker.inubit.mcp.infra.SecretScrubber();
        var credentials = de.dadecker.inubit.mcp.testing.TestSecrets.store(Map.of(
            "INUBIT_ACME_DEV_USERNAME", "jdoe", "INUBIT_ACME_DEV_PASSWORD", "export-fixture-pw"));
        de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher launcher = throughTheLaunch
            ? new de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher()
            : de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.replaying("export_release")
                .onLaunch(spec -> {
                    java.util.regex.Matcher file = EXPORT_FILE.matcher(
                        String.join(" ", spec.startCliArguments()));
                    if (file.find()) {
                        try {
                            Files.write(Path.of(file.group(1)), de.dadecker.inubit.mcp.adapter
                                .archive.v81.ArtifactFixtures.bytes("grp-a.zip"));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                });
        Map<String, String> environment = throughTheLaunch ? System.getenv()
            : Map.of("PATH", de.dadecker.inubit.mcp.testing.Platform.searchPath());
        try (var wiring = de.dadecker.inubit.mcp.TestWiring.of(config, credentials, scrubber,
            Files::exists, de.dadecker.inubit.mcp.testing.Platform.windows(), launcher,
            environment);
            de.dadecker.inubit.mcp.mcp.McpTestClient client =
                de.dadecker.inubit.mcp.mcp.McpTestClient.start(wiring.toolHandlers(),
                    scrubber)) {
            client.initialize();
            return client.callTool("export_artifacts", Map.of("target", "dev/node1",
                "diagramGroups", List.of("GRP-01")));
        }
    }

    /**
     * The result without the commit, whose id and time differ between the two calls, with the
     * workspace relative to the root of the call and without the times in the warnings (the
     * probe of the two calls fails at different seconds).
     */
    private static JsonNode withoutCommit(JsonNode result) {
        ObjectNode copy = (ObjectNode) result.path("structuredContent").deepCopy();
        copy.remove("commit");
        copy.put("workspace", Path.of(copy.path("workspace").asString()).getFileName()
            .toString());
        if (copy.path("warnings").isArray()) {
            var warnings = copy.putArray("warnings");
            result.path("structuredContent").path("warnings").forEach(warning -> warnings.add(
                warning.asString().replaceAll("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ",
                    "<time>")));
        }
        return copy;
    }

    private void assertSameResult(Function<InventoryService, ToolHandler> tool, String name,
        Map<String, Object> arguments) throws IOException {
        List<Path> before = exportDirectories();
        JsonNode launched;
        try (InventoryToolFixture fixture =
            InventoryToolFixture.throughTheLaunch(dev, integration, tool)) {
            launched = fixture.client.callTool(name, arguments);
        }
        JsonNode simulated;
        try (InventoryToolFixture fixture = new InventoryToolFixture(dev, integration, tool)) {
            simulated = fixture.client.callTool(name, arguments);
        }

        DiagnosisToolFixture.assertMatchesOutputSchema(name, launched);
        JsonNode entry = launched.path("structuredContent").path("results").path(0);
        assertThat(entry.has("error")).as(launched.toString()).isFalse();
        assertThat(withoutCollectedAt(launched)).isEqualTo(withoutCollectedAt(simulated));
        assertThat(exportDirectories()).as("no temporary data remains").isEqualTo(before);
    }

    /** The result without the collection times, which differ between the two calls. */
    private static JsonNode withoutCollectedAt(JsonNode result) {
        JsonNode copy = result.path("structuredContent").deepCopy();
        copy.path("results").forEach(entry -> ((ObjectNode) entry).remove("collectedAt"));
        return copy;
    }

    /** This process's export directories of the profile {@code acme} below the temp directory. */
    private static List<Path> exportDirectories() throws IOException {
        String prefix = CliResources.EXPORT_PREFIX + "acme-" + ProcessHandle.current().pid() + "-";
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix))
                .sorted().toList();
        }
    }
}
