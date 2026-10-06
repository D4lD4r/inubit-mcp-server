package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.application.ArtifactCheckService;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.WorkspaceLock;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T027: {@code check_artifacts} over MCP (contracts/mcp-tools-delta.md): confined paths, the
 * workspace lock, bounded findings with a full report, messages of at most 500 characters.
 */
@Timeout(60)
class CheckArtifactsToolTest {

    private static final String DESCRIPTION = "[acme] Check workspace files before an import:"
        + " workflow structure (edges, ids, branch conditions, referenced modules, variables,"
        + " repository references), run a stylesheet against an input file, or validate XML"
        + " against a schema. Never changes INUBIT; writes only test outputs.";
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @TempDir
    Path root;
    @TempDir
    Path outside;

    private McpTestClient client;

    private void start(int maxItems) {
        ArtifactCheckService service = new ArtifactCheckService(root, new WorkspaceInspector(),
            group -> Optional.empty(), node -> {
                throw new AssertionError("no server lookups");
            }, node -> Optional.empty(), new ResultLimiter(maxItems,
                ResultLimiter.DEFAULT_MAX_CHARS), Clock.fixed(NOW, ZoneOffset.UTC));
        client = McpTestClient.start(List.of(new CheckArtifactsTool(service)));
        client.initialize();
    }

    @BeforeEach
    void exportDefect() throws IOException, URISyntaxException {
        Path directory = Path.of(getClass().getResource(
            "/fixtures/v8_1/artifacts/defects/dangling-edge").toURI());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String fixed : List.of("archive.properties", "Repository.zip",
            "workflow/workflow.xml")) {
            entries.put(fixed, Files.readAllBytes(directory.resolve(fixed)));
        }
        try (Stream<Path> modules = Files.list(directory.resolve("module"))) {
            for (Path module : modules.sorted().toList()) {
                entries.put("module/" + module.getFileName(), Files.readAllBytes(module));
            }
        }
        new ArchiveCodec().prepare(new GroupId("dev"), "jdoe", List.of(
            de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.zip(entries)))
            .writeTo(root);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("check_artifacts", arguments);
    }

    private SortedMap<String, String> snapshot() throws IOException {
        SortedMap<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString();
                if (!relative.startsWith(".reports") && !relative.equals(".lock")) {
                    files.put(relative, Files.readString(file, StandardCharsets.ISO_8859_1));
                }
            }
        }
        return files;
    }

    @Test
    void theToolIsReadOnlyIdempotentAndOpenWorld() {
        start(100);
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("check_artifacts");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean()).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
    }

    @Test
    void aDefectIsReportedAndNothingIsWritten() throws IOException {
        start(100);
        SortedMap<String, String> before = snapshot();

        JsonNode result = call(Map.of("paths", List.of("dev/jdoe")));

        assertMatchesOutputSchema("check_artifacts", result);
        JsonNode report = result.path("structuredContent");
        assertThat(report.path("counts").path("ERROR").asInt()).isEqualTo(1);
        assertThat(report.path("counts").path("WARNING").asInt()).isZero();
        assertThat(report.path("findings").get(0).path("code").asString())
            .isEqualTo("EDGE_TARGET_MISSING");
        assertThat(report.path("findings").get(0).path("check").asString())
            .isEqualTo("STRUCTURE");
        assertThat(report.path("truncated").asBoolean()).isFalse();
        assertThat(report.has("fullReport")).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void manyFindingsAreBoundedWithAFullReport() throws IOException {
        start(1);
        Path workflow = root.resolve("dev/jdoe/workflows/GRP-01/Workflow-0001.xml");
        Files.writeString(workflow, Files.readString(workflow).replace("<ModuleId>1</ModuleId>",
            "<ModuleId>1</ModuleId><Connection moduleOutId=\"77\"/><Connection moduleOutId="
                + "\"" + "8".repeat(600) + "\"/>"));

        JsonNode result = call(Map.of("paths", List.of("dev")));

        assertMatchesOutputSchema("check_artifacts", result);
        JsonNode report = result.path("structuredContent");
        assertThat(report.path("counts").path("ERROR").asInt()).isEqualTo(3);
        assertThat(report.path("findings")).hasSize(1);
        assertThat(report.path("truncated").asBoolean()).isTrue();
        String fullReport = report.path("fullReport").asString();
        assertThat(fullReport).isEqualTo(".reports/check-20261006T120000000Z.json");
        JsonNode all = JsonMapper.builder().build().readTree(Files.readString(
            root.resolve(fullReport)));
        assertThat(all.path("findings")).hasSize(3);
        for (JsonNode finding : all.path("findings")) {
            assertThat(finding.path("message").asString()).hasSizeLessThanOrEqualTo(500);
        }
        assertThat(all.path("findings")).anySatisfy(finding -> assertThat(finding.path("message")
            .asString()).hasSize(500).endsWith("…"));
    }

    @Test
    void pathsMustStayInsideTheWorkspace() throws IOException {
        start(100);
        Files.writeString(outside.resolve("secret.xml"), "<a/>");
        Files.createSymbolicLink(root.resolve("dev/escape"), outside);

        for (String path : List.of("../x", "dev/../../x", "/etc", root.toString(), "dev/escape",
            "dev/escape/secret.xml", "dev/nothing-here")) {
            assertThat(toolError(call(Map.of("paths", List.of(path)))).path("code").asString())
                .as(path).isEqualTo("INVALID_INPUT");
        }
        assertThat(call(Map.of("paths", List.of())).path("isError").asBoolean()).isTrue();
        assertThat(call(Map.of()).path("isError").asBoolean()).isTrue();
    }

    @Test
    void aRunningExportOrCheckRefusesTheCheckAtOnce() {
        start(100);
        try (WorkspaceLock held = WorkspaceLock.acquire(root)) {
            assertThat(toolError(call(Map.of("paths", List.of("dev")))).path("code").asString())
                .isEqualTo("PRECONDITION_FAILED");
        }
    }
}
