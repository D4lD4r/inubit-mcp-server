package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.application.ExportHarness;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T023: {@code export_artifacts} over MCP (contracts/mcp-tools-delta.md) on a real workspace
 * history with a fake StartCLI.
 */
@Timeout(60)
class ExportArtifactsToolTest {

    private static final String DESCRIPTION = "[acme] Export technical workflows (by diagram"
        + " group) or single modules from one group or node into the local workspace as readable"
        + " files, record the export in the workspace history and list what changed. Only"
        + " technical workflows are exported (no system diagrams or other diagram types). Secrets"
        + " are replaced by placeholders. Read-only for INUBIT.";
    private static final int MAX_ITEMS = 5;

    @TempDir
    java.nio.file.Path root;

    private ExportHarness harness;
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        harness = new ExportHarness(root);
        harness.artifacts.exports.put("GRP-01", ArtifactFixtures.bytes("grp-a.zip"));
        harness.artifacts.exports.put("GRP-02", ArtifactFixtures.bytes("grp-b.zip"));
        harness.artifacts.exports.put("Module-0023", ArtifactFixtures.bytes("module-one.zip"));
        harness.pluginTypes.put("Module-0023", "XSLT Converter");
        TargetResolver targets = new TargetResolver(List.of(ExportHarness.DEV,
            NodeId.parse("dev/node2"), NodeId.parse("qa/node1")));
        client = McpTestClient.start(List.of(new ExportArtifactsTool(harness.service(), targets,
            node -> node.group().value().equals("dev") ? Optional.of("jdoe") : Optional.empty(),
            new ResultLimiter(MAX_ITEMS, ResultLimiter.DEFAULT_MAX_CHARS))));
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("export_artifacts", arguments);
    }

    private static JsonNode content(JsonNode result) {
        return result.path("structuredContent");
    }

    @Test
    void theToolIsReadOnlyButNotIdempotent() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("export_artifacts");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("readOnlyHint").asBoolean()).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
    }

    @Test
    void aGroupUsesItsFirstNodeAndTheChangesAreBounded() throws IOException {
        JsonNode result = call(Map.of("target", "dev", "diagramGroups", List.of("GRP-01")));

        assertMatchesOutputSchema("export_artifacts", result);
        JsonNode export = content(result);
        assertThat(export.path("node").asString()).isEqualTo("dev/node1");
        assertThat(export.path("owner").asString()).isEqualTo("jdoe");
        assertThat(export.path("workspace").asString()).isEqualTo(root.toString());
        assertThat(export.path("unchanged").asBoolean()).isFalse();
        assertThat(export.has("localChanges")).isFalse();
        String commit = export.path("commit").asString();
        assertThat(harness.git("rev-parse", "--short", "HEAD").strip()).isEqualTo(commit);
        int total = export.path("counts").path("added").asInt()
            + export.path("counts").path("modified").asInt()
            + export.path("counts").path("deleted").asInt();
        assertThat(total).isGreaterThan(MAX_ITEMS);
        assertThat(export.path("changes")).hasSize(MAX_ITEMS);
        assertThat(export.path("truncated").asBoolean()).isTrue();
        assertThat(export.path("fullList").asString())
            .isEqualTo(".reports/export-" + commit + ".txt");
        assertThat(Files.readAllLines(root.resolve(".reports/export-" + commit + ".txt")))
            .hasSize(total).contains("ADDED dev/jdoe/workflows/GRP-01/Workflow-0001.xml");
        assertThat(export.path("warnings").get(0).asString())
            .isEqualTo("Workflow Workflow-0002 is in edit mode by jdoe (CheckoutUser)");
        assertThat(harness.history.status()).as("the report is not versioned").isEmpty();
        assertThat(harness.artifacts.calls).containsExactly("group jdoe GRP-01");
    }

    @Test
    void anUnchangedExportHasNoCommit() {
        call(Map.of("target", "dev/node1", "diagramGroups", List.of("GRP-01")));

        JsonNode result = call(Map.of("target", "dev/node1", "diagramGroups", List.of("GRP-01")));

        assertMatchesOutputSchema("export_artifacts", result);
        assertThat(content(result).path("unchanged").asBoolean()).isTrue();
        assertThat(content(result).has("commit")).isFalse();
        assertThat(content(result).path("changes")).isEmpty();
        assertThat(content(result).path("truncated").asBoolean()).isFalse();
    }

    @Test
    void anExplicitOwnerAndModulesWithoutPluginType() {
        JsonNode result = call(Map.of("target", "dev", "owner", "OWNERS",
            "modules", List.of(Map.of("name", "Module-0023"))));

        assertMatchesOutputSchema("export_artifacts", result);
        assertThat(content(result).path("owner").asString()).isEqualTo("OWNERS");
        assertThat(content(result).path("commit").asString()).isNotBlank();
        assertThat(harness.artifacts.calls).containsExactly("listModules OWNERS",
            "module OWNERS XSLT Converter Module-0023");
    }

    @Test
    void invalidRequestsAreRefusedBeforeStartCli() {
        assertThat(toolError(call(Map.of("target", "prod", "diagramGroups", List.of("GRP-01"))))
            .path("code").asString()).isEqualTo("TARGET_UNKNOWN");
        assertThat(toolError(call(Map.of("target", "dev"))).path("code").asString())
            .isEqualTo("INVALID_INPUT");
        assertThat(toolError(call(Map.of("target", "dev", "diagramGroups", List.of("GRP-01"),
            "modules", List.of(Map.of("name", "Module-0023"))))).path("code").asString())
            .isEqualTo("INVALID_INPUT");
        assertThat(toolError(call(Map.of("target", "qa", "diagramGroups", List.of("GRP-01"))))
            .path("code").asString()).isEqualTo("NOT_CONFIGURED");
        JsonNode blank = call(Map.of("target", "dev", "diagramGroups", List.of(" ")));
        assertThat(blank.path("isError").asBoolean()).isTrue();
        assertThat(blank.has("structuredContent")).isFalse();
        JsonNode quote = call(Map.of("target", "dev", "diagramGroups", List.of("it's")));
        assertThat(quote.path("isError").asBoolean()).isTrue();

        assertThat(harness.artifacts.calls).isEmpty();
    }
}
