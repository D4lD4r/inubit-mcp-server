package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.InventoryBounds.Bounded;
import de.dadecker.inubit.mcp.application.InventoryCache.Key;
import de.dadecker.inubit.mcp.application.InventoryCache.Snapshot;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryDetail;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailableInventoryPart;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramDetail;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramMetadata;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.VersionHistory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The use cases behind {@code list_inventory} and {@code get_inventory_item} (US3, FR-014 –
 * FR-017, research R-11).
 *
 * <ul>
 *   <li>The target ({@code TARGET_UNKNOWN}) and the paging ({@code INVALID_INPUT}) are
 *       checked before any server is contacted. Servers are queried in parallel
 *       ({@link FanOut}); results come in config order, a failed server only gets its
 *       {@code error}. Ports are taken without waiting for a version detection.
 *   <li>Every lookup uses the server's owner ({@code inventory.owner}, FR-016a). Diagram lists,
 *       module indexes and the version history per diagram group and type come from the
 *       {@link InventoryCache}; {@code refresh} reloads them. Each result states
 *       {@code collectedAt}, the oldest collection time of the cached parts it uses.
 *   <li>Lists: {@code nameContains} is a case-insensitive substring, {@code type} and
 *       {@code group} are case-insensitive equality; sorted by name (case-insensitive), paged
 *       with the server's {@link ResultLimiter#share(int) share}; items are bounded by
 *       {@link InventoryBounds}.
 *   <li>Diagram details: the list entry, {@code modelByName} (modules), the export metadata
 *       (active, check-in comment, owner) and the version history of the diagram's group;
 *       {@code lastChange} is the check-in time of the newest version. A REST failure fails the
 *       server; a failed history becomes an {@code unavailable} part next to the rest.
 *   <li>Module usage (T126, finding F1): which technical workflows use a module comes from
 *       the module nodes of every technical workflow of the owner ({@code modelByName}, read
 *       {@value #USAGE_CONCURRENCY} at a time within {@code cliExportTimeout},
 *       {@link ModuleUsageIndexer}), joined with a connector's own {@code WorkflowName}. The
 *       index is cached like the lists; an incomplete one is served but not kept, and every
 *       module result states {@code usageComplete}. List items name at most
 *       {@value #MAX_LISTED_WORKFLOWS} workflows plus their count; details name all.
 *   <li>Module details: the module index entry, its usage, and the history of the group of the
 *       first using workflow (in name order) that is in the diagram list (its type and group).
 *       A module without using workflow has no {@code versions} and an {@code unavailable} part:
 *       "not used by any technical workflow" if the usage index is complete, otherwise that its
 *       usage could not be determined.
 *   <li>A name that does not exist: {@code NOT_FOUND} together with an item holding up to five
 *       similar names ({@link SimilarNames}).
 *   <li>No "active version" is inferred (FR-016).
 *   <li>Deadlines per server (design note 10, review I4, T126): REST-only calls
 *       {@code 2 × timeout}; each CLI export adds {@code cliExportTimeout} plus the time to stop
 *       StartCLI and the login before it; the usage index of a module call adds the diagram
 *       list ({@code timeout}) and its budget ({@code cliExportTimeout}); plus
 *       {@link #FAN_OUT_GRACE}.
 * </ul>
 */
public final class InventoryService {

    static final Duration FAN_OUT_GRACE = Duration.ofSeconds(1);
    /** How many workflows the usage index reads at once per server. */
    static final int USAGE_CONCURRENCY = 8;
    /** How many using workflows a list item names ({@code workflowCount} counts all). */
    static final int MAX_LISTED_WORKFLOWS = 5;
    private static final String VERSIONS_SCOPE = "versions:";
    private static final String USAGE_SCOPE = "usage";
    private static final String TECHNICAL = "technical";
    private static final Comparator<InventoryItem> ORDER = Comparator
        .comparing(InventoryItem::name, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(InventoryItem::name)
        .thenComparing(InventoryItem::type)
        .thenComparing(InventoryItem::group);

    /** The arguments of {@code list_inventory} (contracts/mcp-tools.md §5). */
    public record ListRequest(String target, InventoryKind kind, Optional<String> nameContains,
        Optional<String> type, Optional<String> group, int offset, int limit, boolean refresh) {

        public ListRequest {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(kind, "kind");
            nameContains = nameContains == null ? Optional.empty() : nameContains;
            type = type == null ? Optional.empty() : type;
            group = group == null ? Optional.empty() : group;
        }
    }

    /** The arguments of {@code get_inventory_item} (contracts/mcp-tools.md §6). */
    public record ItemRequest(String target, InventoryKind kind, String name, boolean refresh) {

        public ItemRequest {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * One server's page of a list, with the time its data was collected.
     *
     * @param usageComplete modules only: whether the usage index could be built completely
     */
    public record Listing(Instant collectedAt, Page<InventoryItem> page,
        Optional<Boolean> usageComplete) {

        public Listing {
            Objects.requireNonNull(collectedAt, "collectedAt");
            Objects.requireNonNull(page, "page");
            usageComplete = usageComplete == null ? Optional.empty() : usageComplete;
        }
    }

    /** One server's detail (or not-found item), with the oldest collection time of its parts. */
    public record Lookup(Instant collectedAt, InventoryDetail item) {

        public Lookup {
            Objects.requireNonNull(collectedAt, "collectedAt");
            Objects.requireNonNull(item, "item");
        }
    }

    private final GatewayFactory gateways;
    private final TargetResolver targets;
    private final FanOut fanOut;
    private final ResultLimiter limiter;
    private final InventoryCache cache;
    private final Function<NodeId, Optional<String>> owners;
    private final Function<NodeId, Duration> timeouts;
    private final Function<NodeId, Duration> exportTimeouts;
    private final Duration exportStopGrace;
    private final ToIntFunction<Object> sizeOf;
    private final Terminology terms;

    /**
     * @param owners         the effective {@code inventory.owner} of each node; empty if none is
     *                       configured ({@code NOT_CONFIGURED} for that node)
     * @param timeouts       the REST timeout of each server
     * @param exportTimeouts the {@code cliExportTimeout} of each server
     * @param exportStopGrace how long a stopped export may take to end (2 × the CLI kill grace)
     * @param sizeOf         a value's serialized size in chars (the tool result's JSON)
     * @param terms          the display names of the levels in messages
     */
    public InventoryService(GatewayFactory gateways, TargetResolver targets, FanOut fanOut,
        ResultLimiter limiter, InventoryCache cache, Function<NodeId, Optional<String>> owners,
        Function<NodeId, Duration> timeouts, Function<NodeId, Duration> exportTimeouts,
        Duration exportStopGrace, ToIntFunction<Object> sizeOf, Terminology terms) {
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.fanOut = Objects.requireNonNull(fanOut, "fanOut");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.exportTimeouts = Objects.requireNonNull(exportTimeouts, "exportTimeouts");
        this.exportStopGrace = Objects.requireNonNull(exportStopGrace, "exportStopGrace");
        this.sizeOf = Objects.requireNonNull(sizeOf, "sizeOf");
        this.terms = Objects.requireNonNull(terms, "terms");
    }

    /**
     * One page of diagrams or modules per resolved server, in config order.
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN} or {@code INVALID_INPUT} before any
     *     server is contacted
     */
    public List<NodeResult<Listing>> list(ListRequest request) {
        List<NodeId> servers = targets.resolve(request.target());
        if (request.offset() < 0 || request.limit() < 1) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "offset must be >= 0 and limit >= 1, got " + request.offset() + " and "
                    + request.limit(),
                "The paging parameters are out of range",
                "Use offset >= 0 and limit between 1 and 100"));
        }
        ResultLimiter shared = limiter.share(servers.size());
        return fanOut.run(servers, server -> listDeadline(request.kind(), server),
            server -> listing(server, request, shared));
    }

    /**
     * The detail of one diagram or module per resolved server, in config order.
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN} or {@code INVALID_INPUT} (blank
     *     name) before any server is contacted
     */
    public List<NodeResult<Lookup>> item(ItemRequest request) {
        List<NodeId> servers = targets.resolve(request.target());
        if (request.name().isBlank()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "name must not be blank", "No diagram or module name was given",
                "Pass the exact name, e.g. from list_inventory"));
        }
        int budget = limiter.share(servers.size()).maxChars();
        List<NodeResult<NodeResult<Lookup>>> nested = fanOut.run(servers,
            server -> itemDeadline(request.kind(), server),
            server -> lookup(server, request, budget));
        return nested.stream()
            .map(result -> result.payload().orElseGet(() ->
                NodeResult.failure(result.node(), result.error().orElseThrow())))
            .toList();
    }

    /**
     * Diagrams: {@code 2 × timeout} (guard wait + list); modules: one export
     * ({@code cliExportTimeout + exportStopGrace}), the usage index ({@code cliExportTimeout})
     * and {@code 3 × timeout} (guard wait, the login before the export, the diagram list); plus
     * {@link #FAN_OUT_GRACE} (review I4, T126).
     */
    Duration listDeadline(InventoryKind kind, NodeId server) {
        Duration timeout = timeouts.apply(server);
        Duration deadline = kind == InventoryKind.MODULE
            ? export(server).plus(usageBudget(server)).plus(timeout.multipliedBy(3))
            : timeout.multipliedBy(2);
        return deadline.plus(FAN_OUT_GRACE);
    }

    /**
     * Diagram: one history export plus {@code 5 × timeout} (guard wait, list, nodes, export,
     * login before the export); module: two exports (module index, history), the usage index
     * ({@code cliExportTimeout}) and {@code 4 × timeout} (guard wait, login, diagram list,
     * login); plus grace. A stopped export thus ends before the deadline and becomes a partial
     * result, not a {@code TIMEOUT}.
     */
    Duration itemDeadline(InventoryKind kind, NodeId server) {
        Duration timeout = timeouts.apply(server);
        Duration deadline = kind == InventoryKind.DIAGRAM
            ? export(server).plus(timeout.multipliedBy(5))
            : export(server).multipliedBy(2).plus(usageBudget(server))
                .plus(timeout.multipliedBy(4));
        return deadline.plus(FAN_OUT_GRACE);
    }

    /** How long the usage index may take to read the workflows: {@code cliExportTimeout}. */
    private Duration usageBudget(NodeId server) {
        return exportTimeouts.apply(server);
    }

    /** The longest one export can take: its timeout plus the time to stop StartCLI. */
    private Duration export(NodeId server) {
        return exportTimeouts.apply(server).plus(exportStopGrace);
    }

    // --- lists --------------------------------------------------------------------------------

    private Listing listing(NodeId server, ListRequest request, ResultLimiter shared) {
        Snapshot<List<InventoryItem>> snapshot;
        Optional<Boolean> usageComplete = Optional.empty();
        if (request.kind() == InventoryKind.DIAGRAM) {
            snapshot = diagrams(server, request.refresh());
        } else {
            Snapshot<List<ModuleEntry>> index = modules(server, request.refresh());
            Snapshot<ModuleUsage> usage = usage(server, request.refresh());
            Set<String> duplicates = sharedNames(index.value());
            snapshot = new Snapshot<>(index.value().stream()
                .map(module -> module.item().withUsage(
                    usage.value().workflowsOf(module, duplicates.contains(module.item().name())),
                    MAX_LISTED_WORKFLOWS))
                .toList(), oldest(index.collectedAt(), usage.collectedAt()), index.expiresAt());
            usageComplete = Optional.of(usage.value().complete());
        }
        List<InventoryItem> matching = snapshot.value().stream()
            .filter(matches(request))
            .sorted(ORDER)
            .toList();
        int from = Math.min(request.offset(), matching.size());
        int to = Math.min(matching.size(),
            from + Math.min(request.limit(), shared.maxItems()));
        List<InventoryItem> prepared = new ArrayList<>(matching);
        boolean cut = false;
        for (int i = from; i < to; i++) {
            Bounded<InventoryItem> bounded = InventoryBounds.item(matching.get(i), sizeOf);
            prepared.set(i, bounded.value());
            cut |= bounded.cut();
        }
        Page<InventoryItem> page = shared.page(prepared, request.offset(), request.limit(),
            sizeOf::applyAsInt);
        return new Listing(snapshot.collectedAt(), cut ? page.asTruncated() : page,
            usageComplete);
    }

    private static Predicate<InventoryItem> matches(ListRequest request) {
        Optional<String> name = request.nameContains().filter(value -> !value.isBlank())
            .map(InventoryService::lower);
        Optional<String> type = request.type().filter(value -> !value.isBlank());
        Optional<String> group = request.group().filter(value -> !value.isBlank());
        return item -> name.map(value -> lower(item.name()).contains(value)).orElse(true)
            && type.map(value -> item.type().equalsIgnoreCase(value)).orElse(true)
            && group.map(value -> item.group().equalsIgnoreCase(value)).orElse(true);
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    // --- details ------------------------------------------------------------------------------

    private NodeResult<Lookup> lookup(NodeId server, ItemRequest request, int budget) {
        return request.kind() == InventoryKind.DIAGRAM
            ? diagramLookup(server, request, budget)
            : moduleLookup(server, request, budget);
    }

    private NodeResult<Lookup> diagramLookup(NodeId server, ItemRequest request,
        int budget) {
        String owner = owner(server);
        Snapshot<List<InventoryItem>> list = diagrams(server, request.refresh());
        Optional<InventoryItem> entry = find(list.value(), request.name());
        if (entry.isEmpty()) {
            return notFound(server, request, owner, list.collectedAt(),
                list.value().stream().map(InventoryItem::name).toList(), "diagram");
        }
        InventoryItem diagram = entry.get();
        InventoryPort port = gateways.inventory(server);
        DiagramDetail nodes = port.diagramDetail(owner, diagram.name());
        DiagramMetadata metadata = port.diagramMetadata(diagram.name());
        Instant collectedAt = list.collectedAt();
        List<UnavailableInventoryPart> unavailable = new ArrayList<>();
        Optional<List<VersionEntry>> versions = Optional.empty();
        try {
            Snapshot<VersionHistory> history = history(server, owner, diagram.type(),
                diagram.group(), request.refresh());
            collectedAt = oldest(collectedAt, history.collectedAt());
            versions = Optional.ofNullable(history.value().workflows().get(diagram.name()));
            if (versions.isEmpty()) {
                unavailable.add(UnavailableInventoryPart.of(UnavailableInventoryPart.VERSIONS,
                    missingInExport(server, "diagram", diagram.name(), diagram.group())));
            }
        } catch (ToolErrorException e) {
            unavailable.add(UnavailableInventoryPart.of(UnavailableInventoryPart.VERSIONS,
                e.error()));
        }
        InventoryDetail detail = new InventoryDetail(server, InventoryKind.DIAGRAM,
            diagram.name(), Optional.of(diagram.type()), Optional.of(diagram.group()),
            metadata.owner().orElse(owner), metadata.active(),
            versions.flatMap(InventoryService::newestCheckin), Optional.empty(), Optional.empty(),
            Optional.empty(), metadata.checkinComment(), Optional.empty(), versions,
            Optional.of(nodes.modules()), Optional.empty(), Optional.empty(), unavailable,
            false);
        return NodeResult.success(server,
            new Lookup(collectedAt, InventoryBounds.detail(detail, budget, sizeOf)));
    }

    private NodeResult<Lookup> moduleLookup(NodeId server, ItemRequest request,
        int budget) {
        String owner = owner(server);
        Snapshot<List<ModuleEntry>> index = modules(server, request.refresh());
        Optional<ModuleEntry> entry = index.value().stream()
            .filter(module -> module.item().name().equals(request.name()))
            .findFirst();
        if (entry.isEmpty()) {
            return notFound(server, request, owner, index.collectedAt(),
                index.value().stream().map(module -> module.item().name()).toList(), "module");
        }
        ModuleEntry found = entry.get();
        InventoryItem module = found.item();
        Snapshot<ModuleUsage> usage = usage(server, request.refresh());
        List<String> workflows = usage.value().workflowsOf(found,
            sharedNames(index.value()).contains(module.name()));
        Instant collectedAt = oldest(index.collectedAt(), usage.collectedAt());
        List<UnavailableInventoryPart> unavailable = new ArrayList<>();
        Optional<List<VersionEntry>> versions = Optional.empty();
        if (workflows.isEmpty()) {
            unavailable.add(UnavailableInventoryPart.of(UnavailableInventoryPart.VERSIONS,
                usage.value().failure().orElseGet(() -> ToolError.of(ErrorCode.NOT_FOUND,
                    "Module " + Names.quote(module.name()) + " on " + server + " is not used by"
                        + " any technical workflow of " + owner,
                    "INUBIT 8.1 exports the version history of a module only with the group of a"
                        + " workflow that uses it",
                    "Look the module's history up in the Workbench"))));
        } else {
            try {
                Snapshot<List<InventoryItem>> diagrams = diagrams(server, false);
                collectedAt = oldest(collectedAt, diagrams.collectedAt());
                Optional<InventoryItem> workflow = workflows.stream()
                    .map(name -> find(diagrams.value(), name))
                    .flatMap(Optional::stream)
                    .findFirst();
                if (workflow.isEmpty()) {
                    unavailable.add(UnavailableInventoryPart.of(
                        UnavailableInventoryPart.VERSIONS, ToolError.of(ErrorCode.NOT_FOUND,
                            "No workflow using module " + Names.quote(module.name()) + " ("
                                + Names.quote(workflows.get(0))
                                + (workflows.size() > 1 ? " and " + (workflows.size() - 1)
                                    + " more" : "")
                                + ") is in the diagram list of " + owner + " on " + server,
                            "The workflow belongs to another owner or was renamed",
                            "Look the module's history up in the Workbench")));
                } else {
                    Snapshot<VersionHistory> history = history(server, owner,
                        workflow.get().type(), workflow.get().group(), request.refresh());
                    collectedAt = oldest(collectedAt, history.collectedAt());
                    versions = Optional.ofNullable(history.value().modules().get(module.name()));
                    if (versions.isEmpty()) {
                        unavailable.add(UnavailableInventoryPart.of(
                            UnavailableInventoryPart.VERSIONS, missingInExport(server, "module",
                                module.name(), workflow.get().group())));
                    }
                }
            } catch (ToolErrorException e) {
                unavailable.add(UnavailableInventoryPart.of(UnavailableInventoryPart.VERSIONS,
                    e.error()));
            }
        }
        InventoryDetail detail = new InventoryDetail(server, InventoryKind.MODULE, module.name(),
            Optional.of(module.type()), Optional.of(module.group()), module.owner(),
            module.active(), module.lastChange(), Optional.of(workflows),
            Optional.of(workflows.size()), Optional.of(usage.value().complete()),
            found.checkinComment(), found.userComment(), versions, Optional.empty(),
            Optional.of(found.connector()), Optional.empty(), unavailable, false);
        return NodeResult.success(server,
            new Lookup(collectedAt, InventoryBounds.detail(detail, budget, sizeOf)));
    }

    /** Module names that more than one module of the index has. */
    private static Set<String> sharedNames(List<ModuleEntry> modules) {
        Set<String> seen = new HashSet<>();
        Set<String> shared = new HashSet<>();
        for (ModuleEntry module : modules) {
            if (!seen.add(module.item().name())) {
                shared.add(module.item().name());
            }
        }
        return shared;
    }

    private NodeResult<Lookup> notFound(NodeId server, ItemRequest request, String owner,
        Instant collectedAt, List<String> names, String what) {
        List<String> similar = SimilarNames.of(request.name(), names,
            InventoryDetail.MAX_SIMILAR_NAMES);
        InventoryDetail item = InventoryBounds.detail(InventoryDetail.notFound(server,
            request.kind(), request.name(), owner, similar), ItemBounds.MAX_ITEM_CHARS, sizeOf);
        ToolError error = ToolError.of(ErrorCode.NOT_FOUND,
            "No " + what + " named " + Names.quote(request.name()) + " owned by " + owner
                + " on " + server,
            "The name is misspelled (names are case-sensitive), or the " + what
                + " belongs to another owner than inventory.owner",
            similar.isEmpty()
                ? "Search with list_inventory and nameContains"
                : "Use one of item.similarNames, or search with list_inventory and"
                    + " nameContains")
            .withNode(server);
        return new NodeResult<>(server, Optional.of(new Lookup(collectedAt, item)),
            Optional.of(error));
    }

    private static ToolError missingInExport(NodeId server, String what, String name,
        String group) {
        return ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, "The version history export of"
                + " group " + Names.quote(group) + " on " + server + " has no entry for " + what
                + " " + Names.quote(name),
            "The export and the diagram list disagree (e.g. a change between the two calls)",
            "Retry with refresh=true");
    }

    private static Optional<InventoryItem> find(List<InventoryItem> items, String name) {
        return items.stream().filter(item -> item.name().equals(name)).findFirst();
    }

    private static Optional<Instant> newestCheckin(List<VersionEntry> versions) {
        return versions.isEmpty() ? Optional.empty() : versions.get(0).checkinAt();
    }

    private static Instant oldest(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    // --- cached sources -----------------------------------------------------------------------

    /**
     * The effective {@code inventory.owner} of {@code server}; every inventory lookup starts
     * here, so a node without one is never contacted (FR-014, research D-10).
     *
     * @throws ToolErrorException {@code NOT_CONFIGURED} if no owner is configured for the node,
     *     its group or in {@code defaults}
     */
    private String owner(NodeId server) {
        return owners.apply(server).orElseThrow(() -> new ToolErrorException(ToolError.of(
            ErrorCode.NOT_CONFIGURED,
            terms.render("{Node} ") + server + " has no inventory.owner, so its diagrams and"
                + " modules cannot be listed",
            terms.render("inventory.owner is not set for this {node}, its {group} or in"
                + " defaults; there is no built-in default"),
            "set inventory.owner for " + server + " or in defaults").withNode(server)));
    }

    private Snapshot<List<InventoryItem>> diagrams(NodeId server, boolean refresh) {
        String owner = owner(server);
        return cache.get(new Key(server, InventoryKind.DIAGRAM, InventoryCache.ALL), refresh,
            () -> gateways.inventory(server).listDiagrams(owner));
    }

    private Snapshot<List<ModuleEntry>> modules(NodeId server, boolean refresh) {
        String owner = owner(server);
        return cache.get(new Key(server, InventoryKind.MODULE, InventoryCache.ALL), refresh,
            () -> gateways.inventory(server).listModules(owner));
    }

    /**
     * The usage index of the server (T126): cached like the lists; an incomplete index is
     * served to the callers that waited for it but discarded, so the next call builds it again.
     * A failed diagram list gives an index that knows only the connector workflows.
     */
    private Snapshot<ModuleUsage> usage(NodeId server, boolean refresh) {
        Key key = new Key(server, InventoryKind.MODULE, USAGE_SCOPE);
        Snapshot<ModuleUsage> snapshot = cache.get(key, refresh, () -> buildUsage(server,
            refresh));
        if (!snapshot.value().complete()) {
            cache.discard(key, snapshot);
        }
        return snapshot;
    }

    private ModuleUsage buildUsage(NodeId server, boolean refresh) {
        String owner = owner(server);
        List<String> technical;
        try {
            technical = diagrams(server, refresh).value().stream()
                .filter(diagram -> diagram.type().equalsIgnoreCase(TECHNICAL))
                .map(InventoryItem::name)
                .toList();
        } catch (ToolErrorException e) {
            ToolError error = e.error();
            return ModuleUsage.unavailable(ToolError.of(error.code(),
                "The module usage of " + server + " is unknown: the diagram list failed ("
                    + error.message() + ")", error.likelyCause(),
                "Retry with refresh=true; if it persists: " + error.nextStep())
                .withNode(server));
        }
        InventoryPort port = gateways.inventory(server);
        return ModuleUsageIndexer.build(server, technical,
            name -> port.diagramDetail(owner, name).modules(), USAGE_CONCURRENCY,
            usageBudget(server));
    }

    private Snapshot<VersionHistory> history(NodeId server, String owner, String type,
        String group, boolean refresh) {
        return cache.get(new Key(server, InventoryKind.DIAGRAM,
                VERSIONS_SCOPE + type + "/" + group), refresh,
            () -> gateways.inventory(server).versionHistory(owner, type, group));
    }
}
