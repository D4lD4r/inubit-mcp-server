package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.PartnerService.CloudCount;
import de.dadecker.inubit.mcp.application.PartnerService.CloudWorkflows;
import de.dadecker.inubit.mcp.application.PartnerService.ComparedEntryView;
import de.dadecker.inubit.mcp.application.PartnerService.ComparedPartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.ComparedSettingView;
import de.dadecker.inubit.mcp.application.PartnerService.DiagramRead;
import de.dadecker.inubit.mcp.application.PartnerService.EntryView;
import de.dadecker.inubit.mcp.application.PartnerService.KeyView;
import de.dadecker.inubit.mcp.application.PartnerService.PartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.ServerView;
import de.dadecker.inubit.mcp.application.PartnerService.SettingView;
import de.dadecker.inubit.mcp.application.PartnerService.WorkflowView;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

/**
 * Size bounds of the {@code find_partners} result (FR-017, research D-9), measured with the
 * serialized size ({@code sizeOf}, the tool result's JSON), as {@link InventoryBounds} does for
 * the inventory.
 *
 * <ul>
 *   <li>Texts: every name and shown value (also each similar value) is cut to
 *       {@link ItemBounds#MAX_FIELD_CHARS}, warnings and the messages of unreadable diagrams to
 *       {@link ToolError#MAX_EXCERPT_LENGTH}, ending with {@link ItemBounds#TRUNCATION_MARKER}.
 *       A masked value has no text: it stays {@code masked}/{@code set}, whatever is cut.
 *   <li>Warnings: the version gate's are always kept (FR-016); the reader's are cut to the
 *       longest prefix within {@link #WARNING_SHARE} of the budget, divided among the servers
 *       (one warning per entry with an unresolved workflow could otherwise fill the result).
 *   <li>System diagrams: each server's {@code unreadableDiagrams} (failure details first), then
 *       {@code systemDiagramsRead}, then {@code withoutPartnerManagement} share
 *       {@link #DIAGRAM_SHARE} of the budget, divided among the servers; when they do not fit,
 *       the system diagrams the partners on the page come from are kept first, in page order,
 *       the others in list order (an owner can have hundreds). Every partner returned has its
 *       system diagram in {@code systemDiagramsRead}: the first partner's is always kept, and
 *       a partner whose system diagram does not fit leaves the page with the partners after it.
 *   <li>Referencable workflows: each cloud's list is cut once to the longest prefix within
 *       {@link #WORKFLOW_SHARE} of the budget; the clouds of partners cut from the page go with
 *       them.
 *   <li>The page: while the result exceeds its budget, detail is cut before the page's
 *       entries (its partners), each detail step from the last partner on the page to the
 *       first: first the additional parameters, then the protocol settings down to
 *       {@link PartnerService#SUMMARY_KEYS}; then partners from the end of the page
 *       ({@code nextOffset} points at the first one left out). The first partner is always
 *       kept, so paging makes progress; if it alone still does not fit, its protocol entries
 *       are cut from the last.
 *   <li>Every cut is reported ({@code truncated}).
 *   <li>Never left out: the version gate's warnings, the errors of failed servers, the similar
 *       values (at most five), and the fields and the system diagram of the one partner always
 *       kept. Only if these alone exceed the budget does the result too; {@link PartnerService}
 *       then still reports {@code truncated}.
 *   <li>A comparison (T027) is bounded alike: each compared value cut like a shown one (also
 *       each value of a server in {@code differs}), its page cut in the same order; a compared
 *       partner is returned only if its system diagram is listed on every server holding it.
 * </ul>
 */
final class PartnerBounds {

    /** The part of the budget that the warnings of all servers may take at most. */
    static final double WARNING_SHARE = 0.25;
    /** The part of the budget that the system diagram lists of all servers may take at most. */
    static final double DIAGRAM_SHARE = 0.25;
    /** The part of the budget that the referencable workflows of one cloud may take at most. */
    static final double WORKFLOW_SHARE = 0.25;

    /** A partner on the page, with the server it was read from and its cloud's workflows. */
    record Item(NodeId server, PartnerView partner, CloudWorkflows cloud) {

