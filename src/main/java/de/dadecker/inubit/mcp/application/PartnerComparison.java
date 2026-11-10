package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.Partner;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerField;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerKey;
import de.dadecker.inubit.mcp.domain.model.partner.ProtocolEntry;
import de.dadecker.inubit.mcp.domain.model.partner.SecretFingerprint;
import de.dadecker.inubit.mcp.domain.model.partner.Setting;
import de.dadecker.inubit.mcp.domain.model.partner.SettingValue;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The comparison of the Partner Managements of several servers (FR-012 … FR-014, research D-10,
 * data-model.md → PartnerComparison result). Pure: no I/O, no state between calls.
 *
 * <ul>
 *   <li><b>Partner key</b> (FR-013): system diagram, communication cloud (module name) and the
 *       value of one key field, decided per cloud and not per partner (D-10): {@code Id}, or,
 *       without it, the first field declared in {@code PartnerFields} (in the order of the first
 *       server holding the cloud) that every server holding the cloud declares and that at least
 *       one partner of the cloud holds as a non-blank shown value. A masked value is never a key.
 *       A partner whose key field is masked, absent or blank has no key: it is never matched and
 *       is compared on its server alone ({@code key} empty). A blank {@code Id} therefore counts
 *       as absent for its partner: it matches no other blank {@code Id}, and one blank {@code Id}
 *       does not make the cloud fall back to another field (carried over from T006).
 *   <li><b>Ambiguity</b> (FR-013): a key held by several partners on one server, or found on a
 *       server where two clouds of the diagram share the module name, is not matched: each of its
 *       partners is compared on its server alone, all carrying the same {@link Ambiguity} list;
 *       partners of a shared cloud name carry their {@code moduleId}.
 *   <li><b>Entries</b> (FR-013): matched by protocol, workflow name and position; an entry whose
 *       workflow does not resolve is never matched and is compared on its server alone. The
 *       {@code format} attribute is compared like a setting (FR-012).
 *   <li><b>Settings</b> (FR-012, FR-012a): shown values are {@link Grouped.Equal equal} or
 *       {@link Grouped.Differs differ}, compared uncut; a setting masked on any server is
 *       compared as {@link SettingComparison.Masked}.
 *   <li><b>Failed servers</b> (FR-014) are only listed in {@code notCompared}; {@code onlyOn}
 *       and every statement consider the answering servers only.
 *   <li><b>Unreadable system diagrams</b> (D-4): a server that answered but could not read a
 *       system diagram is listed in {@code notRead} with it and left out of the statements of
 *       that diagram's partners as a failed server is; the comparison is incomplete.
 * </ul>
 *
 * <p>The result holds no value of a masked setting and no {@link SecretFingerprint}: fingerprints
 * are turned into server groups here and dropped. Order: clouds as first seen (servers in input
 * order, then export order), within a cloud the matched keys as first seen, then the partners
 * without a key; entries likewise, unresolved entries last.
 */
public final class PartnerComparison {

    /** The setting name under which an entry's {@code format} attribute is compared. */
    public static final String FORMAT = "format";
    private static final String NOT_VERIFIED = "not verified on ";
    private static final String UNKNOWN_VERSION = "an unknown version";

    /** The answering servers in input order, with their version. */
    private final Map<NodeId, Optional<InubitVersion>> versions;
    /** Per answering server, the system diagrams it could not read. */
    private final Map<NodeId, Set<DiagramRef>> unreadable;

    private PartnerComparison(Map<NodeId, Optional<InubitVersion>> versions,
        Map<NodeId, Set<DiagramRef>> unreadable) {
        this.versions = versions;
        this.unreadable = unreadable;
    }

    /** One targeted or compared server: its Partner Managements, or why it gave none. */
    public sealed interface ServerInput {
        NodeId node();
    }

    /**
     * A server that answered.
     *
     * @param version    its INUBIT version; empty if unknown (then no secret equality statement)
     * @param diagrams   the system diagrams read, masked
     * @param unreadable the requested system diagrams that gave no data (D-4)
     */
    public record Answered(NodeId node, Optional<InubitVersion> version,
        List<SystemDiagramPartners> diagrams, List<UnreadableDiagram> unreadable)
        implements ServerInput {

        public Answered {
            Objects.requireNonNull(node, "node");
            version = version == null ? Optional.empty() : version;
            diagrams = List.copyOf(diagrams);
            unreadable = List.copyOf(unreadable);
        }

        /** A server that read every requested system diagram. */
        public Answered(NodeId node, Optional<InubitVersion> version,
            List<SystemDiagramPartners> diagrams) {
            this(node, version, diagrams, List.of());
        }
    }

