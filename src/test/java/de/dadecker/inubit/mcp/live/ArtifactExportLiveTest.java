package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T038: {@code export_artifacts} and {@code check_artifacts} against a real,
 * <b>non-production</b> INUBIT server (opt-in, Constitution III) through the production wiring,
 * into a <b>temporary</b> workspace (never the person's real one). Read-only for INUBIT: two
 * StartCLI exports of the diagram group {@code INUBIT_LIVE_DIAGRAM_GROUP}. Run with
 * {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=test/node1 INUBIT_LIVE_DIAGRAM_GROUP=<group>
 * mvn verify -Plive}; configuration, credentials and the production refusal: {@link LiveTarget}.
 * Checks: the second export is {@code unchanged}; no {@code AES-} value and no
 * {@code type="Password"} value other than a placeholder in the workspace; the structure check
 * of the exported workflows has no ERROR. Only counts and timings are printed, never names or
 * content.
 */
@Tag("live")
@Timeout(900)
class ArtifactExportLiveTest {

    static final String GROUP_VARIABLE = "INUBIT_LIVE_DIAGRAM_GROUP";
    private static final Duration EXPORT_WAIT = Duration.ofSeconds(400);
    private static final Pattern AES = Pattern.compile("AESG?-?[A-Za-z0-9+/=:-]{12,}");
    private static final Pattern PASSWORD = Pattern.compile(
        "type=\"Password\"[^>]*>([^<]+)<");

    @TempDir
    Path workspace;

    @Test
    void aDiagramGroupIsExportedTwiceAndChecked() throws IOException {
        LiveTarget live = LiveTarget.resolve();
        String diagramGroup = System.getenv(GROUP_VARIABLE);
        Assumptions.assumeTrue(diagramGroup != null && !diagramGroup.isBlank(), GROUP_VARIABLE
            + " is not set; the artifact export live test is skipped");
        NodeId node = live.node();

        try (TestWiring wiring = live.wiring(workspace);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            Map<String, Object> export = Map.of("target", node.value(), "diagramGroups",
                List.of(diagramGroup.strip()));

            long start = System.nanoTime();
            JsonNode first = structured(client.callTool("export_artifacts", export,
                EXPORT_WAIT));
            Duration firstElapsed = Duration.ofNanos(System.nanoTime() - start);
            JsonNode second = structured(client.callTool("export_artifacts", export,
                EXPORT_WAIT));

            assertThat(first.path("unchanged").asBoolean()).isFalse();
            assertThat(second.path("unchanged").asBoolean()).as("SC-001: unchanged re-export")
                .isTrue();
            int aes = 0;
            int passwords = 0;
            try (Stream<Path> files = Files.walk(workspace)) {
                for (Path file : files.filter(Files::isRegularFile)
                    .filter(f -> !workspace.relativize(f).startsWith(".git")).toList()) {
                    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    aes += (int) AES.matcher(text).results().count();
                    passwords += (int) PASSWORD.matcher(text).results()
                        .filter(m -> !m.group(1).strip().startsWith("${secret:")).count();
                }
            }
            assertThat(aes).as("AES- values in the workspace").isZero();
            assertThat(passwords).as("non-placeholder Password values").isZero();

            String owner = first.path("owner").asString();
            JsonNode check = structured(client.callTool("check_artifacts", Map.of("paths",
                List.of(node.group().value() + "/" + owner + "/workflows")), EXPORT_WAIT));
            assertThat(check.path("counts").path("ERROR").asInt())
                .as(() -> "structure errors: " + check.path("findings")).isZero();

            System.err.println("[live] " + node + ": export_artifacts of one diagram group "
                + first.path("counts") + ", " + first.path("secretsReplaced").asInt()
                + " secrets replaced, " + firstElapsed.toMillis() + " ms; re-export unchanged;"
                + " check_artifacts " + check.path("counts"));
        }
    }

    /** The structured result; a failure shows only the error. */
    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean())
            .as(() -> result.path("content").toString()).isFalse();
        return result.path("structuredContent");
    }
}