        /** The system diagram it comes from, on its server. */
        List<Source> sources() {
            return List.of(new Source(server, partner.systemDiagram()));
        }
    }

    /**
     * A compared partner on the page (T027), with the servers holding it and the workflows of
     * its cloud on them; partners of one cloud share the same {@code cloud}.
     */
    record ComparedItem(List<NodeId> holders, ComparedPartnerView partner, CloudWorkflows cloud) {

        ComparedItem {
            holders = List.copyOf(holders);
        }

        /** Its system diagram on every server holding it. */
        List<Source> sources() {
            return holders.stream().map(server -> new Source(server, partner.systemDiagram()))
                .toList();
        }
    }

    /** A system diagram on a server that an element of the page comes from. */
    record Source(NodeId server, DiagramRef diagram) {
    }

    /** A bounded value and whether anything was cut. */
    record Bounded<T>(T value, boolean cut) {
    }

    /**
     * The bounded servers and how much of the page they list.
     *
     * @param shown the length of the longest prefix of the page whose partners' system diagrams
     *              are listed in the {@code systemDiagramsRead} of every server they come from;
     *              at least 1 for a page that is not empty
     * @param cut   whether anything of the servers was cut (not the page: its cut shows in
     *              the number of partners returned)
     */
    record Servers(List<ServerView> value, int shown, boolean cut) {
    }

    /** How the page of a single server is cut. */
    private static final Shape<Item, PartnerView> FOUND = new Shape<Item, PartnerView>(Item::partner,
        (item, partner) -> new Item(item.server(), partner, item.cloud()),
        item -> List.of(item.server(), item.cloud()), Item::cloud,
        (item, cloud) -> new Item(item.server(), item.partner(), cloud),
        List.of(PartnerBounds::withoutAdditionalParameters, PartnerBounds::summarySettings),
        PartnerBounds::withoutLastEntry);
    /** How the page of a comparison is cut: in the same order as {@link #FOUND}. */
    private static final Shape<ComparedItem, ComparedPartnerView> COMPARED =
        new Shape<ComparedItem, ComparedPartnerView>(ComparedItem::partner,
        (item, partner) -> new ComparedItem(item.holders(), partner, item.cloud()),
        ComparedItem::cloud, ComparedItem::cloud,
        (item, cloud) -> new ComparedItem(item.holders(), item.partner(), cloud),
        List.of(PartnerBounds::withoutAdditionalParameters, PartnerBounds::summarySettings),
        PartnerBounds::withoutLastEntry);

    private PartnerBounds() {
    }

