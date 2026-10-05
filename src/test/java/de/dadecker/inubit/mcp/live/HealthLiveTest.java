package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * T063: {@code get_health} against a real, <b>non-production</b> INUBIT server (opt-in,
 * Constitution III), through the production wiring ({@link TestWiring}). Run with
 * {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=test/node1 mvn verify -Plive};
 * configuration, credentials and the production refusal: {@link LiveTarget}.
 * Read-only: only the four health endpoints are called.
 */
@Tag("live")
@Timeout(60)
class HealthLiveTest {

    @Test
    void getHealthReportsTheLiveServerAsReachableReadyAnd81() {
        LiveTarget live = LiveTarget.resolve();
        NodeId serverId = live.node();

        try (TestWiring wiring = live.wiring();
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();

            long start = System.nanoTime();
            JsonNode result = client.callTool("get_health", Map.of("target", serverId.value()));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
            JsonNode health = result.path("structuredContent").path("reports").path(0);
            assertThat(health.path("node").asString()).isEqualTo(serverId.value());
            assertThat(health.path("reachable").asBoolean()).as(health.toString()).isTrue();
            assertThat(health.path("ready").asBoolean()).as(health.toString()).isTrue();
            assertThat(health.path("version").asString()).as(health.toString())
                .startsWith("8.1");
            assertThat(elapsed).as("SC-002").isLessThan(Duration.ofSeconds(5));
            // a short, secret-free summary for the person running the test
            System.err.println("[live] " + serverId + ": version "
                + health.path("version").asString() + ", status "
                + health.path("status").asString() + ", maintenanceMode "
                + health.path("maintenanceMode").asString() + ", load "
                + Optional.of(health.path("load")).filter(JsonNode::isObject)
                    .map(load -> load.path("memoryUsedPercent").asString() + "% memory")
                    .orElse("unavailable") + ", unavailable " + health.path("unavailable")
                + ", " + elapsed.toMillis() + " ms");
        }
    }
}
