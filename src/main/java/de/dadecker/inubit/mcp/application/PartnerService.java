package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.InventoryCache.Key;
import de.dadecker.inubit.mcp.application.InventoryCache.Snapshot;
import de.dadecker.inubit.mcp.application.PartnerComparison.ComparedEntry;
import de.dadecker.inubit.mcp.application.PartnerComparison.ComparedPartner;
import de.dadecker.inubit.mcp.application.PartnerComparison.Grouped;
import de.dadecker.inubit.mcp.application.PartnerComparison.SettingComparison;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
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
import de.dadecker.inubit.mcp.domain.model.partner.PartnerUsage.CloudUse;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
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
 *       order above; {@code offset} and {@code limit} (at most {@code resultLimits.maxItems})
 *       select the partners returned, with the number of matches as {@code total} and
 *       {@code nextOffset} while more follow.
 *   <li>Size (T024, FR-017, SC-004): the whole result stays within
 *       {@code resultLimits.maxChars}; texts are cut, and detail before the partners of the page
 *       ({@link PartnerBounds}). {@code truncated} reports any cut, also a page shorter than
 *       {@code limit} although more matches follow; {@code nextOffset} then points at the first
 *       partner left out. Search and filters always match the full values. A result that is
 *       still over its bound (only parts that are never cut) is reported {@code truncated}.
 *   <li>Similar values (T020, FR-005b, FR-011, FR-019): a search without hits on a call where
 *       at least one server answered lists up to five similar shown {@code Id} and {@code Name}
 *       values ({@link SimilarNames}), never a masked one; a {@code systemDiagram} that no
 *       answering server has ends the call with {@code NOT_FOUND} naming up to five similar
 *       system diagram names.
 *   <li>Audit and log (T021, FR-020, FR-021, research D-8): one {@link PartnerAudit} per call
 *       with one call id; one record and one INFO line per targeted server, also for cache hits;
 *       an audit record that cannot be written ends the call in {@code INTERNAL} without data.
 *       {@code partnersReturned} and {@code valuesMasked} count what the result finally holds,
 *       after the size bound: {@code valuesMasked} the masked values shown (fields and the
 *       settings of the entries shown), not the partners' {@code maskedCount}.
 *   <li>Comparison (T026, FR-012…FR-014, US3): {@link #answer} compares a stage target of
 *       several servers, or a target with {@code compareWith}, through {@link PartnerComparison};
 *       a single server stays {@link Found}. The search matches shown field values, never a
 *       field masked on any server; list mode keeps the summary and only what differs. Every
 *       server keeps its own audit record, counting the partners it holds on the page and the
 *       masked values shown for it; a failed server is {@code notCompared}.
 *   <li>Story 4 (T029, FR-015): {@link #referencingClouds} gives {@code get_inventory_item} the
 *       clouds whose {@code referencableWorkflows} name a technical workflow, from the same read
 *       and cache, admitted for {@code get_inventory_item} and not audited (names only).
 * </ul>
 */
public final class PartnerService {

    static final Duration FAN_OUT_GRACE = Duration.ofSeconds(1);
    private static final String TOOL = "find_partners";
    /** The tool whose details read the clouds naming a workflow (story 4, FR-015). */
    private static final String INVENTORY_TOOL = "get_inventory_item";
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
    /** {@link #LIST_ORDER} of a comparison, on the first shown value of a differing field. */
    private static final Comparator<ComparedPartner> COMPARED_ORDER = Comparator
        .comparing((ComparedPartner partner) -> partner.diagram().diagramGroup(), TEXT)
        .thenComparing(partner -> partner.diagram().name(), TEXT)
        .thenComparing(ComparedPartner::cloud, TEXT)
        .thenComparing(partner -> shown(partner, "Id"), TEXT)
        .thenComparing(partner -> shown(partner, "Name"), TEXT);

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
     * @param gateWarnings       how many of the first {@code warnings} are the version gate's
     *                           (FR-016: never cut by the size bound)
     * @param version            the INUBIT version the version gate admitted the read on; empty
     *                           if unknown (a comparison then makes no secret equality statement)
     */
    record ServerRead(String owner, List<DiagramRef> systemDiagrams, Instant collectedAt,
        boolean fromCache, List<SystemDiagramPartners> diagrams,
        List<UnreadableDiagram> unreadableDiagrams, List<String> warnings, int gateWarnings,
        Optional<InubitVersion> version) {

        ServerRead {
            Objects.requireNonNull(owner, "owner");
            systemDiagrams = List.copyOf(systemDiagrams);
            Objects.requireNonNull(collectedAt, "collectedAt");
            diagrams = List.copyOf(diagrams);
            unreadableDiagrams = List.copyOf(unreadableDiagrams);
            warnings = List.copyOf(warnings);
            Objects.requireNonNull(version, "version");
        }
    }

    /**
     * The clouds naming one workflow ({@link #referencingClouds}).
     *
     * @param clouds      in the order of the system diagram list and of the clouds
     * @param unreadable  the system diagrams that gave no data (D-4)
     * @param collectedAt the oldest collection time of the cached parts used
     * @param warnings    the version gate's warnings for the Partner Management (FR-016)
     */
    record CloudReferences(List<CloudUse> clouds, List<UnreadableDiagram> unreadable,
        Instant collectedAt, List<String> warnings) {

        CloudReferences {
            clouds = List.copyOf(clouds);
            unreadable = List.copyOf(unreadable);
            Objects.requireNonNull(collectedAt, "collectedAt");
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * The answer of one call: the partners of one server ({@link Found}), or the comparison of
     * several ({@link Compared}, T026).
     */
    public sealed interface Answer permits Found, Compared {

        /** The call id shared by every audit record of the call (FR-020). */
        UUID callId();
    }

    /**
     * The answer of one call (T019, T020): every field of the tool result, in its order, so the
     * size bound measures exactly what the tool returns (FR-017).
     *
     * @param callId                the call id shared by every audit record of the call (FR-020)
     * @param mode                  {@code search} with a search term, else {@code list}
     * @param compared              always {@code false}; a comparison answers {@link Compared}
     *                              (T026)
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
     * @param offset                the {@code offset} requested
     * @param limit                 the page size applied: {@code limit}, at most
     *                              {@code resultLimits.maxItems}
     * @param nextOffset            the offset of the next page, if more matches follow
     * @param truncated             whether a text, a part of a partner or a partner of the page
     *                              was cut to keep the result within its size bound (FR-017)
     */
    public record Found(UUID callId, String mode, boolean compared, List<ServerView> servers,
        List<CloudWorkflows> referencableWorkflows, List<PartnerView> partners,
        Optional<List<String>> similarValues, int total, int offset, int limit,
        OptionalInt nextOffset, boolean truncated) implements Answer {

        public Found {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(mode, "mode");
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

    /**
     * The answer of a comparison (T026, FR-012…FR-014): a stage target of several servers, or
     * {@code compareWith}. The fields of {@link Found}, in its order, with compared partners,
     * then whether a server failed and which.
     *
     * @param compared              always {@code true}
     * @param servers               one per targeted, then per compared server, in config order
     * @param referencableWorkflows per communication cloud (and module id) of a returned partner:
     *                              the workflows of every server holding a partner of that cloud
     *                              on the page before the size bound, as first seen
     * @param partners              the page of matched partners (FR-012c): the comparison order
     *                              with a search term, else the list order
     * @param total                 the number of matched partners across all servers (FR-012c)
     * @param incomplete            whether a server failed (FR-014)
     * @param notCompared           the failed servers, in server order
     */
    public record Compared(UUID callId, String mode, boolean compared, List<ServerView> servers,
        List<CloudWorkflows> referencableWorkflows, List<ComparedPartnerView> partners,
        Optional<List<String>> similarValues, int total, int offset, int limit,
        OptionalInt nextOffset, boolean truncated, boolean incomplete,
        List<NotCompared> notCompared) implements Answer {

        public Compared {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(mode, "mode");
            servers = List.copyOf(servers);
            referencableWorkflows = List.copyOf(referencableWorkflows);
            partners = List.copyOf(partners);
            similarValues = similarValues.map(List::copyOf);
            Objects.requireNonNull(nextOffset, "nextOffset");
            notCompared = List.copyOf(notCompared);
        }
    }

    /**
     * A server left out of a comparison (FR-014), or of the comparison of one system diagram it
     * could not read (D-4); its error is in its {@link ServerView}.
     *
     * @param systemDiagram the system diagram not read; empty if the server failed as a whole
     */
    public record NotCompared(NodeId node, ErrorCode code, Optional<DiagramRef> systemDiagram) {

        public NotCompared {
            systemDiagram = systemDiagram == null ? Optional.empty() : systemDiagram;
        }

        /** A server that failed as a whole. */
        public NotCompared(NodeId node, ErrorCode code) {
            this(node, code, Optional.empty());
        }
    }

    /** The partner key of a matched partner (FR-013): its key field and that field's value. */
    public record KeyView(String field, String value) {
    }

    /**
     * One partner matched across the servers that hold it, or alone on its server (FR-012,
     * FR-013).
     *
     * @param moduleId    only when two clouds of the diagram share the module name on its server
     * @param key         empty for a partner without key (compared on its server alone)
     * @param onlyOn      the servers holding it, if not every answering server that read its
     *                    system diagram does
     * @param fields      every field, grouped like settings
     * @param entries     all, or those the entry filters match; in list mode each with the
     *                    summary keys and only the differing settings (FR-012c)
     * @param maskedCount the number of masked values per server, grouped (FR-009)
     */
    public record ComparedPartnerView(DiagramRef systemDiagram, String cloud,
        Optional<String> moduleId, Optional<KeyView> key, Optional<List<NodeId>> onlyOn,
        List<ComparedSettingView> fields, List<ComparedEntryView> entries,
        GroupedCount maskedCount, List<PartnerComparison.Ambiguity> ambiguous) {

        public ComparedPartnerView {
            onlyOn = onlyOn.map(List::copyOf);
            fields = List.copyOf(fields);
            entries = List.copyOf(entries);
            ambiguous = List.copyOf(ambiguous);
        }
    }

    /**
     * One entry of a compared partner, matched by protocol, workflow and position (FR-013).
     *
     * @param onlyOn               the servers holding it, if not every server holding the partner
     *                             does
     * @param format               the {@code format} attribute as a setting named
     *                             {@link PartnerComparison#FORMAT}; in list mode only if it
     *                             differs
     * @param additionalParameters all; in list mode only the differing ones, absent if none
     */
    public record ComparedEntryView(String protocol, WorkflowView workflow, int position,
        Optional<List<NodeId>> onlyOn, Optional<ComparedSettingView> format,
        List<ComparedSettingView> protocolSettings,
        Optional<List<ComparedSettingView>> additionalParameters) {

        public ComparedEntryView {
            onlyOn = onlyOn.map(List::copyOf);
            protocolSettings = List.copyOf(protocolSettings);
            additionalParameters = additionalParameters.map(List::copyOf);
        }
    }

    /**
     * A compared field or setting: shown values {@code equal} once or {@code differs} per server;
     * a masked one {@code masked} with {@code set} per server and, if made, the one statement on
     * the stored values: {@code sameOn} groups or why there is none ({@code equality}, FR-012a).
     * Never carries a secret or its fingerprint.
     *
     * @param onlyOn the servers holding it, if not every server holding its partner or entry does
     */
    public record ComparedSettingView(String name, Optional<String> equal,
        Optional<Map<String, String>> differs, Optional<Boolean> masked,
        Optional<Map<String, Boolean>> set, Optional<List<List<NodeId>>> sameOn,
        Optional<String> equality, Optional<List<NodeId>> onlyOn) {
    }

    /** A number equal on every server holding the partner, or per server (FR-009). */
    public record GroupedCount(Optional<Integer> equal, Optional<Map<String, Integer>> differs) {
    }

    private final GatewayFactory gateways;
    private final TargetResolver targets;
    private final FanOut fanOut;
    private final ResultLimiter limiter;
    private final InventoryCache cache;
    private final Function<NodeId, Optional<String>> owners;
    private final Function<NodeId, Duration> timeouts;
    private final Function<NodeId, Duration> exportTimeouts;
    private final ToIntFunction<Object> sizeOf;
    private final Terminology terms;
    private final VersionGate gate;
    private final SecretTiming secretTiming;
    private final PartnerAudit.Dependencies audits;

    /**
     * @param owners         the effective {@code inventory.owner} of each node; empty if none is
     *                       configured
     * @param timeouts       the REST timeout of each server
     * @param limiter        {@code resultLimits}: the page size and the size of the result
     * @param exportTimeouts the {@code cliExportTimeout} of each server: the budget of the
     *                       exports of its system diagrams, as for the module usage index of
     *                       {@link InventoryService}
     * @param sizeOf         a value's serialized size in chars (the tool result's JSON)
     * @param terms          the display names of the levels in messages
     * @param secretTiming   the fan-out allowance for fetching the secrets (feature 010)
     * @param audits         what the audit of every call shares (FR-020)
     */
    public PartnerService(GatewayFactory gateways, TargetResolver targets, FanOut fanOut,
        ResultLimiter limiter, InventoryCache cache, Function<NodeId, Optional<String>> owners,
        Function<NodeId, Duration> timeouts, Function<NodeId, Duration> exportTimeouts,
        ToIntFunction<Object> sizeOf, Terminology terms, VersionGate gate,
        SecretTiming secretTiming, PartnerAudit.Dependencies audits) {
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.fanOut = Objects.requireNonNull(fanOut, "fanOut");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.exportTimeouts = Objects.requireNonNull(exportTimeouts, "exportTimeouts");
        this.sizeOf = Objects.requireNonNull(sizeOf, "sizeOf");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.secretTiming = Objects.requireNonNull(secretTiming, "secretTiming");
        this.audits = Objects.requireNonNull(audits, "audits");
    }

    /**
     * On a single server without {@code query.compareWith()}: the partners whose non-masked
     * fields contain {@code query.search()}, case-insensitively, each in full (FR-004, FR-005,
     * FR-007, FR-011); without a search term every partner as a summary in list order (FR-006);
     * either narrowed by the {@code protocol} and {@code workflow} entry filters (FR-005a); the
     * page {@code query.offset()}, {@code query.limit()} of them, the result within
     * {@code resultLimits} (FR-017). A search without hits gets similar values if the server
     * answered (FR-005b).
     *
     * <p>With a stage target of several servers or with {@code query.compareWith()}, the
     * comparison of the targeted and the compared servers (T026, FR-012…FR-014): partners matched
     * across the servers, a partner included if the search matches a shown field on at least one
     * server and then shown from all; without a search term a summary of each with only the
     * differences (FR-012c); {@code total} and {@code nextOffset} count matched partners; within
     * {@code resultLimits} as well (T027). A failed server is not compared and makes the
     * comparison incomplete.
     *
     * <p>Audit and log (T021, FR-020, FR-021): one {@link PartnerAudit} per call; an unknown
     * target is one {@code REFUSED} record; otherwise one record and one INFO line per server,
     * also for a cache hit or a failed server (its error code as reason, never the message),
     * counting the partners and masked values the result holds after the size bound.
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN} for an unknown {@code target} or
     *     {@code compareWith} before any server is contacted; {@code NOT_FOUND} with up to
     *     {@link #MAX_SIMILAR} similar system diagram names if no server that answered has a
     *     system diagram named {@code query.systemDiagram()} (FR-005a, FR-019); {@code INTERNAL}
     *     naming the call id, and no partner data, if any audit record could not be written
     */
    public Answer answer(PartnerQuery query) {
        PartnerAudit audit = PartnerAudit.of(audits, query);
        List<NodeId> targeted = resolve(audit, query.target());
        Optional<List<NodeId>> compared = query.compareWith().map(other -> resolve(audit, other));
        List<NodeId> servers = Stream.concat(targeted.stream(),
                compared.stream().flatMap(List::stream))
            .distinct()
            .toList();
        return compared.isEmpty() && servers.size() == 1 ? found(audit, servers, query)
            : compared(audit, servers, query);
    }

    /**
     * The servers of {@code target}; an unknown target is audited and logged as refused.
     *
     * @throws ToolErrorException {@code TARGET_UNKNOWN}
     */
    private List<NodeId> resolve(PartnerAudit audit, String target) {
        try {
            return targets.resolve(target);
        } catch (ToolErrorException e) {
            PartnerAudit.Refusal refusal = audit.refused(e.error());
            LOG.info("{} on {}: REFUSED {} (call {}{})", TOOL, LogText.loggable(target),
                e.error().code(), audit.callId(), refusal.recorded() ? "" : ", no audit record");
            throw refusal.exception();
        }
    }

    private Found found(PartnerAudit audit, List<NodeId> targeted, PartnerQuery query) {
        Map<NodeId, List<String>> admitting = new ConcurrentHashMap<>();
        List<NodeResult<ServerRead>> results = read(targeted, query, admitting);
        // a read past the deadline may still be admitted later: one snapshot for view and count
        Map<NodeId, List<String>> admitted = Map.copyOf(admitting);
        Optional<ToolError> notFound = query.systemDiagram()
            .flatMap(name -> unknownSystemDiagram(name, results));
        boolean summary = query.search().isEmpty();
        List<List<Match>> matched = results.stream()
            .map(result -> notFound.isEmpty() ? matched(result, query) : List.<Match>of())
            .toList();
        int total = matched.stream().mapToInt(List::size).sum();
        int from = Math.min(query.offset(), total);
        int limit = Math.min(query.limit(), limiter.maxItems());
        int to = Math.min(from + limit, total);
        List<PartnerBounds.Item> page = new ArrayList<>();
        int position = 0;
        for (int i = 0; i < results.size(); i++) {
            NodeId node = results.get(i).node();
            List<Match> server = matched.get(i);
            int size = server.size();
            server.subList(Math.clamp(from - position, 0, size), Math.clamp(to - position, 0, size))
                .forEach(match -> page.add(new PartnerBounds.Item(node,
                    partnerView(match, summary), new CloudWorkflows(match.diagram().diagram(),
                        match.cloud().moduleName(), moduleId(match.diagram(), match.cloud()),
                        match.cloud().referencableWorkflows()))));
            position += size;
        }
        boolean answered = results.stream().anyMatch(result -> result.payload().isPresent());
        PartnerBounds.Bounded<Optional<List<String>>> similar = PartnerBounds.similarValues(
            query.search()
                .filter(search -> total == 0 && answered)
                .map(search -> SimilarNames.of(search, similarCandidates(results), MAX_SIMILAR)));
        int budget = limiter.maxChars();
        String mode = summary ? "list" : "search";
        // the page is cut to the partners whose system diagram the servers list
        PartnerBounds.Servers servers = PartnerBounds.servers(
            results.stream().map(result -> serverView(result, admitted)).toList(),
            results.stream().map(result -> gateWarnings(result, admitted)).toList(),
            page.stream().map(PartnerBounds.Item::sources).toList(), budget, sizeOf);
        // nextOffset and truncated at their widest: paging can only shorten them
        int fixed = sizeOf.applyAsInt(new Found(audit.callId(), mode, false, servers.value(),
            List.of(), List.of(), similar.value(), total, query.offset(), limit,
            OptionalInt.of(total), false));
        PartnerBounds.Bounded<List<PartnerBounds.Item>> bounded =
            PartnerBounds.page(page.subList(0, servers.shown()), fixed, budget, sizeOf);
        List<PartnerBounds.Item> returned = bounded.value();
        ToolErrorException unaudited = null;
        for (NodeResult<ServerRead> result : results) {
            List<PartnerView> shown = returned.stream()
                .filter(item -> item.server().equals(result.node()))
                .map(PartnerBounds.Item::partner)
                .toList();
            try {
                audit(audit, query, result, notFound.map(ToolError::code), shown.size(),
                    shown.stream().mapToInt(PartnerService::maskedShown).sum());
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
        int next = from + returned.size();
        boolean truncated = servers.cut() || similar.cut() || bounded.cut()
            || returned.size() < Math.min(query.limit(), total - from);
        Found found = new Found(audit.callId(), mode, false, servers.value(),
            PartnerBounds.workflows(returned),
            returned.stream().map(PartnerBounds.Item::partner).toList(), similar.value(), total,
            query.offset(), limit, next < total ? OptionalInt.of(next) : OptionalInt.empty(),
            truncated);
        // a result over its bound (parts that are never left out, see PartnerBounds) is never
        // reported as complete
        return truncated || sizeOf.applyAsInt(found) <= budget ? found
            : new Found(found.callId(), mode, false, found.servers(),
                found.referencableWorkflows(), found.partners(), found.similarValues(), total,
                query.offset(), limit, found.nextOffset(), true);
    }

    /**
     * The comparison of {@code servers} (T026): read as for {@link #found}, compared
     * ({@link PartnerComparison}), then searched, filtered, ordered and paged by matched partner,
     * the result bounded as {@link #found} does (T027, {@link PartnerBounds}).
     */
    private Compared compared(PartnerAudit audit, List<NodeId> servers, PartnerQuery query) {
        Map<NodeId, List<String>> admitting = new ConcurrentHashMap<>();
        List<NodeResult<ServerRead>> results = read(servers, query, admitting);
        // a read past the deadline may still be admitted later: one snapshot for view and count
        Map<NodeId, List<String>> admitted = Map.copyOf(admitting);
        Optional<ToolError> notFound = query.systemDiagram()
            .flatMap(name -> unknownSystemDiagram(name, results));
        boolean summary = query.search().isEmpty();
        PartnerComparison.Result comparison = PartnerComparison.compare(results.stream()
            .map(result -> result.payload()
                .<PartnerComparison.ServerInput>map(read -> new PartnerComparison.Answered(
                    result.node(), read.version(), read.diagrams(),
                    read.unreadableDiagrams()))
                .orElseGet(() -> new PartnerComparison.Failed(result.node(),
                    result.error().orElseThrow())))
            .toList());
        List<ComparedPartner> matched = notFound.isPresent() ? List.of()
            : matched(comparison.partners(), query);
        int total = matched.size();
        int from = Math.min(query.offset(), total);
        int limit = Math.min(query.limit(), limiter.maxItems());
        List<ComparedPartner> page = matched.subList(from, Math.min(from + limit, total));
        List<NodeId> answering = results.stream()
            .filter(result -> result.payload().isPresent())
            .map(NodeResult::node)
            .toList();
        PartnerBounds.Bounded<Optional<List<String>>> similar = PartnerBounds.similarValues(
            query.search()
                .filter(search -> total == 0 && !answering.isEmpty())
                .map(search -> SimilarNames.of(search, similarCandidates(comparison),
                    MAX_SIMILAR)));
        int budget = limiter.maxChars();
        String mode = summary ? "list" : "search";
        Map<CloudKey, CloudWorkflows> clouds = comparedWorkflows(page, results, comparison);
        List<PartnerBounds.ComparedItem> items = page.stream()
            .map(partner -> new PartnerBounds.ComparedItem(
                holders(partner, comparison),
                comparedView(partner, summary),
                clouds.get(new CloudKey(partner.diagram(), partner.cloud(), partner.moduleId()))))
            .toList();
        // the page is cut to the partners whose system diagram every server holding them lists
        PartnerBounds.Servers bounded = PartnerBounds.servers(
            results.stream().map(result -> serverView(result, admitted)).toList(),
            results.stream().map(result -> gateWarnings(result, admitted)).toList(),
            items.stream().map(PartnerBounds.ComparedItem::sources).toList(), budget, sizeOf);
        List<NotCompared> notCompared = notCompared(comparison, bounded.value());
        // nextOffset and truncated at their widest: paging can only shorten them
        int fixed = sizeOf.applyAsInt(new Compared(audit.callId(), mode, true, bounded.value(),
            List.of(), List.of(), similar.value(), total, query.offset(), limit,
            OptionalInt.of(total), false, comparison.incomplete(), notCompared));
        PartnerBounds.Bounded<List<PartnerBounds.ComparedItem>> cut = PartnerBounds.compared(
            items.subList(0, bounded.shown()), fixed, budget, sizeOf);
        List<PartnerBounds.ComparedItem> returned = cut.value();
        ToolErrorException unaudited = null;
        for (NodeResult<ServerRead> result : results) {
            NodeId node = result.node();
            List<ComparedPartnerView> held = result.payload().isEmpty() ? List.of()
                : returned.stream()
                    .filter(item -> item.holders().contains(node))
                    .map(PartnerBounds.ComparedItem::partner)
                    .toList();
            try {
                audit(audit, query, result, notFound.map(ToolError::code), held.size(),
                    held.stream().mapToInt(view -> maskedShown(view, node)).sum());
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
        int next = from + returned.size();
        boolean truncated = bounded.cut() || similar.cut() || cut.cut()
            || returned.size() < Math.min(query.limit(), total - from);
        Compared result = new Compared(audit.callId(), mode, true, bounded.value(),
            PartnerBounds.comparedWorkflows(returned),
            returned.stream().map(PartnerBounds.ComparedItem::partner).toList(), similar.value(),
            total, query.offset(), limit, next < total ? OptionalInt.of(next) : OptionalInt.empty(),
            truncated, comparison.incomplete(), notCompared);
        // never reported as complete while over its bound
        return truncated || sizeOf.applyAsInt(result) <= budget ? result
            : new Compared(result.callId(), result.mode(), true, result.servers(),
                result.referencableWorkflows(), result.partners(), result.similarValues(), total,
                query.offset(), limit, result.nextOffset(), true, result.incomplete(),
                result.notCompared());
    }

    /**
     * The compared partners that match the search term on a shown value of at least one server
     * (FR-012c) and the entry filters per entry key (FR-005a), each with only its matching
     * entries under a filter: in comparison order with a search term, else in
     * {@link #COMPARED_ORDER}. A field masked on any server is never searched (FR-011).
     */
    private static List<ComparedPartner> matched(List<ComparedPartner> partners,
        PartnerQuery query) {
        Optional<Pattern> term = query.search().map(PartnerService::containing);
        boolean entryFilter = query.protocol().isPresent() || query.workflow().isPresent();
        List<ComparedPartner> matched = new ArrayList<>();
        for (ComparedPartner partner : partners) {
            List<ComparedEntry> entries = partner.entries().stream()
                .filter(entry -> matches(entry, query))
                .toList();
            if ((term.isEmpty() || matches(partner, term.get()))
                && !(entryFilter && entries.isEmpty())) {
                matched.add(new ComparedPartner(partner.diagram(), partner.cloud(),
                    partner.moduleId(), partner.key(), partner.onlyOn(), partner.fields(),
                    partner.maskedCount(), entries, partner.ambiguous()));
            }
        }
        if (query.search().isEmpty()) {
            matched.sort(COMPARED_ORDER);
        }
        return matched;
    }

    /** {@link #matches(ProtocolEntry, PartnerQuery)} for an entry key of a comparison. */
    private static boolean matches(ComparedEntry entry, PartnerQuery query) {
        return query.protocol().map(entry.protocol()::equalsIgnoreCase).orElse(true)
            && query.workflow()
                .map(workflow -> entry.workflow().filter(workflow::equalsIgnoreCase).isPresent())
                .orElse(true);
    }

    /** Whether a shown value of a field of {@code partner}, on any server, contains the term. */
    private static boolean matches(ComparedPartner partner, Pattern term) {
        return partner.fields().stream()
            .flatMap(PartnerService::shownValues)
            .anyMatch(value -> term.matcher(value).find());
    }

    /** The shown values of {@code setting}: one if equal, else one per server; none if masked. */
    private static Stream<String> shownValues(SettingComparison setting) {
        return switch (setting) {
            case SettingComparison.Plain plain -> switch (plain.value()) {
                case Grouped.Equal<String> equal -> Stream.of(equal.value());
                case Grouped.Differs<String> differs -> differs.values().values().stream();
            };
            case SettingComparison.Masked masked -> Stream.empty();
        };
    }

    /**
     * The first shown value of the field {@code name} of {@code partner} (the value of the first
     * server where it differs); {@code null} if masked or missing.
     */
    private static String shown(ComparedPartner partner, String name) {
        return partner.fields().stream()
            .filter(field -> field.name().equals(name))
            .flatMap(PartnerService::shownValues)
            .findFirst()
            .orElse(null);
    }

    /**
     * Per communication cloud (and module id) of the page, the referencable workflows of every
     * server holding a partner of the cloud on the page, as first seen (FR-007); the
     * size bound keeps them whole for the partners of the cloud it leaves on the page.
     */
    private static Map<CloudKey, CloudWorkflows> comparedWorkflows(List<ComparedPartner> page,
        List<NodeResult<ServerRead>> results, PartnerComparison.Result comparison) {
        Map<CloudKey, Set<String>> clouds = new LinkedHashMap<>();
        for (ComparedPartner partner : page) {
            List<NodeId> holders = holders(partner, comparison);
            Set<String> workflows = clouds.computeIfAbsent(
                new CloudKey(partner.diagram(), partner.cloud(), partner.moduleId()),
                key -> new LinkedHashSet<>());
            results.stream()
                .filter(result -> holders.contains(result.node()))
                .flatMap(result -> result.payload().stream())
                .flatMap(read -> read.diagrams().stream())
                .filter(diagram -> diagram.diagram().equals(partner.diagram()))
                .flatMap(diagram -> diagram.clouds().stream())
                .filter(cloud -> cloud.moduleName().equals(partner.cloud())
                    && partner.moduleId().map(cloud.moduleId()::equals).orElse(true))
                .forEach(cloud -> workflows.addAll(cloud.referencableWorkflows()));
        }
        Map<CloudKey, CloudWorkflows> workflows = new LinkedHashMap<>();
        clouds.forEach((key, names) -> workflows.put(key, new CloudWorkflows(key.diagram(),
            key.cloud(), key.moduleId(), List.copyOf(names))));
        return workflows;
    }

    /** The servers holding {@code partner}: its {@code onlyOn}, else every reader of it. */
    private static List<NodeId> holders(ComparedPartner partner,
        PartnerComparison.Result comparison) {
        return partner.onlyOn().isEmpty() ? comparison.readers(partner.diagram())
            : partner.onlyOn();
    }

    /**
     * The failed servers and the system diagrams answering servers could not read, in server
     * order (FR-014, D-4): only those its bounded {@code unreadableDiagrams} keep, with their
     * cut names, so they stay within the size bound (their cut shows in {@code truncated}).
     */
    private static List<NotCompared> notCompared(PartnerComparison.Result comparison,
        List<ServerView> servers) {
        List<NotCompared> notCompared = new ArrayList<>();
        for (ServerView server : servers) {
            comparison.notCompared().stream()
                .filter(failed -> failed.node().equals(server.node()))
                .forEach(failed -> notCompared.add(
                    new NotCompared(failed.node(), failed.error().code())));
            server.unreadableDiagrams().forEach(gone -> notCompared.add(new NotCompared(
                server.node(), gone.code(), Optional.of(gone.systemDiagram()))));
        }
        return notCompared;
    }

    /** A communication cloud of a compared partner, told apart by module id where needed. */
    private record CloudKey(DiagramRef diagram, String cloud, Optional<String> moduleId) {
    }

    /**
     * {@code partner} in full, or in list mode (FR-012c) with every field and per entry the
     * summary keys and the settings that differ; {@code format} and the additional parameters
     * only where they differ.
     */
    private static ComparedPartnerView comparedView(ComparedPartner partner, boolean summary) {
        return new ComparedPartnerView(partner.diagram(), partner.cloud(), partner.moduleId(),
            partner.key().map(key -> new KeyView(key.field(), key.value())),
            nodes(partner.onlyOn()), comparedViews(partner.fields().stream()),
            partner.entries().stream().map(entry -> comparedView(entry, summary)).toList(),
            switch (partner.maskedCount()) {
                case Grouped.Equal<Integer> equal ->
                    new GroupedCount(Optional.of(equal.value()), Optional.empty());
                case Grouped.Differs<Integer> differs ->
                    new GroupedCount(Optional.empty(), Optional.of(byNode(differs.values())));
            }, partner.ambiguous());
    }

    private static ComparedEntryView comparedView(ComparedEntry entry, boolean summary) {
        WorkflowView workflow = new WorkflowView(entry.workflow(),
            entry.workflow().isPresent() ? Optional.empty() : Optional.of(true));
        if (!summary) {
            return new ComparedEntryView(entry.protocol(), workflow, entry.position(),
                nodes(entry.onlyOn()), entry.format().map(PartnerService::comparedView),
                comparedViews(entry.protocolSettings().stream()),
                Optional.of(comparedViews(entry.additionalParameters().stream())));
        }
        List<ComparedSettingView> additional = comparedViews(entry.additionalParameters()
            .stream().filter(PartnerService::differs));
        return new ComparedEntryView(entry.protocol(), workflow, entry.position(),
            nodes(entry.onlyOn()),
            entry.format().filter(PartnerService::differs).map(PartnerService::comparedView),
            comparedViews(entry.protocolSettings().stream()
                .filter(setting -> SUMMARY_KEYS.contains(setting.name()) || differs(setting))),
            additional.isEmpty() ? Optional.empty() : Optional.of(additional));
    }

    /**
     * Whether {@code setting} is a difference of list mode (FR-012c): held only on some servers,
     * shown with different values, or masked with a different {@code set} or with more than one
     * group of stored values.
     */
    private static boolean differs(SettingComparison setting) {
        return !setting.onlyOn().isEmpty() || switch (setting) {
            case SettingComparison.Plain plain -> plain.value() instanceof Grouped.Differs<String>;
            case SettingComparison.Masked masked ->
                Set.copyOf(masked.set().values()).size() > 1 || masked.sameOn().size() > 1;
        };
    }

    private static List<ComparedSettingView> comparedViews(Stream<SettingComparison> settings) {
        return settings.map(PartnerService::comparedView).toList();
    }

    private static ComparedSettingView comparedView(SettingComparison setting) {
        return switch (setting) {
            case SettingComparison.Plain plain -> switch (plain.value()) {
                case Grouped.Equal<String> equal -> new ComparedSettingView(plain.name(),
                    Optional.of(equal.value()), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), nodes(plain.onlyOn()));
                case Grouped.Differs<String> differs -> new ComparedSettingView(plain.name(),
                    Optional.empty(), Optional.of(byNode(differs.values())), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), nodes(plain.onlyOn()));
            };
            case SettingComparison.Masked masked -> new ComparedSettingView(masked.name(),
                Optional.empty(), Optional.empty(), Optional.of(true),
                Optional.of(byNode(masked.set())),
                masked.sameOn().isEmpty() ? Optional.empty() : Optional.of(masked.sameOn()),
                masked.equality(), nodes(masked.onlyOn()));
        };
    }

    /** {@code values} keyed by node id text, in their order. */
    private static <T> Map<String, T> byNode(Map<NodeId, T> values) {
        Map<String, T> byNode = new LinkedHashMap<>();
        values.forEach((node, value) -> byNode.put(node.toString(), value));
        return Collections.unmodifiableMap(byNode);
    }

    /** {@code onlyOn} as shown: absent when empty (every server holds it). */
    private static Optional<List<NodeId>> nodes(List<NodeId> onlyOn) {
        return onlyOn.isEmpty() ? Optional.empty() : Optional.of(onlyOn);
    }

    /** The masked values {@code partner} shows for {@code node}: those set or not set there. */
    private static int maskedShown(ComparedPartnerView partner, NodeId node) {
        String key = node.toString();
        return (int) Stream.concat(partner.fields().stream(), partner.entries().stream()
                .flatMap(entry -> Stream.of(entry.format().stream(),
                        entry.protocolSettings().stream(),
                        entry.additionalParameters().stream().flatMap(List::stream))
                    .flatMap(Function.identity())))
            .filter(setting -> setting.set().filter(set -> set.containsKey(key)).isPresent())
            .count();
    }

    /**
     * The shown {@code Id} and {@code Name} values of every partner read, uncut (the size bound
     * cuts the similar values and reports it); a masked value is never a candidate (FR-011).
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
                ? Stream.of(shown.value()) : Stream.empty())
            .toList();
    }

    /**
     * The shown {@code Id} and {@code Name} values of the compared partners, uncut, by the rule of
     * the comparison search ({@link #shownValues}): a field masked on any server is never a
     * candidate, so every suggestion is a term the search can match.
     */
    private static List<String> similarCandidates(PartnerComparison.Result comparison) {
        return comparison.partners().stream()
            .flatMap(partner -> partner.fields().stream())
            .filter(field -> SIMILAR_FIELDS.contains(field.name()))
            .flatMap(PartnerService::shownValues)
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
        NodeResult<ServerRead> result, Optional<ErrorCode> notFound, int returned, int masked) {
        Optional<ServerRead> read = result.payload();
        Optional<ErrorCode> reason = result.error().map(ToolError::code).or(() -> notFound);
        AuditOutcome outcome = reason
            .map(code -> REFUSALS.contains(code) ? AuditOutcome.REFUSED : AuditOutcome.FAILED)
            .orElse(AuditOutcome.EXECUTED);
        boolean fromCache = read.map(ServerRead::fromCache).orElse(false);
        int diagramsRead = read.map(server -> server.diagrams().size()).orElse(0);
        boolean written = false;
        try {
            audit.perServer(result.node(), outcome, reason, fromCache, diagramsRead,
                returned, masked);
            written = true;
        } finally {
            LOG.info("{} on {}: {}{} (owner {}, systemDiagramsRead {}, partnersReturned {},"
                    + " valuesMasked {}, fromCache {}; call {}{})", TOOL, result.node(), outcome,
                reason.map(code -> " " + code).orElse(""),
                read.map(ServerRead::owner).or(query::owner)
                    .or(() -> owners.apply(result.node())).orElse("none"), diagramsRead,
                returned, masked, fromCache, audit.callId(),
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

    /**
     * The view of one server's read; a failed server shows the version gate's warnings it was
     * admitted with ({@code admitted}), as an answered one does.
     */
    private static ServerView serverView(NodeResult<ServerRead> result,
        Map<NodeId, List<String>> admitted) {
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
            read.map(ServerRead::warnings)
                .orElseGet(() -> admitted.getOrDefault(result.node(), List.of())),
            result.error());
    }

    /** How many of the server view's first warnings are the version gate's. */
    private static int gateWarnings(NodeResult<ServerRead> result,
        Map<NodeId, List<String>> admitted) {
        return result.payload().map(ServerRead::gateWarnings)
            .orElseGet(() -> admitted.getOrDefault(result.node(), List.of()).size());
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
                Optional.of(shown.value()), Optional.empty(), Optional.empty());
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
        return read(targets.resolve(query.target()), query, new ConcurrentHashMap<>());
    }

    /**
     * @param admitted receives, per server admitted by the version gate, the gate's warnings, so
     *                 that a server whose read fails afterwards (or misses its deadline) keeps them
     */
    private List<NodeResult<ServerRead>> read(List<NodeId> servers, PartnerQuery query,
        Map<NodeId, List<String>> admitted) {
        Set<NodeId> pending = secretTiming.pending(servers);
        return secretTiming.explainTimeouts(fanOut.run(servers, this::deadline,
            server -> read(TOOL, server, query, admitted)), pending);
    }

    /**
     * The clouds of {@code owner}'s system diagrams on {@code server} whose
     * {@code referencableWorkflows} contain {@code workflowName}, case-insensitively like the
     * {@code workflow} filter (story 4, FR-015): the read of {@code find_partners}, admitted by
     * the version gate for {@code get_inventory_item}, through the same cache and not audited
     * (names only). The read runs on its own thread and is awaited for at most
     * {@link #referenceBudget}; a read that takes longer is a {@code TIMEOUT} of this part only
     * (the rest of the detail stays), and goes on to fill the cache for the next call.
     *
     * @throws ToolErrorException the version gate's refusal, a failed read or {@code TIMEOUT}
     */
    CloudReferences referencingClouds(NodeId server, String owner, String workflowName,
        boolean refresh) {
        PartnerQuery query = new PartnerQuery(server.toString(), Optional.empty(),
            Optional.of(owner), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), 0, PartnerQuery.DEFAULT_LIMIT, refresh, Optional.empty());
        ServerRead read = awaitReference(server, query);
        List<CloudUse> clouds = read.diagrams().stream()
            .flatMap(diagram -> diagram.clouds().stream()
                .filter(cloud -> cloud.referencableWorkflows().stream()
                    .anyMatch(workflowName::equalsIgnoreCase))
                .map(cloud -> new CloudUse(diagram.diagram(), cloud.moduleName())))
            .toList();
        return new CloudReferences(clouds, read.unreadableDiagrams(), read.collectedAt(),
            read.warnings().subList(0, read.gateWarnings()));
    }

    /**
     * How long {@link #referencingClouds} waits for the read: {@code 3 × timeout} (guard wait,
     * detection, system diagram list) plus the export budget ({@code cliExportTimeout}), as
     * {@code InventoryService.itemDeadline} allows for it.
     */
    Duration referenceBudget(NodeId server) {
        return timeouts.apply(server).multipliedBy(3).plus(exportTimeouts.apply(server));
    }

    private ServerRead awaitReference(NodeId server, PartnerQuery query) {
        CompletableFuture<ServerRead> read = new CompletableFuture<>();
        Thread.ofVirtual().name("partner-usage " + server).start(() -> {
            try {
                read.complete(read(INVENTORY_TOOL, server, query, new ConcurrentHashMap<>()));
            } catch (RuntimeException | Error e) {
                read.completeExceptionally(e);
            }
        });
        Duration budget = referenceBudget(server);
        try {
            return read.get(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "The system diagrams of " + server + " were not read within " + budget.toMillis()
                    + " ms",
                "Many or large system diagrams, or a slow server; the read goes on and fills the"
                    + " cache",
                "Retry in a moment, or raise cliExportTimeout").withNode(server));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "The read of the system diagrams of " + server + " was stopped",
                "The call reached its deadline", "Retry in a moment").withNode(server));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) e.getCause();
        }
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

    private ServerRead read(String tool, NodeId server, PartnerQuery query,
        Map<NodeId, List<String>> admitted) {
        // a node without owner is never contacted, not even for the version detection
        String owner = query.owner().orElseGet(() -> configuredOwner(server));
        VersionGate.Admission admission = gate.admit(tool, server, Capability.PARTNER_MANAGEMENT,
            Capability.Kind.READ, Set.of(Channel.REST));
        admitted.put(server, List.copyOf(admission.warnings()));
        List<String> warnings = new ArrayList<>(admission.warnings());
        int gateWarnings = warnings.size();
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
            warnings, gateWarnings, admission.version());
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