    /**
     * The servers with their texts, their reader's warnings and their lists of system diagrams
     * cut, and how many partners of the page have their system diagram listed.
     *
     * @param gateWarnings per server, how many of its first warnings are the version gate's
     * @param page         per partner of the page, the system diagrams it comes from (one per
     *                     server holding it); these are kept first, in page order, the first
     *                     partner's always
     */
    static Servers servers(List<ServerView> servers, List<Integer> gateWarnings,
        List<List<Source>> page, int budget, ToIntFunction<Object> sizeOf) {
        Texts texts = new Texts();
        int shares = Math.max(servers.size(), 1);
        int warningShare = (int) (budget * WARNING_SHARE / shares);
        int diagramShare = (int) (budget * DIAGRAM_SHARE / shares);
        List<ServerView> bounded = new ArrayList<>();
        Set<List<Object>> listed = new HashSet<>();
        for (int i = 0; i < servers.size(); i++) {
            ServerView server = servers.get(i);
            List<DiagramRef> refs = server.systemDiagramsRead().stream()
                .map(diagram -> new DiagramRef(diagram.name(), diagram.diagramGroup()))
                .toList();
            List<Integer> contributing = page.stream()
                .flatMap(List::stream)
                .filter(source -> source.server().equals(server.node()))
                .map(source -> refs.indexOf(source.diagram()))
                .distinct()
                .toList();
            boolean first = !page.isEmpty() && page.getFirst().stream()
                .anyMatch(source -> source.server().equals(server.node()));
            List<UnreadableDiagram> unreadable = fit(server.unreadableDiagrams(), List.of(), 0,
                diagramShare, sizeOf);
            int left = diagramShare - sizeOf.applyAsInt(unreadable);
            List<DiagramRead> read = fit(server.systemDiagramsRead(), contributing,
                first ? 1 : 0, left, sizeOf);
            read.forEach(diagram -> listed.add(List.of(server.node(),
                new DiagramRef(diagram.name(), diagram.diagramGroup()))));
            left -= sizeOf.applyAsInt(read);
            List<DiagramRef> without = fit(server.withoutPartnerManagement(), List.of(), 0,
                left, sizeOf);
            List<String> warnings = server.warnings().stream()
                .map(warning -> texts.cut(warning, ToolError.MAX_EXCERPT_LENGTH))
                .toList();
            int gate = gateWarnings.get(i);
            List<String> kept = new ArrayList<>(warnings.subList(0, gate));
            kept.addAll(InventoryBounds.prefixWithin(warnings.subList(gate, warnings.size()),
                warningShare, sizeOf));
            texts.cut |= kept.size() < warnings.size()
                || read.size() < server.systemDiagramsRead().size()
                || without.size() < server.withoutPartnerManagement().size()
                || unreadable.size() < server.unreadableDiagrams().size();
            bounded.add(new ServerView(server.node(), server.group(),
                server.owner().map(texts::field), server.collectedAt(), server.fromCache(),
                read.stream()
                    .map(diagram -> new DiagramRead(texts.field(diagram.name()),
                        texts.field(diagram.diagramGroup()), diagram.clouds().stream()
                            .map(cloud -> new CloudCount(texts.field(cloud.cloud()),
                                cloud.moduleId().map(texts::field), cloud.partners()))
                            .toList()))
                    .toList(),
                without.stream().map(texts::diagram).toList(),
                unreadable.stream()
                    .map(diagram -> new UnreadableDiagram(
                        texts.diagram(diagram.systemDiagram()), diagram.code(),
                        texts.cut(diagram.message(), ToolError.MAX_EXCERPT_LENGTH)))
                    .toList(),
                kept, server.error()));
        }
        int shown = (int) page.stream()
            .takeWhile(sources -> sources.stream()
                .allMatch(source -> listed.contains(List.of(source.server(), source.diagram()))))
            .count();
        return new Servers(List.copyOf(bounded), shown, texts.cut);
    }

    /**
     * {@code list} if it fits {@code max}; else the elements at the positions {@code first}, in
     * that order, then the others in list order, as many as fit {@code max} (at least
     * {@code keep}), kept in list order. Elements are kept by position: of equal elements, only
     * those at a kept position.
     */
    private static <T> List<T> fit(List<T> list, List<Integer> first, int keep, int max,
        ToIntFunction<Object> sizeOf) {
        if (sizeOf.applyAsInt(list) <= max) {
            return list;
        }
        List<Integer> order = new ArrayList<>(first);
        IntStream.range(0, list.size()).filter(index -> !first.contains(index))
            .forEach(order::add);
        int count = Math.max(keep, InventoryBounds.prefixWithin(
            order.stream().map(list::get).toList(), Math.max(max, 0), sizeOf).size());
        return order.subList(0, count).stream().sorted().map(list::get).toList();
    }

    /**
     * The page with its texts cut and, if the result would exceed {@code budget}, its detail,
     * then its last partners, then the entries of the one partner left.
     *
     * @param fixed the size of the result without partners and referencable workflows (both
     *              empty), with room for every field that paging can add
     */
    static Bounded<List<Item>> page(List<Item> page, int fixed, int budget,
        ToIntFunction<Object> sizeOf) {
        Texts texts = new Texts();
        return cut(page, FOUND, texts, texts::partner, fixed, budget, sizeOf);
    }

    /**
     * {@link #page} for the page of a comparison (T027): its texts cut and, if the result would
     * exceed {@code budget}, its detail, then its last partners, then the entries of the one
     * partner left.
     */
    static Bounded<List<ComparedItem>> compared(List<ComparedItem> page, int fixed, int budget,
        ToIntFunction<Object> sizeOf) {
        Texts texts = new Texts();
        return cut(page, COMPARED, texts, texts::compared, fixed, budget, sizeOf);
    }

