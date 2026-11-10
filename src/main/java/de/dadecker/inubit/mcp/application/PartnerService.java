package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.InventoryCache.Key;
import de.dadecker.inubit.mcp.application.InventoryCache.Snapshot;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.Partner;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.model.partner.Setting;
import de.dadecker.inubit.mcp.domain.model.partner.SettingValue;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.model.partner.WorkflowRef;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 *   <li>Search and details (T019, FR-004, FR-005, FR-007, FR-011): a partner matches if one of
 *       its non-masked fields contains the search term, case-insensitively; masked values and
 *       entry settings are never searched. Each match is shown in full with its system diagram
 *       and communication cloud; a masked value only as "set"/"not set", never with its
 *       fingerprint. Per server: every system diagram read with its clouds and their partner
 *       counts, those without a Partner Management, the unreadable ones and the warnings; per
 *       cloud that contributed a match, its referencable workflows.
 *   <li>Similar values (T020, FR-005b, FR-011, FR-019): a search without hits lists up to five
 *       similar shown {@code Id} and {@code Name} values ({@link SimilarNames}), never a masked
 *       one; a {@code systemDiagram} that no answering server has ends the call with
 *       {@code NOT_FOUND} naming up to five similar system diagram names.
 * </ul>
 */
public final class PartnerService {

    static final Duration FAN_OUT_GRACE = Duration.ofSeconds(1);
    private static final String TOOL = "find_partners";
    private static final String SYSTEM_DIAGRAM = "systemdiagram";
    /** Upper bound of similar values and similar system diagram names (FR-005b, FR-019). */
    static final int MAX_SIMILAR = 5;
    /** The partner fields similar values are taken from (FR-005b). */
    private static final Set<String> SIMILAR_FIELDS = Set.of("Id", "Name");

    /**
     * What one server answered for one call.
     *
     * @param owner              the owner its system diagrams were read for
     * @param systemDiagrams     all of the owner's system diagrams, also those a
     *                           {@code systemDiagram} filter left out (FR-019)
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
    record ServerRead(String owner, List<DiagramRef> systemDiagrams, Instant collectedAt,
        boolean fromCache, List<SystemDiagramPartners> diagrams,
        List<UnreadableDiagram> unreadableDiagrams, List<String> warnings) {

        ServerRead {
            Objects.requireNonNull(owner, "owner");
            systemDiagrams = List.copyOf(systemDiagrams);
            Objects.requireNonNull(collectedAt, "collectedAt");
            diagrams = List.copyOf(diagrams);
            unreadableDiagrams = List.copyOf(unreadableDiagrams);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * The answer of one call (T019, T020).
     *
     * @param servers               one per targeted server, in config order
     * @param referencableWorkflows per communication cloud that contributed a partner (FR-007)
     * @param partners              the matching partners in export order
     * @param similarValues         only when a search matched nothing: up to
     *                              {@link #MAX_SIMILAR} non-masked {@code Id} and {@code Name}
     *                              values of the system diagrams read, closest first (FR-005b)
     */
    public record Found(List<ServerView> servers, List<CloudWorkflows> referencableWorkflows,
        List<PartnerView> partners, Optional<List<String>> similarValues) {

        public Found {
            servers = List.copyOf(servers);
            referencableWorkflows = List.copyOf(referencableWorkflows);
            partners = List.copyOf(partners);
            similarValues = similarValues.map(List::copyOf);
        }
    }

    /**
     * What one server answered; a failed server has only {@code node}, {@code group} and
     * {@code error}.
     *
     * @param systemDiagramsRead       every system diagram read, also those without a Partner
     *                                 Management (FR-007)
     * @param withoutPartnerManagement the system diagrams read that hold no Partner Management
     * @param warnings                 the version gate's and the reader's warnings
     */
    public record ServerView(NodeId node, GroupId group, Optional<String> owner,
        Optional<Instant> collectedAt, Optional<Boolean> fromCache,
        List<DiagramRead> systemDiagramsRead, List<DiagramRef> withoutPartnerManagement,
        List<UnreadableDiagram> unreadableDiagrams, List<String> warnings,
        Optional<ToolError> error) {

        public ServerView {
            systemDiagramsRead = List.copyOf(systemDiagramsRead);
            withoutPartnerManagement = List.copyOf(withoutPartnerManagement);
            unreadableDiagrams = List.copyOf(unreadableDiagrams);
            warnings = List.copyOf(warnings);
        }
    }

    /** A system diagram read, with each communication cloud and its number of partners. */
    public record DiagramRead(String name, String diagramGroup, List<CloudCount> clouds) {

