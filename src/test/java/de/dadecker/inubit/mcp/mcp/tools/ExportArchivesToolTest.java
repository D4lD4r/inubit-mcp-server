package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.V81WorkflowArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkflowArchiveFixtures;
import de.dadecker.inubit.mcp.application.ArchiveExportAudit;
import de.dadecker.inubit.mcp.application.ArchiveExportService;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetFolderRules;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Feature 015 T012 (research R-1, FR-001): {@code export_archives} over MCP
 * (contracts/mcp-tools-delta.md) with a fake StartCLI, a recording audit log, an admitting
 * version gate and the real {@link V81WorkflowArchives}.
 */
@Timeout(60)
class ExportArchivesToolTest {

    private static final String DESCRIPTION = "[acme] Export the technical workflows of one"
        + " diagram group of one group or node as one Workbench-importable archive per workflow"
        + " (`<name>.diagram.zip`) into a local folder you name. The archives contain the"
        + " workflows' secrets IN CLEAR TEXT; set acknowledgeClearTextSecrets to true only when"
        + " the user wants that. The folder must be an absolute path outside the workspace, the"
        + " server's directories (including its configuration directory), git working trees and"
        + " network paths; existing files are never replaced. Read-only for INUBIT; does not"
        + " touch the workspace.";
    private static final NodeId DEV1 = NodeId.parse("dev/node1");

    @TempDir(factory = GitFreeTempDir.class)
    Path tmp;

    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    private final List<String> exports = new CopyOnWriteArrayList<>();
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        NodeVersions versions = new NodeVersions(
            new MutableClock(Instant.parse("2026-11-06T08:00:00Z")), node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        AuditPort auditPort = audit::add;
        ArchiveExportService service = new ArchiveExportService(
            new TargetResolver(List.of(DEV1)), node -> Optional.of(WorkflowArchiveFixtures.OWNER),
            node -> Optional.of("jdoe"), node -> false, node -> false,
            new TargetFolderRules(tmp.resolve("home"), tmp.resolve("workspace"),
                tmp.resolve("audit"), List.of(tmp.resolve("config")), Map.of(),
                letter -> false, false),
            this::artifacts, new V81WorkflowArchives(),
            gateWithoutClients(versions, new FakeVersionDetection(versions),
                CapabilityMatrix.production()),
            new ArchiveFolderWriter(), ResultLimiter.withDefaults(),
            new ArchiveExportAudit.Dependencies(auditPort,
                new MutableClock(Instant.parse("2026-11-06T10:00:00Z")), "acme",
                UUID::randomUUID));
        client = McpTestClient.start(List.of(new ExportArchivesTool(service)));
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private ArtifactPort artifacts(NodeId node) {
        return new ArtifactPort() {
            @Override
            public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
                exports.add(node + " " + owner + " " + diagramGroup);
                return WorkflowArchiveFixtures.grpB();
            }

            @Override
            public byte[] exportModule(String owner, String pluginType, String name) {
                throw new AssertionError("not used");
            }

            @Override
            public byte[] exportRelease(String owner, String tag) {
                throw new AssertionError("not used");
            }

            @Override
            public byte[] exportRepository(String path) {
                throw new AssertionError("not used");
            }
        };
    }

