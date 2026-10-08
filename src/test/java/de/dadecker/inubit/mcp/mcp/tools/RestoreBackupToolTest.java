package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.ImportHarness;
import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T019 (feature 004, contracts/mcp-tools-delta.md): {@code restore_backup} over MCP —
 * destructive annotations, the contract description and input schema, the challenge and the
 * result as structured content validated against the output schema, refusals as tool errors.
 */
@Timeout(120)
class RestoreBackupToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DESCRIPTION = "[acme] Re-import the backup taken by an earlier"
        + " development call or deployment on ONE node, limited to the artifacts that call"
        + " changed; same checks, conflict detection, verification and rollback. A deployment"
        + " backup is restored on its node of the target group, always after a preview with a"
        + " code.";

    @TempDir
    Path temp;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private McpTestClient client(ImportService service) {
        McpTestClient client = McpTestClient.start(List.of(new RestoreBackupTool(service)));
        closeables.add(client);
        client.initialize();
        return client;
    }

    private static String imported(ImportHarness harness) {
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();
        WriteOutcome outcome = ((ImportService.Response.Completed) harness.service()
            .importArtifacts(harness.group("Move it"))).outcome();
        return outcome.backupRef().orElseThrow();
    }

    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("structuredContent");
    }

    @Test
    void theToolIsDestructiveWithTheContractDescriptionAndSchema() throws IOException {
        McpTestClient client = client(ImportHarness.grpA(temp).service());

        JsonNode tool = client.listTools().path("tools").get(0);
        assertThat(tool.path("name").asString()).isEqualTo("restore_backup");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        // feature 005: also a deployment backup on a node of a target group
        assertThat(tool.path("annotations").path("title").asString())
            .isEqualTo("Restore a development or deployment backup on an INUBIT node");
        assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(tool.path("annotations").path("idempotentHint").asBoolean()).isFalse();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("node", "backupRef", "reason");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("backupRef").path("pattern").asString())
            .isEqualTo("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void theChallengeAndTheResultAreStructuredContent() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String ref = imported(harness);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        McpTestClient client = client(harness.service());
        harness.exportGroup();

        JsonNode challenge = structured(client.callTool("restore_backup", Map.of("node",
            "dev/node1", "backupRef", ref, "reason", "Undo"))).path("challenge");
        harness.exportGroup().importRestored().exportGroup();
        JsonNode result = structured(client.callTool("restore_backup", Map.of("node",
            "dev/node1", "backupRef", ref, "reason", "Undo", "confirmationCode",
            challenge.path("confirmationCode").asString()))).path("result");

        assertThat(challenge.path("modify")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(challenge.path("scope").asString()).isEqualTo("diagram group GRP-01");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("modified")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        harness.cli.verifyComplete();
    }

    @Test
    void anUnknownBackupIsAToolErrorAndAMalformedOneIsRejectedByTheSchema() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        McpTestClient client = client(harness.service());
        String ref = UUID.randomUUID().toString();

        JsonNode unknown = client.callTool("restore_backup", Map.of("node", "dev/node1",
            "backupRef", ref, "reason", "Undo"));
        JsonNode malformed = client.callTool("restore_backup", Map.of("node", "dev/node1",
            "backupRef", "../x", "reason", "Undo"));

        assertThat(unknown.path("isError").asBoolean()).isTrue();
        assertThat(JSON.readTree(unknown.path("content").get(0).path("text").asString())
            .path("error").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(malformed.path("isError").asBoolean()).isTrue();
        assertThat(harness.cli.launches()).isEmpty();
    }
}