        public DiagramRead {
            clouds = List.copyOf(clouds);
        }
    }

    /**
     * A communication cloud (module name) and the number of partners it holds, 0 if none.
     *
     * @param moduleId only when another cloud of the same system diagram has the same name
     */
    public record CloudCount(String cloud, Optional<String> moduleId, int partners) {
    }

    /** The referencable workflows of a communication cloud that contributed a partner. */
    public record CloudWorkflows(DiagramRef systemDiagram, String cloud, List<String> workflows) {

        public CloudWorkflows {
            workflows = List.copyOf(workflows);
        }
    }

    /**
     * One partner in full (FR-004).
     *
     * @param moduleId only when another cloud of the same system diagram has the same name
     */
    public record PartnerView(DiagramRef systemDiagram, String cloud, Optional<String> moduleId,
        List<SettingView> fields, int maskedCount, List<EntryView> entries) {

        public PartnerView {
            fields = List.copyOf(fields);
            entries = List.copyOf(entries);
        }
    }

    /** One {@code ReferencedTWF} in export order; {@code format} shown uninterpreted. */
    public record EntryView(String protocol, WorkflowView workflow, Optional<String> format,
        List<SettingView> protocolSettings, List<SettingView> additionalParameters) {

        public EntryView {
            protocolSettings = List.copyOf(protocolSettings);
            additionalParameters = List.copyOf(additionalParameters);
        }
    }

    /**
     * The referenced workflow: its {@code name}, or {@code unresolved} {@code true} without a
     * name if the entry's {@code referencableTWFId} names no referencable workflow.
     */
    public record WorkflowView(Optional<String> name, Optional<Boolean> unresolved) {
    }

