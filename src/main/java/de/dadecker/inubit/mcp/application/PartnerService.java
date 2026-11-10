package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.InventoryCache.Key;
import de.dadecker.inubit.mcp.application.InventoryCache.Snapshot;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The use case behind {@code find_partners} (feature 018). T018: the read path.
 *
 * <ul>
 *   <li>The target is resolved before any server is contacted ({@code TARGET_UNKNOWN}); the
 *       servers are read in parallel ({@link FanOut}), a failed server only gets its error.
 *   <li>Owner per server (FR-002a): the query's {@code owner} for every server, else the server's
 *       {@code inventory.owner}; a server without one fails alone with {@code NOT_CONFIGURED} and
 *       is never contacted. The owner reaches the ports unchanged (the adapter's collision check
 *       compares it case-sensitively).
 *   <li>Version gate (FR-016, D-7): {@code PARTNER_MANAGEMENT}, {@code READ}, over REST; 6.1 is
 *       refused ({@code UNSUPPORTED_VERSION}), 9.x and unknown versions read with a warning.
 *   <li>The owner's system diagrams (D-4): the gateway's {@code InventoryPort.listDiagrams(owner)}
 *       filtered to type {@code systemdiagram}, cached under {@code systemdiagrams:<owner>}; never
 *       through {@link InventoryService}. A {@code systemDiagram} filter keeps the diagrams of that
 *       name (exact, case-insensitive, any diagram group, FR-005a).
 *   <li>Partner Managements (D-6, FR-018) through {@link GatewayFactory#partners}, cached as
 *       {@link PartnerSnapshot}: all of the owner's under {@code partners:<owner>}; with a
 *       {@code systemDiagram} filter, the owner's entry if a fresh one covers the matching
 *       diagrams, else one entry per diagram under {@code partners:<owner>|<group>/<name>}. An
 *       entry serves a call only if it was read for the call's owner and its {@code covered}
 *       contains every diagram the call needs, never on the key alone; otherwise it is read
 *       again. Only the requested diagrams, their unreadable entries and their warnings are
 *       returned. {@code refresh} bypasses both caches; concurrent calls share one read
 *       ({@link InventoryCache}).
 * </ul>
 */
public final class PartnerService {

    static final Duration FAN_OUT_GRACE = Duration.ofSeconds(1);
    private static final String TOOL = "find_partners";
    private static final String SYSTEM_DIAGRAM = "systemdiagram";

    /**
     * What one server answered for one call.
     *
     * @param owner              the owner its system diagrams were read for
     * @param collectedAt        the oldest collection time of the cached parts used
     * @param fromCache          whether this call made no read on the server: the system diagram
     *                           list and the Partner Managements came from the cache or from the
     *                           read of a concurrent call
     * @param diagrams           the requested system diagrams that were read, in list order
     * @param unreadableDiagrams the requested system diagrams that gave no data (D-4)
     * @param warnings           the version gate's warnings, then the reader's warnings of the
     *                           requested diagrams in list order (the same whether the owner's
     *                           entry or the diagram's own entry answers)
     */
    record ServerRead(String owner, Instant collectedAt, boolean fromCache,
        List<SystemDiagramPartners> diagrams, List<UnreadableDiagram> unreadableDiagrams,
        List<String> warnings) {

        ServerRead {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(collectedAt, "collectedAt");
            diagrams = List.copyOf(diagrams);
            unreadableDiagrams = List.copyOf(unreadableDiagrams);
            warnings = List.copyOf(warnings);
        }
    }

    private final GatewayFactory gateways;
    private final TargetResolver targets;
    private final FanOut fanOut;
    private final InventoryCache cache;
    private final Function<NodeId, Optional<String>> owners;
    private final Function<NodeId, Duration> timeouts;
    private final Function<NodeId, Duration> exportTimeouts;
    private final Terminology terms;
    private final VersionGate gate;
    private final SecretTiming secretTiming;

    /**
     * @param owners         the effective {@code inventory.owner} of each node; empty if none is
     *                       configured
     * @param timeouts       the REST timeout of each server
     * @param exportTimeouts the {@code cliExportTimeout} of each server: the budget of the
     *                       exports of its system diagrams, as for the module usage index of
     *                       {@link InventoryService}
     * @param terms          the display names of the levels in messages
     * @param secretTiming   the fan-out allowance for fetching the secrets (feature 010)
     */
    public PartnerService(GatewayFactory gateways, TargetResolver targets, FanOut fanOut,
        InventoryCache cache, Function<NodeId, Optional<String>> owners,
        Function<NodeId, Duration> timeouts, Function<NodeId, Duration> exportTimeouts,
        Terminology terms, VersionGate gate, SecretTiming secretTiming) {
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.fanOut = Objects.requireNonNull(fanOut, "fanOut");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.exportTimeouts = Objects.requireNonNull(exportTimeouts, "exportTimeouts");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.secretTiming = Objects.requireNonNull(secretTiming, "secretTiming");
    }

    /**
     * The read of every server of {@code query.target()}, in config order.
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN} before any server is contacted
     */
    List<NodeResult<ServerRead>> read(PartnerQuery query) {
        List<NodeId> servers = targets.resolve(query.target());
        Set<NodeId> pending = secretTiming.pending(servers);
        return secretTiming.explainTimeouts(fanOut.run(servers, this::deadline,
            server -> read(server, query)), pending);
    }

    /**
     * {@code 3 × timeout} (guard wait, detection, system diagram list) plus the export budget
     * ({@code cliExportTimeout}), plus {@link #FAN_OUT_GRACE} and the read allowance of a node
     * whose credentials are not held yet.
     */
    Duration deadline(NodeId server) {
        return timeouts.apply(server).multipliedBy(3).plus(exportTimeouts.apply(server))
            .plus(FAN_OUT_GRACE).plus(secretTiming.allowance(server));
    }

    private ServerRead read(NodeId server, PartnerQuery query) {
        // a node without owner is never contacted, not even for the version detection
        String owner = query.owner().orElseGet(() -> configuredOwner(server));
        List<String> warnings = new ArrayList<>(gate.admit(TOOL, server,
            Capability.PARTNER_MANAGEMENT, Capability.Kind.READ, Set.of(Channel.REST)).warnings());
        AtomicBoolean read = new AtomicBoolean();
        Snapshot<List<DiagramRef>> list = cache.get(
            key(server, PartnerSnapshot.systemDiagramsScope(owner)), query.refresh(), () -> {
                read.set(true);
                return systemDiagrams(server, owner);
            });
        List<DiagramRef> needed = query.systemDiagram()
            .map(name -> list.value().stream()
                .filter(diagram -> diagram.name().equalsIgnoreCase(name))
                .toList())
            .orElse(list.value());
        List<Snapshot<PartnerSnapshot>> snapshots = query.systemDiagram().isEmpty()
            ? List.of(covering(server, owner, PartnerSnapshot.partnersScope(owner), needed,
                query.refresh(), read))
            : forDiagrams(server, owner, needed, query.refresh(), read);
        Instant collectedAt = list.collectedAt();
        List<SystemDiagramPartners> diagrams = new ArrayList<>();
        List<UnreadableDiagram> unreadable = new ArrayList<>();
        for (Snapshot<PartnerSnapshot> snapshot : snapshots) {
            collectedAt = collectedAt.isBefore(snapshot.collectedAt()) ? collectedAt
                : snapshot.collectedAt();
            snapshot.value().diagrams().stream()
                .filter(partners -> needed.contains(partners.diagram()))
                .forEach(diagrams::add);
            snapshot.value().unreadableDiagrams().stream()
                .filter(diagram -> needed.contains(diagram.systemDiagram()))
                .forEach(unreadable::add);
            needed.forEach(diagram ->
                warnings.addAll(snapshot.value().warnings().getOrDefault(diagram, List.of())));
        }
        return new ServerRead(owner, collectedAt, !read.get(), diagrams, unreadable, warnings);
    }

    /**
     * A {@code systemDiagram} call: the owner's fresh entry if it covers {@code needed}, else one
     * entry per diagram.
     */
    private List<Snapshot<PartnerSnapshot>> forDiagrams(NodeId server, String owner,
        List<DiagramRef> needed, boolean refresh, AtomicBoolean read) {
        Optional<Snapshot<PartnerSnapshot>> whole = refresh ? Optional.empty()
            : cache.<PartnerSnapshot>peek(key(server, PartnerSnapshot.partnersScope(owner)))
                .filter(snapshot -> snapshot.value().answers(owner, needed));
        return whole.map(List::of).orElseGet(() -> needed.stream()
            .map(diagram -> covering(server, owner, PartnerSnapshot.partnersScope(owner, diagram),
                List.of(diagram), refresh, read))
            .toList());
    }

    /**
     * The entry of {@code scope} if it answers {@code owner} and {@code needed}; an entry made for
     * other diagrams (an older system diagram list) or for another owner or diagram whose scope
     * reads the same is read again. Each further round either runs this call's read, which
     * answers, or joins a read in flight.
     */
    private Snapshot<PartnerSnapshot> covering(NodeId server, String owner, String scope,
        List<DiagramRef> needed, boolean refresh, AtomicBoolean read) {
        Key key = key(server, scope);
        Supplier<PartnerSnapshot> loader = () -> {
            read.set(true);
            PartnerManagementPort.Result result = gateways.partners(server).read(owner, needed);
            return new PartnerSnapshot(owner, result.diagrams(), Set.copyOf(needed),
                result.unreadableDiagrams(), result.warnings());
        };
        Snapshot<PartnerSnapshot> snapshot = cache.get(key, refresh, loader);
        while (!snapshot.value().answers(owner, needed)) {
            snapshot = cache.get(key, true, loader);
        }
        return snapshot;
    }

    private List<DiagramRef> systemDiagrams(NodeId server, String owner) {
        return gateways.inventory(server).listDiagrams(owner).stream()
            .filter(item -> item.type().equalsIgnoreCase(SYSTEM_DIAGRAM))
            .map(item -> new DiagramRef(item.name(), item.group()))
            .toList();
    }

    private static Key key(NodeId server, String scope) {
        return new Key(server, InventoryKind.DIAGRAM, scope);
    }

    /**
     * @throws ToolErrorException {@code NOT_CONFIGURED} if no owner is configured for the node,
     *     its group or in {@code defaults}
     */
    private String configuredOwner(NodeId server) {
        return owners.apply(server).orElseThrow(() -> new ToolErrorException(ToolError.of(
            ErrorCode.NOT_CONFIGURED,
            terms.render("{Node} ") + server + " has no inventory.owner, so its system diagrams"
                + " cannot be read",
            terms.render("No owner was given, and inventory.owner is not set for this {node},"
                + " its {group} or in defaults"),
            "pass owner, or set inventory.owner for " + server + " or in defaults")
            .withNode(server)));
    }
}
