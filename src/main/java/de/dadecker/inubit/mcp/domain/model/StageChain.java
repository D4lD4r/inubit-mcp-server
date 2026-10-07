package de.dadecker.inubit.mcp.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The stage chain of a profile (feature 005, research D-2, data-model.md → StageChain), derived
 * from the {@code deploy} records of the configuration: per target group the group it receives
 * releases from, how ({@link DeployMode}) and what is never sent there. The chain is acyclic,
 * and a group never receives from itself.
 *
 * @param targets target group → its link, in configuration order (the order of
 *                {@link #render()})
 */
public record StageChain(Map<GroupId, ChainLink> targets) {

    /**
     * How a target is reached.
     *
     * @param source  the group the target receives releases from ({@code deploy.from})
     * @param mode    {@link DeployMode#EXECUTE} or {@link DeployMode#PACKAGE_ONLY}
     * @param exclude what is never deployed into the target
     */
    public record ChainLink(GroupId source, DeployMode mode, List<Exclusion> exclude) {
        public ChainLink {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(mode, "mode");
            exclude = List.copyOf(exclude);
        }
    }

    /**
     * One {@code deploy.exclude} entry: a diagram group name, or a glob ({@code *} one segment,
     * {@code **} any) on workflow and module names or on repository paths.
     */
    public record Exclusion(Kind kind, String pattern) {

        /** What the pattern applies to; {@link #key()} is the configuration key. */
        public enum Kind {
            DIAGRAM_GROUP("diagramGroup"), NAME("name"), REPOSITORY_PATH("repositoryPath");

            private final String key;

            Kind(String key) {
                this.key = key;
            }

            /** The key of the configuration entry, e.g. {@code repositoryPath}. */
            public String key() {
                return key;
            }
        }

        public Exclusion {
            Objects.requireNonNull(kind, "kind");
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("An exclusion needs a non-blank pattern");
            }
        }

        /** As configured, e.g. {@code repositoryPath /Root/*}{@code /stage/**}. */
        @Override
        public String toString() {
            return kind.key() + " " + pattern;
        }
    }

    public StageChain {
        targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        for (GroupId target : targets.keySet()) {
            Set<GroupId> seen = new LinkedHashSet<>();
            for (GroupId current = target; current != null;
                current = Optional.ofNullable(targets.get(current)).map(ChainLink::source)
                    .orElse(null)) {
                if (!seen.add(current)) {
                    throw new IllegalArgumentException("The stage chain has a cycle through "
                        + target);
                }
            }
        }
    }

    /** The link of {@code target}, or empty if it receives no deployments. */
    public Optional<ChainLink> link(GroupId target) {
        return Optional.ofNullable(targets.get(target));
    }

    /** True if no group receives deployments. */
    public boolean isEmpty() {
        return targets.isEmpty();
    }

    /**
     * One line per chain, from a group without {@code deploy} to a group that feeds no other,
     * e.g. {@code dev → int → qa → prod (package only)}; chains in configuration order.
     */
    public List<String> render() {
        List<GroupId> roots = new ArrayList<>();
        for (GroupId target : targets.keySet()) {
            GroupId root = target;
            while (targets.containsKey(root)) {
                root = targets.get(root).source();
            }
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }
        List<String> lines = new ArrayList<>();
        for (GroupId root : roots) {
            walk(root, root.value(), lines);
        }
        return List.copyOf(lines);
    }

    private void walk(GroupId group, String line, List<String> lines) {
        List<GroupId> next = targets.entrySet().stream()
            .filter(entry -> entry.getValue().source().equals(group)).map(Map.Entry::getKey)
            .toList();
        if (next.isEmpty()) {
            lines.add(line);
            return;
        }
        for (GroupId target : next) {
            walk(target, line + " → " + target.value()
                + (targets.get(target).mode() == DeployMode.PACKAGE_ONLY ? " (package only)" : ""),
                lines);
        }
    }
}
