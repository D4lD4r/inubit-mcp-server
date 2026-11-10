package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeAudit;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeService;
import de.dadecker.inubit.mcp.application.knowledge.NoteRefs;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * 019-T031: {@code record_knowledge} over MCP (019 contracts/mcp-tools-delta.md): announcement
 * and schemas as in the contract, the three modes with the client in the note and the audit, the
 * errors of the service passed through and schema violations rejected before the tool.
 */
@Timeout(60)
class RecordKnowledgeToolTest {

    private static final String DESCRIPTION = "[acme] Write a note to this profile's local"
        + " knowledge on this computer: create a new note, append a section to a note, or replace"
        + " a note's content (replace needs the versionToken from read_knowledge). Only after the"
        + " operator agreed in this conversation to the proposed name, title, topics and content,"
        + " or asked for the note - propose first, never record on your own. Never write"
        + " passwords, keys, tokens or other secrets: text that contains one is refused. Notes are"
        + " never deleted; the previous version is kept in the history. Does not contact INUBIT.";
    private static final Instant NOW = Instant.parse("2026-11-10T10:15:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000031");
    private static final String HELD = "record-tool-pw";

    @TempDir
    Path temp;
    private Path root;
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    private McpTestClient client;

    @BeforeEach
    void start() throws IOException {
        root = Files.createDirectories(temp.resolve("knowledge"));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        KnowledgeService service = new KnowledgeService(
            new KnowledgeDirectory(root, PrivateFiles.forHost(), clock),
            FakeConfiguredSecrets.held(HELD), new NoteRefs(new SecureRandom()),
            Duration.ofSeconds(10), new KnowledgeService.Writing(PrivateFiles.forHost(), clock,
                new KnowledgeAudit.Dependencies(audit::add, clock, "acme", () -> ID)));
        client = McpTestClient.start(List.of(new RecordKnowledgeTool(service),
            new ReadKnowledgeTool(service)));
        client.initialize();
    }

    @AfterEach
    void stop() {
        client.close();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("record_knowledge", arguments);
    }

    @Test
    void theToolIsListedAsALocalOnlyWriteWithTheContractSchemas() {
        JsonNode tool = null;
        for (JsonNode listed : client.listTools().path("tools")) {
            if (listed.path("name").asString().equals("record_knowledge")) {
                tool = listed;
            }
        }

        assertThat(tool).isNotNull();
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString())
            .isEqualTo("Record a note in the local knowledge");
        assertThat(annotations.path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("openWorldHint").asBoolean(true)).isFalse();
        JsonNode input = tool.path("inputSchema");
        assertThat(input.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(input.path("required").toString()).isEqualTo("[\"mode\",\"content\"]");
        assertThat(input.path("properties").propertyNames()).containsExactly("name", "ref",
            "mode", "title", "topics", "content", "versionToken");
        JsonNode output = tool.path("outputSchema");
        assertThat(output.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(output.path("properties").propertyNames()).containsExactly("name", "mode",
            "lastChange", "versionToken", "sizeBefore", "sizeAfter", "historyVersions",
            "auditId", "warnings");
    }

    @Test
    void createAppendAndReplaceMatchTheSchemaAndNameTheClient() throws IOException {
        JsonNode created = call(Map.of("name", "partners/acme", "mode", "create",
            "title", "ACME", "topics", List.of("edi"), "content", "Uses EDIFACT.\n"));
        JsonNode appended = call(Map.of("name", "partners/acme", "mode", "append",
            "content", "Second."));
        String token = client.callTool("read_knowledge", Map.of("name", "partners/acme"))
            .path("structuredContent").path("versionToken").asString();
        JsonNode replaced = call(Map.of("name", "partners/acme", "mode", "replace",
            "topics", List.of(), "content", "New.", "versionToken", token));

        for (JsonNode result : List.of(created, appended, replaced)) {
            assertMatchesOutputSchema("record_knowledge", result);
        }
        JsonNode first = created.path("structuredContent");
        assertThat(first.path("name").asString()).isEqualTo("partners/acme");
        assertThat(first.path("mode").asString()).isEqualTo("create");
        assertThat(first.path("lastChange").asString()).endsWith("Z");
        assertThat(first.path("sizeBefore").asLong()).isZero();
        assertThat(first.path("historyVersions").asInt()).isZero();
        assertThat(first.path("auditId").asString()).isEqualTo(ID.toString());
        assertThat(first.path("warnings")).isEmpty();
        assertThat(appended.path("structuredContent").path("mode").asString())
            .isEqualTo("append");
        assertThat(appended.path("structuredContent").path("sizeBefore").asLong())
            .isEqualTo(first.path("sizeAfter").asLong());
        assertThat(replaced.path("structuredContent").path("historyVersions").asInt())
            .isEqualTo(2);
        String text = Files.readString(root.resolve("partners/acme.md"));
        assertThat(text).contains("title: 'ACME'", "topics: []",
            "source: 'assistant (mcp-test-client)'").endsWith("---\nNew.");
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(
            AuditOutcome.PENDING, AuditOutcome.EXECUTED, AuditOutcome.PENDING,
            AuditOutcome.EXECUTED, AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(audit).allSatisfy(record -> assertThat(record.mcpClient())
            .isEqualTo(Optional.of("mcp-test-client/1.0")));
    }

    @Test
    void aRefSelectsTheNote() throws IOException {
        call(Map.of("name", "a", "mode", "create", "title", "A", "content", "x"));
        String ref = client.callTool("read_knowledge", Map.of()).path("structuredContent")
            .path("notes").get(0).path("ref").asString();

        JsonNode result = call(Map.of("ref", ref, "mode", "append", "content", "more"));

        assertMatchesOutputSchema("record_knowledge", result);
        assertThat(Files.readString(root.resolve("a.md"))).endsWith("x\n\nmore");
    }

    @Test
    void theErrorsOfTheServiceArePassedThroughWithoutTheSecret() {
        JsonNode secret = toolError(call(Map.of("name", "a", "mode", "create", "title", "A",
            "content", "password: " + HELD)));
        JsonNode combination = toolError(call(Map.of("name", "a", "mode", "append",
            "content", "x", "versionToken", "v1.AAAAAAAAAAAAAAAAAAAAAAAA")));
        call(Map.of("name", "b", "mode", "create", "title", "B", "content", "x"));
        JsonNode conflict = toolError(call(Map.of("name", "B", "mode", "create", "title", "B",
            "content", "y")));

        assertThat(secret.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(secret.path("message").asString()).contains("password assignment in content");
        assertThat(combination.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(combination.path("message").asString()).contains("versionToken");
        assertThat(conflict.path("code").asString()).isEqualTo("CONFLICT");
        assertThat(client.rawOutput()).doesNotContain(HELD);
    }

    @Test
    void schemaViolationsAreRejectedBeforeTheToolAndNotAudited() {
        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
            Map.of("name", "a", "mode", "delete", "content", "x"),
            Map.of("name", "a", "mode", "create", "title", "A"),
            Map.of("name", "a", "content", "x"),
            Map.of("name", "a", "mode", "create", "title", "A", "content", "x", "other", 1),
            Map.of("name", "a", "mode", "create", "title", "A", "content", "x",
                "topics", "edi"))) {
            JsonNode result = call(arguments);

            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(result.has("structuredContent")).isFalse();
        }
        assertThat(audit).isEmpty();
        assertThat(root).isEmptyDirectory();
    }
}