    /** A server that failed (FR-014): reported as not compared. */
    public record Failed(NodeId node, ToolError error) implements ServerInput {

        public Failed {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(error, "error");
        }
    }

    /** A system diagram an answering server could not read (D-4). */
    public record NotRead(NodeId node, UnreadableDiagram diagram) {

        public NotRead {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(diagram, "diagram");
        }
    }

    /**
     * @param partners    every partner of the answering servers, matched where possible
     * @param notCompared the failed servers, in input order
     * @param notRead     the system diagrams answering servers could not read, in input order
     * @param answering   the answering servers, in input order
     */
    public record Result(List<ComparedPartner> partners, List<Failed> notCompared,
        List<NotRead> notRead, List<NodeId> answering) {

        public Result {
            partners = List.copyOf(partners);
            notCompared = List.copyOf(notCompared);
            notRead = List.copyOf(notRead);
            answering = List.copyOf(answering);
        }

        /**
         * Whether a server failed or could not read a system diagram, so the comparison is
         * incomplete (FR-014).
         */
        public boolean incomplete() {
            return !notCompared.isEmpty() || !notRead.isEmpty();
        }

        /**
         * The servers the partners of {@code diagram} are compared on: the answering servers
         * that did not fail to read it; {@code onlyOn} of such a partner is relative to them.
         */
        public List<NodeId> readers(DiagramRef diagram) {
            return answering.stream()
                .filter(node -> notRead.stream().noneMatch(
                    gone -> gone.node().equals(node) && gone.diagram().systemDiagram()
                        .equals(diagram)))
                .toList();
        }
    }

    /**
     * One partner, matched across the servers that hold it or alone on one server.
     *
     * @param moduleId    the cloud's {@code ModuleId}, only when two clouds of the diagram share
     *                    the module name on the partner's server
     * @param key         empty for a partner without key (never matched)
     * @param onlyOn      the servers holding it, if not every answering server that read its
     *                    system diagram does ({@link Result#readers}); else empty
     * @param maskedCount the number of masked values per server, grouped (FR-009)
     * @param ambiguous   per server where its key is ambiguous; empty for a matched partner
     */
    public record ComparedPartner(DiagramRef diagram, String cloud, Optional<String> moduleId,
        Optional<PartnerKey> key, List<NodeId> onlyOn, List<SettingComparison> fields,
        Grouped<Integer> maskedCount, List<ComparedEntry> entries, List<Ambiguity> ambiguous) {

        public ComparedPartner {
            onlyOn = List.copyOf(onlyOn);
            fields = List.copyOf(fields);
            entries = List.copyOf(entries);
            ambiguous = List.copyOf(ambiguous);
        }
    }

    /**
     * One entry of a compared partner.
     *
     * @param workflow empty for an unresolved workflow (then compared on one server alone)
     * @param onlyOn   the servers holding it, if not every server holding the partner does
     * @param format   the {@code format} attribute compared like a setting named {@link #FORMAT};
     *                 empty if no server's entry has one
     */
    public record ComparedEntry(String protocol, Optional<String> workflow, int position,
        List<NodeId> onlyOn, Optional<SettingComparison> format,
        List<SettingComparison> protocolSettings, List<SettingComparison> additionalParameters) {

        public ComparedEntry {
            onlyOn = List.copyOf(onlyOn);
            protocolSettings = List.copyOf(protocolSettings);
            additionalParameters = List.copyOf(additionalParameters);
        }
    }

    /**
     * Why a key is not matched on {@code node} (FR-013).
     *
     * @param partners  the number of partners holding the key there (above 1: a duplicate)
     * @param moduleIds the {@code ModuleId}s of the clouds of that module name there (more than
     *                  one: clouds of the same name)
     */
    public record Ambiguity(NodeId node, int partners, List<String> moduleIds) {

        public Ambiguity {
            moduleIds = List.copyOf(moduleIds);
        }
    }

    /** A value equal on every server holding it, or the value per server. */
    public sealed interface Grouped<T> {

        record Equal<T>(T value) implements Grouped<T> {
        }

        /** @param values per server, in input order */
        record Differs<T>(Map<NodeId, T> values) implements Grouped<T> {

            public Differs {
                values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            }
        }
    }

    /** One setting (or partner field, or {@code format}) across the servers holding its owner. */
    public sealed interface SettingComparison {

        String name();

