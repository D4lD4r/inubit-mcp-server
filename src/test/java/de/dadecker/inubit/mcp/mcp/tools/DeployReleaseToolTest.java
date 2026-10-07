package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.DeployHarness;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T024 (feature 005, contracts/mcp-tools-delta.md): {@code deploy_release} over MCP —
 * destructive, the contract's description and input schema (a group id, never a node id), the
 * preview as {@code challenge} and the outcome as {@code result}, both bounded by
 * {@code resultLimits} (review m5).
 */
class DeployReleaseToolTest {

    private static final String TAG = "TAG-01";
    private static final String MODULE_IMPORT =
        "--importModule --importUser 'jdoe' --returnProtocol";

    @TempDir
    Path temp;

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private DeployHarness harness;

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private McpTestClient client(int maxItems) throws IOException {
        harness = new DeployHarness(temp);
        McpTestClient client = McpTestClient.start(List.of(new DeployReleaseTool(
            harness.deployService(List.of()), maxItems)));
        closeables.add(client);
        client.initialize();
        return client;
    }

    private void readsForAPlan() {
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.SOURCE);
        DeployHarness.TARGETS.forEach(this::nodeExports);
    }

    private void nodeExports(NodeId node) {
        harness.exportGroup(node).exportRepository(node, DeployHarness.RELEASE_XSL);
    }

    private JsonNode preview(McpTestClient client) {
        harness.tagged("GRP-01", TAG);
        readsForAPlan();
        return client.callTool("deploy_release", Map.of("target", "int", "tag", TAG));
    }

    @Test
    void theToolIsDestructiveWithTheContractDescriptionAndSchema() throws IOException {
        JsonNode tool = client(100).listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("deploy_release");
        assertThat(tool.path("description").asString()).startsWith("[acme] Deploy a release")
            .contains("ONE target group, node by node", "package-only");
        assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(tool.path("annotations").path("idempotentHint").asBoolean()).isFalse();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("target", "tag");
        // stage 3 review m4: a node id passes the schema so that the guard can name the group
        assertThat(schema.path("properties").path("target").path("pattern").asString())
            .isEqualTo("^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$");
        assertThat(schema.path("properties").path("confirmationCode").path("pattern")
            .asString()).isEqualTo("^[A-Za-z0-9_-]{22}$");
    }

    @Test
    void aNodeIdIsRefusedByTheGuardNamingItsGroup() throws IOException {
        McpTestClient client = client(100);

        JsonNode error = DiagnosisToolFixture.toolError(client.callTool("deploy_release",
            Map.of("target", "int/node1", "tag", TAG)));

        assertThat(error.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.path("message").asString()).contains("is a node id", "(int)");
        assertThat(harness.launches()).isEmpty();
    }

    @Test
    void thePreviewIsAChallengeAndTheConfirmedCallAResult() throws IOException {
        McpTestClient client = client(100);

        JsonNode first = preview(client);

        assertThat(first.path("isError").asBoolean()).as(first.toString()).isFalse();
        JsonNode challenge = first.path("structuredContent").path("challenge");
        assertThat(challenge.path("target").asString()).isEqualTo("int");
        assertThat(challenge.path("source").asString()).isEqualTo("dev");
        assertThat(challenge.path("mode").asString()).isEqualTo("EXECUTE");
        assertThat(challenge.path("diagramGroups").get(0).asString()).isEqualTo("GRP-01");
        assertThat(challenge.path("executable").asBoolean()).isTrue();
        JsonNode node = challenge.path("nodes").get(0);
        assertThat(node.path("node").asString()).isEqualTo("int/node1");
        assertThat(node.path("counts").path("changed").asInt()).isEqualTo(1);
        assertThat(node.path("counts").path("new").asInt()).isEqualTo(1);
        assertThat(node.path("counts").path("layoutOnly").asInt()).isZero();
        assertThat(node.path("counts").path("onlyOnTarget").asInt()).isZero();
        assertThat(node.path("activeFlags").get(0).path("kept").asBoolean()).isTrue();
        assertThat(node.path("warnings").get(0).path("kind").asString())
            .isEqualTo("OUTSIDE_CHAIN");
        assertThat(node.path("diff").asString()).endsWith("int-node1.diff");
        String code = challenge.path("confirmationCode").asString();
        assertThat(code).hasSize(22);

        readsForAPlan();
        DeployHarness.TARGETS.forEach(target -> {
            nodeExports(target);
            harness.importRepositoryApplied(target).importApplied(target, MODULE_IMPORT);
            nodeExports(target);
            harness.tagVerified(target, "GRP-01", TAG);
        });
        JsonNode second = client.callTool("deploy_release", Map.of("target", "int", "tag", TAG,
            "confirmationCode", code));

        assertThat(second.path("isError").asBoolean()).as(second.toString()).isFalse();
        JsonNode result = second.path("structuredContent").path("result");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("commit").asString()).isNotBlank();
        JsonNode deployed = result.path("nodes").get(2);
        assertThat(deployed.path("state").asString()).isEqualTo("DEPLOYED");
        assertThat(deployed.path("tag").path("applied").asBoolean()).isTrue();
        assertThat(deployed.path("backupRef").asString()).isNotBlank();
        harness.verifyComplete();
    }

    @Test
    void listsAreCappedByResultLimitsWithATruncationCount() throws IOException {
        McpTestClient client = client(1);

        JsonNode challenge = preview(client).path("structuredContent").path("challenge");

        JsonNode node = challenge.path("nodes").get(0);
        assertThat(node.path("activeFlags")).hasSize(1);
        assertThat(node.path("activeFlagsTruncated").asInt()).isEqualTo(1);
        assertThat(challenge.path("nodes")).hasSize(3); // nodes are never cut
    }

    @Test
    void diagramGroupsAreCappedByResultLimits() throws IOException {
        // stage 3 review m4
        McpTestClient client = client(1);
        DeployHarness.SOURCES.forEach(node -> harness.servers.get(node).copyWorkflow(
            "Workflow-0002", "Workflow-0099", "GRP-02"));
        harness.tagged("GRP-01", TAG).tagged("GRP-02", TAG);
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.SOURCE).exportGroup(DeployHarness.SOURCE, "GRP-02");
        DeployHarness.TARGETS.forEach(node -> harness.exportGroup(node)
            .exportGroup(node, "GRP-02").exportRepository(node, DeployHarness.RELEASE_XSL));

        JsonNode challenge = client.callTool("deploy_release", Map.of("target", "int", "tag",
            TAG)).path("structuredContent").path("challenge");

        assertThat(challenge.path("diagramGroups")).hasSize(1);
        assertThat(challenge.path("diagramGroupsTruncated").asInt()).isEqualTo(1);
    }

    @Test
    void reportsAreCappedByResultLimits() throws IOException {
        // stage 3 review m4: a verification mismatch writes a verify and a rollback report
        McpTestClient client = client(1);
        String code = preview(client).path("structuredContent").path("challenge")
            .path("confirmationCode").asString();
        readsForAPlan();
        NodeId first = DeployHarness.INT1;
        nodeExports(first);
        harness.importRepositoryApplied(first).importApplied(first, MODULE_IMPORT);
        harness.cli.get(first).then(spec -> harness.servers.get(first).tamperNextImport =
            new String[] {"xslt_mode", "xslt_mode_X"});
        nodeExports(first);
        harness.importApplied(first, MODULE_IMPORT);
        nodeExports(first);

        JsonNode result = client.callTool("deploy_release", Map.of("target", "int", "tag", TAG,
            "confirmationCode", code)).path("structuredContent").path("result");

        assertThat(result.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(result.path("reports")).hasSize(1);
        assertThat(result.path("reportsTruncated").asInt()).isEqualTo(1);
        harness.verifyComplete();
    }
}
