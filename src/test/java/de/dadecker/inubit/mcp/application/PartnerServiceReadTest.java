package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.InventoryCache.Key;
import de.dadecker.inubit.mcp.application.InventoryCache.Snapshot;
import de.dadecker.inubit.mcp.application.PartnerService.ServerRead;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T018: the read path of {@link PartnerService} (FR-002a, FR-003, FR-016, FR-018, research D-4,
 * D-6, D-7): owner per server, the version gate, the owner's system diagrams through the inventory
 * port, the Partner Managements through the partner port, both cached with coverage.
 */
@Timeout(30)
class PartnerServiceReadTest {

    /** The audit is checked by {@code PartnerServiceAuditTest}. */
    private static final PartnerAudit.Dependencies NO_AUDIT = new PartnerAudit.Dependencies(
        record -> { }, Clock.systemUTC(), "default", UUID::randomUUID);

    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final NodeId NODE2 = NodeId.parse("dev/node2");
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String CONFIGURED = "OWNERS";
    private static final String EXPLICIT = "Owner-B";
    private static final DiagramRef ONE = new DiagramRef("SystemDiagram-01", "GRP-PM");
    private static final DiagramRef TWO = new DiagramRef("SystemDiagram-02", "GRP-PM");
    /** Same name as {@link #ONE} in another diagram group: a different system diagram. */
    private static final DiagramRef ONE_ELSEWHERE = new DiagramRef("SystemDiagram-01", "GRP-PM2");
    /** The masked clouds of the synthetic detail set (with every kind of secret). */
    private static final List<CommunicationCloud> CLOUDS = PartnerManagementReader.read(ONE,
        XmlTree.parse(PartnerFixtures.workflowXml(PartnerFixtures.detail())).root())
        .partners().clouds();

    private final MutableClock clock = new MutableClock(T0);
    private final InventoryCache cache = new InventoryCache(clock, node -> TTL);
    private final Map<NodeId, Optional<String>> owners = new ConcurrentHashMap<>(Map.of(
        NODE1, Optional.of(CONFIGURED), NODE2, Optional.of(CONFIGURED)));
    private final Map<String, List<InventoryItem>> diagramsOf = new ConcurrentHashMap<>();
    private final List<String> listCalls = new CopyOnWriteArrayList<>();
    private final List<PortRead> portReads = new CopyOnWriteArrayList<>();
    private final Map<DiagramRef, UnreadableDiagram> unreadable = new ConcurrentHashMap<>();
    private final Map<DiagramRef, List<String>> readerWarnings = new ConcurrentHashMap<>();
    /** The thread of each owner lookup: one per call that resolves the configured owner. */
    private final List<Thread> ownerLookups = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch portEntered = new CountDownLatch(0);
    private volatile CountDownLatch portRelease = new CountDownLatch(0);
    private volatile ToolErrorException portFailure;

    private record PortRead(NodeId node, String owner, List<DiagramRef> diagrams) {
    }

    PartnerServiceReadTest() {
        diagramsOf.put(CONFIGURED, List.of(
            item(NODE1, ONE, "systemdiagram", CONFIGURED),
            item(NODE1, TWO, "SystemDiagram", CONFIGURED),
            item(NODE1, ONE_ELSEWHERE, "systemdiagram", CONFIGURED),
            InventoryItem.diagram(NODE1, "Workflow-0001", "technical", "GRP-PM", CONFIGURED)));
        diagramsOf.put(EXPLICIT, List.of(
            item(NODE1, new DiagramRef("SystemDiagram-09", "GRP-B"), "systemdiagram", EXPLICIT),
            InventoryItem.diagram(NODE1, "Workflow-0002", "technical", "GRP-B", EXPLICIT)));
    }

    // --- owner ---------------------------------------------------------------------------------

    @Test
    void withoutOwnerTheServersConfiguredOwnerReadsItsSystemDiagramsOnly() {
        ServerRead read = success(service().read(query(Optional.empty(), false)));

        assertThat(listCalls).containsExactly(CONFIGURED);
        assertThat(portReads).containsExactly(
            new PortRead(NODE1, CONFIGURED, List.of(ONE, TWO, ONE_ELSEWHERE)));
        assertThat(read.owner()).isEqualTo(CONFIGURED);
        assertThat(read.diagrams()).extracting(SystemDiagramPartners::diagram)
            .containsExactly(ONE, TWO, ONE_ELSEWHERE);
        assertThat(read.fromCache()).isFalse();
        assertThat(read.collectedAt()).isEqualTo(T0);
    }