        /** The servers holding the setting, if not every server holding its owner does. */
        List<NodeId> onlyOn();

        /** A setting shown on every server holding it. */
        record Plain(String name, Grouped<String> value, List<NodeId> onlyOn)
            implements SettingComparison {

            public Plain {
                onlyOn = List.copyOf(onlyOn);
            }
        }

        /**
         * A setting masked on at least one server (FR-009, FR-012a); a server that shows it counts
         * as set when the value is not empty and has no fingerprint.
         *
         * <p>Only when it is set on at least two servers: {@code sameOn} groups the servers that
         * share a stored value if every one of them runs a version line of
         * {@link CapabilityMatrix#COMPARABLE_SECRET_LINES} and has a fingerprint; else, if one of
         * them runs another or an unknown version, {@code equality} says
         * {@code not verified on <versions>}. A set value without fingerprint (child elements, or
         * shown on its server) gets neither statement (carried over from T011).
         *
         * @param set    per server holding it, whether the value is set
         * @param sameOn groups of servers with an equal stored value, in input order; empty when
         *               no equality statement is made
         */
        record Masked(String name, Map<NodeId, Boolean> set, List<List<NodeId>> sameOn,
            Optional<String> equality, List<NodeId> onlyOn) implements SettingComparison {

            public Masked {
                set = Collections.unmodifiableMap(new LinkedHashMap<>(set));
                sameOn = sameOn.stream().<List<NodeId>>map(List::copyOf).toList();
                onlyOn = List.copyOf(onlyOn);
            }
        }
    }

    private record Slot(DiagramRef diagram, String cloud) {
    }

    private record Held(CommunicationCloud cloud, Partner partner) {
    }

    private record EntryKey(String protocol, String workflow, int position) {
    }

    private record SettingKey(String name, int occurrence) {
    }

    /** Compares {@code servers} (each server once, in the order of the result's maps). */
    public static Result compare(List<? extends ServerInput> servers) {
        Map<NodeId, Optional<InubitVersion>> versions = new LinkedHashMap<>();
        Map<NodeId, Set<DiagramRef>> unreadable = new HashMap<>();
        Map<Slot, Map<NodeId, List<CommunicationCloud>>> slots = new LinkedHashMap<>();
        List<Failed> failed = new ArrayList<>();
        List<NotRead> notRead = new ArrayList<>();
        for (ServerInput server : servers) {
            switch (server) {
                case Failed f -> failed.add(f);
                case Answered answered -> {
                    versions.put(answered.node(), answered.version());
                    for (UnreadableDiagram gone : answered.unreadable()) {
                        notRead.add(new NotRead(answered.node(), gone));
                        unreadable.computeIfAbsent(answered.node(), node -> new HashSet<>())
                            .add(gone.systemDiagram());
                    }
                    for (SystemDiagramPartners diagram : answered.diagrams()) {
                        for (CommunicationCloud cloud : diagram.clouds()) {
                            slots.computeIfAbsent(new Slot(diagram.diagram(), cloud.moduleName()),
                                    slot -> new LinkedHashMap<>())
                                .computeIfAbsent(answered.node(), node -> new ArrayList<>())
                                .add(cloud);
                        }
                    }
                }
            }
        }
        PartnerComparison comparison = new PartnerComparison(versions, unreadable);
        List<ComparedPartner> partners = new ArrayList<>();
        slots.forEach((slot, clouds) -> partners.addAll(comparison.partners(slot, clouds)));
        return new Result(partners, failed, notRead, List.copyOf(versions.keySet()));
    }

    private List<ComparedPartner> partners(Slot slot,
        Map<NodeId, List<CommunicationCloud>> clouds) {
        Optional<String> field = keyField(clouds.values());
        Map<String, Map<NodeId, List<Held>>> keyed = new LinkedHashMap<>();
        List<ComparedPartner> unmatched = new ArrayList<>();
        clouds.forEach((node, onNode) -> {
            for (CommunicationCloud cloud : onNode) {
                for (Partner partner : cloud.partners()) {
                    Held held = new Held(cloud, partner);
                    Optional<String> value = field.flatMap(name -> keyValue(partner, name));
                    if (value.isPresent()) {
                        keyed.computeIfAbsent(value.get(), v -> new LinkedHashMap<>())
                            .computeIfAbsent(node, n -> new ArrayList<>()).add(held);
                    } else {
                        unmatched.add(alone(slot, Optional.empty(), node, held, onNode,
                            List.of()));
                    }
                }
            }
        });
        List<ComparedPartner> result = new ArrayList<>();
        keyed.forEach((value, held) -> {
            Optional<PartnerKey> key = Optional.of(
                new PartnerKey(slot.diagram(), slot.cloud(), field.orElseThrow(), value));
            List<Ambiguity> ambiguous = held.entrySet().stream()
                .filter(on -> on.getValue().size() > 1 || clouds.get(on.getKey()).size() > 1)
                .map(on -> new Ambiguity(on.getKey(), on.getValue().size(),
                    clouds.get(on.getKey()).stream().map(CommunicationCloud::moduleId).toList()))
                .toList();
            if (ambiguous.isEmpty()) {
                result.add(compared(slot, key, Optional.empty(),
                    map(held, onNode -> onNode.get(0).partner()), List.of()));
            } else {
                held.forEach((node, onNode) -> onNode.forEach(one -> result.add(
                    alone(slot, key, node, one, clouds.get(node), ambiguous))));
            }
        });
        result.addAll(unmatched);
        return result;
    }

