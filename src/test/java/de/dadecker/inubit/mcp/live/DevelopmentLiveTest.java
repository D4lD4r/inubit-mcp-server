package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T026, T031 (feature 004, research D-23, D-26, SC-006): the development tools against a real
 * <b>development</b> node, on a dedicated test diagram group with a dedicated test workflow only
 * — opt-in, never part of the default build, never on production. Run with
 * {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_DEV_NODE=dev/node1 INUBIT_LIVE_DEV_OWNER=<owner>
 * INUBIT_LIVE_DEV_DIAGRAM_GROUP=<test group> INUBIT_LIVE_DEV_WORKFLOW=<test workflow>
 * mvn verify -Plive -Dtest=DevelopmentLiveTest}; starting it is the approval of exactly this
 * scenario.
 *
 * <p>The owner may be a user or a user group (research D-26: the server no longer tells them
 * apart, every import uses {@code --importUser}). Because a user group's diagram groups are
 * shared, nothing is chosen by default: the diagram group <b>and</b> the workflow must both be
 * named explicitly, otherwise the test is skipped. Refused before anything is written unless the
 * node is a development node ({@link LiveTarget#resolveDevelopment}). Scenario, into a
 * <b>temporary</b> workspace: export → change one layout
 * value of one workflow → {@code import_artifacts} → verified (the workspace shows the change)
 * → {@code restore_backup} → {@code set_active} off/on (or on/off) → {@code tag_artifacts} of
 * the whole diagram group with the fixed tag {@link #TAG}. The tag is never removed (StartCLI
 * removes a tag only owner-wide, research D-26): it stays as a harmless label on the group's
 * current versions, and the next run reuses the name, which moves it to the then current
 * versions. Every write needs a confirmation code under
 * {@code development.confirmation: SERVER}; the test confirms the preview it got. Only counts
 * and timings are printed, never names or content.
 */
@Tag("live")
@Timeout(1800)
class DevelopmentLiveTest {

    static final String OWNER_VARIABLE = "INUBIT_LIVE_DEV_OWNER";
    static final String GROUP_VARIABLE = "INUBIT_LIVE_DEV_DIAGRAM_GROUP";
    /** The workflow to change; required, never a default (a group may be shared). */
    static final String WORKFLOW_VARIABLE = "INUBIT_LIVE_DEV_WORKFLOW";
    /** The tag of every run: reused, never removed, so runs do not pile up tag names. */
    static final String TAG = "LIVE-TEST";
    private static final Duration WAIT = Duration.ofSeconds(600);
    private static final Pattern X_POS = Pattern.compile("xPos=\"(\\d+)\"");
    private static final Pattern ACTIVE = Pattern.compile("<IsActive>(true|false)</IsActive>");

    @TempDir
    Path workspace;

    @Test
    void importRestoreActivateAndTagOnATestDiagramGroup() throws IOException {
        LiveTarget live = LiveTarget.resolveDevelopment();
        String owner = System.getenv(OWNER_VARIABLE);
        String group = System.getenv(GROUP_VARIABLE);
        String named = System.getenv(WORKFLOW_VARIABLE);
        Assumptions.assumeTrue(given(owner) && given(group) && given(named), OWNER_VARIABLE
            + ", " + GROUP_VARIABLE + " and " + WORKFLOW_VARIABLE + " are not all set; the"
            + " development live test is skipped (nothing is chosen by default)");
        owner = owner.strip();
        group = group.strip();
        NodeId node = live.node();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.now());

        try (TestWiring wiring = live.wiring(workspace);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            long start = System.nanoTime();

            structured(client.callTool("export_artifacts", Map.of("target", node.value(),
                "owner", owner, "diagramGroups", List.of(group)), WAIT));
            Path file = namedWorkflow(node, owner, group, named.strip());
            String original = Files.readString(file, StandardCharsets.UTF_8);
            Matcher x = X_POS.matcher(original);
            assertThat(x.find()).as("a layout value to change").isTrue();
            String moved = original.substring(0, x.start()) + "xPos=\""
                + (Integer.parseInt(x.group(1)) + 10) + "\"" + original.substring(x.end());
            Files.writeString(file, moved, StandardCharsets.UTF_8);

            JsonNode imported = write(client, "import_artifacts", Map.of("node", node.value(),
                "owner", owner, "diagramGroup", group, "reason", "Live test layout " + stamp));
            assertThat(imported.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(imported.path("modified").size()).isEqualTo(1);
            assertThat(X_POS.matcher(Files.readString(file)).results().findFirst().orElseThrow()
                .group()).as("the verified state is in the workspace")
                .isEqualTo(X_POS.matcher(moved).results().findFirst().orElseThrow().group());

            JsonNode restored = write(client, "restore_backup", Map.of("node", node.value(),
                "backupRef", imported.path("backupRef").asString(), "reason",
                "Live test restore " + stamp));
            assertThat(restored.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(X_POS.matcher(Files.readString(file)).results().findFirst().orElseThrow()
                .group()).isEqualTo(x.group());

            String workflow = file.getFileName().toString().replaceAll("\\.xml$", "");
            Matcher active = ACTIVE.matcher(Files.readString(file));
            assertThat(active.find()).isTrue();
            boolean wasActive = Boolean.parseBoolean(active.group(1));
            for (boolean value : List.of(!wasActive, wasActive)) {
                JsonNode switched = write(client, "set_active", Map.of("node", node.value(),
                    "owner", owner, "diagramGroup", group, "workflow",
                    de.dadecker.inubit.mcp.domain.model.NameCodec.decode(workflow), "active",
                    value, "reason", "Live test active " + value));
                assertThat(switched.path("outcome").asString()).isEqualTo("EXECUTED");
            }

            JsonNode tagResult = write(client, "tag_artifacts", Map.of("node", node.value(),
                "owner", owner, "diagramGroups", List.of(group), "tag", TAG, "reason",
                "Live test tag " + stamp));
            assertThat(tagResult.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(tagResult.path("workflows").asInt()).isPositive();

            System.err.println("[live] " + node + ": import, restore, set_active x2 and tag of"
                + " one test diagram group: " + tagResult.path("workflows").asInt()
                + " workflow(s), " + tagResult.path("modules").asInt() + " module(s) carry "
                + TAG + ", " + Duration.ofNanos(System.nanoTime() - start).toSeconds() + " s");
        }
    }

    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }

    /** The workflow file named by {@code INUBIT_LIVE_DEV_WORKFLOW} in the temporary workspace. */
    private Path namedWorkflow(NodeId node, String owner, String group, String named) {
        Path file = workspace.resolve(WorkspacePath.workflow(node.group(), owner, group, named)
            .toRelativePath());
        if (!Files.isRegularFile(file)) {
            throw new AssertionError(WORKFLOW_VARIABLE + " names no workflow of the group");
        }
        return file;
    }

    /** The result of a write, confirming a preview with its code (the run is the approval). */
    private static JsonNode write(McpTestClient client, String tool,
        Map<String, Object> arguments) {
        JsonNode first = structured(client.callTool(tool, arguments, WAIT));
        if (!first.has("challenge")) {
            return first.path("result");
        }
        Map<String, Object> confirmed = new HashMap<>(arguments);
        confirmed.put("confirmationCode", first.path("challenge").path("confirmationCode")
            .asString());
        return structured(client.callTool(tool, confirmed, WAIT)).path("result");
    }

    /** The structured result; a failure shows only the error. */
    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean())
            .as(() -> result.path("content").toString()).isFalse();
        return result.path("structuredContent");
    }
}
