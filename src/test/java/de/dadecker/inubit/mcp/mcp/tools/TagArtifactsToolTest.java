package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.DevelopmentGuard;
import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.application.TagService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T021 (feature 004, contracts/mcp-tools-delta.md): {@code tag_artifacts} over MCP —
 * destructive annotations, the contract description and input schema (no blank or
 * wildcard-like group reaches the tool), the result as structured content validated against
 * the output schema.
 */
class TagArtifactsToolTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String DESCRIPTION = "[acme] Tag the current versions of the technical"
        + " workflows (and their modules) of the given diagram groups of an owner on ONE"
        + " development node. Never owner-wide; an existing tag is never moved.";
    private static final String VALUE = "^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$";

    @TempDir
    Path root;

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final List<String> tagged = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    /** One technical workflow in GRP-01 whose head gets the tag once it is set. */
    private final TagPort port = new TagPort() {
        @Override
        public void checkAvailable() {
        }

        @Override
        public History history(String owner) {
            return history(owner, "GRP-01");
        }

        @Override
        public History history(String owner, String group) {
            List<String> tags = List.copyOf(tagged);
            return new History(Map.of("W-1", new Diagram("GRP-01", "technical", List.of(
                new VersionEntry(1, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), tags)))), Map.of());
        }

        @Override
        public void tag(String tag, String group, String owner) {
            tagged.add(tag);
        }

        @Override
        public void deleteTag(String tag, String owner) {
            tagged.remove(tag);
        }
    };

    private McpTestClient client() {
        DevelopmentPolicy dev = new DevelopmentPolicy(DEV, false, true,
            WritePolicy.Confirmation.CLIENT, Duration.ofMinutes(5), E2ePolicy.FORBIDDEN,
            Optional.empty());
        MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
        TagService service = new TagService(new TagService.Dependencies(root, "acme",
            new DevelopmentGuard(new TargetResolver(List.of(DEV)), Map.of(DEV, dev)::get,
                node -> { }), node -> port, node -> Optional.of("jdoe"),
            node -> new ImportService.Account("jdoe", "inubit-dev-1.example.test"),
            new WriteChallengeRegistry(clock), record -> { }, clock, UUID::randomUUID));
        McpTestClient client = McpTestClient.start(List.of(new TagArtifactsTool(service)));
        closeables.add(client);
        client.initialize();
        return client;
    }

    @Test
    void theToolIsDestructiveWithTheContractDescriptionAndSchema() {
        JsonNode tool = client().listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("tag_artifacts");
        assertThat(tool.path("description").asString()).isEqualTo(DESCRIPTION);
        assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(tool.path("annotations").path("idempotentHint").asBoolean()).isFalse();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("node", "diagramGroups", "tag", "reason");
        JsonNode groups = schema.path("properties").path("diagramGroups");
        assertThat(groups.path("minItems").asInt()).isEqualTo(1);
        assertThat(groups.path("maxItems").asInt()).isEqualTo(20);
        assertThat(groups.path("uniqueItems").asBoolean()).isTrue();
        assertThat(groups.path("items").path("pattern").asString()).isEqualTo(VALUE);
        assertThat(schema.path("properties").path("tag").path("pattern").asString())
            .isEqualTo(VALUE);
    }

    @Test
    void blankEmptyOrWildcardGroupsAreRejectedBeforeTheTool() {
        McpTestClient client = client();

        for (Object groups : List.of(List.of(), List.of(""), List.of(" "), List.of("*"),
            List.of("GRP-01", "GRP-01"))) {
            assertThat(client.callTool("tag_artifacts", Map.of("node", "dev/node1",
                "diagramGroups", groups, "tag", "REL-1", "reason", "x")).path("isError")
                .asBoolean()).as(groups.toString()).isTrue();
        }
        assertThat(tagged).isEmpty();
    }

    @Test
    void theResultIsStructuredContent() {
        JsonNode result = client().callTool("tag_artifacts", Map.of("node", "dev/node1",
            "diagramGroups", List.of("GRP-01"), "tag", "REL-1", "reason", "Tested"));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode outcome = result.path("structuredContent").path("result");
        assertThat(outcome.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(outcome.path("tag").asString()).isEqualTo("REL-1");
        assertThat(outcome.path("workflows").asInt()).isEqualTo(1);
        assertThat(outcome.path("removedAgain").asBoolean(true)).isFalse();
        assertThat(outcome.has("failure")).isFalse();
    }
}
