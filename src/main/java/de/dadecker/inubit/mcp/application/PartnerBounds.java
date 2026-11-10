package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.PartnerService.CloudCount;
import de.dadecker.inubit.mcp.application.PartnerService.CloudWorkflows;
import de.dadecker.inubit.mcp.application.PartnerService.DiagramRead;
import de.dadecker.inubit.mcp.application.PartnerService.EntryView;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Size bounds of the {@code find_partners} result (FR-017, research D-9), measured with the
 * serialized size ({@code sizeOf}, the tool result's JSON), as {@link InventoryBounds} does for
 * the inventory.
 *
 * <ul>
 *   <li>Texts: every name and shown value is cut to {@link ItemBounds#MAX_FIELD_CHARS}, warnings
 *       and the messages of unreadable diagrams to {@link ToolError#MAX_EXCERPT_LENGTH}, ending
 *       with {@link ItemBounds#TRUNCATION_MARKER}. A masked value has no text: it stays
 *       {@code masked}/{@code set}, whatever is cut.
 *   <li>Warnings: the version gate's are always kept (FR-016); the reader's are cut to the
 *       longest prefix within {@link #WARNING_SHARE} of the budget, divided among the servers
 *       (one warning per entry with an unresolved workflow could otherwise fill the result).
 *   <li>System diagrams: each server's {@code unreadableDiagrams} (failure details first), then
 *       {@code systemDiagramsRead}, then {@code withoutPartnerManagement} share
 *       {@link #DIAGRAM_SHARE} of the budget, divided among the servers; when they do not fit,
 *       the system diagrams a partner on the page comes from are kept first, the others in list
 *       order (an owner can have hundreds).
 *   <li>Referencable workflows: each cloud's list is cut to the longest prefix within
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
 *   <li>Never cut: the version gate's warnings, the errors of failed servers, the similar
 *       values (at most five) and the fields of the one partner always kept. Only if these alone
 *       exceed the budget does the result too; {@link PartnerService} then still reports
 *       {@code truncated}.
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
    }

    /** A bounded value and whether anything was cut. */
    record Bounded<T>(T value, boolean cut) {
    }

    /** The detail cuts of the page, in order. */
    private static final List<UnaryOperator<PartnerView>> DETAIL_CUTS = List.of(
        PartnerBounds::withoutAdditionalParameters, PartnerBounds::summarySettings);

    private PartnerBounds() {
    }

    /**
     * The servers with their texts, their reader's warnings and their lists of system diagrams
     * cut.
     *
     * @param gateWarnings per server, how many of its first warnings are the version gate's
     * @param page         the partners of the page; the system diagrams they come from are
     *                     kept first
     */
    static Bounded<List<ServerView>> servers(List<ServerView> servers,
        List<Integer> gateWarnings, List<Item> page, int budget, ToIntFunction<Object> sizeOf) {
        Texts texts = new Texts();
        int shares = Math.max(servers.size(), 1);
        int warningShare = (int) (budget * WARNING_SHARE / shares);
        int diagramShare = (int) (budget * DIAGRAM_SHARE / shares);
        List<ServerView> bounded = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            ServerView server = servers.get(i);
            Set<DiagramRef> contributing = page.stream()
                .filter(item -> item.server().equals(server.node()))
                .map(item -> item.partner().systemDiagram())
                .collect(Collectors.toSet());
            List<UnreadableDiagram> unreadable = fit(server.unreadableDiagrams(),
                diagram -> false, diagramShare, sizeOf);
            int left = diagramShare - sizeOf.applyAsInt(unreadable);
            List<DiagramRead> read = fit(server.systemDiagramsRead(),
                diagram -> contributing.contains(
                    new DiagramRef(diagram.name(), diagram.diagramGroup())),
                left, sizeOf);
            left -= sizeOf.applyAsInt(read);
            List<DiagramRef> without = fit(server.withoutPartnerManagement(), diagram -> false,
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
        return new Bounded<>(List.copyOf(bounded), texts.cut);
    }

    /**
     * {@code list} if it fits {@code max}; else the longest prefix within {@code max} of the
     * elements that are {@code first}, then the others, each in list order, kept in list order.
     */
    private static <T> List<T> fit(List<T> list, Predicate<T> first, int max,
        ToIntFunction<Object> sizeOf) {
        if (sizeOf.applyAsInt(list) <= max) {
            return list;
        }
        List<T> ordered = new ArrayList<>(list.stream().filter(first).toList());
        ordered.addAll(list.stream().filter(first.negate()).toList());
        Set<T> kept = Set.copyOf(InventoryBounds.prefixWithin(ordered, Math.max(max, 0),
            sizeOf));
        return list.stream().filter(kept::contains).toList();
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
        int workflowShare = (int) (budget * WORKFLOW_SHARE);
        List<Item> items = new ArrayList<>(page.stream()
            .map(item -> new Item(item.server(), texts.partner(item.partner()),
                texts.cloud(item.cloud(), workflowShare, sizeOf)))
            .toList());
        List<Integer> sizes = new ArrayList<>(items.stream()
            .map(item -> sizeOf.applyAsInt(item.partner()))
            .toList());
        boolean cut = texts.cut;
        for (UnaryOperator<PartnerView> step : DETAIL_CUTS) {
            for (int i = items.size() - 1;
                i >= 0 && size(items, sizes, fixed, sizeOf) > budget; i--) {
                cut |= shrink(items, sizes, i, step, sizeOf);
            }
        }
        while (items.size() > 1 && size(items, sizes, fixed, sizeOf) > budget) {
            items.removeLast();
            sizes.removeLast();
            cut = true;
        }
        while (!items.isEmpty() && size(items, sizes, fixed, sizeOf) > budget
            && shrink(items, sizes, 0, PartnerBounds::withoutLastEntry, sizeOf)) {
            cut = true;
        }
        return new Bounded<>(List.copyOf(items), cut);
    }

    /** Applies {@code step} to the partner at {@code index}; whether that changed it. */
    private static boolean shrink(List<Item> items, List<Integer> sizes, int index,
        UnaryOperator<PartnerView> step, ToIntFunction<Object> sizeOf) {
        Item item = items.get(index);
        PartnerView smaller = step.apply(item.partner());
        if (smaller.equals(item.partner())) {
            return false;
        }
        items.set(index, new Item(item.server(), smaller, item.cloud()));
        sizes.set(index, sizeOf.applyAsInt(smaller));
        return true;
    }

    /**
     * The referencable workflows of the clouds of {@code items}: once per server and cloud, in
     * the order of their first partner.
     */
    static List<CloudWorkflows> workflows(List<Item> items) {
        Map<List<Object>, CloudWorkflows> clouds = new LinkedHashMap<>();
        items.forEach(item -> clouds.putIfAbsent(List.of(item.server(), item.cloud()),
            item.cloud()));
        return List.copyOf(clouds.values());
    }

    /** The result size with {@code items}: arrays add their elements and the commas. */
    private static int size(List<Item> items, List<Integer> sizes, int fixed,
        ToIntFunction<Object> sizeOf) {
        int partners = sizes.stream().mapToInt(Integer::intValue).sum()
            + Math.max(items.size() - 1, 0);
        return fixed + partners + sizeOf.applyAsInt(workflows(items)) - "[]".length();
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

        /** A masked setting carries no value: only its name can be cut. */
        List<SettingView> settings(List<SettingView> settings) {
            return settings.stream()
                .map(setting -> new SettingView(field(setting.name()),
                    setting.value().map(this::field), setting.masked(), setting.set()))
                .toList();
        }
    }
}
