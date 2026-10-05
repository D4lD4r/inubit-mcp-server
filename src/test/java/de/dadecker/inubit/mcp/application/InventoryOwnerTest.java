package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.application.InventoryService.ItemRequest;
import de.dadecker.inubit.mcp.application.InventoryService.ListRequest;
import de.dadecker.inubit.mcp.application.InventoryService.Listing;
import de.dadecker.inubit.mcp.application.InventoryService.Lookup;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * 002-T014: the inventory owner is configuration-only (FR-014, research D-10). A node without an
 * effective {@code inventory.owner} gets a per-node {@code NOT_CONFIGURED} error from the
 * inventory capabilities and is never contacted for them; other nodes and {@code get_health} are
 * unaffected.
 */
@Timeout(30)
class InventoryOwnerTest {

    private static final NodeId NODE1 = NodeId.parse("test/node1");
    private static final NodeId NODE2 = NodeId.parse("test/node2");
    private static final Terminology CUSTOM =
        new Terminology("Umgebung", "Umgebungen", "Knoten", "Knoten");
    private static final String NEXT_STEP = "set inventory.owner for test/node1 or in defaults";

    private final Map<NodeId, AtomicInteger> portCalls = new ConcurrentHashMap<>();

    private InventoryService service() {
        GatewayFactory gateways = new GatewayFactory() {
            @Override
            public Gateway forServer(NodeId node) {
                throw new AssertionError("not used");
            }

            @Override
            public MonitoringPort monitoring(NodeId node) {
                throw new AssertionError("not used");
            }

            @Override
            public ProcessQueryPort processes(NodeId node) {
                throw new AssertionError("not used");
            }

            @Override
            public LogPort logs(NodeId node) {
                throw new AssertionError("not used");
            }

            @Override
            public InventoryPort inventory(NodeId node) {
                portCalls.computeIfAbsent(node, id -> new AtomicInteger()).incrementAndGet();
                return new OneDiagram(node);
            }

            @Override
            public ProcessControlPort processControl(NodeId node) {
                throw new AssertionError("not used");
            }

            @Override
            public Optional<Gateway> knownGateway(NodeId node) {
                return Optional.empty();
            }
        };
        Map<NodeId, Optional<String>> owners = Map.of(NODE1, Optional.empty(),
            NODE2, Optional.of("INTEGRATION"));
        return new InventoryService(gateways, new TargetResolver(List.of(NODE1, NODE2), CUSTOM),
            new FanOut(), ResultLimiter.withDefaults(),
            new InventoryCache(new MutableClock(Instant.parse("2026-10-01T08:00:00Z")),
                node -> Duration.ofMinutes(10)),
            owners::get, node -> Duration.ofSeconds(5), node -> Duration.ofSeconds(120),
            Duration.ofSeconds(4), ResultJson::size, CUSTOM);
    }

