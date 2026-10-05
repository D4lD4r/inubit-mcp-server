package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the {@code target} / {@code node} input of a tool to configured nodes
 * (data-model.md → Target). Failures are thrown as {@link ToolErrorException}; their texts use
 * the profile's terminology (FR-007).
 */
public final class TargetResolver {

    private final List<NodeId> nodes;
    private final Map<GroupId, List<NodeId>> byGroup = new LinkedHashMap<>();
    private final List<String> knownIds;
    private final Terminology terms;

    /** With the default terminology ({@link Terminology#DEFAULT}). */
    public TargetResolver(List<NodeId> nodes) {
        this(nodes, Terminology.DEFAULT);
    }

    /**
     * @param nodes all configured nodes in config order
     * @param terms the display names of the levels in messages
     */
    public TargetResolver(List<NodeId> nodes, Terminology terms) {
        this.nodes = List.copyOf(nodes);
        this.terms = Objects.requireNonNull(terms, "terms");
        if (new HashSet<>(this.nodes).size() != this.nodes.size()) {
            throw new IllegalArgumentException("duplicate node ids: " + nodes);
        }
        for (NodeId node : this.nodes) {
            byGroup.computeIfAbsent(node.group(), group -> new ArrayList<>()).add(node);
        }
        List<String> ids = new ArrayList<>();
        byGroup.forEach((group, groupNodes) -> {
            ids.add(group.value());
            groupNodes.forEach(node -> ids.add(node.value()));
        });
        this.knownIds = List.copyOf(ids);
    }

    /** All nodes in config order; used when a tool's {@code target} is omitted. */
    public List<NodeId> all() {
        return nodes;
    }

    /** All group and node ids, in config order. */
    public List<String> knownIds() {
        return knownIds;
    }

    /** The terminology of the messages. */
    public Terminology terms() {
        return terms;
    }

    /** A group id gives all its nodes in config order; a node id gives that node. */
    public List<NodeId> resolve(String target) {
        return switch (parse(target)) {
            case Target.Group group -> groupNodes(group.id(), target);
            case Target.Node node -> List.of(known(node.id(), target));
        };
    }

    /** For write capabilities: only a single node id is accepted (Story 4 / AS 8). */
    public NodeId resolveSingleServer(String input) {
        return switch (parse(input)) {
            case Target.Group group -> {
                List<NodeId> candidates = groupNodes(group.id(), input);
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    Names.quote(input) + terms.render(" is the id of one {group}; this action needs"
                        + " the id of exactly one {node} (<{group}>/<{node}>)"),
                    terms.render("Write actions affect exactly one {node} and never all {nodes} of"
                        + " one {group}."),
                    "Repeat the call with one of: " + join(candidates.stream()
                        .map(NodeId::value).toList()) + "."));
            }
            case Target.Node id -> known(id.id(), input);
        };
    }

    private Target parse(String target) {
        try {
            return Target.parse(target);
        } catch (IllegalArgumentException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "Invalid target " + Names.quote(target) + terms.render(": expected the id of one"
                    + " {group} (e.g. 'test') or of one {node} (<{group}>/<{node}>, e.g."
                    + " 'test/node1') matching ") + Target.PATTERN.pattern(),
                terms.render("The id does not have the form <{group}> or <{group}>/<{node}>."),
                "Call list_nodes to see the valid ids."));
        }
    }

    private List<NodeId> groupNodes(GroupId group, String input) {
        List<NodeId> groupNodes = byGroup.get(group);
        if (groupNodes == null) {
            throw unknown(input);
        }
        return List.copyOf(groupNodes);
    }

    private NodeId known(NodeId node, String input) {
        if (!nodes.contains(node)) {
            throw unknown(input);
        }
        return node;
    }

    private ToolErrorException unknown(String input) {
        return new ToolErrorException(ToolError.of(ErrorCode.TARGET_UNKNOWN,
            "Unknown target " + Names.quote(input) + terms.render(". Configured {groups} and"
                + " {nodes}: ") + join(knownIds),
            terms.render("The {group} or {node} is not configured (typo or outdated id)."),
            "Use one of the listed ids; list_nodes shows all of them."));
    }

    private static String join(List<String> ids) {
        return String.join(", ", ids);
    }
}
