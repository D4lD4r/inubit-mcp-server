package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * T078: {@code find_processes} and {@code query_logs} against a real, <b>non-production</b>
 * INUBIT server (opt-in, Constitution III) through the production wiring. Run with
 * {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=test/node1 mvn verify -Plive};
 * configuration, credentials and the production refusal: {@link LiveTarget}.
 * Read-only: two {@code POST /ibis/rest/log/…} queries
 * (quickstart V4, V6). Only counts and shapes are printed and asserted, never business data.
 */
@Tag("live")
@Timeout(120)
class DiagnosisLiveTest {

    @Test
    void failedProcessesOfTheLastDayAndTheNewestSystemLogEntries() {
        LiveTarget live = LiveTarget.resolve();
        NodeId serverId = live.node();

        try (TestWiring wiring = live.wiring();
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();

            long start = System.nanoTime();
            JsonNode processes = client.callTool("find_processes", Map.of(
                "target", serverId.value(), "states", List.of("ERROR"), "since", "PT24H",
                "limit", 20));
            Duration processElapsed = Duration.ofNanos(System.nanoTime() - start);
            JsonNode processPage = page(processes, serverId);
            List<JsonNode> instances = items(processPage);
            Instant dayAgo = Instant.now().minus(Duration.ofHours(24)).minusSeconds(60);
            assertThat(instances).hasSizeLessThanOrEqualTo(20);
            assertThat(processPage.path("total").asLong(-1))
                .isGreaterThanOrEqualTo(instances.size());
            List<Instant> since = new ArrayList<>();
            for (JsonNode instance : instances) {
                assertThat(instance.path("processId").asString()).matches("^[0-9]{1,19}$");
                assertThat(instance.path("state").asString()).isEqualTo("ERROR");
                assertThat(instance.path("hanging").asBoolean(true)).isFalse();
                Instant time = Instant.parse(instance.path("since").asString());
                assertThat(time).isAfter(dayAgo);
                since.add(time);
            }
            assertThat(since).isSortedAccordingTo((a, b) -> b.compareTo(a));

            start = System.nanoTime();
            JsonNode logs = client.callTool("query_logs", Map.of(
                "target", serverId.value(), "logType", "systemLog", "limit", 5));
            Duration logElapsed = Duration.ofNanos(System.nanoTime() - start);
            JsonNode logPage = page(logs, serverId);
            List<JsonNode> entries = items(logPage);
            assertThat(entries).hasSizeLessThanOrEqualTo(5);
            assertThat(logPage.path("total").asLong(-1)).isGreaterThanOrEqualTo(entries.size());
            List<Instant> timestamps = new ArrayList<>();
            for (JsonNode entry : entries) {
                assertThat(entry.path("logType").asString()).isEqualTo("systemLog");
                assertThat(entry.path("severity").asString()).isIn("ERROR", "INFO", "OTHER");
                timestamps.add(Instant.parse(entry.path("timestamp").asString()));
            }
            assertThat(timestamps).isSortedAccordingTo((a, b) -> b.compareTo(a));

            // a short summary without business data for the person running the test
            System.err.println("[live] " + serverId + ": find_processes(ERROR, PT24H) total "
                + processPage.path("total").asLong() + ", page " + instances.size() + ", "
                + processElapsed.toMillis() + " ms; query_logs(systemLog, 5) total "
                + logPage.path("total").asLong() + ", page " + entries.size() + ", "
                + logElapsed.toMillis() + " ms");
        }
    }

    /** The page of the single server; a failure shows only the error (no business data). */
    private static JsonNode page(JsonNode result, NodeId server) {
        assertThat(result.path("isError").asBoolean())
            .as(() -> result.path("content").toString()).isFalse();
        JsonNode entry = result.path("structuredContent").path("results").path(0);
        assertThat(entry.path("node").asString()).isEqualTo(server.value());
        assertThat(entry.has("error")).as(() -> entry.path("error").toString()).isFalse();
        return entry.path("page");
    }

    private static List<JsonNode> items(JsonNode page) {
        List<JsonNode> items = new ArrayList<>();
        page.path("items").forEach(items::add);
        return items;
    }
}