    private static <T, P> Bounded<List<T>> cut(List<T> page, Shape<T, P> shape, Texts texts,
        UnaryOperator<P> cutTexts, int fixed, int budget, ToIntFunction<Object> sizeOf) {
        int workflowShare = (int) (budget * WORKFLOW_SHARE);
        Map<Object, CloudWorkflows> clouds = new HashMap<>();
        Page<T, P> items = new Page<>(shape, fixed, sizeOf);
        page.forEach(item -> {
            CloudWorkflows cloud = clouds.computeIfAbsent(shape.cloudKey().apply(item),
                key -> texts.cloud(shape.cloud().apply(item), workflowShare, sizeOf));
            T cut = shape.withPartner().apply(item, cutTexts.apply(shape.partner().apply(item)));
            items.add(shape.withCloud().apply(cut, cloud));
        });
        boolean cut = texts.cut;
        for (UnaryOperator<P> step : shape.detailCuts()) {
            for (int i = items.items.size() - 1; i >= 0 && items.size() > budget; i--) {
                cut |= items.shrink(i, step);
            }
        }
        while (items.items.size() > 1 && items.size() > budget) {
            items.removeLast();
            cut = true;
        }
        while (!items.items.isEmpty() && items.size() > budget
            && items.shrink(0, shape.withoutLastEntry())) {
            cut = true;
        }
        return new Bounded<>(List.copyOf(items.items), cut);
    }

    /**
     * The referencable workflows of the clouds of {@code items}: once per server and cloud, in
     * the order of their first partner.
     */
    static List<CloudWorkflows> workflows(List<Item> items) {
        return workflows(items, FOUND);
    }

    /**
     * The referencable workflows of the clouds of compared partners: once per cloud, in the
     * order of their first partner.
     */
    static List<CloudWorkflows> comparedWorkflows(List<ComparedItem> items) {
        return workflows(items, COMPARED);
    }

    private static <T> List<CloudWorkflows> workflows(List<T> items, Shape<T, ?> shape) {
        Map<Object, CloudWorkflows> clouds = new LinkedHashMap<>();
        items.forEach(item -> clouds.putIfAbsent(shape.cloudKey().apply(item),
            shape.cloud().apply(item)));
        return List.copyOf(clouds.values());
    }

    /**
     * The similar values with each text cut, none left out (at most five); values that are equal
     * once cut are shown once.
     */
    static Bounded<Optional<List<String>>> similarValues(Optional<List<String>> values) {
        Texts texts = new Texts();
        Optional<List<String>> bounded = values
            .map(list -> list.stream().map(texts::field).distinct().toList());
        return new Bounded<>(bounded, texts.cut);
    }

    private static PartnerView withoutAdditionalParameters(PartnerView partner) {
        return withEntries(partner, partner.entries().stream()
            .map(entry -> new EntryView(entry.protocol(), entry.workflow(), entry.format(),
                entry.protocolSettings(), Optional.empty()))
            .toList());
    }

    private static PartnerView summarySettings(PartnerView partner) {
        return withEntries(partner, partner.entries().stream()
            .map(entry -> new EntryView(entry.protocol(), entry.workflow(), entry.format(),
                entry.protocolSettings().stream()
                    .filter(setting -> PartnerService.SUMMARY_KEYS.contains(setting.name()))
                    .toList(),
                entry.additionalParameters()))
            .toList());
    }

    private static PartnerView withoutLastEntry(PartnerView partner) {
        List<EntryView> entries = partner.entries();
        return entries.isEmpty() ? partner
            : withEntries(partner, entries.subList(0, entries.size() - 1));
    }

    private static PartnerView withEntries(PartnerView partner, List<EntryView> entries) {
        return new PartnerView(partner.systemDiagram(), partner.cloud(), partner.moduleId(),
            partner.fields(), partner.maskedCount(), entries);
    }

