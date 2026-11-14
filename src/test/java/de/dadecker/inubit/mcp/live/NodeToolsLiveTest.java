package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.application.nodetools.LeaseStore;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.live.NodeToolsLiveTestSupport.IoProcess;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * Feature 024 T067 (quickstart "Live check", SC-001, SC-002, SC-004): the unsupported node tools
 * against a real, <b>non-production</b> INUBIT server (opt-in, Constitution III) through the
 * production wiring. Configuration, credentials and the production refusal: {@link LiveTarget};
 * the checks: {@link NodeToolsLiveTestSupport}. Run with {@code INUBIT_MCP_PROFILE=<profile>
 * INUBIT_LIVE_NODE=test/node1 mvn verify -Plive -Dtest=NodeToolsLiveTest}; skipped without the
 * node.
 *
 * <ul>
 *   <li>Reads ({@code unsupported.nodeStatusRead} and {@code unsupported.nodeConfigRead}): the
 *       cluster's node list within 10 s, naming the answering node; that node's details, masked;
 *       all configuration values, masked.
 *   <li>I/O logging, only with {@code INUBIT_LIVE_IO_PROCESS=<workflow>/<processId>}
 *       ({@code unsupported.ioLogRead}): the listing of that run and its first message, masked.
 *   <li>Write part, only with {@code INUBIT_LIVE_LOG_LEVEL=approved} after the owner approved
 *       the run (write access and {@code unsupported.logLevelWrite}): one logger at {@code INFO}
 *       for 1 minute, the lease in {@code get_health}, the automatic reset within 2 minutes of
 *       the set, the lease {@code RESET} and the audit records of the set and of the reset.
 * </ul>
 *
 * <p>Prints {@code [live] …} lines with counts and timings only, never a node detail, value,
 * workflow, logger or message text.
 */
@Tag("live")
@Timeout(600)
class NodeToolsLiveTest {

    private static final Duration CALL_WAIT = Duration.ofSeconds(300);
    /** SC-001. */
    private static final Duration NODE_LIST_WITHIN = Duration.ofSeconds(10);
    /** The 1-minute lease plus at most one minute until its reset (SC-004). */
    private static final Duration RESET_WITHIN = Duration.ofMinutes(2);
    private static final Duration POLL = Duration.ofSeconds(5);
    private static final String SET = "set_temporary_log_level";

    @Test
    void theClusterNodesTheirDetailsAndTheConfigurationValuesAreRead() {
        LiveTarget live = LiveTarget.resolve();
        NodeId node = live.node();
        NodeToolsLiveTestSupport.requireOptIn(live, "unsupported.nodeStatusRead",
            EffectiveNodeConfig::nodeStatusRead);
        NodeToolsLiveTestSupport.requireOptIn(live, "unsupported.nodeConfigRead",
            EffectiveNodeConfig::nodeConfigRead);

        try (TestWiring wiring = live.wiring();
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            long start = System.nanoTime();
            JsonNode list = structured(client.callTool("list_cluster_nodes",
                Map.of("target", node.value()), CALL_WAIT));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            NodeToolsLiveTestSupport.assertMatchesSchema("list_cluster_nodes", list);
            NodeToolsLiveTestSupport.assertClusterNodes(list, node);
            assertThat(elapsed).as("SC-001: the node list").isLessThan(NODE_LIST_WITHIN);

            JsonNode details = structured(client.callTool("list_cluster_nodes",
                Map.of("target", node.value(), "nodeName", list.path("inubitNode").asString()),
                CALL_WAIT));
            NodeToolsLiveTestSupport.assertMatchesSchema("list_cluster_nodes", details);
            NodeToolsLiveTestSupport.assertDetails(details, live.scrubber());

            JsonNode config = structured(client.callTool("get_node_config",
                Map.of("target", node.value()), CALL_WAIT));
            NodeToolsLiveTestSupport.assertMatchesSchema("get_node_config", config);
            NodeToolsLiveTestSupport.assertNodeConfig(config, node, live.scrubber());

            System.err.println(NodeToolsLiveTestSupport.clusterSummary(node, list, elapsed,
                details.path("details").path("pairs").size()));
            System.err.println(NodeToolsLiveTestSupport.configSummary(node, config));
        }
    }

