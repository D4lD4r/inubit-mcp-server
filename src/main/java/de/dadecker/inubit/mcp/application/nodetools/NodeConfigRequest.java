package de.dadecker.inubit.mcp.application.nodetools;

import java.util.List;
import java.util.Objects;

/**
 * The input of one {@code get_node_config} call (feature 024), as the tool received it after the
 * input schema.
 *
 * @param target the id of exactly one server ({@code <group>/<node>})
 * @param names  the configuration names asked for, as given; empty for all of them
 */
public record NodeConfigRequest(String target, List<String> names) {

    public NodeConfigRequest {
        Objects.requireNonNull(target, "target");
        names = List.copyOf(names);
    }
}