    /**
     * The key field of one cloud across the servers holding it: {@code Id}, else the first
     * declared field; each only if every one of {@code clouds} declares it and some partner holds
     * a key value in it.
     */
    private static Optional<String> keyField(Collection<List<CommunicationCloud>> clouds) {
        List<CommunicationCloud> all = clouds.stream().flatMap(List::stream).toList();
        return Stream.concat(Stream.of(CommunicationCloud.KEY_FIELD),
                all.get(0).partnerFields().stream().map(PartnerField::name))
            .filter(field -> all.stream().allMatch(cloud -> cloud.partnerFields().stream()
                .anyMatch(declared -> declared.name().equals(field))))
            .filter(field -> all.stream().flatMap(cloud -> cloud.partners().stream())
                .anyMatch(partner -> keyValue(partner, field).isPresent()))
            .findFirst();
    }

    /** The shown, non-blank value of {@code field}; empty if it is absent, masked or blank. */
    private static Optional<String> keyValue(Partner partner, String field) {
        return partner.fields().stream()
            .filter(setting -> setting.name().equals(field))
            .findFirst()
            .map(Setting::value)
            .filter(SettingValue.Shown.class::isInstance)
            .map(value -> ((SettingValue.Shown) value).value())
            .filter(value -> !value.isBlank());
    }

    private ComparedPartner alone(Slot slot, Optional<PartnerKey> key, NodeId node, Held held,
        List<CommunicationCloud> onNode, List<Ambiguity> ambiguous) {
        Optional<String> moduleId = onNode.size() > 1 ? Optional.of(held.cloud().moduleId())
            : Optional.empty();
        return compared(slot, key, moduleId, Map.of(node, held.partner()), ambiguous);
    }

    private ComparedPartner compared(Slot slot, Optional<PartnerKey> key,
        Optional<String> moduleId, Map<NodeId, Partner> holders, List<Ambiguity> ambiguous) {
        return new ComparedPartner(slot.diagram(), slot.cloud(), moduleId, key,
            onlyOn(holders.keySet(), readers(slot.diagram())),
            settings(map(holders, Partner::fields)),
            grouped(map(holders, Partner::maskedCount)), entries(holders), ambiguous);
    }

    /** The answering servers that did not fail to read {@code diagram}. */
    private List<NodeId> readers(DiagramRef diagram) {
        return versions.keySet().stream()
            .filter(node -> !unreadable.getOrDefault(node, Set.of()).contains(diagram))
            .toList();
    }

    private List<ComparedEntry> entries(Map<NodeId, Partner> holders) {
        // the position is unique per (protocol, workflow) within a partner (ProtocolEntry)
        Map<EntryKey, Map<NodeId, ProtocolEntry>> keyed = new LinkedHashMap<>();
        List<Map<NodeId, ProtocolEntry>> unresolved = new ArrayList<>();
        holders.forEach((node, partner) -> {
            for (ProtocolEntry entry : partner.entries()) {
                entry.workflow().name().ifPresentOrElse(
                    workflow -> keyed.computeIfAbsent(
                            new EntryKey(entry.protocol(), workflow, entry.position()),
                            key -> new LinkedHashMap<>())
                        .put(node, entry),
                    () -> unresolved.add(Map.of(node, entry)));
            }
        });
        return Stream.concat(keyed.values().stream(), unresolved.stream())
            .map(held -> entry(held, holders.keySet()))
            .toList();
    }