    private Map<String, Object> valid() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("target", "dev/node1");
        arguments.put("diagramGroup", "GRP-02");
        arguments.put("workflows", List.of("Workflow-0004", "Workflow-0006"));
        arguments.put("targetFolder", tmp.resolve("archives").toString());
        arguments.put("acknowledgeClearTextSecrets", true);
        arguments.put("reason", "backup before test");
        return arguments;
    }

    private Map<String, Object> with(String key, Object value) {
        Map<String, Object> arguments = valid();
        arguments.put(key, value);
        return arguments;
    }

    @Test
    void theToolWritesLocallyWithTheContractDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("export_archives");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("Export Workbench archives of workflows");
        assertThat(annotations.path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "diagramGroup", "targetFolder");
        assertThat(schema.path("properties").propertyNames()).containsExactly("target", "owner",
            "diagramGroup", "workflows", "targetFolder", "acknowledgeClearTextSecrets", "reason");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("acknowledgeClearTextSecrets").has("const"))
            .as("research R-1: the tool checks the acknowledgement, not the schema").isFalse();
    }

    @Test
    void theSchemaRejectsMalformedCallsBeforeTheToolWithoutAudit() {
        List<Map<String, Object>> calls = List.of(
            with("overwrite", true),
            with("workflows", List.of()),
            with("workflows", IntStream.rangeClosed(1, 51)
                .mapToObj(i -> "Workflow-%04d".formatted(i)).toList()),
            with("workflows", List.of("Workflow-0004", "Workflow-0004")),
            with("workflows", List.of("W".repeat(201))),
            with("workflows", List.of("Workflow\u00010004")),
            with("workflows", List.of("Workflow-0004\n")),
            with("workflows", List.of("Workflow\u007F0004")),
            with("reason", "r".repeat(501)),
            with("reason", "line one\nline two"),
            with("reason", "line one\rline two"));

        for (Map<String, Object> arguments : calls) {
            JsonNode result = client.callTool("export_archives", arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
        }
        assertThat(audit).as("schema violations are not audited").isEmpty();
        assertThat(exports).as("the handler was not called").isEmpty();
        assertThat(tmp.resolve("archives")).doesNotExist();
    }

    @Test
    void theLimitsOfTheSchemaAreInclusive() {
        JsonNode result = client.callTool("export_archives", with("reason", "r".repeat(500)));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    }

    @Test
    void aSuccessfulCallReturnsStructuredContentValidAgainstTheOutputSchema() {
        Path folder = tmp.resolve("archives");

        JsonNode result = client.callTool("export_archives", valid());

        assertMatchesOutputSchema("export_archives", result);
        JsonNode content = result.path("structuredContent");
        assertThat(content.path("node").asString()).isEqualTo("dev/node1");
        assertThat(content.path("owner").asString()).isEqualTo("OWNERS");
        assertThat(content.path("diagramGroup").asString()).isEqualTo("GRP-02");
        assertThat(content.path("targetFolder").asString()).isEqualTo(folder.toString());
        assertThat(content.path("auditId").asString()).isEqualTo(audit.get(0).auditId()
            .toString());
        assertThat(content.path("clearTextSecrets").asString())
            .startsWith("The archives contain the secrets of these workflows");
        assertThat(content.path("archives")).extracting(archive -> archive.path("file")
            .asString()).containsExactly("Workflow-0004.diagram.zip",
                "Workflow-0006.diagram.zip");
        JsonNode last = content.path("archives").get(1);
        assertThat(last.path("path").asString())
            .isEqualTo(folder.resolve("Workflow-0006.diagram.zip").toString());
        assertThat(last.path("modules").get(0).has("pluginType")).isTrue();
        assertThat(last.path("repositoryPaths")).extracting(JsonNode::asString)
            .containsExactly("/Root/OWNERS/xsd/core.xsd", "/Root/OWNERS/xsd/msg.xsd");
        assertThat(content.path("failed")).isEmpty();
        assertThat(content.path("notWritten")).isEmpty();
        assertThat(content.path("truncated").asBoolean()).isFalse();
        assertThat(Files.exists(folder.resolve("Workflow-0004.diagram.zip"))).isTrue();
        assertThat(exports).containsExactly("dev/node1 OWNERS GRP-02");
    }

    @Test
    void theCallPassesTheMcpClientAndTheArgumentsToTheAudit() {
        Map<String, Object> arguments = valid();
        arguments.put("target", "dev");
        arguments.put("owner", "jdoe");

        client.callTool("export_archives", arguments);

        assertThat(audit).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        AuditRecord executed = audit.get(1);
        assertThat(executed.mcpClient()).contains("mcp-test-client/1.0");
        assertThat(executed.node()).isEqualTo("dev/node1");
        assertThat(executed.inputs()).containsEntry("owner", "jdoe")
            .containsEntry("diagramGroup", "GRP-02")
            .containsEntry("targetFolder", tmp.resolve("archives").toString())
            .containsEntry("workflow.1", "Workflow-0004")
            .containsEntry("workflow.2", "Workflow-0006")
            .containsEntry("acknowledgeClearTextSecrets", "true")
            .containsEntry("reason", "backup before test");
        assertThat(exports).containsExactly("dev/node1 jdoe GRP-02");
    }

    @Test
    void absentOptionalArgumentsReachTheServiceAsDefaults() {
        client.callTool("export_archives", Map.of("target", "dev/node1", "diagramGroup",
            "GRP-02", "targetFolder", tmp.resolve("archives").toString()));

        assertThat(audit).isNotEmpty();
        assertThat(audit.get(0).inputs()).containsEntry("acknowledgeClearTextSecrets", "false")
            .containsEntry("owner", "OWNERS").doesNotContainKeys("workflow.1", "reason");
        assertThat(audit.get(0).mcpClient()).contains("mcp-test-client/1.0");
    }
}