    @Test
    void theIoLoggingOfOneRunIsListedAndItsFirstMessageRead() {
        LiveTarget live = LiveTarget.resolve();
        IoProcess process = NodeToolsLiveTestSupport.ioProcess(System.getenv());
        NodeId node = live.node();
        NodeToolsLiveTestSupport.requireOptIn(live, "unsupported.ioLogRead",
            EffectiveNodeConfig::ioLogRead);

        try (TestWiring wiring = live.wiring();
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("target", node.value());
            arguments.put("workflow", process.workflow());
            arguments.put("processId", process.processId());
            JsonNode listing = structured(client.callTool("read_io_log", arguments, CALL_WAIT));
            NodeToolsLiveTestSupport.assertMatchesSchema("read_io_log", listing);
            NodeToolsLiveTestSupport.assertIoListing(listing, node, process);

            String ref = listing.path("entries").path(0).path("ref").asString();
            arguments.put("message", ref);
            JsonNode read = structured(client.callTool("read_io_log", arguments, CALL_WAIT));
            NodeToolsLiveTestSupport.assertMatchesSchema("read_io_log", read);
            NodeToolsLiveTestSupport.assertIoMessage(read, ref, live.scrubber());

            System.err.println(NodeToolsLiveTestSupport.ioSummary(node, listing, read));
        }
    }

    @Test
    void oneLoggerIsRaisedAtInfoForOneMinuteAndResetAutomatically()
        throws IOException, InterruptedException {
        LiveTarget live = NodeToolsLiveTestSupport.writeTarget(System.getenv(),
            Path.of(System.getProperty("user.home")),
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
        NodeId node = live.node();

        try (TestWiring wiring = live.wiring();
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            // as the launcher: the scheduler resets the lease at its expiry, and closing the
            // wiring resets it if the test fails before
            wiring.startLeases();
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("node", node.value());
            arguments.put("loggers", List.of(NodeToolsLiveTestSupport.LOGGER));
            arguments.put("level", "INFO");
            arguments.put("durationMinutes", 1);
            JsonNode challenge = structured(client.callTool(SET, arguments, CALL_WAIT))
                .path("challenge");
            assertThat(challenge.path("confirmationCode").asString())
                .as("the set asks for a confirmation").isNotBlank();
            NodeToolsLiveTestSupport.assertPreview(challenge);
            arguments.put("confirmationCode", challenge.path("confirmationCode").asString());
            Instant before = Instant.now();
            JsonNode set = structured(client.callTool(SET, arguments, CALL_WAIT)).path("result");
            long setAt = System.nanoTime();
            assertThat(set.path("outcome").asString()).as("outcome").isEqualTo("EXECUTED");
            NodeToolsLiveTestSupport.assertExpiresOneMinuteAfterTheSet(set, before,
                Instant.now());
            String leaseId = set.path("leaseId").asString();

            NodeToolsLiveTestSupport.assertLeaseShown(health(client, node), leaseId);

            LeaseStore leases = new LeaseStore(LeaseStore.defaultDirectory(wiring.userHome(),
                live.loaded().config().profile().name()), OsPrivateFileSystem.forHost(),
                Clock.systemUTC());
            LeaseState state = LeaseState.ACTIVE;
            while (state == LeaseState.ACTIVE
                && System.nanoTime() - setAt < RESET_WITHIN.toNanos()) {
                Thread.sleep(POLL.toMillis());
                state = state(leases, leaseId);
            }
            Duration untilReset = Duration.ofNanos(System.nanoTime() - setAt);
            assertThat(state).as("lease " + leaseId + " within " + RESET_WITHIN.toMinutes()
                + " minutes of the set").isEqualTo(LeaseState.RESET);
            assertThat(health(client, node).path("reports").path(0).has("temporaryLogLevels"))
                .as("get_health shows no lease after the reset").isFalse();

            // the cleanup stores the lease state before it appends the EXECUTED reset record
            Path audit = live.isolated(live.loaded().config(), null).auditDirectory();
            int records;
            while (true) {
                try {
                    records = NodeToolsLiveTestSupport.assertAudited(
                        NodeToolsLiveTestSupport.auditRecords(audit), leaseId,
                        set.path("auditId").asString());
                    break;
                } catch (AssertionError e) {
                    if (System.nanoTime() - setAt >= RESET_WITHIN.toNanos()) {
                        throw e;
                    }
                    Thread.sleep(POLL.toMillis());
                }
            }
            System.err.println(NodeToolsLiveTestSupport.writeSummary(node, untilReset, records));
        }
    }

    private static JsonNode health(McpTestClient client, NodeId node) {
        return structured(client.callTool("get_health", Map.of("target", node.value()),
            CALL_WAIT));
    }

    /** The state of lease {@code leaseId} in the store. */
    private static LeaseState state(LeaseStore leases, String leaseId) {
        return leases.read().stream()
            .filter(lease -> lease.leaseId().toString().equals(leaseId))
            .map(Lease::state)
            .findFirst()
            .orElseThrow(() -> new AssertionError("lease " + leaseId
                + " is not in the lease store"));
    }

    /** The structured result; a failure shows only the error. */
    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean())
            .as(() -> result.path("content").toString()).isFalse();
        return result.path("structuredContent");
    }
}
