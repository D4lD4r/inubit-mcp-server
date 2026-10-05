package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/** Id of one INUBIT server ("environment"): {@code <stage>/<server>}, e.g. {@code qa/node2}. */
public record NodeId(GroupId group, String name) {

    public NodeId {
        Objects.requireNonNull(group, "group");
        if (!GroupId.isValidName(name)) {
            throw new IllegalArgumentException("Invalid node name " + Names.quote(name)
                + ": expected group/node with names matching " + GroupId.NAME_PATTERN.pattern());
        }
    }

    /** Parses {@code <stage>/<server>}. */
    public static NodeId parse(String value) {
        int slash = value == null ? -1 : value.indexOf('/');
        if (slash < 0 || value.indexOf('/', slash + 1) >= 0
            || !GroupId.isValidName(value.substring(0, slash))
            || !GroupId.isValidName(value.substring(slash + 1))) {
            throw new IllegalArgumentException("Invalid node id " + Names.quote(value)
                + ": expected group/node, e.g. 'test/node1', with names matching "
                + GroupId.NAME_PATTERN.pattern());
        }
        return new NodeId(new GroupId(value.substring(0, slash)), value.substring(slash + 1));
    }

    public static NodeId of(String group, String node) {
        return new NodeId(new GroupId(group), node);
    }

    /** The id as used in tool inputs and results. */
    public String value() {
        return group.value() + "/" + name;
    }

    @Override
    public String toString() {
        return value();
    }
}
