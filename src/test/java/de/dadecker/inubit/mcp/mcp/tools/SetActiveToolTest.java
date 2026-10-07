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

/**
 * T020 (feature 004, contracts/mcp-tools-delta.md): {@code set_active} over MCP — destructive
 * annotations, the contract description and input schema, the challenge and the result as
 * structured content validated against the output schema.
 */
@Timeout(120)
class SetActiveToolTest {

    private static final String DESCRIPTION = "[acme] Activate or deactivate ONE workflow on"
        + " ONE development node (INUBIT creates a new version).";
    private static final String VALUE = "^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$";

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
        McpTestClient client = McpTestClient.start(List.of(new SetActiveTool(
            harness.service())));
        closeables.add(client);
        client.initialize();
        return client;
    }

    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("structuredContent");
    }

    @Test
    void theToolIsDestructiveWithTheContractDescriptionAndSchema() throws IOException {
        McpTestClient client = client(ImportHarness.grpA(temp));

        JsonNode tool = client.listTools().path("tools").get(0);
        assertThat(tool.path("name").asString()).isEqualTo("set_active");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(tool.path("annotations").path("idempotentHint").asBoolean()).isFalse();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("node", "diagramGroup", "workflow", "active", "reason");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        JsonNode properties = schema.path("properties");
        assertThat(properties.path("active").path("type").asString()).isEqualTo("boolean");
        for (String name : List.of("owner", "diagramGroup", "workflow")) {
            assertThat(properties.path(name).path("pattern").asString()).as(name)
                .isEqualTo(VALUE);
        }
    }

    @Test
    void theChallengeAndTheResultAreStructuredContent() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        McpTestClient client = client(harness);
        harness.exportGroup();
        Map<String, Object> arguments = Map.of("node", "dev/node1", "diagramGroup", "GRP-01",
            "workflow", "Workflow-0001", "active", true, "reason", "On");

        JsonNode challenge = structured(client.callTool("set_active", arguments))
            .path("challenge");
        harness.exportGroup().importApplied("--importWorkflow --importWorkflowActive"
            + " --importUser 'jdoe' --returnProtocol").exportGroup();
        Map<String, Object> confirmed = new java.util.HashMap<>(arguments);
        confirmed.put("confirmationCode", challenge.path("confirmationCode").asString());
        JsonNode result = structured(client.callTool("set_active", confirmed)).path("result");

        assertThat(challenge.path("modify")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("warnings")).extracting(JsonNode::asString)
            .anyMatch(w -> w.contains("new version"));
        harness.cli.verifyComplete();
    }
}
