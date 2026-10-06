package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.ImportHarness;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T018 (feature 004, contracts/mcp-tools-delta.md): {@code import_artifacts} over MCP —
 * destructive annotations, the contract description and input schema, the challenge and the
 * result as structured content validated against the output schema, refusals as tool errors.
 */
@Timeout(60)
class ImportArtifactsToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DESCRIPTION = "[acme] Import the changed workflows of ONE"
        + " diagram group (with their changed or new modules), or changed single modules, from"
        + " the workspace into ONE development node. The server checks the files, refuses on"
        + " conflicts (changed on the server or open in the Workbench), backs up, imports only"
        + " what changed, verifies by re-export and rolls back on failure. Secrets are taken"
        + " from the node.";

    @TempDir
    Path temp;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private McpTestClient client(ImportHarness harness) {
        McpTestClient client = McpTestClient.start(List.of(new ImportArtifactsTool(
            harness.service())));
        closeables.add(client);
        client.initialize();
        return client;
    }

    private ImportHarness edited() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        return harness;
    }

    private static JsonNode tool(McpTestClient client) {
        return client.listTools().path("tools").get(0);
    }

    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("structuredContent");
    }

    private static JsonNode toolError(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        return JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
    }

    @Test
    void theToolIsDestructiveWithTheContractDescriptionAndSchema() throws IOException {
        McpTestClient client = client(edited());

        JsonNode tool = tool(client);
        assertThat(tool.path("name").asString()).isEqualTo("import_artifacts");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("destructiveHint").asBoolean()).isTrue();
        assertThat(annotations.path("idempotentHint").asBoolean()).isFalse();
        assertThat(annotations.path("readOnlyHint").asBoolean()).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean()).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("node", "reason");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        JsonNode properties = schema.path("properties");
        assertThat(properties.path("reason").path("maxLength").asInt()).isEqualTo(500);
        assertThat(properties.path("reason").path("pattern").asString())
            .isEqualTo("^[^#@\\u0000-\\u001F\\u007F]{1,500}$");
        assertThat(properties.path("modules").path("maxItems").asInt()).isEqualTo(50);
        assertThat(properties.path("confirmationCode").path("pattern").asString())
            .isEqualTo("^[A-Za-z0-9_-]{22}$");
    }

    @Test
    void theSchemaRejectsBothScopesAndUnknownKeysBeforeTheTool() throws IOException {
        ImportHarness harness = edited();
        McpTestClient client = client(harness);

        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
            Map.of("node", "dev/node1", "reason", "x"),
            Map.of("node", "dev/node1", "reason", "x", "diagramGroup", "GRP-01",
                "modules", List.of(Map.of("name", "Module-0001"))),
            Map.of("node", "dev", "reason", "x", "diagramGroup", "GRP-01"),
            Map.of("node", "dev/node1", "reason", "x", "diagramGroup", "GRP-01", "force", true),
            Map.of("node", "dev/node1", "reason", "a###b", "diagramGroup", "GRP-01"))) {
            assertThat(client.callTool("import_artifacts", arguments).path("isError")
                .asBoolean()).as(arguments.toString()).isTrue();
        }
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void theFirstCallReturnsTheChallengeAndTheSecondTheResult() throws IOException {
        ImportHarness harness = edited();
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        McpTestClient client = client(harness);
        harness.exportGroup();

        JsonNode challenge = structured(client.callTool("import_artifacts", Map.of("node",
            "dev/node1", "diagramGroup", "GRP-01", "reason", "Move it"))).path("challenge");
        harness.exportGroup().importApplied().exportGroup();
        JsonNode result = structured(client.callTool("import_artifacts", Map.of("node",
            "dev/node1", "diagramGroup", "GRP-01", "reason", "Move it", "confirmationCode",
            challenge.path("confirmationCode").asString()))).path("result");

        assertThat(challenge.path("modify")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(challenge.path("scope").asString()).isEqualTo("diagram group GRP-01");
        assertThat(challenge.path("ownerKind").asString()).isEqualTo("USER");
        assertThat(challenge.path("expiresAt").asString()).endsWith("Z");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("modified")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(result.path("backupRef").asString()).isEqualTo(
            result.path("auditId").asString());
        assertThat(result.has("failure")).isFalse();
        assertThat(result.has("rollback")).isFalse();
        harness.cli.verifyComplete();
    }

    @Test
    void aFailedImportIsAResultWithFailureAndRollback() throws IOException {
        ImportHarness harness = edited();
        harness.exportGroup().importRefused().exportGroup();
        McpTestClient client = client(harness);

        JsonNode result = structured(client.callTool("import_artifacts", Map.of("node",
            "dev/node1", "diagramGroup", "GRP-01", "reason", "Fails"))).path("result");

        assertThat(result.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(result.path("failure").path("code").asString()).isEqualTo("IMPORT_FAILED");
        assertThat(result.path("failure").path("step").asString()).isEqualTo("import");
        assertThat(result.path("rollback").asString()).isEqualTo("NOT_NEEDED");
    }

    @Test
    void aRefusalIsAToolError() throws IOException {
        ImportHarness harness = edited();
        harness.users.clear();
        McpTestClient client = client(harness);

        JsonNode error = toolError(client.callTool("import_artifacts", Map.of("node",
            "dev/node1", "diagramGroup", "GRP-01", "reason", "Refused")));

        assertThat(error.path("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void aModuleImportTakesModulesWithOptionalPluginType() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String directory = harness.moduleDirectory("Module-0003");
        String pluginType = directory.split("/")[3];
        harness.write(directory + "/module.xml", harness.read(directory + "/module.xml")
            .replace("</Properties>", "<Property name=\"x.added\">1</Property></Properties>"));
        harness.exportModule(pluginType, "Module-0003")
            .importApplied("--importModule --importUser 'jdoe' --returnProtocol")
            .exportModule(pluginType, "Module-0003");
        McpTestClient client = client(harness);

        JsonNode result = structured(client.callTool("import_artifacts", Map.of("node",
            "dev/node1", "modules", List.of(Map.of("name", "Module-0003")), "reason",
            "Module"))).path("result");

        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("modified")).extracting(JsonNode::asString)
            .containsExactly("Module-0003");
    }
}
