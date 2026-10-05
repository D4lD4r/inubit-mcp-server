package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.InventoryService.ItemRequest;
import de.dadecker.inubit.mcp.application.InventoryService.ListRequest;
import de.dadecker.inubit.mcp.application.InventoryService.Listing;
import de.dadecker.inubit.mcp.application.InventoryService.Lookup;
import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryDetail;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailableInventoryPart;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * T087: {@code list_inventory} and {@code get_inventory_item} logic with fake ports (FR-014 –
 * FR-017, research R-11): filters, sorting, paging, caching, detail assembly, NOT_FOUND with
 * similar names, partial results and bounds.
 */
@Timeout(30)
class InventoryServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final NodeId QA2 = NodeId.parse("qa/node2");
    private static final List<NodeId> SERVERS = List.of(DEV, QA1, QA2);
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(120);
    /** How long a stopped export may take to die (2 × the CLI kill grace). */
    private static final Duration KILL_GRACES = Duration.ofSeconds(4);

    private final MutableClock clock = new MutableClock(T0);
    private final Map<NodeId, FakeInventory> ports = new ConcurrentHashMap<>();
    private ResultLimiter limiter = ResultLimiter.withDefaults();

    private FakeInventory port(NodeId server) {
        return ports.computeIfAbsent(server, FakeInventory::new);
    }

    private InventoryService service() {
        GatewayFactory gateways = new GatewayFactory() {
            @Override
            public Gateway forServer(NodeId server) {
                throw new AssertionError("US3 must not wait for a version detection");
            }

            @Override
            public MonitoringPort monitoring(NodeId server) {
                throw new AssertionError("US3 does not use the monitoring");
            }

            @Override
            public ProcessQueryPort processes(NodeId server) {
                throw new AssertionError("US3 does not query processes");
            }

            @Override
            public LogPort logs(NodeId server) {
                throw new AssertionError("US3 does not query logs");
            }

            @Override
            public InventoryPort inventory(NodeId server) {
                return port(server);
            }

            @Override
            public ProcessControlPort processControl(NodeId server) {
                throw new AssertionError("no process control here");
            }

            @Override
            public Optional<Gateway> knownGateway(NodeId server) {
                return Optional.empty();
            }
        };
        InventoryCache cache = new InventoryCache(clock, server -> Duration.ofMinutes(10));
        return new InventoryService(gateways, new TargetResolver(SERVERS), new FanOut(), limiter,
            cache, server -> Optional.of("OWNERS"), server -> TIMEOUT, server -> EXPORT_TIMEOUT,
            KILL_GRACES, ResultJson::size, Terminology.DEFAULT);
    }

    private static ListRequest list(String target, InventoryKind kind) {
        return new ListRequest(target, kind, Optional.empty(), Optional.empty(),
            Optional.empty(), 0, 50, false);
    }

    private static ListRequest filtered(String target, InventoryKind kind,
        String nameContains, String type, String group) {
        return new ListRequest(target, kind, Optional.ofNullable(nameContains),
            Optional.ofNullable(type), Optional.ofNullable(group), 0, 50, false);
    }

    private static ItemRequest item(String target, InventoryKind kind, String name) {
        return new ItemRequest(target, kind, name, false);
    }

    private static Instant berlin(String isoUtc) {
        return Instant.parse(isoUtc);
    }

    private static VersionEntry version(int number, String utc, String comment,
        String... tags) {
        return new VersionEntry(number, Optional.of("user1"), Optional.of(berlin(utc)),
            Optional.of(comment), Optional.empty(), List.of(tags));
    }

    private static ModuleRef node(String name, String id) {
        return new ModuleRef(name, "twWorkflowConnector", id);
    }

    /** DEV-like data: six diagrams in four groups, three modules, one group history. */
    private void standardData(NodeId server) {
        FakeInventory port = port(server);
        port.diagrams = List.of(
            InventoryItem.diagram(server, "Workflow-0101", "technical", "GRP-41",
                "OWNERS"),
            InventoryItem.diagram(server, "Workflow-Order-0001", "technical", "GRP-35", "OWNERS"),
            InventoryItem.diagram(server, "Workflow-0012", "bpd", "OWNERS",
                "OWNERS"),
            InventoryItem.diagram(server, "billing order import", "technical", "GRP-35",
                "OWNERS"),
            InventoryItem.diagram(server, "Workflow-Map-0001", "systemdiagram", "GRP-19", "OWNERS"),
            InventoryItem.diagram(server, "Workflow-0110", "technical", "GRP-41",
                "OWNERS"));
        port.details.put("Workflow-0101", new InventoryPort.DiagramDetail(
            "Workflow-0101", Optional.of("technical"), Optional.of("head"),
            List.of(node("Module-0030", "133"), node("Module-0032", "102"))));
        port.details.put("Workflow-0110", new InventoryPort.DiagramDetail(
            "Workflow-0110", Optional.of("technical"), Optional.of("head"), List.of()));
        port.details.put("Workflow-Order-0001", new InventoryPort.DiagramDetail(
            "Workflow-Order-0001", Optional.of("technical"), Optional.of("head"), List.of()));
        port.details.put("billing order import", new InventoryPort.DiagramDetail(
            "billing order import", Optional.of("technical"), Optional.of("head"), List.of()));
        port.metadata.put("Workflow-0101", new InventoryPort.DiagramMetadata(
            Optional.of(true), Optional.of("head comment"), Optional.of("OWNERS")));
        port.histories.put("GRP-41", new InventoryPort.VersionHistory(
            Map.of("Workflow-0101", List.of(
                    version(2, "2026-01-23T06:54:27Z", "second", "TAG-02"),
                    version(1, "2025-10-20T09:31:48Z", "first")),
                "Workflow-0110", List.of(version(1, "2026-02-25T13:15:24Z", "only"))),
            Map.of("Module-0030", List.of(version(3, "2025-11-13T07:11:21Z", "m3"),
                version(1, "2025-10-10T09:44:08Z", "m1")))));
        port.histories.put("GRP-35", new InventoryPort.VersionHistory(
            Map.of("Workflow-Order-0001", List.of(version(1, "2026-03-01T10:00:00Z", "o"))),
            Map.of()));
        // Module-0030 is used only through a node of Workflow-0101 (no WorkflowName in
        // the module export, F1); Http-Out is a connector bound to Workflow-Order-0001
        port.modules = List.of(
            module(server, "Module-0030", "XSLT Converter", Optional.empty()),
            module(server, "Unused-XSLT", "XSLT Converter", Optional.empty()),
            module(server, "Http-Out", "HTTP Connector", Optional.of("Workflow-Order-0001")));
    }

    private static InventoryPort.ModuleEntry module(NodeId server, String name, String plugin,
        Optional<String> connectorWorkflow) {
        return new InventoryPort.ModuleEntry(InventoryItem.module(server, name, plugin, plugin,
            "OWNERS", Optional.of(true), Optional.of(Instant.parse("2025-11-13T07:00:00Z"))),
            Optional.of("module comment"), Optional.of("user comment"),
            new ConnectorFlags(false, true, false), connectorWorkflow);
    }

    private static <T> T payload(NodeResult<T> result) {
        assertThat(result.error()).as(() -> result.error().toString()).isEmpty();
        return result.payload().orElseThrow();
    }

    private static List<String> names(Listing listing) {
        return listing.page().items().stream().map(InventoryItem::name).toList();
    }

    // --- list_inventory -------------------------------------------------------------------

    @Test
    void diagramsAreFilteredByNameCaseInsensitivelyTypeAndGroupAndSortedByName() {
        standardData(DEV);

        Listing byName = payload(service().list(filtered("dev/node1", InventoryKind.DIAGRAM,
            "ORDER", "Technical", null)).get(0));
        Listing byGroup = payload(service().list(filtered("dev/node1", InventoryKind.DIAGRAM,
            null, null, "grp-41")).get(0));

        assertThat(names(byName)).containsExactly("billing order import", "Workflow-Order-0001");
        assertThat(names(byGroup)).containsExactly("Workflow-0101", "Workflow-0110");
        assertThat(byName.page().items().get(1)).isEqualTo(InventoryItem.diagram(DEV,
            "Workflow-Order-0001", "technical", "GRP-35", "OWNERS"));
    }

    @Test
    void theListIsPagedWithTotalAndNextOffset() {
        standardData(DEV);

        Listing page = payload(service().list(new ListRequest("dev/node1",
            InventoryKind.DIAGRAM, Optional.empty(), Optional.empty(), Optional.empty(), 1, 2,
            false)).get(0));

        assertThat(names(page)).containsExactly("Workflow-0012", "Workflow-0101");
        assertThat(page.page().total()).hasValue(6);
        assertThat(page.page().nextOffset()).hasValue(3);
        assertThat(page.page().truncated()).isFalse();
    }

    @Test
    void modulesAreFilteredByPluginTypeAndModuleGroup() {
        standardData(DEV);

        Listing xslt = payload(service().list(filtered("dev/node1", InventoryKind.MODULE, null,
            "xslt converter", null)).get(0));
        Listing http = payload(service().list(filtered("dev/node1", InventoryKind.MODULE, null,
            null, "HTTP Connector")).get(0));

        assertThat(names(xslt)).containsExactly("Module-0030", "Unused-XSLT");
        assertThat(xslt.page().items().get(0).active()).contains(true);
        assertThat(names(http)).containsExactly("Http-Out");
    }

    // --- module usage (T126 / F1) -----------------------------------------------------------

    @Test
    void moduleUsageComesFromTheWorkflowNodesAndTheConnectorWorkflow() {
        standardData(DEV);

        Listing listing = payload(service().list(list("dev/node1", InventoryKind.MODULE))
            .get(0));

        assertThat(listing.usageComplete()).contains(true);
        Map<String, InventoryItem> byName = listing.page().items().stream()
            .collect(java.util.stream.Collectors.toMap(InventoryItem::name, item -> item));
        assertThat(byName.get("Module-0030").workflows()).contains(List.of("Workflow-0101"));
        assertThat(byName.get("Module-0030").workflowCount()).contains(1);
        assertThat(byName.get("Http-Out").workflows()).contains(List.of("Workflow-Order-0001"));
        assertThat(byName.get("Unused-XSLT").workflows()).as("genuinely unused")
            .contains(List.of());
        assertThat(byName.get("Unused-XSLT").workflowCount()).contains(0);
        assertThat(port(DEV).detailCallNames).as("every technical workflow, nothing else")
            .containsExactlyInAnyOrder("Workflow-0101", "Workflow-Order-0001",
                "billing order import", "Workflow-0110");
    }

    @Test
    void aDiagramListHasNoUsageFlag() {
        standardData(DEV);

        Listing listing = payload(service().list(list("dev/node1", InventoryKind.DIAGRAM))
            .get(0));

        assertThat(listing.usageComplete()).isEmpty();
        assertThat(listing.page().items()).allSatisfy(item -> {
            assertThat(item.workflows()).isEmpty();
            assertThat(item.workflowCount()).isEmpty();
        });
        assertThat(port(DEV).detailCalls).hasValue(0);
    }

    @Test
    void aListItemNamesAtMostFiveWorkflowsAndCountsThemAll() {
        standardData(DEV);
        List<InventoryItem> diagrams = new ArrayList<>(port(DEV).diagrams);
        for (int i = 1; i <= 7; i++) {
            String name = "W-" + i;
            diagrams.add(InventoryItem.diagram(DEV, name, "technical", "G", "OWNERS"));
            port(DEV).details.put(name, new InventoryPort.DiagramDetail(name,
                Optional.of("technical"), Optional.of("head"), List.of(node("Unused-XSLT", "1"))));
        }
        port(DEV).diagrams = diagrams;

        InventoryItem item = payload(service().list(filtered("dev/node1", InventoryKind.MODULE,
            "Unused", null, null)).get(0)).page().items().get(0);

        assertThat(item.workflowCount()).contains(7);
        assertThat(item.workflows()).contains(List.of("W-1", "W-2", "W-3", "W-4", "W-5"));
    }

    @Test
    void anIncompleteUsageIndexIsFlaggedNeverClaimsUnusedAndIsBuiltAgainNextTime() {
        standardData(DEV);
        port(DEV).detailFailures.put("billing order import", ToolError.of(ErrorCode.TIMEOUT, "slow",
            "c", "s").withNode(DEV));
        InventoryService service = service();

        Listing first = payload(service.list(list("dev/node1", InventoryKind.MODULE)).get(0));
        int callsAfterFirst = port(DEV).detailCalls.get();
        Listing second = payload(service.list(list("dev/node1", InventoryKind.MODULE)).get(0));

        assertThat(first.usageComplete()).contains(false);
        assertThat(second.usageComplete()).contains(false);
        assertThat(port(DEV).detailCalls.get()).as("an incomplete index is not cached")
            .isEqualTo(callsAfterFirst * 2);
        assertThat(port(DEV).moduleLoads).as("the module list is cached").hasValue(1);
    }

    @Test
    void aFailedDiagramListStillListsTheModulesWithIncompleteUsage() {
        standardData(DEV);
        port(DEV).listFailure = ToolError.of(ErrorCode.UNREACHABLE, "down", "c", "s")
            .withNode(DEV);

        Listing listing = payload(service().list(list("dev/node1", InventoryKind.MODULE))
            .get(0));

        assertThat(listing.usageComplete()).contains(false);
        assertThat(listing.page().items()).extracting(InventoryItem::name)
            .containsExactly("Http-Out", "Module-0030", "Unused-XSLT");
        assertThat(listing.page().items().get(0).workflows())
            .contains(List.of("Workflow-Order-0001"));
    }

    @Test
    void theCompleteUsageIndexIsCachedAndRefreshBuildsItAgain() {
        standardData(DEV);
        InventoryService service = service();

        service.list(list("dev/node1", InventoryKind.MODULE));
        service.item(item("dev/node1", InventoryKind.MODULE, "Module-0030"));
        int cached = port(DEV).detailCalls.get();
        service.list(new ListRequest("dev/node1", InventoryKind.MODULE, Optional.empty(),
            Optional.empty(), Optional.empty(), 0, 50, true));

        assertThat(cached).isEqualTo(4);
        assertThat(port(DEV).detailCalls).hasValue(8);
    }

    @Test
    void listsAreCachedStateCollectedAtAndRefreshLoadsAgain() {
        standardData(DEV);
        InventoryService service = service();

        Listing first = payload(service.list(list("dev/node1", InventoryKind.MODULE)).get(0));
        clock.advance(Duration.ofMinutes(1));
        Listing cached = payload(service.list(list("dev/node1", InventoryKind.MODULE)).get(0));
        Listing refreshed = payload(service.list(new ListRequest("dev/node1",
            InventoryKind.MODULE, Optional.empty(), Optional.empty(), Optional.empty(), 0, 50,
            true)).get(0));

        assertThat(first.collectedAt()).isEqualTo(T0);
        assertThat(cached.collectedAt()).isEqualTo(T0);
        assertThat(refreshed.collectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(port(DEV).moduleLoads).hasValue(2);
    }

    @Test
    void aStageGivesOneResultPerServerInConfigOrderAndAFailedServerOnlyItsError() {
        standardData(QA1);
        port(QA2).listFailure = ToolError.of(ErrorCode.UNREACHABLE, "down", "c", "s")
            .withNode(QA2);

        List<NodeResult<Listing>> results = service().list(list("qa",
            InventoryKind.DIAGRAM));

        assertThat(results).extracting(NodeResult::node).containsExactly(QA1, QA2);
        assertThat(payload(results.get(0)).page().items()).hasSize(6);
        assertThat(results.get(1).payload()).isEmpty();
        assertThat(results.get(1).error().orElseThrow().code()).isEqualTo(ErrorCode.UNREACHABLE);
    }

    @Test
    void anUnknownTargetOrInvalidPagingFailsTheCallBeforeAnyServerIsContacted() {
        assertThatThrownBy(() -> service().list(list("test", InventoryKind.DIAGRAM)))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN));
        assertThatThrownBy(() -> service().list(new ListRequest("dev", InventoryKind.DIAGRAM,
            Optional.empty(), Optional.empty(), Optional.empty(), -1, 50, false)))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> service().item(item("nope/x", InventoryKind.DIAGRAM, "W")))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN));
        assertThat(ports).isEmpty();
    }

    @Test
    void longOrEscapeHeavyTextIsCutSoThatEveryItemFitsItsBound() {
        String control = "\u0001".repeat(ItemBounds.MAX_FIELD_CHARS);
        port(DEV).diagrams = List.of(
            InventoryItem.diagram(DEV, "N" + "x".repeat(500), control, control, control),
            InventoryItem.diagram(DEV, "short", "technical", "G", "OWNERS"));

        Listing listing = payload(service().list(list("dev/node1", InventoryKind.DIAGRAM))
            .get(0));

        assertThat(listing.page().items()).hasSize(2).allSatisfy(item ->
            assertThat(ResultJson.size(item)).isLessThanOrEqualTo(ItemBounds.MAX_ITEM_CHARS));
        assertThat(listing.page().items().get(0).name()).hasSizeLessThanOrEqualTo(
            ItemBounds.MAX_FIELD_CHARS).endsWith(ItemBounds.TRUNCATION_MARKER);
        assertThat(listing.page().truncated()).isTrue();
    }

    // --- get_inventory_item: diagrams -------------------------------------------------------

    @Test
    void aDiagramDetailCombinesListEntryNodesExportMetadataAndTheGroupsHistory() {
        standardData(DEV);
        InventoryService service = service();
        service.list(list("dev/node1", InventoryKind.DIAGRAM));
        clock.advance(Duration.ofMinutes(2));

        NodeResult<Lookup> result = service.item(item("dev/node1", InventoryKind.DIAGRAM,
            "Workflow-0101")).get(0);

        Lookup lookup = payload(result);
        InventoryDetail detail = lookup.item();
        assertThat(lookup.collectedAt()).as("the oldest cached part").isEqualTo(T0);
        assertThat(detail.node()).isEqualTo(DEV);
        assertThat(detail.kind()).isEqualTo(InventoryKind.DIAGRAM);
        assertThat(detail.name()).isEqualTo("Workflow-0101");
        assertThat(detail.type()).contains("technical");
        assertThat(detail.group()).contains("GRP-41");
        assertThat(detail.owner()).isEqualTo("OWNERS");
        assertThat(detail.active()).contains(true);
        assertThat(detail.checkinComment()).contains("head comment");
        assertThat(detail.modules().orElseThrow()).containsExactly(node("Module-0030", "133"),
            node("Module-0032", "102"));
        assertThat(detail.versions().orElseThrow()).extracting(VersionEntry::version)
            .containsExactly(2, 1);
        assertThat(detail.versions().orElseThrow().get(0).tags()).containsExactly("TAG-02");
        assertThat(detail.lastChange()).as("check-in time of the newest version")
            .contains(Instant.parse("2026-01-23T06:54:27Z"));
        assertThat(detail.unavailable()).isEmpty();
        assertThat(detail.similarNames()).isEmpty();
        assertThat(detail.connector()).isEmpty();
        assertThat(detail.truncated()).isFalse();
        assertThat(port(DEV).historyCalls).containsExactly("OWNERS|technical|GRP-41");
    }

    @Test
    void theVersionHistoryIsCachedPerDiagramGroup() {
        standardData(DEV);
        InventoryService service = service();

        service.item(item("dev/node1", InventoryKind.DIAGRAM, "Workflow-0101"));
        service.item(item("dev/node1", InventoryKind.DIAGRAM, "Workflow-0110"));
        service.item(item("dev/node1", InventoryKind.DIAGRAM, "Workflow-Order-0001"));
        service.item(new ItemRequest("dev/node1", InventoryKind.DIAGRAM, "Workflow-0101",
            true));

        assertThat(port(DEV).historyCalls).containsExactly("OWNERS|technical|GRP-41",
            "OWNERS|technical|GRP-35", "OWNERS|technical|GRP-41");
        assertThat(port(DEV).diagramLoads).as("refresh reloads the list too").hasValue(2);
    }

    @Test
    void aFailedVersionHistoryIsReportedAsUnavailableNextToTheRestOfTheDetail() {
        standardData(DEV);
        port(DEV).historyFailure = ToolError.of(ErrorCode.CLI_UNAVAILABLE, "No CLI home",
            "cliHome is not set", "Set cliHome").withNode(DEV);

        NodeResult<Lookup> result = service().item(item("dev/node1", InventoryKind.DIAGRAM,
            "Workflow-0101")).get(0);

        InventoryDetail detail = payload(result).item();
        assertThat(detail.versions()).isEmpty();
        assertThat(detail.lastChange()).isEmpty();
        assertThat(detail.active()).contains(true);
        assertThat(detail.unavailable()).containsExactly(new UnavailableInventoryPart(
            UnavailableInventoryPart.VERSIONS, "CLI_UNAVAILABLE: No CLI home",
            Optional.of("cliHome is not set"), Optional.of("Set cliHome")));
    }

    @Test
    void aDiagramMissingFromItsGroupExportIsReportedAsUnavailable() {
        standardData(DEV);
        port(DEV).histories.put("GRP-35",
            new InventoryPort.VersionHistory(Map.of(), Map.of()));

        InventoryDetail detail = payload(service().item(item("dev/node1",
            InventoryKind.DIAGRAM, "Workflow-Order-0001")).get(0)).item();

        assertThat(detail.versions()).isEmpty();
        assertThat(detail.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailableInventoryPart.VERSIONS);
            assertThat(part.reason()).startsWith("UNEXPECTED_RESPONSE: ").contains("GRP-35");
        });
    }

    @Test
    void aRestFailureOfTheDetailFailsTheServer() {
        standardData(DEV);
        port(DEV).detailFailure = ToolError.of(ErrorCode.UNREACHABLE, "down", "c", "s")
            .withNode(DEV);

        NodeResult<Lookup> result = service().item(item("dev/node1", InventoryKind.DIAGRAM,
            "Workflow-0101")).get(0);

        assertThat(result.payload()).isEmpty();
        assertThat(result.error().orElseThrow().code()).isEqualTo(ErrorCode.UNREACHABLE);
    }

    @Test
    void anUnknownNameIsNotFoundWithUpToFiveSimilarNames() {
        standardData(DEV);
        List<InventoryItem> many = new ArrayList<>(port(DEV).diagrams);
        for (int i = 1; i <= 8; i++) {
            many.add(InventoryItem.diagram(DEV, "Workflow-0101" + i, "technical", "G", "OWNERS"));
        }
        port(DEV).diagrams = many;

        NodeResult<Lookup> result = service().item(item("dev/node1", InventoryKind.DIAGRAM,
            "workflow-0101")).get(0);
        NodeResult<Lookup> nothingSimilar = service().item(item("dev/node1",
            InventoryKind.DIAGRAM, "zzz")).get(0);

        ToolError error = result.error().orElseThrow();
        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains("workflow-0101", "OWNERS");
        Lookup lookup = result.payload().orElseThrow();
        assertThat(lookup.collectedAt()).isEqualTo(T0);
        assertThat(lookup.item()).isEqualTo(InventoryDetail.notFound(DEV, InventoryKind.DIAGRAM,
            "workflow-0101", "OWNERS", lookup.item().similarNames().orElseThrow()));
        assertThat(lookup.item().similarNames().orElseThrow()).hasSize(5)
            .startsWith("Workflow-0101", "Workflow-01011");
        assertThat(nothingSimilar.payload().orElseThrow().item().similarNames())
            .contains(List.of());
        assertThat(port(DEV).detailCalls).hasValue(0);
        assertThat(port(DEV).historyCalls).isEmpty();
    }

    @Test
    void theDetailHasNoActiveVersionField() {
        standardData(DEV);

        Lookup lookup = payload(service().item(item("dev/node1", InventoryKind.DIAGRAM,
            "Workflow-0101")).get(0));

        JsonNode json = ResultJson.mapper().valueToTree(lookup.item());
        assertThat(json.propertyNames()).doesNotContain("activeVersion")
            .contains("active", "versions");
    }

    @Test
    void aStageTargetGivesTheSameStructureOnEveryServer() {
        standardData(QA1);
        standardData(QA2);

        List<NodeResult<Lookup>> results = service().item(item("qa", InventoryKind.DIAGRAM,
            "Workflow-0101"));

        assertThat(results).extracting(NodeResult::node).containsExactly(QA1, QA2);
        JsonNode first = ResultJson.mapper().valueToTree(payload(results.get(0)).item());
        JsonNode second = ResultJson.mapper().valueToTree(payload(results.get(1)).item());
        assertThat(first.propertyNames()).containsExactlyElementsOf(second.propertyNames());
        assertThat(((tools.jackson.databind.node.ObjectNode) first).without("node"))
            .isEqualTo(((tools.jackson.databind.node.ObjectNode) second).without("node"));
    }

    @Test
    void aLongHistoryKeepsTheNewestVersionsWithinTheBudget() {
        standardData(DEV);
        List<VersionEntry> versions = new ArrayList<>();
        for (int i = 400; i >= 1; i--) {
            versions.add(new VersionEntry(i, Optional.of("user1"),
                Optional.of(T0.minusSeconds(i)), Optional.of("c".repeat(500)),
                Optional.of("u\u0001".repeat(150)), List.of("T-" + i)));
        }
        port(DEV).histories.put("GRP-41", new InventoryPort.VersionHistory(
            Map.of("Workflow-0101", versions), Map.of()));
        limiter = new ResultLimiter(100, 20_000);

        InventoryDetail detail = payload(service().item(item("dev/node1",
            InventoryKind.DIAGRAM, "Workflow-0101")).get(0)).item();

        assertThat(ResultJson.size(detail)).isLessThanOrEqualTo(20_000);
        assertThat(detail.truncated()).isTrue();
        List<VersionEntry> kept = detail.versions().orElseThrow();
        assertThat(kept).isNotEmpty().hasSizeLessThan(400);
        assertThat(kept.get(0).version()).isEqualTo(400);
        assertThat(kept.get(0).checkinComment().orElseThrow()).hasSizeLessThanOrEqualTo(
            ItemBounds.MAX_FIELD_CHARS).endsWith(ItemBounds.TRUNCATION_MARKER);
        assertThat(detail.lastChange()).contains(T0.minusSeconds(400));
    }

    // --- get_inventory_item: modules --------------------------------------------------------

    @Test
    void aModuleDetailHasTheIndexEntryAndTheHistoryOfTheWorkflowsGroup() {
        standardData(DEV);
        InventoryService service = service();
        service.list(list("dev/node1", InventoryKind.MODULE));
        clock.advance(Duration.ofMinutes(3));

        Lookup lookup = payload(service.item(item("dev/node1", InventoryKind.MODULE,
            "Module-0030")).get(0));

        InventoryDetail detail = lookup.item();
        assertThat(lookup.collectedAt()).isEqualTo(T0);
        assertThat(detail.kind()).isEqualTo(InventoryKind.MODULE);
        assertThat(detail.type()).contains("XSLT Converter");
        assertThat(detail.group()).contains("XSLT Converter");
        assertThat(detail.workflows()).contains(List.of("Workflow-0101"));
        assertThat(detail.workflowCount()).contains(1);
        assertThat(detail.usageComplete()).contains(true);
        assertThat(detail.active()).contains(true);
        assertThat(detail.lastChange()).as("LastUpdate, not the newest check-in")
            .contains(Instant.parse("2025-11-13T07:00:00Z"));
        assertThat(detail.checkinComment()).contains("module comment");
        assertThat(detail.userComment()).contains("user comment");
        assertThat(detail.connector()).contains(new ConnectorFlags(false, true, false));
        assertThat(detail.versions().orElseThrow()).extracting(VersionEntry::version)
            .containsExactly(3, 1);
        assertThat(detail.modules()).isEmpty();
        assertThat(detail.unavailable()).isEmpty();
        assertThat(port(DEV).historyCalls).containsExactly("OWNERS|technical|GRP-41");
    }

    @Test
    void aModuleThatNoWorkflowUsesHasNoVersionsAndSaysWhy() {
        standardData(DEV);

        InventoryDetail detail = payload(service().item(item("dev/node1", InventoryKind.MODULE,
            "Unused-XSLT")).get(0)).item();

        assertThat(detail.versions()).isEmpty();
        assertThat(detail.unavailable()).singleElement().satisfies(part ->
            assertThat(part.reason()).startsWith("NOT_FOUND: ")
                .contains("not used by any technical workflow"));
        assertThat(detail.workflows()).contains(List.of());
        assertThat(detail.usageComplete()).contains(true);
        assertThat(port(DEV).historyCalls).isEmpty();
    }

    @Test
    void theHistoryComesFromTheFirstUsingWorkflowThatIsInTheDiagramList() {
        // follow-up N3 (c): a connector bound to a workflow of another owner sorts first
        standardData(DEV);
        port(DEV).modules = List.of(module(DEV, "Module-0030", "XSLT Converter",
            Optional.of("AAA-Other-Owner")));

        InventoryDetail detail = payload(service().item(item("dev/node1", InventoryKind.MODULE,
            "Module-0030")).get(0)).item();

        assertThat(detail.workflows()).contains(List.of("AAA-Other-Owner", "Workflow-0101"));
        assertThat(detail.unavailable()).isEmpty();
        assertThat(detail.versions().orElseThrow()).extracting(VersionEntry::version)
            .containsExactly(3, 1);
        assertThat(port(DEV).historyCalls).containsExactly("OWNERS|technical|GRP-41");
    }

    @Test
    void anUnusedModuleOfAnIncompleteIndexSaysThatItsUsageIsUnknown() {
        standardData(DEV);
        port(DEV).detailFailures.put("billing order import", ToolError.of(ErrorCode.TIMEOUT, "slow",
            "c", "s").withNode(DEV));

        InventoryDetail detail = payload(service().item(item("dev/node1", InventoryKind.MODULE,
            "Unused-XSLT")).get(0)).item();

        assertThat(detail.usageComplete()).contains(false);
        assertThat(detail.versions()).isEmpty();
        assertThat(detail.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.reason()).startsWith("TIMEOUT: ").contains("1 of 4")
                .doesNotContain("not used");
            assertThat(part.nextStep()).hasValueSatisfying(step ->
                assertThat(step).contains("refresh"));
        });
    }

    @Test
    void aModuleUsedBySeveralWorkflowsTakesItsHistoryFromTheFirstWorkflowInTheList() {
        standardData(DEV);
        port(DEV).details.put("Workflow-Order-0001", new InventoryPort.DiagramDetail(
            "Workflow-Order-0001", Optional.of("technical"), Optional.of("head"),
            List.of(node("Module-0030", "7"))));

        InventoryDetail detail = payload(service().item(item("dev/node1", InventoryKind.MODULE,
            "Module-0030")).get(0)).item();

        assertThat(detail.workflows()).contains(List.of("Workflow-0101", "Workflow-Order-0001"));
        assertThat(detail.versions().orElseThrow()).extracting(VersionEntry::version)
            .containsExactly(3, 1);
        assertThat(port(DEV).historyCalls).containsExactly("OWNERS|technical|GRP-41");
    }

    @Test
    void aModuleWhoseWorkflowIsNotInTheDiagramListHasNoVersionsAndSaysWhy() {
        standardData(DEV);
        port(DEV).modules = List.of(module(DEV, "Orphan", "XSLT Converter",
            Optional.of("Gone-Workflow")));

        InventoryDetail detail = payload(service().item(item("dev/node1", InventoryKind.MODULE,
            "Orphan")).get(0)).item();

        assertThat(detail.versions()).isEmpty();
        assertThat(detail.unavailable()).singleElement().satisfies(part ->
            assertThat(part.reason()).startsWith("NOT_FOUND: ").contains("Gone-Workflow"));
    }

    @Test
    void anUnknownModuleIsNotFoundWithSimilarModuleNames() {
        standardData(DEV);

        NodeResult<Lookup> result = service().item(item("dev/node1", InventoryKind.MODULE,
            "Unused-XSL")).get(0);

        assertThat(result.error().orElseThrow().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(result.payload().orElseThrow().item().similarNames().orElseThrow())
            .startsWith("Unused-XSLT");
    }

    @Test
    void aFailedModuleIndexFailsTheServer() {
        standardData(DEV);
        port(DEV).moduleFailure = ToolError.of(ErrorCode.CLI_UNAVAILABLE, "no cli", "c", "s")
            .withNode(DEV);

        NodeResult<Lookup> result = service().item(item("dev/node1", InventoryKind.MODULE,
            "Module-0030")).get(0);
        NodeResult<Listing> listing = service().list(list("dev/node1",
            InventoryKind.MODULE)).get(0);

        assertThat(result.error().orElseThrow().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(listing.error().orElseThrow().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(listing.payload()).isEmpty();
    }

    // --- deadlines (design note 10) ---------------------------------------------------------

    @Test
    void theFanOutDeadlinesIncludeExportTimeoutKillGraceLoginAndGuardWait() {
        // review I4: an export that is stopped (cliExportTimeout + 2 x kill grace) must end
        // before the fan-out deadline, so that it becomes a partial result, not a TIMEOUT
        InventoryService service = service();
        Duration grace = InventoryService.FAN_OUT_GRACE;
        Duration export = EXPORT_TIMEOUT.plus(KILL_GRACES);

        assertThat(service.listDeadline(InventoryKind.DIAGRAM, DEV))
            .isEqualTo(TIMEOUT.multipliedBy(2).plus(grace));
        // T126: the usage index adds the diagram list (timeout) and its own budget
        // (cliExportTimeout)
        assertThat(service.listDeadline(InventoryKind.MODULE, DEV))
            .isEqualTo(export.plus(EXPORT_TIMEOUT).plus(TIMEOUT.multipliedBy(3)).plus(grace));
        assertThat(service.itemDeadline(InventoryKind.DIAGRAM, DEV))
            .isEqualTo(export.plus(TIMEOUT.multipliedBy(5)).plus(grace));
        assertThat(service.itemDeadline(InventoryKind.MODULE, DEV))
            .isEqualTo(export.multipliedBy(2).plus(EXPORT_TIMEOUT)
                .plus(TIMEOUT.multipliedBy(4)).plus(grace));
    }

    /** A configurable inventory of one server that counts its calls. */
    private static final class FakeInventory implements InventoryPort {

        private final NodeId server;
        List<InventoryItem> diagrams = List.of();
        List<ModuleEntry> modules = List.of();
        final Map<String, DiagramDetail> details = new HashMap<>();
        final Map<String, DiagramMetadata> metadata = new HashMap<>();
        final Map<String, VersionHistory> histories = new HashMap<>();
        ToolError listFailure;
        ToolError detailFailure;
        ToolError historyFailure;
        ToolError moduleFailure;
        final AtomicInteger diagramLoads = new AtomicInteger();
        final AtomicInteger moduleLoads = new AtomicInteger();
        final AtomicInteger detailCalls = new AtomicInteger();
        final List<String> detailCallNames = new CopyOnWriteArrayList<>();
        final Map<String, ToolError> detailFailures = new ConcurrentHashMap<>();
        final List<String> historyCalls = new CopyOnWriteArrayList<>();

        FakeInventory(NodeId server) {
            this.server = server;
        }

        @Override
        public List<InventoryItem> listDiagrams(String owner) {
            diagramLoads.incrementAndGet();
            fail(listFailure);
            return diagrams;
        }

        @Override
        public DiagramDetail diagramDetail(String owner, String name) {
            detailCalls.incrementAndGet();
            detailCallNames.add(name);
            fail(detailFailure);
            fail(detailFailures.get(name));
            DiagramDetail detail = details.get(name);
            if (detail == null) {
                throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND, "no " + name,
                    "c", "s").withNode(server));
            }
            return detail;
        }

        @Override
        public DiagramMetadata diagramMetadata(String name) {
            return metadata.getOrDefault(name, DiagramMetadata.EMPTY);
        }

        @Override
        public VersionHistory versionHistory(String owner, String type, String group) {
            historyCalls.add(owner + "|" + type + "|" + group);
            fail(historyFailure);
            return histories.getOrDefault(group, new VersionHistory(Map.of(), Map.of()));
        }

        @Override
        public List<ModuleEntry> listModules(String owner) {
            moduleLoads.incrementAndGet();
            fail(moduleFailure);
            return modules;
        }

        private static void fail(ToolError error) {
            if (error != null) {
                throw new ToolErrorException(error);
            }
        }
    }
}
