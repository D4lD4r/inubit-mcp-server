package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code list_nodes} (contracts/mcp-tools.md §1, FR-003; 002 contracts/mcp-tools-delta.md): the
 * profile, its terminology, and the configured groups and their nodes in config order.
 * Configuration only, so closed-world; the result carries no URLs, usernames, credentials or
 * credential variable prefix (dedicated result records, 002 review G6).
 */
public final class ListNodesTool implements ToolHandler {

    static final String DESCRIPTION = "List the configured INUBIT {groups} and their {nodes} (ids"
        + " `<{group}>/<{node}>`, e.g. `test/node1`), with production classification and whether"
        + " write actions are enabled. Call this first to learn valid `target` and `node`"
        + " values.";

    /** The result: {@code {profile, terminology, groups: [{name, production, nodes}]}}. */
    record Result(Profile profile, Names terminology, List<Group> groups) {
    }

    /** The profile without its credential prefix; an absent description is omitted. */
    record Profile(String name, Optional<String> description) {
    }

    /** The display names of the two levels. */
    record Names(Level group, Level node) {
    }

    record Level(String singular, String plural) {
    }

    record Group(String name, boolean production, List<NodeSummary> nodes) {
    }

    private final Result result;

    /**
     * @param profile the profile this process serves
     * @param nodes   the summaries of all configured nodes, in config order
     */
    public ListNodesTool(ProfileInfo profile, List<NodeSummary> nodes) {
        Map<GroupId, List<NodeSummary>> byGroup = new LinkedHashMap<>();
        for (NodeSummary node : nodes) {
            byGroup.computeIfAbsent(node.group(), group -> new ArrayList<>()).add(node);
        }
        List<Group> groups = new ArrayList<>();
        byGroup.forEach((group, groupNodes) -> groups.add(new Group(group.value(),
            groupNodes.get(0).production(), List.copyOf(groupNodes))));
        Terminology terms = profile.terminology();
        this.result = new Result(new Profile(profile.name(), profile.description()),
            new Names(new Level(terms.groupSingular(), terms.groupPlural()),
                new Level(terms.nodeSingular(), terms.nodePlural())),
            List.copyOf(groups));
    }

    @Override
    public String name() {
        return "list_nodes";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("List INUBIT {Groups} and {Nodes}", false);
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return result;
    }
}