    private static ComparedPartnerView withoutAdditionalParameters(ComparedPartnerView partner) {
        return withEntries(partner, partner.entries().stream()
            .map(entry -> new ComparedEntryView(entry.protocol(), entry.workflow(),
                entry.position(), entry.onlyOn(), entry.format(), entry.protocolSettings(),
                Optional.empty()))
            .toList());
    }

    private static ComparedPartnerView summarySettings(ComparedPartnerView partner) {
        return withEntries(partner, partner.entries().stream()
            .map(entry -> new ComparedEntryView(entry.protocol(), entry.workflow(),
                entry.position(), entry.onlyOn(), entry.format(), entry.protocolSettings()
                    .stream()
                    .filter(setting -> PartnerService.SUMMARY_KEYS.contains(setting.name()))
                    .toList(),
                entry.additionalParameters()))
            .toList());
    }

    private static ComparedPartnerView withoutLastEntry(ComparedPartnerView partner) {
        List<ComparedEntryView> entries = partner.entries();
        return entries.isEmpty() ? partner
            : withEntries(partner, entries.subList(0, entries.size() - 1));
    }

    private static ComparedPartnerView withEntries(ComparedPartnerView partner,
        List<ComparedEntryView> entries) {
        return new ComparedPartnerView(partner.systemDiagram(), partner.cloud(),
            partner.moduleId(), partner.key(), partner.onlyOn(), partner.fields(), entries,
            partner.maskedCount(), partner.ambiguous());
    }

    /**
     * How a page of one kind of partner is cut.
     *
     * @param cloudKey         what tells the clouds on the page apart
     * @param detailCuts       the detail cuts, in order
     * @param withoutLastEntry the cut of the one partner left
     */
    private record Shape<T, P>(Function<T, P> partner, BiFunction<T, P, T> withPartner,
        Function<T, Object> cloudKey, Function<T, CloudWorkflows> cloud,
        BiFunction<T, CloudWorkflows, T> withCloud, List<UnaryOperator<P>> detailCuts,
        UnaryOperator<P> withoutLastEntry) {
    }

    /**
     * The page being cut, with running totals of its share of the result size: the partners
     * and, once per cloud on the page, the referencable workflows; each array adds its elements
     * and the commas between them.
     */
    private static final class Page<T, P> {

        final List<T> items = new ArrayList<>();
        private final List<Integer> sizes = new ArrayList<>();
        /** Per cloud on the page: how many of its partners are on the page. */
        private final Map<Object, Integer> clouds = new HashMap<>();
        /** Per cloud: its size, measured once. */
        private final Map<Object, Integer> cloudSizes = new HashMap<>();
        private final Shape<T, P> shape;
        private final int fixed;
        private final ToIntFunction<Object> sizeOf;
        private int partnerChars;
        private int cloudChars;

        Page(Shape<T, P> shape, int fixed, ToIntFunction<Object> sizeOf) {
            this.shape = shape;
            this.fixed = fixed;
            this.sizeOf = sizeOf;
        }

        /** The result size with this page. */
        int size() {
            return fixed + partnerChars + Math.max(items.size() - 1, 0) + cloudChars
                + Math.max(clouds.size() - 1, 0);
        }

        void add(T item) {
            items.add(item);
            sizes.add(sizeOf.applyAsInt(shape.partner().apply(item)));
            partnerChars += sizes.getLast();
            Object cloud = shape.cloudKey().apply(item);
            if (clouds.merge(cloud, 1, Integer::sum) == 1) {
                cloudChars += cloudSizes.computeIfAbsent(cloud,
                    key -> sizeOf.applyAsInt(shape.cloud().apply(item)));
            }
        }

        void removeLast() {
            T item = items.removeLast();
            partnerChars -= sizes.removeLast();
            Object cloud = shape.cloudKey().apply(item);
            if (clouds.merge(cloud, -1, Integer::sum) == 0) {
                clouds.remove(cloud);
                cloudChars -= cloudSizes.get(cloud);
            }
        }