    private ComparedEntry entry(Map<NodeId, ProtocolEntry> held, Set<NodeId> partnerHolders) {
        ProtocolEntry first = held.values().iterator().next();
        Map<NodeId, List<Setting>> format = map(held, entry -> entry.format()
            .map(value -> List.of(new Setting(FORMAT, new SettingValue.Shown(value))))
            .orElse(List.of()));
        return new ComparedEntry(first.protocol(), first.workflow().name(), first.position(),
            onlyOn(held.keySet(), partnerHolders), settings(format).stream().findFirst(),
            settings(map(held, ProtocolEntry::protocolSettings)),
            settings(map(held, ProtocolEntry::additionalParameters)));
    }

    /** The settings of one owner per server, matched by name and occurrence of the name. */
    private List<SettingComparison> settings(Map<NodeId, List<Setting>> perServer) {
        Map<SettingKey, Map<NodeId, SettingValue>> byName = new LinkedHashMap<>();
        perServer.forEach((node, settings) -> {
            Map<String, Integer> seen = new HashMap<>();
            for (Setting setting : settings) {
                int occurrence = seen.merge(setting.name(), 1, Integer::sum) - 1;
                byName.computeIfAbsent(new SettingKey(setting.name(), occurrence),
                    key -> new LinkedHashMap<>()).put(node, setting.value());
            }
        });
        List<SettingComparison> result = new ArrayList<>();
        byName.forEach((key, values) -> result.add(
            setting(key.name(), values, onlyOn(values.keySet(), perServer.keySet()))));
        return result;
    }

    private SettingComparison setting(String name, Map<NodeId, SettingValue> values,
        List<NodeId> onlyOn) {
        if (values.values().stream().noneMatch(SettingValue.Masked.class::isInstance)) {
            return new SettingComparison.Plain(name,
                grouped(map(values, value -> ((SettingValue.Shown) value).value())), onlyOn);
        }
        Map<NodeId, Boolean> set = map(values, PartnerComparison::isSet);
        List<NodeId> holders = set.keySet().stream().filter(set::get).toList();
        List<List<NodeId>> sameOn = List.of();
        Optional<String> equality = Optional.empty();
        if (holders.size() >= 2) {
            List<String> unverified = holders.stream()
                .map(versions::get)
                .filter(version -> !comparable(version))
                .map(version -> version.map(InubitVersion::raw).orElse(UNKNOWN_VERSION))
                .distinct()
                .toList();
            if (!unverified.isEmpty()) {
                equality = Optional.of(NOT_VERIFIED + String.join(", ", unverified));
            } else {
                sameOn = sameOn(holders, values);
            }
        }
        return new SettingComparison.Masked(name, set, sameOn, equality, onlyOn);
    }

    /** Groups of {@code holders} by fingerprint; empty if one of them has none. */
    private static List<List<NodeId>> sameOn(List<NodeId> holders,
        Map<NodeId, SettingValue> values) {
        Map<SecretFingerprint, List<NodeId>> groups = new LinkedHashMap<>();
        for (NodeId holder : holders) {
            if (!(values.get(holder) instanceof SettingValue.Masked masked)
                || masked.fingerprint().isEmpty()) {
                return List.of();
            }
            groups.computeIfAbsent(masked.fingerprint().get(), fingerprint -> new ArrayList<>())
                .add(holder);
        }
        return List.copyOf(groups.values());
    }

    private static boolean isSet(SettingValue value) {
        return switch (value) {
            case SettingValue.Masked masked -> masked.set();
            case SettingValue.Shown shown -> !shown.value().isEmpty();
        };
    }

    private static boolean comparable(Optional<InubitVersion> version) {
        return version.map(MajorMinor::of)
            .filter(CapabilityMatrix.COMPARABLE_SECRET_LINES::contains)
            .isPresent();
    }

    /** {@code present} if it is not all of {@code all}, else empty. */
    private static List<NodeId> onlyOn(Collection<NodeId> present, Collection<NodeId> all) {
        return present.size() < all.size() ? List.copyOf(present) : List.of();
    }

    private static <T> Grouped<T> grouped(Map<NodeId, T> values) {
        return new HashSet<>(values.values()).size() == 1
            ? new Grouped.Equal<>(values.values().iterator().next())
            : new Grouped.Differs<>(values);
    }

    /** {@code values} mapped by {@code function}, in the same order. */
    private static <V, T> Map<NodeId, T> map(Map<NodeId, V> values, Function<V, T> function) {
        Map<NodeId, T> mapped = new LinkedHashMap<>();
        values.forEach((node, value) -> mapped.put(node, function.apply(value)));
        return mapped;
    }
}