    private static void assertNotConfigured(NodeResult<?> result) {
        assertThat(result.node()).isEqualTo(NODE1);
        assertThat(result.payload()).isEmpty();
        ToolError error = result.error().orElseThrow();
        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.nextStep()).isEqualTo(NEXT_STEP);
        assertThat(error.message()).contains("Knoten test/node1", "inventory.owner");
        assertThat(error.likelyCause()).contains("inventory.owner", "Knoten", "Umgebung",
            "no built-in default");
        assertThat(error.node()).contains(NODE1);
        assertThat(error.message() + error.likelyCause() + error.nextStep())
            .doesNotContainPattern("(?i)\\b(stages?|servers?|groups?|nodes?)\\b");
    }

    @Test
    void listingANodeWithoutOwnerIsNotConfiguredAndTheOtherNodeWorks() {
        List<NodeResult<Listing>> results = service().list(new ListRequest("test",
            InventoryKind.DIAGRAM, Optional.empty(), Optional.empty(), Optional.empty(), 0, 50,
            false));

        assertThat(results).hasSize(2);
        assertNotConfigured(results.get(0));
        assertThat(results.get(1).error()).isEmpty();
        assertThat(results.get(1).payload().orElseThrow().page().items())
            .extracting(InventoryItem::name).containsExactly("diagram-a");
        assertThat(portCalls).as("the owner-less node is never contacted")
            .doesNotContainKey(NODE1);
    }

    @Test
    void theItemOfANodeWithoutOwnerIsNotConfigured() {
        List<NodeResult<Lookup>> results = service().item(new ItemRequest("test/node1",
            InventoryKind.DIAGRAM, "diagram-a", false));

        assertThat(results).singleElement().satisfies(InventoryOwnerTest::assertNotConfigured);
        assertThat(portCalls).doesNotContainKey(NODE1);
    }

    @Test
    void getHealthIsUnaffectedByAMissingOwner() throws Exception {
        LoadedConfig loaded = new ConfigLoader(Map.of(), Path.of("/home/test"), false).parse("""
            profile:
              name: acme
            terminology:
              group: { singular: Umgebung, plural: Umgebungen }
              node: { singular: Knoten, plural: Knoten }
            groups:
              - name: test
                nodes:
                  - name: node1
                    baseUrl: https://127.0.0.1:9
                    timeout: PT2S
            """, Path.of("/home/test/acme.yaml"));
        Map<String, String> env = Map.of("INUBIT_ACME_TEST_USERNAME", "user",
            "INUBIT_ACME_TEST_PASSWORD", "owner-test-pw");
        SecretScrubber scrubber = new SecretScrubber();
        CredentialResolution credentials = new CredentialResolver(env, scrubber,
            loaded.config().credentialPrefix())
            .resolve(loaded.config().nodeIds());
        try (TestWiring wiring = TestWiring.of(loaded.config(), credentials, scrubber,
            path -> false, false)) {
            JsonNode inventory = json(handler(wiring, "list_inventory").handle(Map.of(
                "target", "test/node1", "kind", "DIAGRAM")));
            JsonNode health = json(handler(wiring, "get_health").handle(Map.of(
                "target", "test/node1")));

            assertThat(inventory.path("results").path(0).path("error").path("code").asString())
                .isEqualTo("NOT_CONFIGURED");
            JsonNode report = health.path("reports").path(0);
            assertThat(report.path("node").asString()).isEqualTo("test/node1");
            assertThat(report.path("reachable").asBoolean(true))
                .as("no INUBIT server listens on port 9").isFalse();
            assertThat(report.path("error").path("code").asString())
                .isNotEqualTo("NOT_CONFIGURED");
        }
    }

    private static ToolHandler handler(TestWiring wiring, String name) {
        return wiring.toolHandlers().stream().filter(h -> h.name().equals(name)).findFirst()
            .orElseThrow();
    }

    private static JsonNode json(Object payload) {
        return ResultJson.mapper().valueToTree(payload);
    }

    /** One diagram, owned by whatever owner the service passes. */
    private static final class OneDiagram implements InventoryPort {

        private final NodeId node;

        OneDiagram(NodeId node) {
            this.node = node;
        }

        @Override
        public List<InventoryItem> listDiagrams(String owner) {
            return List.of(InventoryItem.diagram(node, "diagram-a", "technical", "group-a",
                owner));
        }

        @Override
        public DiagramDetail diagramDetail(String owner, String name) {
            return new DiagramDetail(name, Optional.of("technical"), Optional.of("head"),
                List.of());
        }

        @Override
        public DiagramMetadata diagramMetadata(String name) {
            return DiagramMetadata.EMPTY;
        }

        @Override
        public VersionHistory versionHistory(String owner, String type, String group) {
            return new VersionHistory(Map.of(), Map.of());
        }

        @Override
        public List<ModuleEntry> listModules(String owner) {
            return List.of();
        }
    }
}