        /** Applies {@code step} to the partner at {@code index}; whether that changed it. */
        boolean shrink(int index, UnaryOperator<P> step) {
            T item = items.get(index);
            P partner = shape.partner().apply(item);
            P smaller = step.apply(partner);
            if (smaller.equals(partner)) {
                return false;
            }
            items.set(index, shape.withPartner().apply(item, smaller));
            int size = sizeOf.applyAsInt(smaller);
            partnerChars += size - sizes.set(index, size);
            return true;
        }
    }

    /** Cuts texts and remembers whether any was cut. */
    private static final class Texts {

        boolean cut;

        String cut(String text, int max) {
            String bounded = InventoryBounds.cut(text, max);
            cut |= bounded.length() < text.length();
            return bounded;
        }

        String field(String text) {
            return cut(text, ItemBounds.MAX_FIELD_CHARS);
        }

        DiagramRef diagram(DiagramRef diagram) {
            return new DiagramRef(field(diagram.name()), field(diagram.diagramGroup()));
        }

        /** The cloud with its texts cut and its workflows within {@code max}. */
        CloudWorkflows cloud(CloudWorkflows cloud, int max, ToIntFunction<Object> sizeOf) {
            List<String> workflows = InventoryBounds.prefixWithin(
                cloud.workflows().stream().map(this::field).toList(), max, sizeOf);
            cut |= workflows.size() < cloud.workflows().size();
            return new CloudWorkflows(diagram(cloud.systemDiagram()), field(cloud.cloud()),
                cloud.moduleId().map(this::field), workflows);
        }

        PartnerView partner(PartnerView partner) {
            return new PartnerView(diagram(partner.systemDiagram()), field(partner.cloud()),
                partner.moduleId().map(this::field), settings(partner.fields()),
                partner.maskedCount(), partner.entries().stream()
                    .map(entry -> new EntryView(field(entry.protocol()),
                        new WorkflowView(entry.workflow().name().map(this::field),
                            entry.workflow().unresolved()),
                        entry.format().map(this::field), settings(entry.protocolSettings()),
                        entry.additionalParameters().map(this::settings)))
                    .toList());
        }

        /** {@link #partner} for a compared partner (T027): every text, also each differing value. */
        ComparedPartnerView compared(ComparedPartnerView partner) {
            return new ComparedPartnerView(diagram(partner.systemDiagram()),
                field(partner.cloud()), partner.moduleId().map(this::field),
                partner.key().map(key -> new KeyView(field(key.field()), field(key.value()))),
                partner.onlyOn(), comparedSettings(partner.fields()), partner.entries().stream()
                    .map(entry -> new ComparedEntryView(field(entry.protocol()),
                        new WorkflowView(entry.workflow().name().map(this::field),
                            entry.workflow().unresolved()),
                        entry.position(), entry.onlyOn(), entry.format().map(this::compared),
                        comparedSettings(entry.protocolSettings()),
                        entry.additionalParameters().map(this::comparedSettings)))
                    .toList(),
                partner.maskedCount(), partner.ambiguous().stream()
                    .map(ambiguity -> new PartnerComparison.Ambiguity(ambiguity.node(),
                        ambiguity.partners(), ambiguity.moduleIds().stream()
                            .map(this::field).toList()))
                    .toList());
        }

        List<ComparedSettingView> comparedSettings(List<ComparedSettingView> settings) {
            return settings.stream().map(this::compared).toList();
        }

        /** A masked setting carries no value: its name and the reason why not equal are cut. */
        ComparedSettingView compared(ComparedSettingView setting) {
            return new ComparedSettingView(field(setting.name()), setting.equal().map(this::field),
                setting.differs().map(values -> {
                    Map<String, String> cut = new LinkedHashMap<>();
                    values.forEach((node, value) -> cut.put(node, field(value)));
                    return Collections.unmodifiableMap(cut);
                }),
                setting.masked(), setting.set(), setting.sameOn(),
                setting.equality().map(this::field), setting.onlyOn());
        }

        /** A masked setting carries no value: only its name can be cut. */
        List<SettingView> settings(List<SettingView> settings) {
            return settings.stream()
                .map(setting -> new SettingView(field(setting.name()),
                    setting.value().map(this::field), setting.masked(), setting.set()))
                .toList();
        }
    }
}