    @Test
    void anExplicitOwnerGetsItsOwnDiagramsAndReachesThePortUnchanged() {
        ServerRead read = success(service().read(query("dev/node1", Optional.of(EXPLICIT),
            Optional.empty(), false)));

        // the collision check of the adapter compares the owner case-sensitively (T012 review)
        assertThat(listCalls).containsExactly(EXPLICIT);
        assertThat(portReads).containsExactly(new PortRead(NODE1, EXPLICIT,
            List.of(new DiagramRef("SystemDiagram-09", "GRP-B"))));
        assertThat(read.owner()).isEqualTo(EXPLICIT);
    }

    @Test
    void anExplicitOwnerAppliesToAServerWithoutConfiguredOwner() {
        owners.put(NODE1, Optional.empty());

        ServerRead read = success(service().read(query("dev/node1", Optional.of(EXPLICIT),
            Optional.empty(), false)));

        assertThat(read.owner()).isEqualTo(EXPLICIT);
    }

    @Test
    void aServerWithoutOwnerFailsAloneAndIsNeverContacted() {
        owners.put(NODE1, Optional.empty());

        List<NodeResult<ServerRead>> results = service().read(query("dev", Optional.empty(),
            Optional.empty(), false));

        assertThat(results).extracting(NodeResult::node).containsExactly(NODE1, NODE2);
        ToolError error = results.get(0).error().orElseThrow();
        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.node()).contains(NODE1);
        assertThat(error.message()).contains("dev/node1", "inventory.owner");
        assertThat(error.nextStep()).contains("owner", "inventory.owner");
        assertThat(results.get(1).isSuccess()).isTrue();
        assertThat(portReads).extracting(PortRead::node).containsExactly(NODE2);
        assertThat(listCalls).hasSize(1);
    }

    // --- version gate --------------------------------------------------------------------------

    @Test
    void version61IsRefusedUncontactedAnd90IsReadWithAWarning() {
        NodeVersions versions = new NodeVersions(clock,
            Map.<NodeId, VersionSetting>of(NODE1, VersionSetting.V6_1, NODE2,
                VersionSetting.V9_0)::get, node -> TTL);
        VersionGate gate = gateWithoutClients(versions, new FakeVersionDetection(versions),
            CapabilityMatrix.production());

        List<NodeResult<ServerRead>> results = service(gate).read(query("dev", Optional.empty(),
            Optional.empty(), false));

        ToolError refused = results.get(0).error().orElseThrow();
        assertThat(refused.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(refused.message()).startsWith("find_partners: ");
        ServerRead read = results.get(1).payload().orElseThrow();
        assertThat(read.warnings()).anySatisfy(warning ->
            assertThat(warning).contains("dev/node2", "not verified for partner management"));
        assertThat(portReads).extracting(PortRead::node).containsExactly(NODE2);
    }

    // --- system diagram filter and coverage ----------------------------------------------------

    @Test
    void aSystemDiagramFilterReadsOnlyTheMatchingDiagramsUnderTheirOwnScopes() {
        ServerRead read = success(service().read(query(Optional.of("systemdiagram-01"), false)));

        // exact name, case-insensitive, in any diagram group (FR-005a); one read per diagram
        assertThat(portReads).containsExactly(new PortRead(NODE1, CONFIGURED, List.of(ONE)),
            new PortRead(NODE1, CONFIGURED, List.of(ONE_ELSEWHERE)));
        assertThat(read.diagrams()).extracting(SystemDiagramPartners::diagram)
            .containsExactly(ONE, ONE_ELSEWHERE);
        assertThat(cache.peek(partnersKey(PartnerSnapshot.partnersScope(CONFIGURED, ONE))))
            .isPresent();
        assertThat(cache.peek(partnersKey(PartnerSnapshot.partnersScope(CONFIGURED,
            ONE_ELSEWHERE)))).isPresent();
        assertThat(cache.peek(partnersKey(PartnerSnapshot.partnersScope(CONFIGURED)))).isEmpty();
    }

    @Test
    void anUnknownSystemDiagramReadsNoPartnerManagement() {
        ServerRead read = success(service().read(query(Optional.of("SystemDiagram-77"), false)));

        assertThat(portReads).isEmpty();
        assertThat(read.diagrams()).isEmpty();
    }

    @Test
    void theOwnersEntryAnswersACallForOneDiagram() {
        PartnerService service = service();
        service.read(query(Optional.empty(), false));
        clock.advance(Duration.ofMinutes(1));

        ServerRead read = success(service.read(query(Optional.of("SystemDiagram-02"), false)));

        assertThat(portReads).hasSize(1);
        assertThat(read.fromCache()).isTrue();
        assertThat(read.collectedAt()).isEqualTo(T0);
        assertThat(read.diagrams()).extracting(SystemDiagramPartners::diagram)
            .containsExactly(TWO);
    }

    @Test
    void anEntryForOneDiagramDoesNotAnswerACallForAll() {
        PartnerService service = service();
        service.read(query(Optional.of("SystemDiagram-02"), false));

        ServerRead read = success(service.read(query(Optional.empty(), false)));

        assertThat(portReads).containsExactly(new PortRead(NODE1, CONFIGURED, List.of(TWO)),
            new PortRead(NODE1, CONFIGURED, List.of(ONE, TWO, ONE_ELSEWHERE)));
        assertThat(read.fromCache()).isFalse();
        assertThat(read.diagrams()).hasSize(3);
    }

    @Test
    void anOwnersEntryThatDoesNotCoverANewDiagramIsReadAgain() {
        PartnerService service = service();
        service.read(query(Optional.empty(), false));
        DiagramRef added = new DiagramRef("SystemDiagram-03", "GRP-PM");
        // a newer list (it expires first, as it is loaded first) while the Partner Managements
        // are still fresh
        cache.get(new Key(NODE1, InventoryKind.DIAGRAM,
            PartnerSnapshot.systemDiagramsScope(CONFIGURED)), true,
            () -> List.of(ONE, TWO, ONE_ELSEWHERE, added));

        ServerRead read = success(service.read(query(Optional.empty(), false)));

        assertThat(portReads).extracting(PortRead::diagrams).containsExactly(
            List.of(ONE, TWO, ONE_ELSEWHERE), List.of(ONE, TWO, ONE_ELSEWHERE, added));
        assertThat(read.fromCache()).isFalse();
        assertThat(read.diagrams()).extracting(SystemDiagramPartners::diagram).contains(added);
    }

    @Test
    void collidingDiagramScopesAreServedByCoverageNotByTheKey() {
        // "a/b" + "c" and "a" + "b/c" give the same scope partners:<owner>|a/b/c (T015 review)
        DiagramRef first = new DiagramRef("c", "a/b");
        DiagramRef second = new DiagramRef("b/c", "a");
        assertThat(PartnerSnapshot.partnersScope(CONFIGURED, first))
            .isEqualTo(PartnerSnapshot.partnersScope(CONFIGURED, second));
        diagramsOf.put(CONFIGURED, List.of(item(NODE1, first, "systemdiagram", CONFIGURED),
            item(NODE1, second, "systemdiagram", CONFIGURED)));
        PartnerService service = service();
        service.read(query(Optional.of("c"), false));

        ServerRead read = success(service.read(query(Optional.of("b/c"), false)));

        assertThat(portReads).extracting(PortRead::diagrams)
            .containsExactly(List.of(first), List.of(second));
        assertThat(read.diagrams()).extracting(SystemDiagramPartners::diagram)
            .containsExactly(second);
    }

    // --- cache ---------------------------------------------------------------------------------

    @Test
    void aSecondCallWithinTheTtlMakesNoReadAndRefreshReadsAgain() {
        PartnerService service = service();
        service.read(query(Optional.empty(), false));
        clock.advance(Duration.ofMinutes(2));

        ServerRead cached = success(service.read(query(Optional.empty(), false)));

        assertThat(listCalls).hasSize(1);
        assertThat(portReads).hasSize(1);
        assertThat(cached.fromCache()).isTrue();
        assertThat(cached.collectedAt()).isEqualTo(T0);
        assertThat(cached.diagrams()).hasSize(3);

        ServerRead refreshed = success(service.read(query(Optional.empty(), true)));

        assertThat(listCalls).hasSize(2);
        assertThat(portReads).hasSize(2);
        assertThat(refreshed.fromCache()).isFalse();
        assertThat(refreshed.collectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
    }

    @Test
    void afterTheTtlTheNextCallReadsAgain() {
        PartnerService service = service();
        service.read(query(Optional.empty(), false));
        clock.advance(TTL);

        ServerRead read = success(service.read(query(Optional.empty(), false)));

        assertThat(portReads).hasSize(2);
        assertThat(read.fromCache()).isFalse();
    }

    @Test
    void twoConcurrentCallsShareOneRead() throws Exception {
        portEntered = new CountDownLatch(1);
        portRelease = new CountDownLatch(1);
        PartnerService service = service();
        CompletableFuture<List<NodeResult<ServerRead>>> first = CompletableFuture.supplyAsync(
            () -> service.read(query(Optional.empty(), false)));
        assertThat(portEntered.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<List<NodeResult<ServerRead>>> second = CompletableFuture.supplyAsync(
            () -> service.read(query(Optional.empty(), false)));
        awaitParked(1);
        assertThat(second).isNotDone();
        portRelease.countDown();

        ServerRead one = success(first.get(10, TimeUnit.SECONDS));
        ServerRead two = success(second.get(10, TimeUnit.SECONDS));

        assertThat(portReads).hasSize(1);
        assertThat(listCalls).hasSize(1);
        assertThat(one.fromCache()).isFalse();
        assertThat(two.fromCache()).isTrue();
        assertThat(two.collectedAt()).isEqualTo(one.collectedAt());
    }

    @Test
    void aFailedReadFailsItsServerAndIsNotCached() {
        portFailure = new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "The export of a system diagram could not be read", "cause", "next").withNode(NODE1));
        PartnerService service = service();

        List<NodeResult<ServerRead>> failed = service.read(query(Optional.empty(), false));

        assertThat(failed.get(0).error().orElseThrow().code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        portFailure = null;
        assertThat(success(service.read(query(Optional.empty(), false))).fromCache()).isFalse();
        assertThat(portReads).hasSize(2);
    }

    // --- per-diagram results -------------------------------------------------------------------

    @Test
    void unreadableDiagramsAndReaderWarningsArePassedThroughAlsoFromTheCache() {
        UnreadableDiagram gone = new UnreadableDiagram(TWO, ErrorCode.NOT_FOUND,
            "the export of GRP-PM/SystemDiagram-02 on dev/node1 was not found");
        UnreadableDiagram collision = new UnreadableDiagram(ONE_ELSEWHERE,
            ErrorCode.UNEXPECTED_RESPONSE, "the export of this name returned a diagram of another"
                + " group or owner; the name is not unique");
        unreadable.put(TWO, gone);
        unreadable.put(ONE_ELSEWHERE, collision);
        readerWarnings.put(ONE, List.of(warning(ONE)));
        PartnerService service = service();

        ServerRead read = success(service.read(query(Optional.empty(), false)));
        ServerRead cached = success(service.read(query(Optional.empty(), false)));

        for (ServerRead result : List.of(read, cached)) {
            assertThat(result.diagrams()).extracting(SystemDiagramPartners::diagram)
                .containsExactly(ONE);
            assertThat(result.unreadableDiagrams()).containsExactly(gone, collision);
            assertThat(result.warnings()).containsExactly(warning(ONE));
        }
        assertThat(cached.fromCache()).isTrue();

        ServerRead one = success(service.read(query(Optional.of("SystemDiagram-02"), false)));

        assertThat(one.diagrams()).isEmpty();
        assertThat(one.unreadableDiagrams()).containsExactly(gone);
        assertThat(one.warnings()).isEmpty();
        assertThat(portReads).hasSize(1);
    }

    @Test
    void theOwnersEntryAnswersACallForOneDiagramWithThatDiagramsWarningsOnly() {
        readerWarnings.put(ONE, List.of(warning(ONE)));
        readerWarnings.put(TWO, List.of(warning(TWO), warning(TWO) + " (second)"));
        PartnerService service = service();
        ServerRead all = success(service.read(query(Optional.empty(), false)));

        ServerRead fromOwnersEntry = success(service.read(query(Optional.of("SystemDiagram-02"),
            false)));
        ServerRead freshRead = success(service.read(query(Optional.of("SystemDiagram-02"),
            true)));

        assertThat(all.warnings()).containsExactly(warning(ONE), warning(TWO),
            warning(TWO) + " (second)");
        assertThat(fromOwnersEntry.fromCache()).isTrue();
        assertThat(freshRead.fromCache()).isFalse();
        assertThat(portReads).extracting(PortRead::diagrams).last().isEqualTo(List.of(TWO));
        assertThat(fromOwnersEntry.warnings()).containsExactly(warning(TWO),
            warning(TWO) + " (second)");
        assertThat(freshRead.warnings()).isEqualTo(fromOwnersEntry.warnings());
    }

    @Test
    void anEntryThatKeepsNotAnsweringEndsInOneReadOfTheCallsOwn() {
        // phase 3 review: a concurrent call's read for other diagrams wins every round
        ContendedCache contended = new ContendedCache();

        ServerRead read = success(service(FakeVersionDetection.pinned81(), contended)
            .read(query(Optional.empty(), false)));

        assertThat(contended.partnerRefreshes).as("the first round and one refreshed round only")
            .containsExactly(false, true);
        assertThat(portReads).extracting(PortRead::diagrams)
            .containsExactly(List.of(ONE, TWO, ONE_ELSEWHERE));
        assertThat(read.diagrams()).extracting(SystemDiagramPartners::diagram)
            .containsExactly(ONE, TWO, ONE_ELSEWHERE);
        assertThat(read.fromCache()).isFalse();
    }

    @Test
    void anEntryReadForAnotherOwnerIsNotServedEvenIfItsScopeReadsTheSame() {
        // owner "a|g/n" gives partners:a|g/n, like owner "a" with the system diagram g/n
        DiagramRef diagram = new DiagramRef("n", "g");
        String collidingOwner = "a|g/n";
        assertThat(PartnerSnapshot.partnersScope(collidingOwner))
            .isEqualTo(PartnerSnapshot.partnersScope("a", diagram));
        owners.put(NODE1, Optional.of(collidingOwner));
        diagramsOf.put(collidingOwner, List.of(item(NODE1, diagram, "systemdiagram",
            collidingOwner)));
        diagramsOf.put("a", List.of(item(NODE1, diagram, "systemdiagram", "a")));
        PartnerService service = service();
        service.read(query(Optional.empty(), false));

        ServerRead read = success(service.read(query("dev/node1", Optional.of("a"),
            Optional.of("n"), false)));

        assertThat(portReads).containsExactly(new PortRead(NODE1, collidingOwner, List.of(diagram)),
            new PortRead(NODE1, "a", List.of(diagram)));
        assertThat(read.owner()).isEqualTo("a");
        assertThat(read.fromCache()).isFalse();
    }

    @Test
    void noSecretValueReachesTheReadOrAnError() {
        List<NodeResult<ServerRead>> results = service().read(query("dev", Optional.empty(),
            Optional.empty(), false));
        owners.put(NODE1, Optional.empty());
        List<NodeResult<ServerRead>> failed = service().read(query("dev/node1", Optional.empty(),
            Optional.empty(), true));

        String text = results + " " + failed;
        assertThat(results.get(0).payload().orElseThrow().diagrams().get(0).clouds())
            .isEqualTo(CLOUDS);
        for (String secret : PartnerFixtures.SECRETS) {
            assertThat(text).doesNotContain(secret);
        }
    }

    // --- support -------------------------------------------------------------------------------

    private static InventoryItem item(NodeId node, DiagramRef diagram, String type,
        String owner) {
        return InventoryItem.diagram(node, diagram.name(), type, diagram.diagramGroup(), owner);
    }

    private static Key partnersKey(String scope) {
        return new Key(NODE1, InventoryKind.DIAGRAM, scope);
    }

    private static PartnerQuery query(Optional<String> systemDiagram, boolean refresh) {
        return query("dev/node1", Optional.empty(), systemDiagram, refresh);
    }

    private static PartnerQuery query(String target, Optional<String> owner,
        Optional<String> systemDiagram, boolean refresh) {
        return new PartnerQuery(target, Optional.empty(), owner, Optional.empty(), systemDiagram,
            Optional.empty(), Optional.empty(), 0, PartnerQuery.DEFAULT_LIMIT, refresh,
            Optional.empty());
    }

    private static ServerRead success(List<NodeResult<ServerRead>> results) {
        assertThat(results).hasSize(1);
        return results.get(0).payload()
            .orElseThrow(() -> new AssertionError(results.get(0).error()));
    }

    private static String warning(DiagramRef diagram) {
        return "Partner Id ACME-0001 in the communication cloud Module-0901 of the system diagram "
            + diagram.diagramGroup() + "/" + diagram.name() + ": a FTP entry refers to a workflow"
            + " its Partner Management does not list by name; the workflow is shown as unresolved";
    }

    private Optional<String> owner(NodeId node) {
        ownerLookups.add(Thread.currentThread());
        return owners.get(node);
    }

    /**
     * Waits until the call that made owner lookup {@code index} is parked (it waits for the read
     * in flight of another call), at most 10 s.
     */
    private void awaitParked(int index) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ownerLookups.size() <= index && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(ownerLookups).hasSizeGreaterThan(index);
        Thread caller = ownerLookups.get(index);
        while (!parked(caller) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(parked(caller)).as("the second call waits for the first call's read").isTrue();
    }

    private static boolean parked(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    private PartnerService service() {
        return service(FakeVersionDetection.pinned81());
    }

    private PartnerService service(VersionGate gate) {
        return service(gate, cache);
    }

    private PartnerService service(VersionGate gate, InventoryCache cache) {
        return new PartnerService(new Gateways(), new TargetResolver(List.of(NODE1, NODE2)),
            new FanOut(), ResultLimiter.withDefaults(), cache, this::owner,
            node -> Duration.ofSeconds(5), node -> Duration.ofSeconds(120), ResultJson::size,
            Terminology.DEFAULT, gate, SecretTiming.NONE, NO_AUDIT);
    }

    private PartnerManagementPort.Result portRead(NodeId node, String owner,
        List<DiagramRef> diagrams) {
        portReads.add(new PortRead(node, owner, List.copyOf(diagrams)));
        portEntered.countDown();
        try {
            assertThat(portRelease.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (portFailure != null) {
            throw portFailure;
        }
        return new PartnerManagementPort.Result(diagrams.stream()
            .filter(diagram -> !unreadable.containsKey(diagram))
            .map(diagram -> new SystemDiagramPartners(diagram, CLOUDS))
            .toList(),
            diagrams.stream().filter(unreadable::containsKey).map(unreadable::get).toList(),
            readerWarnings.entrySet().stream()
                .filter(entry -> diagrams.contains(entry.getKey())
                    && !unreadable.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * A cache under contention: every Partner Management entry it hands out was read by another
     * call for another system diagram, without calling the loader; the system diagram list comes
     * from a real cache. More than ten rounds count as unbounded.
     */
    private final class ContendedCache extends InventoryCache {

        final List<Boolean> partnerRefreshes = new CopyOnWriteArrayList<>();

        ContendedCache() {
            super(clock, node -> TTL);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Snapshot<T> get(Key key, boolean refresh, Supplier<T> loader) {
            if (!key.scope().startsWith(PartnerSnapshot.PARTNERS_SCOPE)) {
                return super.get(key, refresh, loader);
            }
            partnerRefreshes.add(refresh);
            if (partnerRefreshes.size() > 10) {
                throw new AssertionError("unbounded rounds on " + key);
            }
            DiagramRef other = new DiagramRef("SystemDiagram-99", "GRP-PM");
            return (Snapshot<T>) new Snapshot<>(new PartnerSnapshot(CONFIGURED,
                List.of(new SystemDiagramPartners(other, CLOUDS)), Set.of(other), List.of(),
                Map.of()), T0, T0.plus(TTL));
        }
    }

    /** Only the inventory list and the partner port; every other port is an assertion error. */
    private final class Gateways implements GatewayFactory {

        @Override
        public InventoryPort inventory(NodeId server) {
            return new InventoryPort() {
                @Override
                public List<InventoryItem> listDiagrams(String owner) {
                    listCalls.add(owner);
                    return diagramsOf.getOrDefault(owner, List.of());
                }

                @Override
                public DiagramDetail diagramDetail(String owner, String name) {
                    throw new AssertionError("not used");
                }

                @Override
                public DiagramMetadata diagramMetadata(String name) {
                    throw new AssertionError("not used");
                }

                @Override
                public VersionHistory versionHistory(String owner, String type, String group) {
                    throw new AssertionError("not used");
                }

                @Override
                public List<ModuleEntry> listModules(String owner) {
                    throw new AssertionError("not used");
                }
            };
        }

        @Override
        public PartnerManagementPort partners(NodeId server) {
            return (owner, diagrams) -> portRead(server, owner, diagrams);
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("the version gate detects through its own factory");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            return Optional.empty();
        }
    }
}
