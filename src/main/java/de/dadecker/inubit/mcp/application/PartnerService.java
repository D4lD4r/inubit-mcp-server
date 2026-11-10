package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.InventoryCache.Key;
import de.dadecker.inubit.mcp.application.InventoryCache.Snapshot;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
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
import de.dadecker.inubit.mcp.domain.model.partner.ProtocolEntry;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *       its non-masked fields contains the search term, case-insensitively (Unicode, char by
 *       char); masked values and entry settings are never searched. Each match is shown in
 *       full with its system diagram and communication cloud; a masked value only as
 *       "set"/"not set", never with its fingerprint. Per server: every system diagram read with
 *       its clouds and their partner counts, those without a Partner Management, the unreadable
 *       ones and the warnings; per cloud that contributed a returned partner, its referencable
 *       workflows.
 *   <li>Filters (T023, FR-005a), in both modes and AND-combined with the search: an entry
 *       matches if its {@code protocol} attribute equals {@code protocol} (never an additional
 *       parameter such as {@code FTPProtocol}) and its resolved workflow name equals
 *       {@code workflow}, each case-insensitively; an unresolved workflow never matches. With
 *       either filter a partner is a match only through a matching entry, and shows only its
 *       matching entries. No match is an empty result, never {@code NOT_FOUND}.
 *   <li>List mode (T023, FR-006): without a search term each partner is a summary: its system
 *       diagram, communication cloud and all fields, and per entry only the protocol, the
 *       workflow and the protocol settings {@link #SUMMARY_KEYS} that are present (a masked one
 *       stays masked). The matches of a server are ordered by system diagram group and name,
 *       communication cloud module name, partner {@code Id}, then {@code Name}
 *       (case-insensitive; a masked or missing value last; ties in export order). A search keeps
 *       the export order (contracts/find_partners.md, "Guarantees").
 *   <li>Paging (FR-017, phase 3 review): the matches of all servers in server order, then in the
 *       order above; {@code offset} and {@code limit} select the partners returned, with the
 *       number of matches as {@code total} and {@code nextOffset} while more follow. The size
 *       bound ({@code resultLimits}) follows in T024.
 *   <li>Similar values (T020, FR-005b, FR-011, FR-019): a search without hits on a call where
 *       at least one server answered lists up to five similar shown {@code Id} and {@code Name}
 *       values ({@link SimilarNames}), never a masked one; a {@code systemDiagram} that no
 *       answering server has ends the call with {@code NOT_FOUND} naming up to five similar
 *       system diagram names.
 *   <li>Audit and log (T021, FR-020, FR-021, research D-8): one {@link PartnerAudit} per call
 *       with one call id; one record and one INFO line per targeted server, also for cache hits;
 *       an audit record that cannot be written ends the call in {@code INTERNAL} without data.
 *       {@code valuesMasked} counts the masked values the returned page shows (fields and the
 *       settings of the entries shown), not the partners' {@code maskedCount}.
 * </ul>
 */
public final class PartnerService {

