package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * T098: {@code list_inventory} and {@code get_inventory_item} against a real,
 * <b>non-production</b> INUBIT server (opt-in, Constitution III) through the production wiring.
 * Run with {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=test/node1 mvn verify -Plive};
 * configuration, credentials and the production refusal: {@link LiveTarget}.
 * Read-only: {@code GET /ibis/rest/model/…} and two
 * StartCLI exports into a private temporary directory (module index ≈ 10–15 s, the history of one
 * diagram group: {@code INUBIT_LIVE_GROUP}, else the group of the first technical diagram, so
 * that the test works on every node), plus the history export of the group of a workflow that
 * uses an XSLT Converter module (T126: module usage from the workflow nodes). Only counts,
 * timings and shapes are printed, never workflow, module or group names (quickstart V7, V7a,
 * V8).
 */
@Tag("live")
@Timeout(600)
class InventoryLiveTest {

    /** The group of the DEV spike S-6b; only its count is reported (it may not exist on QA). */
    private static final String SPIKE_GROUP = "GRP-41";
    /** Optional override of the diagram group whose history is exported. */
    static final String GROUP_VARIABLE = "INUBIT_LIVE_GROUP";
    private static final Duration EXPORT_WAIT = Duration.ofSeconds(280);

    @Test
    void diagramsModulesAndOneDetailComeFromTheServerAndTheCache() {
        LiveTarget live = LiveTarget.resolve();
        NodeId serverId = live.node();
        String target = serverId.value();

        try (TestWiring wiring = live.wiring();
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();

            long start = System.nanoTime();
            JsonNode technical = entry(client.callTool("list_inventory", Map.of("target",
                target, "kind", "DIAGRAM", "type", "technical", "limit", 100), EXPORT_WAIT),
                serverId).path("page");
            Duration diagramElapsed = elapsed(start);
            assertThat(technical.path("total").asInt()).isPositive();
            Optional<String> override = Optional.ofNullable(System.getenv(GROUP_VARIABLE))
                .filter(value -> !value.isBlank()).map(String::strip);
            String group = override.orElseGet(() -> firstExportableGroup(technical));
            int spikeGroup = entry(client.callTool("list_inventory", Map.of("target", target,
                "kind", "DIAGRAM", "group", SPIKE_GROUP, "limit", 1), EXPORT_WAIT), serverId)
                .path("page").path("total").asInt();

            JsonNode diagramPage = entry(client.callTool("list_inventory", Map.of("target",
                target, "kind", "DIAGRAM", "type", "technical", "group", group, "limit", 100),
                EXPORT_WAIT), serverId).path("page");
            assertThat(diagramPage.path("total").asInt()).as("diagrams of the chosen group")
                .isPositive();
            diagramPage.path("items").forEach(item -> {
                assertThat(item.path("type").asString()).isEqualTo("technical");
                assertThat(item.path("group").asString()).isEqualToIgnoringCase(group);
            });

            start = System.nanoTime();
            JsonNode modules = entry(client.callTool("list_inventory", Map.of("target", target,
                "kind", "MODULE", "limit", 5), EXPORT_WAIT), serverId);
            Duration moduleElapsed = elapsed(start);
            assertThat(modules.path("page").path("total").asInt()).isPositive();
            Instant collectedAt = Instant.parse(modules.path("collectedAt").asString());

            start = System.nanoTime();
            JsonNode cached = entry(client.callTool("list_inventory", Map.of("target", target,
                "kind", "MODULE", "offset", 5, "limit", 5), EXPORT_WAIT), serverId);
            Duration cachedElapsed = elapsed(start);
            assertThat(Instant.parse(cached.path("collectedAt").asString()))
                .as("served from the cache").isEqualTo(collectedAt);
            assertThat(cached.path("page").path("total").asInt())
                .isEqualTo(modules.path("page").path("total").asInt());

            // T126 / F1: module usage from the workflow nodes; an XSLT Converter module is used
            // by at least one workflow and its version history is available through it
            JsonNode xslt = entry(client.callTool("list_inventory", Map.of("target", target,
                "kind", "MODULE", "type", "XSLT Converter", "limit", 100), EXPORT_WAIT),
                serverId);
            assertThat(xslt.path("usageComplete").asBoolean()).as("usage index complete")
                .isTrue();
            List<JsonNode> xsltItems = new ArrayList<>();
            xslt.path("page").path("items").forEach(xsltItems::add);
            List<JsonNode> used = xsltItems.stream()
                .filter(module -> module.path("workflowCount").asInt() >= 1).toList();
            assertThat(used).as("XSLT Converter modules used by a workflow").isNotEmpty();
            start = System.nanoTime();
            JsonNode usedDetail = entry(client.callTool("get_inventory_item", Map.of("target",
                target, "kind", "MODULE", "name", used.get(0).path("name").asString()),
                EXPORT_WAIT), serverId).path("item");
            Duration usedDetailElapsed = elapsed(start);
            assertThat(usedDetail.path("usageComplete").asBoolean()).isTrue();
            assertThat(usedDetail.path("workflowCount").asInt()).isPositive();
            assertThat(usedDetail.path("unavailable")).as(() -> usedDetail.path("unavailable")
                .toString()).isEmpty();
            assertThat(usedDetail.path("versions")).as("history via a using workflow")
                .isNotEmpty();

            String name = diagramPage.path("items").get(0).path("name").asString();
            start = System.nanoTime();
            JsonNode detail = entry(client.callTool("get_inventory_item", Map.of("target",
                target, "kind", "DIAGRAM", "name", name), EXPORT_WAIT), serverId);
            Duration detailElapsed = elapsed(start);
            JsonNode item = detail.path("item");
            assertThat(item.path("unavailable")).as(() -> item.path("unavailable").toString())
                .isEmpty();
            assertThat(item.path("versions")).isNotEmpty();
            assertThat(item.path("versions").get(0).path("version").asInt())
                .isGreaterThanOrEqualTo(item.path("versions").get(item.path("versions").size() - 1)
                    .path("version").asInt());
            assertThat(item.has("active")).isTrue();
            assertThat(item.has("activeVersion")).isFalse();

            // a short summary without business data for the person running the test
            System.err.println("[live] " + serverId + ": list_inventory(DIAGRAM, technical) total "
                + technical.path("total").asInt() + ", " + diagramElapsed.toMillis() + " ms; "
                + SPIKE_GROUP + " diagrams " + spikeGroup + "; group "
                + (override.isPresent() ? "from " + GROUP_VARIABLE : "of the first technical"
                    + " diagram") + " with " + diagramPage.path("total").asInt()
                + " technical diagrams; list_inventory(MODULE) total "
                + modules.path("page").path("total").asInt() + ", "
                + moduleElapsed.toMillis() + " ms, cached repeat " + cachedElapsed.toMillis()
                + " ms; get_inventory_item(first diagram of that group) "
                + count(item.path("versions").size(), "version") + ", "
                + count(item.path("modules").size(), "module") + ", " + detailElapsed.toMillis()
                + " ms; XSLT Converter modules " + xslt.path("page").path("total").asInt()
                + " (first page: " + used.size() + " used, " + (xsltItems.size() - used.size())
                + " unused); get_inventory_item(first used XSLT module) "
                + count(usedDetail.path("workflowCount").asInt(), "workflow") + ", "
                + count(usedDetail.path("versions").size(), "version") + ", "
                + usedDetailElapsed.toMillis() + " ms");
        }
    }

    /** The group of the first technical diagram that the CLI quoting rule can export. */
    private static String firstExportableGroup(JsonNode page) {
        for (JsonNode item : page.path("items")) {
            String group = item.path("group").asString();
            if (CliCommand.VALUE.matcher(group).matches()) {
                return group;
            }
        }
        throw new AssertionError("no technical diagram with an exportable group on the first"
            + " page; set " + GROUP_VARIABLE);
    }

    /** {@code 1 workflow}, {@code 2 workflows}. */
    private static String count(int number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }

    private static Duration elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    /** The entry of the single server; a failure shows only the error (no business data). */
    private static JsonNode entry(JsonNode result, NodeId server) {
        assertThat(result.path("isError").asBoolean())
            .as(() -> result.path("content").toString()).isFalse();
        JsonNode entry = result.path("structuredContent").path("results").path(0);
        assertThat(entry.path("node").asString()).isEqualTo(server.value());
        assertThat(entry.has("error")).as(() -> entry.path("error").toString()).isFalse();
        assertThat(entry.has("collectedAt")).isTrue();
        return entry;
    }
}
