package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.application.OwnerKindResolver;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.TagPort;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T026 (feature 004, research D-23, SC-006): the development tools against a real
 * <b>development</b> node, on a <b>personal</b> diagram group of a <b>user</b> owner with
 * dedicated test workflows only — opt-in, never part of the default build, never on production
 * or shared owners. Run with
 * {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_DEV_NODE=dev/node1 INUBIT_LIVE_DEV_OWNER=<user>
 * INUBIT_LIVE_DEV_DIAGRAM_GROUP=<personal group> mvn verify -Plive -Dtest=DevelopmentLiveTest};
 * starting it is the approval of exactly this scenario.
 *
 * <p>Refused before anything is written unless the node is a development node
 * ({@link LiveTarget#resolveDevelopment}) and the owner is a user (profile {@code owners} or
 * INUBIT's user list). Scenario, into a <b>temporary</b> workspace: export → change one layout
 * value of one workflow → {@code import_artifacts} → verified (the workspace shows the change)
 * → {@code restore_backup} → {@code set_active} off/on (or on/off) → {@code tag_artifacts} with a
 * unique {@code LIVE-<timestamp>} tag → the tag is removed again ({@code tag --tagDelete}) and
 * the history shows it nowhere. Every write needs a confirmation code under
 * {@code development.confirmation: SERVER}; the test confirms the preview it got. Only counts
 * and timings are printed, never names or content.
 */
@Tag("live")
@Timeout(1800)
class DevelopmentLiveTest {

    static final String OWNER_VARIABLE = "INUBIT_LIVE_DEV_OWNER";
    static final String GROUP_VARIABLE = "INUBIT_LIVE_DEV_DIAGRAM_GROUP";
    private static final Duration WAIT = Duration.ofSeconds(600);
    private static final Pattern X_POS = Pattern.compile("xPos=\"(\\d+)\"");
    private static final Pattern ACTIVE = Pattern.compile("<IsActive>(true|false)</IsActive>");

    @TempDir
    Path workspace;

    @Test
    void importRestoreActivateAndTagOnAPersonalDiagramGroup() throws IOException {
        LiveTarget live = LiveTarget.resolveDevelopment();
        String owner = System.getenv(OWNER_VARIABLE);
        String group = System.getenv(GROUP_VARIABLE);
        Assumptions.assumeTrue(owner != null && !owner.isBlank() && group != null
            && !group.isBlank(), OWNER_VARIABLE + " and " + GROUP_VARIABLE
            + " are not set; the development live test is skipped");
        owner = owner.strip();
        group = group.strip();
        NodeId node = live.node();
        String tag = "LIVE-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        boolean tagged = false;

        try (TestWiring wiring = live.wiring(workspace);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            OwnerKind kind = new OwnerKindResolver(live.loaded().config().owners(),
                wiring.gateways()::users).resolve(node, owner);
            assertThat(kind).as("the development live test runs only for a user owner (a"
                + " personal diagram group)").isEqualTo(OwnerKind.USER);
            client.initialize();
            long start = System.nanoTime();

            structured(client.callTool("export_artifacts", Map.of("target", node.value(),
                "owner", owner, "diagramGroups", List.of(group)), WAIT));
            Path file = firstWorkflow(node, owner, group);
            String original = Files.readString(file, StandardCharsets.UTF_8);
            Matcher x = X_POS.matcher(original);
            assertThat(x.find()).as("a layout value to change").isTrue();
            String moved = original.substring(0, x.start()) + "xPos=\""
                + (Integer.parseInt(x.group(1)) + 10) + "\"" + original.substring(x.end());
            Files.writeString(file, moved, StandardCharsets.UTF_8);

            JsonNode imported = write(client, "import_artifacts", Map.of("node", node.value(),
                "owner", owner, "diagramGroup", group, "reason", "Live test layout " + tag));
            assertThat(imported.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(imported.path("modified").size()).isEqualTo(1);
            assertThat(X_POS.matcher(Files.readString(file)).results().findFirst().orElseThrow()
                .group()).as("the verified state is in the workspace")
                .isEqualTo(X_POS.matcher(moved).results().findFirst().orElseThrow().group());

            JsonNode restored = write(client, "restore_backup", Map.of("node", node.value(),
                "backupRef", imported.path("backupRef").asString(), "reason",
                "Live test restore " + tag));
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
                "owner", owner, "diagramGroups", List.of(group), "tag", tag, "reason",
                "Live test tag"));
            tagged = true;
            assertThat(tagResult.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(tagResult.path("workflows").asInt()).isPositive();

            TagPort tags = wiring.gateways().tags(node);
            tags.deleteTag(tag, owner);
            tagged = false;
            TagPort.History history = tags.history(owner);
            long carrying = Stream.concat(history.diagrams().values().stream()
                .flatMap(d -> d.versions().stream()), history.modules().values().stream()
                .flatMap(List::stream)).filter(v -> v.tags().contains(tag)).count();
            assertThat(carrying).as("the live tag was removed again").isZero();

            System.err.println("[live] " + node + ": import, restore, set_active x2 and tag of"
                + " one personal diagram group: " + tagResult.path("workflows").asInt()
                + " workflow(s), " + tagResult.path("modules").asInt() + " module(s) tagged and"
                + " untagged, " + Duration.ofNanos(System.nanoTime() - start).toSeconds()
                + " s");
        } finally {
            if (tagged) {
                try (TestWiring cleanup = live.wiring(workspace)) {
                    cleanup.gateways().tags(node).deleteTag(tag, owner);
                }
            }
        }
    }

    /** The first workflow file of the diagram group in the temporary workspace. */
    private Path firstWorkflow(NodeId node, String owner, String group) throws IOException {
        Path directory = workspace.resolve(WorkspacePath.workflow(node.group(), owner, group, "x")
            .toRelativePath()).getParent();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> f.toString().endsWith(".xml")).sorted().findFirst()
                .orElseThrow(() -> new AssertionError("the diagram group has no workflow"));
        }
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
