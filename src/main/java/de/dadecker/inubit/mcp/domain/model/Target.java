package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** The target of a read capability: a whole stage or a single server (data-model.md → Target). */
public sealed interface Target {

    Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$");

    /** Parses a stage id ({@code test}) or a server id ({@code test/inubit01}). */
    static Target parse(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid target " + Names.quote(value)
                + ": expected a group id (e.g. 'test') or a node id group/node (e.g. 'test/node1')"
                + " matching "
                + PATTERN.pattern());
        }
        return value.indexOf('/') < 0
            ? new Group(new GroupId(value))
            : new Node(NodeId.parse(value));
    }

    /** All nodes of a group. */
    record Group(GroupId id) implements Target {
        public Group {
            Objects.requireNonNull(id, "id");
        }
    }

    /** Exactly one node. */
    record Node(NodeId id) implements Target {
        public Node {
            Objects.requireNonNull(id, "id");
        }
    }
}