    static final Duration FAN_OUT_GRACE = Duration.ofSeconds(1);
    private static final String TOOL = "find_partners";
    private static final String SYSTEM_DIAGRAM = "systemdiagram";
    private static final Logger LOG = LoggerFactory.getLogger(PartnerService.class);
    /** Error codes audited as {@code REFUSED} (research D-8); every other error is FAILED. */
    private static final Set<ErrorCode> REFUSALS =
        EnumSet.of(ErrorCode.NOT_CONFIGURED, ErrorCode.UNSUPPORTED_VERSION);
    /** Upper bound of similar values and similar system diagram names (FR-005b, FR-019). */
    static final int MAX_SIMILAR = 5;
    /** The partner fields similar values are taken from (FR-005b). */
    private static final Set<String> SIMILAR_FIELDS = Set.of("Id", "Name");
    /** The protocol settings a list entry shows, for every protocol (FR-006, research D-2). */
    static final List<String> SUMMARY_KEYS = List.of("ServerName", "Login");
    /** A shown text first, case-insensitively; a masked or missing value last. */
    private static final Comparator<String> TEXT =
        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
    /** The order of list mode (FR-006, US2-AS1); {@code List.sort} keeps ties in export order. */
    private static final Comparator<Match> LIST_ORDER = Comparator
        .comparing((Match match) -> match.diagram().diagram().diagramGroup(), TEXT)
        .thenComparing(match -> match.diagram().diagram().name(), TEXT)
        .thenComparing(match -> match.cloud().moduleName(), TEXT)
        .thenComparing(match -> shown(match.partner(), "Id"), TEXT)
        .thenComparing(match -> shown(match.partner(), "Name"), TEXT);

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
     * @param callId                the call id shared by every audit record of the call (FR-020)
     * @param servers               one per targeted server, in config order
     * @param referencableWorkflows per communication cloud that contributed a returned partner
     *                              (FR-007)
     * @param partners              the page of matching partners: server order, then export order
     *                              (search) or the list order (list mode, a summary each)
     * @param similarValues         only when a search matched nothing and at least one server
     *                              answered: up to {@link #MAX_SIMILAR} non-masked {@code Id} and
     *                              {@code Name} values of the system diagrams read, closest first
     *                              (FR-005b)
     * @param total                 the number of matching partners on all servers
     * @param nextOffset            the offset of the next page, if more matches follow
     */
    public record Found(UUID callId, List<ServerView> servers,
        List<CloudWorkflows> referencableWorkflows, List<PartnerView> partners,
        Optional<List<String>> similarValues, int total, OptionalInt nextOffset) {

        public Found {
            Objects.requireNonNull(callId, "callId");
            servers = List.copyOf(servers);
            referencableWorkflows = List.copyOf(referencableWorkflows);
            partners = List.copyOf(partners);
            similarValues = similarValues.map(List::copyOf);
            Objects.requireNonNull(nextOffset, "nextOffset");
        }
    }

    /** A matching partner with the entries it shows (all, or those the entry filters match). */
    private record Match(SystemDiagramPartners diagram, CommunicationCloud cloud, Partner partner,
        List<ProtocolEntry> entries) {
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

    /**
     * The referencable workflows of a communication cloud that contributed a partner.
     *
     * @param moduleId only when another cloud of the same system diagram has the same name
     */
    public record CloudWorkflows(DiagramRef systemDiagram, String cloud,
        Optional<String> moduleId, List<String> workflows) {

        public CloudWorkflows {
            workflows = List.copyOf(workflows);
        }
    }

    /**
     * One partner in full (FR-004), or as a summary in list mode (FR-006).
     *
     * @param moduleId    only when another cloud of the same system diagram has the same name
     * @param maskedCount the masked values of the whole partner, also of entries not shown
     * @param entries     in export order: all, or only those the entry filters match
     */
    public record PartnerView(DiagramRef systemDiagram, String cloud, Optional<String> moduleId,
        List<SettingView> fields, int maskedCount, List<EntryView> entries) {

        public PartnerView {
            fields = List.copyOf(fields);
            entries = List.copyOf(entries);
        }
    }

    /**
     * One {@code ReferencedTWF} in export order; {@code format} shown uninterpreted. A list
     * summary has no {@code format} and no {@code additionalParameters}, and of the
     * {@code protocolSettings} only the {@link #SUMMARY_KEYS} present.
     */
    public record EntryView(String protocol, WorkflowView workflow, Optional<String> format,
        List<SettingView> protocolSettings, Optional<List<SettingView>> additionalParameters) {

        public EntryView {
            protocolSettings = List.copyOf(protocolSettings);
            additionalParameters = additionalParameters.map(List::copyOf);
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
    private final PartnerAudit.Dependencies audits;

    /**
     * @param owners         the effective {@code inventory.owner} of each node; empty if none is
     *                       configured
     * @param timeouts       the REST timeout of each server
     * @param exportTimeouts the {@code cliExportTimeout} of each server: the budget of the
     *                       exports of its system diagrams, as for the module usage index of
     *                       {@link InventoryService}
     * @param terms          the display names of the levels in messages
     * @param secretTiming   the fan-out allowance for fetching the secrets (feature 010)
     * @param audits         what the audit of every call shares (FR-020)
     */
    public PartnerService(GatewayFactory gateways, TargetResolver targets, FanOut fanOut,
        InventoryCache cache, Function<NodeId, Optional<String>> owners,
        Function<NodeId, Duration> timeouts, Function<NodeId, Duration> exportTimeouts,
        Terminology terms, VersionGate gate, SecretTiming secretTiming,
        PartnerAudit.Dependencies audits) {
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
        this.audits = Objects.requireNonNull(audits, "audits");
    }

    /**
     * The partners of every server of {@code query.target()} whose non-masked fields contain
     * {@code query.search()}, case-insensitively, each in full (FR-004, FR-005, FR-007, FR-011);
     * without a search term every partner as a summary in list order (FR-006); either narrowed by
     * the {@code protocol} and {@code workflow} entry filters (FR-005a); the page
     * {@code query.offset()}, {@code query.limit()} of them (FR-017). A search without hits gets
     * similar values if a server answered (FR-005b).
     *
     * <p>Audit and log (T021, FR-020, FR-021): one {@link PartnerAudit} per call; an unknown
     * target is one {@code REFUSED} record; otherwise one record and one INFO line per server,
     * also for a cache hit or a failed server (its error code as reason, never the message),
     * counting the partners returned on the page.
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN} before any server is contacted;
     *     {@code NOT_FOUND} with up to {@link #MAX_SIMILAR} similar system diagram names if no
     *     server that answered has a system diagram named {@code query.systemDiagram()} (FR-019);
     *     {@code INTERNAL} naming the call id, and no partner data, if any audit record could not
     *     be written
     */
    public Found find(PartnerQuery query) {
        PartnerAudit audit = PartnerAudit.of(audits, query);
        List<NodeId> targeted;
        try {
            targeted = targets.resolve(query.target());
        } catch (ToolErrorException e) {
            PartnerAudit.Refusal refusal = audit.refused(e.error());
            LOG.info("{} on {}: REFUSED {} (call {}{})", TOOL, LogText.loggable(query.target()),
                e.error().code(), audit.callId(), refusal.recorded() ? "" : ", no audit record");
            throw refusal.exception();
        }
        List<NodeResult<ServerRead>> results = read(targeted, query);
        Optional<ToolError> notFound = query.systemDiagram()
            .flatMap(name -> unknownSystemDiagram(name, results));
        boolean summary = query.search().isEmpty();
        List<List<Match>> matched = results.stream()
            .map(result -> notFound.isEmpty() ? matched(result, query) : List.<Match>of())
            .toList();
        int total = matched.stream().mapToInt(List::size).sum();
        int from = Math.min(query.offset(), total);
        int to = Math.min(from + query.limit(), total);
        List<ServerView> servers = new ArrayList<>();
        List<CloudWorkflows> workflows = new ArrayList<>();
        List<PartnerView> partners = new ArrayList<>();
        ToolErrorException unaudited = null;
        int position = 0;
        for (int i = 0; i < results.size(); i++) {
            NodeResult<ServerRead> result = results.get(i);
            List<Match> server = matched.get(i);
            int size = server.size();
            List<Match> page = server.subList(Math.clamp(from - position, 0, size),
                Math.clamp(to - position, 0, size));
            position += size;
            List<PartnerView> returned = page.stream()
                .map(match -> partnerView(match, summary))
                .toList();
            page.stream()
                .map(match -> new CloudWorkflows(match.diagram().diagram(),
                    match.cloud().moduleName(), moduleId(match.diagram(), match.cloud()),
                    match.cloud().referencableWorkflows()))
                .distinct()
                .forEach(workflows::add);
            servers.add(serverView(result));
            partners.addAll(returned);
            try {
                audit(audit, query, result, notFound.map(ToolError::code), returned);
            } catch (ToolErrorException e) {
                unaudited = unaudited == null ? e : unaudited;
            }
        }
        if (unaudited != null) {
            throw unaudited;
        }
        if (notFound.isPresent()) {
            throw new ToolErrorException(notFound.get());
        }
        boolean answered = results.stream().anyMatch(result -> result.payload().isPresent());
        Optional<List<String>> similar = query.search()
            .filter(search -> total == 0 && answered)
            .map(search -> SimilarNames.of(search, similarCandidates(results), MAX_SIMILAR));
        return new Found(audit.callId(), servers, workflows, partners, similar, total,
            to < total ? OptionalInt.of(to) : OptionalInt.empty());
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
     * The partners of {@code result} that match the search term and the entry filters of
     * {@code query}: in export order with a search term, else in {@link #LIST_ORDER}.
     */
    private static List<Match> matched(NodeResult<ServerRead> result, PartnerQuery query) {
        Optional<Pattern> term = query.search().map(PartnerService::containing);
        boolean entryFilter = query.protocol().isPresent() || query.workflow().isPresent();
        List<Match> matched = new ArrayList<>();
        for (SystemDiagramPartners diagram : result.payload().map(ServerRead::diagrams)
            .orElse(List.of())) {
            for (CommunicationCloud cloud : diagram.clouds()) {
                for (Partner partner : cloud.partners()) {
                    List<ProtocolEntry> entries = partner.entries().stream()
                        .filter(entry -> matches(entry, query))
                        .toList();
                    if ((term.isEmpty() || matches(partner, term.get()))
                        && !(entryFilter && entries.isEmpty())) {
                        matched.add(new Match(diagram, cloud, partner, entries));
                    }
                }
            }
        }
        if (query.search().isEmpty()) {
            matched.sort(LIST_ORDER);
        }
        return matched;
    }

    /**
     * Whether {@code entry} has the protocol and the resolved workflow name of the query's
     * filters (exact, case-insensitive); an unresolved workflow never matches a name.
     */
    private static boolean matches(ProtocolEntry entry, PartnerQuery query) {
        return query.protocol().map(entry.protocol()::equalsIgnoreCase).orElse(true)
            && query.workflow()
                .map(workflow -> entry.workflow().name().filter(workflow::equalsIgnoreCase)
                    .isPresent())
                .orElse(true);
    }

    /** The shown value of the partner field {@code name}; {@code null} if masked or missing. */
    private static String shown(Partner partner, String name) {
        return partner.fields().stream()
            .filter(field -> field.name().equals(name))
            .map(Setting::value)
            .flatMap(value -> value instanceof SettingValue.Shown shown
                ? Stream.of(shown.value()) : Stream.empty())
            .findFirst()
            .orElse(null);
    }

    /**
     * The audit record and the INFO line of one server: {@code EXECUTED} with its counts, or
     * {@code REFUSED}/{@code FAILED} with its error code ({@code notFound} for a server that
     * answered a call that ends in {@code NOT_FOUND}). Server, owner, outcome, counts and call id
     * only: no partner setting, no secret, no error message.
     *
     * @throws ToolErrorException {@code INTERNAL} if the record could not be written
     */
    private void audit(PartnerAudit audit, PartnerQuery query,
        NodeResult<ServerRead> result, Optional<ErrorCode> notFound,
        List<PartnerView> returned) {
        Optional<ServerRead> read = result.payload();
        Optional<ErrorCode> reason = result.error().map(ToolError::code).or(() -> notFound);
        AuditOutcome outcome = reason
            .map(code -> REFUSALS.contains(code) ? AuditOutcome.REFUSED : AuditOutcome.FAILED)
            .orElse(AuditOutcome.EXECUTED);
        boolean fromCache = read.map(ServerRead::fromCache).orElse(false);
        int diagramsRead = read.map(server -> server.diagrams().size()).orElse(0);
        int masked = returned.stream().mapToInt(PartnerService::maskedShown).sum();
        boolean written = false;
        try {
            audit.perServer(result.node(), outcome, reason, fromCache, diagramsRead,
                returned.size(), masked);
            written = true;
        } finally {
            LOG.info("{} on {}: {}{} (owner {}, systemDiagramsRead {}, partnersReturned {},"
                    + " valuesMasked {}, fromCache {}; call {}{})", TOOL, result.node(), outcome,
                reason.map(code -> " " + code).orElse(""),
                read.map(ServerRead::owner).or(query::owner)
                    .or(() -> owners.apply(result.node())).orElse("none"), diagramsRead,
                returned.size(), masked, fromCache, audit.callId(),
                written ? "" : ", no audit record");
        }
    }

    /**
     * {@code NOT_FOUND} if at least one server answered and none of them has a system diagram
     * called {@code name} (any case, any diagram group).
     */
    private static Optional<ToolError> unknownSystemDiagram(String name,
        List<NodeResult<ServerRead>> results) {
        List<NodeResult<ServerRead>> answered = results.stream()
            .filter(result -> result.payload().isPresent())
            .toList();
        List<String> names = answered.stream()
            .flatMap(result -> result.payload().orElseThrow().systemDiagrams().stream())
            .map(DiagramRef::name)
            .toList();
        if (answered.isEmpty() || names.stream().anyMatch(name::equalsIgnoreCase)) {
            return Optional.empty();
        }
        List<String> similar = SimilarNames.of(name, names, MAX_SIMILAR);
        String where = answered.stream()
            .map(result -> result.node() + " (owner " + result.payload().orElseThrow().owner()
                + ")")
            .collect(Collectors.joining(", "));
        return Optional.of(ToolError.of(ErrorCode.NOT_FOUND,
            "No system diagram named " + Names.quote(name) + " on " + where,
            "The name is misspelled, or the system diagram belongs to another owner",
            similar.isEmpty()
                ? "Call without systemDiagram to see the system diagrams read"
                : "Use one of the similar system diagrams "
                    + similar.stream().map(Names::quote).collect(Collectors.joining(", "))
                    + ", or call without systemDiagram"));
    }

    /** Whether a non-masked field of {@code partner} contains {@code term}. */
    private static boolean matches(Partner partner, Pattern term) {
        return partner.fields().stream()
            .map(Setting::value)
            .anyMatch(value -> value instanceof SettingValue.Shown shown
                && term.matcher(shown.value()).find());
    }

    /**
     * {@code search} as a literal, matched case-insensitively char by char with Unicode case
     * rules; unlike lower-casing both sides, this also holds where lower-casing changes the
     * length (e.g. U+0130).
     */
    private static Pattern containing(String search) {
        return Pattern.compile(Pattern.quote(search),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
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

    /** The masked values {@code partner} shows: in its fields and in the entries shown. */
    private static int maskedShown(PartnerView partner) {
        return (int) Stream.concat(partner.fields().stream(), partner.entries().stream()
                .flatMap(entry -> Stream.concat(entry.protocolSettings().stream(),
                    entry.additionalParameters().stream().flatMap(List::stream))))
            .filter(setting -> setting.masked().isPresent())
            .count();
    }

    /** {@code match} in full, or as a list summary (FR-006). */
    private static PartnerView partnerView(Match match, boolean summary) {
        return new PartnerView(match.diagram().diagram(), match.cloud().moduleName(),
            moduleId(match.diagram(), match.cloud()), settingViews(match.partner().fields()),
            match.partner().maskedCount(), match.entries().stream()
                .map(entry -> summary
                    ? new EntryView(entry.protocol(), workflowView(entry.workflow()),
                        Optional.empty(), settingViews(entry.protocolSettings().stream()
                            .filter(setting -> SUMMARY_KEYS.contains(setting.name()))
                            .toList()), Optional.empty())
                    : new EntryView(entry.protocol(), workflowView(entry.workflow()),
                        entry.format(), settingViews(entry.protocolSettings()),
                        Optional.of(settingViews(entry.additionalParameters()))))
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
        return read(targets.resolve(query.target()), query);
    }

    private List<NodeResult<ServerRead>> read(List<NodeId> servers, PartnerQuery query) {
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
     * reads the same is read again once through the cache. If that joined another call's read,
     * which again does not answer, this call reads on its own without the cache, so a call makes
     * at most two cache rounds and at most one read of its own.
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
        if (!snapshot.value().answers(owner, needed)) {
            snapshot = cache.get(key, true, loader);
        }
        if (snapshot.value().answers(owner, needed)) {
            return snapshot;
        }
        // not cached; the audit's clock is the cache's clock (Wiring)
        Instant started = audits.clock().instant();
        return new Snapshot<>(loader.get(), started, started);
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
