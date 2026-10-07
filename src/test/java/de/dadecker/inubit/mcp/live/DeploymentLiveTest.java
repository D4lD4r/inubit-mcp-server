package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T030 (feature 005, US1–US3, SC-001–SC-003): {@code deploy_release} against a real chained
 * <b>test target group</b> — opt-in, never part of the default build, never production, never a
 * package-only group ({@link LiveTarget#resolveDeployment}). It <b>writes to INUBIT</b>: starting
 * it is the approval of exactly this scenario on exactly the named group, diagram group, tag and
 * owner. Run with
 * {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_DEPLOY_TARGET=<group>
 * INUBIT_LIVE_DEPLOY_DIAGRAM_GROUP=<test diagram group> INUBIT_LIVE_DEPLOY_TAG=<tag>
 * INUBIT_LIVE_DEPLOY_OWNER=<owner> mvn verify -Plive -Dtest=DeploymentLiveTest}; without all
 * four variables the test is skipped.
 *
 * <p>Scenario, in a <b>temporary</b> workspace: tag the test diagram group on the source (only if
 * the source group has exactly one node and it is a development node; otherwise the operator has
 * tagged it on every source node before) → preview (the release must be exactly that diagram
 * group) → execute with the code → a second preview and execution must find every node
 * {@code UNCHANGED} → {@code restore_backup} of the first written node → nothing outside the
 * diagram group and its modules changed on any target node (diagram list, module change times).
 * Only counts and timings are printed, never names or content.
 */
@Tag("live")
@Timeout(3600)
class DeploymentLiveTest {

    static final String GROUP_VARIABLE = "INUBIT_LIVE_DEPLOY_DIAGRAM_GROUP";
    static final String TAG_VARIABLE = "INUBIT_LIVE_DEPLOY_TAG";
    static final String OWNER_VARIABLE = "INUBIT_LIVE_DEPLOY_OWNER";
    private static final Duration WAIT = Duration.ofSeconds(1200);

    @TempDir
    Path workspace;

    @Test
    void deployRedeployAndRestoreOneTestDiagramGroup() throws IOException {
        LiveTarget live = LiveTarget.resolveDeployment();
        String group = System.getenv(GROUP_VARIABLE);
        String tag = System.getenv(TAG_VARIABLE);
        String owner = System.getenv(OWNER_VARIABLE);
        Assumptions.assumeTrue(given(group) && given(tag) && given(owner), GROUP_VARIABLE + ", "
            + TAG_VARIABLE + " and " + OWNER_VARIABLE + " are not all set; the deployment live"
            + " test is skipped (nothing is chosen by default)");
        group = group.strip();
        tag = tag.strip();
        owner = owner.strip();
        GroupId target = live.node().group();
        StageChain.ChainLink link = live.loaded().config().stageChain().link(target)
            .orElseThrow();
        List<NodeId> targetNodes = nodes(live, target);
        List<EffectiveNodeConfig> sourceNodes = live.loaded().config().effectiveNodes().stream()
            .filter(node -> node.id().group().equals(link.source())).toList();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.now());

        try (TestWiring wiring = live.wiring(workspace);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            long start = System.nanoTime();

            // 1. the release: one test diagram group with the tag on the source
            if (sourceNodes.size() == 1 && sourceNodes.get(0).development().enabled()) {
                JsonNode tagged = write(client, "tag_artifacts", Map.of("node",
                    sourceNodes.get(0).id().value(), "owner", owner, "diagramGroups",
                    List.of(group), "tag", tag, "reason", "Live deployment tag " + stamp));
                assertThat(tagged.path("outcome").asString()).isEqualTo("EXECUTED");
            }
            Map<NodeId, State> before = new TreeMap<>();
            for (NodeId node : targetNodes) {
                before.put(node, state(client, node));
            }

            // 2. preview: exactly the test diagram group, executable
            Map<String, Object> arguments = Map.of("target", target.value(), "tag", tag,
                "owner", owner);
            JsonNode preview = structured(client.callTool("deploy_release", arguments, WAIT))
                .path("challenge");
            assertThat(texts(preview.path("diagramGroups"))).as("the release")
                .containsExactly(group);
            assertThat(preview.path("mode").asString()).isEqualTo("EXECUTE");
            assertThat(preview.path("executable").asBoolean()).as(preview.path("nodes")
                .toString()).isTrue();

            // 3. execute
            JsonNode deployed = confirm(client, arguments, preview);
            assertThat(deployed.path("outcome").asString()).isEqualTo("EXECUTED");
            Set<String> imported = new TreeSet<>();
            NodeId restored = null;
            String backupRef = null;
            for (JsonNode node : deployed.path("nodes")) {
                assertThat(node.path("state").asString()).isIn("DEPLOYED", "UNCHANGED");
                imported.addAll(texts(node.path("imported")));
                if (restored == null && node.path("state").asString().equals("DEPLOYED")) {
                    restored = NodeId.parse(node.path("node").asString());
                    backupRef = node.path("backupRef").asString();
                }
            }

            // 4. redeploy: unchanged everywhere, only the tag
            JsonNode again = structured(client.callTool("deploy_release", arguments, WAIT))
                .path("challenge");
            for (JsonNode node : again.path("nodes")) {
                JsonNode counts = node.path("counts");
                assertThat(counts.path("new").asInt() + counts.path("changed").asInt()
                    + counts.path("layoutOnly").asInt()).as(node.path("node").asString())
                    .isZero();
            }
            JsonNode redeployed = confirm(client, arguments, again);
            assertThat(redeployed.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(redeployed.path("nodes")).allSatisfy(node ->
                assertThat(node.path("state").asString()).isEqualTo("UNCHANGED"));

            // 5. restore one written node from its deployment backup
            if (restored != null) {
                JsonNode restore = write(client, "restore_backup", Map.of("node",
                    restored.value(), "backupRef", backupRef, "reason",
                    "Live deployment restore " + stamp));
                assertThat(restore.path("outcome").asString()).isEqualTo("EXECUTED");
            }

            // 6. nothing outside the diagram group and the deployed modules changed
            for (NodeId node : targetNodes) {
                State after = state(client, node);
                State was = before.get(node);
                String test = group;
                assertThat(after.diagrams()).as(node + " diagrams")
                    .containsAll(was.diagrams());
                assertThat(after.diagrams().stream().filter(d -> !was.diagrams().contains(d)))
                    .as(node + " new diagrams").allMatch(d -> d.endsWith("@" + test));
                was.modules().forEach((module, changed) -> {
                    if (!imported.contains(module)) {
                        assertThat(after.modules().get(module)).as(node + " module")
                            .isEqualTo(changed);
                    }
                });
            }

            System.err.println("[live] " + target + ": deploy, redeploy (unchanged) and restore"
                + " of one test diagram group on " + targetNodes.size() + " node(s): "
                + imported.size() + " artifact(s) imported, " + Duration.ofNanos(
                    System.nanoTime() - start).toSeconds() + " s");
        }
    }

    /** The diagrams ({@code name@group}) and modules (name → last change) of a node. */
    private record State(Set<String> diagrams, Map<String, String> modules) {
    }

    private static State state(McpTestClient client, NodeId node) {
        Set<String> diagrams = new TreeSet<>();
        for (JsonNode item : items(client, node, "DIAGRAM")) {
            diagrams.add(item.path("name").asString() + "@" + item.path("group").asString());
        }
        Map<String, String> modules = new TreeMap<>();
        for (JsonNode item : items(client, node, "MODULE")) {
            modules.put(item.path("name").asString(), item.has("lastChange")
                ? item.path("lastChange").asString() : "");
        }
        return new State(diagrams, modules);
    }

    private static List<JsonNode> items(McpTestClient client, NodeId node, String kind) {
        List<JsonNode> items = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            Map<String, Object> arguments = new HashMap<>(Map.of("target", node.value(), "kind",
                kind, "offset", offset, "refresh", offset == 0));
            if (kind.equals("DIAGRAM")) {
                arguments.put("type", "technical");
            }
            JsonNode page = structured(client.callTool("list_inventory", arguments, WAIT))
                .path("results").get(0).path("page");
            page.path("items").forEach(items::add);
            if (!page.has("nextOffset")) {
                return items;
            }
            offset = page.path("nextOffset").asInt();
        }
    }

    private static List<NodeId> nodes(LiveTarget live, GroupId group) {
        return live.loaded().config().effectiveNodes().stream().map(EffectiveNodeConfig::id)
            .filter(id -> id.group().equals(group)).toList();
    }

    private static JsonNode confirm(McpTestClient client, Map<String, Object> arguments,
        JsonNode challenge) {
        Map<String, Object> confirmed = new HashMap<>(arguments);
        confirmed.put("confirmationCode", challenge.path("confirmationCode").asString());
        return structured(client.callTool("deploy_release", confirmed, WAIT)).path("result");
    }

    /** The result of a write, confirming its preview with the code (the run is the approval). */
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

    private static List<String> texts(JsonNode array) {
        List<String> texts = new java.util.ArrayList<>();
        array.forEach(item -> texts.add(item.asString()));
        return texts;
    }

    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }

    /** The structured result; a failure shows only the error. */
    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean())
            .as(() -> result.path("content").toString()).isFalse();
        return result.path("structuredContent");
    }
}