    /**
     * A field or setting: {@code value} if shown (cut at {@code ItemBounds.MAX_FIELD_CHARS}), else
     * {@code masked} {@code true} with {@code set}. Never carries a secret or its fingerprint.
     */
    public record SettingView(String name, Optional<String> value, Optional<Boolean> masked,
        Optional<Boolean> set) {
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
     * The partners of every server of {@code query.target()} whose non-masked fields contain
     * {@code query.search()}, case-insensitively (every partner without a search term), each in
     * full (FR-004, FR-005, FR-007, FR-011). A search without hits gets similar values
     * (FR-005b).
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN} before any server is contacted;
     *     {@code NOT_FOUND} with up to {@link #MAX_SIMILAR} similar system diagram names if no
     *     server that answered has a system diagram named {@code query.systemDiagram()} (FR-019)
     */
    public Found find(PartnerQuery query) {
        Optional<String> term = query.search().map(PartnerService::folded);
        List<ServerView> servers = new ArrayList<>();
        List<CloudWorkflows> workflows = new ArrayList<>();
        List<PartnerView> partners = new ArrayList<>();
        List<NodeResult<ServerRead>> results = read(query);
        query.systemDiagram().ifPresent(name -> requireSystemDiagram(name, results));
        for (NodeResult<ServerRead> result : results) {
            servers.add(serverView(result));
            for (SystemDiagramPartners diagram : result.payload().map(ServerRead::diagrams)
                .orElse(List.of())) {
                for (CommunicationCloud cloud : diagram.clouds()) {
                    List<PartnerView> matches = cloud.partners().stream()
                        .filter(partner -> term.isEmpty() || matches(partner, term.get()))
                        .map(partner -> partnerView(diagram, cloud, partner))
                        .toList();
                    if (!matches.isEmpty()) {
                        partners.addAll(matches);
                        workflows.add(new CloudWorkflows(diagram.diagram(), cloud.moduleName(),
                            cloud.referencableWorkflows()));
                    }
                }
            }
        }
        Optional<List<String>> similar = query.search()
            .filter(search -> partners.isEmpty())
            .map(search -> SimilarNames.of(search, similarCandidates(results), MAX_SIMILAR));
        return new Found(servers, workflows, partners, similar);
    }

    /**
     * The shown {@code Id} and {@code Name} values of every partner read; a masked value is never
     * a candidate (FR-011).
     */
    private static List<String> similarCandidates(List<NodeResult<ServerRead>> results) {
        return results.stream()
            .flatMap(result -> result.payload().stream())
            .flatMap(read -> read.diagrams().stream())
            .flatMap(diagram -> diagram.clouds().stream())
            .flatMap(cloud -> cloud.partners().stream())
            .flatMap(partner -> partner.fields().stream())
            .filter(field -> SIMILAR_FIELDS.contains(field.name()))
            .map(Setting::value)
            .flatMap(value -> value instanceof SettingValue.Shown shown
                ? Stream.of(shown.rendered()) : Stream.empty())
            .toList();
    }

    /**
     * @throws ToolErrorException {@code NOT_FOUND} if at least one server answered and none of
     *     them has a system diagram called {@code name} (any case, any diagram group)
     */
    private static void requireSystemDiagram(String name, List<NodeResult<ServerRead>> results) {
        List<NodeResult<ServerRead>> answered = results.stream()
            .filter(result -> result.payload().isPresent())
            .toList();
        List<String> names = answered.stream()
            .flatMap(result -> result.payload().orElseThrow().systemDiagrams().stream())
            .map(DiagramRef::name)
            .toList();
        if (answered.isEmpty() || names.stream().anyMatch(name::equalsIgnoreCase)) {
            return;
        }
        List<String> similar = SimilarNames.of(name, names, MAX_SIMILAR);
        String where = answered.stream()
            .map(result -> result.node() + " (owner " + result.payload().orElseThrow().owner()
                + ")")
            .collect(Collectors.joining(", "));
        throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
            "No system diagram named " + Names.quote(name) + " on " + where,
            "The name is misspelled, or the system diagram belongs to another owner",
            similar.isEmpty()
                ? "Call without systemDiagram to see the system diagrams read"
                : "Use one of the similar system diagrams "
                    + similar.stream().map(Names::quote).collect(Collectors.joining(", "))
                    + ", or call without systemDiagram"));
    }

    /** Whether a non-masked field of {@code partner} contains {@code term} (already folded). */
    private static boolean matches(Partner partner, String term) {
        return partner.fields().stream()
            .map(Setting::value)
            .anyMatch(value -> value instanceof SettingValue.Shown shown
                && folded(shown.value()).contains(term));
    }

    private static String folded(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static ServerView serverView(NodeResult<ServerRead> result) {
        Optional<ServerRead> read = result.payload();
        List<SystemDiagramPartners> diagrams = read.map(ServerRead::diagrams).orElse(List.of());
        return new ServerView(result.node(), result.node().group(), read.map(ServerRead::owner),
            read.map(ServerRead::collectedAt), read.map(ServerRead::fromCache),
            diagrams.stream()
                .map(diagram -> new DiagramRead(diagram.diagram().name(),
                    diagram.diagram().diagramGroup(), diagram.clouds().stream()
                        .map(cloud -> new CloudCount(cloud.moduleName(),
                            moduleId(diagram, cloud), cloud.partners().size()))
                        .toList()))
                .toList(),
            diagrams.stream()
                .filter(diagram -> diagram.clouds().isEmpty())
                .map(SystemDiagramPartners::diagram)
                .toList(),
            read.map(ServerRead::unreadableDiagrams).orElse(List.of()),
            read.map(ServerRead::warnings).orElse(List.of()),
            result.error());
    }

    private static PartnerView partnerView(SystemDiagramPartners diagram,
        CommunicationCloud cloud, Partner partner) {
        return new PartnerView(diagram.diagram(), cloud.moduleName(), moduleId(diagram, cloud),
            settingViews(partner.fields()), partner.maskedCount(), partner.entries().stream()
                .map(entry -> new EntryView(entry.protocol(), workflowView(entry.workflow()),
                    entry.format(), settingViews(entry.protocolSettings()),
                    settingViews(entry.additionalParameters())))
                .toList());
    }

    /** The module id of {@code cloud} if another cloud of {@code diagram} has its name. */
    private static Optional<String> moduleId(SystemDiagramPartners diagram,
        CommunicationCloud cloud) {
        long named = diagram.clouds().stream()
            .filter(other -> other.moduleName().equals(cloud.moduleName()))
            .count();
        return named > 1 ? Optional.of(cloud.moduleId()) : Optional.empty();
    }

    private static WorkflowView workflowView(WorkflowRef workflow) {
        return new WorkflowView(workflow.name(),
            workflow.name().isPresent() ? Optional.empty() : Optional.of(true));
    }

    private static List<SettingView> settingViews(List<Setting> settings) {
        return settings.stream().map(setting -> switch (setting.value()) {
            case SettingValue.Shown shown -> new SettingView(setting.name(),
                Optional.of(shown.rendered()), Optional.empty(), Optional.empty());
            case SettingValue.Masked masked -> new SettingView(setting.name(), Optional.empty(),
                Optional.of(true), Optional.of(masked.set()));
        }).toList();
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
        return new ServerRead(owner, list.value(), collectedAt, !read.get(), diagrams, unreadable,
            warnings);
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
