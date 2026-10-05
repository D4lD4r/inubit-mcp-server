package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.InventoryDetail;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.UnavailableInventoryPart;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Size bounds of the inventory results (Constitution VI, data-model.md → Page, InventoryDetail),
 * measured escape-aware with the serialized size ({@code sizeOf}, the tool result's JSON).
 *
 * <ul>
 *   <li>List items: every text (also every listed workflow name) is cut to
 *       {@link ItemBounds#MAX_FIELD_CHARS} with the truncation marker; while the item still
 *       exceeds {@link ItemBounds#MAX_ITEM_CHARS} (JSON escapes can expand a char to six), the
 *       last listed workflow is dropped ({@code workflowCount} keeps the full number), then the
 *       longest text is halved.
 *   <li>Details: texts are cut the same way ({@code unavailable} texts to
 *       {@link ToolError#MAX_EXCERPT_LENGTH}); while the detail exceeds its budget (the
 *       server's share of {@code resultLimits.maxChars}), the list of using workflows is first cut
 *       to {@link #WORKFLOW_SHARE} of the budget (follow-up N2: versions are what the detail is
 *       for, the workflows are only names and {@code workflowCount} keeps the full number); then
 *       the oldest versions are dropped (the newest are kept), then the last modules, then the
 *       last similar names, then the longest texts are halved (the capped workflows always
 *       leave three quarters of the budget, so they need no second cut).
 *   <li>Every cut sets {@code truncated} (list: on the page; detail: on the item).
 * </ul>
 */
final class InventoryBounds {

    private static final String MARKER = ItemBounds.TRUNCATION_MARKER;
    /** The part of a detail's budget that its list of using workflows may take at most. */
    static final double WORKFLOW_SHARE = 0.25;

    /** A bounded value and whether anything was cut. */
    record Bounded<T>(T value, boolean cut) {
    }

    private InventoryBounds() {
    }

    static Bounded<InventoryItem> item(InventoryItem item, ToIntFunction<Object> sizeOf) {
        Texts texts = new Texts();
        String name = texts.cut(item.name(), ItemBounds.MAX_FIELD_CHARS);
        String type = texts.cut(item.type(), ItemBounds.MAX_FIELD_CHARS);
        String group = texts.cut(item.group(), ItemBounds.MAX_FIELD_CHARS);
        String owner = texts.cut(item.owner(), ItemBounds.MAX_FIELD_CHARS);
        Optional<List<String>> workflows = item.workflows().map(list ->
            texts.cutAll(list, ItemBounds.MAX_FIELD_CHARS));
        InventoryItem bounded = item(item, name, type, group, owner, workflows);
        while (sizeOf.applyAsInt(bounded) > ItemBounds.MAX_ITEM_CHARS) {
            texts.cut = true;
            // the listed workflows first (the count stays), then the longest required text
            if (workflows.isPresent() && !workflows.get().isEmpty()) {
                List<String> listed = workflows.get();
                workflows = Optional.of(List.copyOf(listed.subList(0, listed.size() - 1)));
            } else {
                String[] values = {name, type, group, owner};
                int longest = 0;
                for (int i = 1; i < values.length; i++) {
                    if (sizeOf.applyAsInt(values[i]) > sizeOf.applyAsInt(values[longest])) {
                        longest = i;
                    }
                }
                values[longest] = halve(values[longest]);
                name = values[0];
                type = values[1];
                group = values[2];
                owner = values[3];
            }
            bounded = item(item, name, type, group, owner, workflows);
        }
        return new Bounded<>(bounded, texts.cut);
    }

    private static InventoryItem item(InventoryItem item, String name, String type, String group,
        String owner, Optional<List<String>> workflows) {
        return new InventoryItem(item.node(), item.kind(), name, type, group, owner,
            item.active(), item.lastChange(), workflows, item.workflowCount());
    }

    /** The detail within {@code budget} chars (at least {@link ItemBounds#MAX_ITEM_CHARS}). */
    static InventoryDetail detail(InventoryDetail detail, int budget,
        ToIntFunction<Object> sizeOf) {
        Draft draft = new Draft(detail);
        draft.cutTexts();
        if (sizeOf.applyAsInt(draft.build()) > budget) {
            draft.truncated = true;
            int share = (int) (budget * WORKFLOW_SHARE);
            draft.workflows = draft.workflows.map(list -> prefixWithin(list, share, sizeOf));
            draft.versions = draft.versions.map(list ->
                fit(list, kept -> draft.with(d -> d.versions = Optional.of(kept)), budget,
                    sizeOf));
            draft.modules = draft.modules.map(list ->
                fit(list, kept -> draft.with(d -> d.modules = Optional.of(kept)), budget,
                    sizeOf));
            draft.similarNames = draft.similarNames.map(list ->
                fit(list, kept -> draft.with(d -> d.similarNames = Optional.of(kept)), budget,
                    sizeOf));
            while (sizeOf.applyAsInt(draft.build()) > budget && draft.halveLongestText(sizeOf)) {
                // halving until it fits
            }
        }
        return draft.build();
    }

    /** The longest prefix of {@code list} whose own serialized size is at most {@code max}. */
    private static <T> List<T> prefixWithin(List<T> list, int max, ToIntFunction<Object> sizeOf) {
        if (sizeOf.applyAsInt(list) <= max) {
            return list;
        }
        int low = 0;
        int high = list.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (sizeOf.applyAsInt(list.subList(0, middle)) <= max) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return List.copyOf(list.subList(0, low));
    }

    /** The longest prefix of {@code list} whose detail fits {@code budget} (possibly empty). */
    private static <T> List<T> fit(List<T> list, Function<List<T>, InventoryDetail> detailWith,
        int budget, ToIntFunction<Object> sizeOf) {
        if (sizeOf.applyAsInt(detailWith.apply(list)) <= budget) {
            return list;
        }
        int low = 0;
        int high = list.size() - 1; // the full list does not fit
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (sizeOf.applyAsInt(detailWith.apply(list.subList(0, middle))) <= budget) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return List.copyOf(list.subList(0, low));
    }

    /** Cuts {@code text} to {@code max} chars, ending with the marker; surrogate-safe. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Math.max(max - MARKER.length(), 0);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + MARKER;
    }

    /** Half of the text (without a previous marker) plus the marker; empty when nothing is left. */
    static String halve(String text) {
        String base = text.endsWith(MARKER)
            ? text.substring(0, text.length() - MARKER.length()) : text;
        if (base.length() <= 1) {
            return "";
        }
        int end = base.length() / 2;
        if (Character.isHighSurrogate(base.charAt(end - 1))) {
            end--;
        }
        return end == 0 ? "" : base.substring(0, end) + MARKER;
    }

    /** Remembers whether any text was cut. */
    private static final class Texts {
        boolean cut;

        String cut(String text, int max) {
            String bounded = InventoryBounds.cut(text, max);
            cut |= !bounded.equals(text);
            return bounded;
        }

        Optional<String> cut(Optional<String> text, int max) {
            return text.map(value -> cut(value, max));
        }

        List<String> cutAll(List<String> values, int max) {
            return values.stream().map(value -> cut(value, max)).toList();
        }
    }

    /** A mutable copy of a detail while it is bounded. */
    private static final class Draft {

        private final NodeId server;
        private final InventoryKind kind;
        private String name;
        private Optional<String> type;
        private Optional<String> group;
        private String owner;
        private final Optional<Boolean> active;
        private final Optional<Instant> lastChange;
        private Optional<List<String>> workflows;
        private final Optional<Integer> workflowCount;
        private final Optional<Boolean> usageComplete;
        private Optional<String> checkinComment;
        private Optional<String> userComment;
        private Optional<List<VersionEntry>> versions;
        private Optional<List<ModuleRef>> modules;
        private final Optional<ConnectorFlags> connector;
        private Optional<List<String>> similarNames;
        private List<UnavailableInventoryPart> unavailable;
        private boolean truncated;

        Draft(InventoryDetail detail) {
            server = detail.node();
            kind = detail.kind();
            name = detail.name();
            type = detail.type();
            group = detail.group();
            owner = detail.owner();
            active = detail.active();
            lastChange = detail.lastChange();
            workflows = detail.workflows();
            workflowCount = detail.workflowCount();
            usageComplete = detail.usageComplete();
            checkinComment = detail.checkinComment();
            userComment = detail.userComment();
            versions = detail.versions();
            modules = detail.modules();
            connector = detail.connector();
            similarNames = detail.similarNames();
            unavailable = detail.unavailable();
            truncated = detail.truncated();
        }

        void cutTexts() {
            Texts texts = new Texts();
            int max = ItemBounds.MAX_FIELD_CHARS;
            name = texts.cut(name, max);
            type = texts.cut(type, max);
            group = texts.cut(group, max);
            owner = texts.cut(owner, max);
            workflows = workflows.map(list -> texts.cutAll(list, max));
            checkinComment = texts.cut(checkinComment, max);
            userComment = texts.cut(userComment, max);
            versions = versions.map(list -> list.stream().map(version -> new VersionEntry(
                version.version(), texts.cut(version.checkinUser(), max), version.checkinAt(),
                texts.cut(version.checkinComment(), max), texts.cut(version.userComment(), max),
                texts.cutAll(version.tags(), max))).toList());
            modules = modules.map(list -> list.stream().map(module -> new ModuleRef(
                texts.cut(module.name(), max), texts.cut(module.type(), max),
                texts.cut(module.nodeId(), max))).toList());
            similarNames = similarNames.map(list -> texts.cutAll(list, max));
            int longMax = ToolError.MAX_EXCERPT_LENGTH;
            unavailable = unavailable.stream().map(part -> new UnavailableInventoryPart(
                part.part(), texts.cut(part.reason(), longMax),
                texts.cut(part.likelyCause(), longMax), texts.cut(part.nextStep(), longMax)))
                .toList();
            truncated |= texts.cut;
        }

        /** The detail with one change applied to a copy of this draft. */
        InventoryDetail with(Consumer<Draft> change) {
            Draft copy = new Draft(build());
            change.accept(copy);
            return copy.build();
        }

        /**
         * Halves the longest optional text (finally dropping it), else the longest required
         * text; false if nothing is left to shorten.
         */
        boolean halveLongestText(ToIntFunction<Object> sizeOf) {
            List<Optional<String>> optional = List.of(checkinComment, userComment, type, group);
            int best = -1;
            for (int i = 0; i < optional.size(); i++) {
                if (optional.get(i).isPresent() && (best < 0
                    || sizeOf.applyAsInt(optional.get(i).get())
                        > sizeOf.applyAsInt(optional.get(best).get()))) {
                    best = i;
                }
            }
            if (best >= 0) {
                Optional<String> halved = Optional.of(halve(optional.get(best).get()))
                    .filter(text -> !text.isEmpty());
                switch (best) {
                    case 0 -> checkinComment = halved;
                    case 1 -> userComment = halved;
                    case 2 -> type = halved;
                    default -> group = halved;
                }
                return true;
            }
            if (!unavailable.isEmpty()) {
                unavailable = List.of();
                return true;
            }
            if (name.isEmpty() && owner.isEmpty()) {
                return false;
            }
            if (sizeOf.applyAsInt(name) >= sizeOf.applyAsInt(owner)) {
                name = halve(name);
            } else {
                owner = halve(owner);
            }
            return true;
        }

        InventoryDetail build() {
            return new InventoryDetail(server, kind, name, type, group, owner, active,
                lastChange, workflows, workflowCount, usageComplete, checkinComment, userComment,
                versions, modules, connector, similarNames, unavailable, truncated);
        }
    }
}
