package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one server's call within a fan-out. Payload and error may coexist (e.g. a
 * {@code NOT_FOUND} error together with an item listing similar names); at least one is present.
 */
public record NodeResult<T>(NodeId node, Optional<T> payload, Optional<ToolError> error) {

    public NodeResult {
        Objects.requireNonNull(node, "node");
        payload = payload == null ? Optional.empty() : payload;
        error = error == null ? Optional.empty() : error;
        if (payload.isEmpty() && error.isEmpty()) {
            throw new IllegalArgumentException("a node result needs a payload or an error");
        }
    }

    public static <T> NodeResult<T> success(NodeId node, T payload) {
        return new NodeResult<>(node, Optional.of(payload), Optional.empty());
    }

    public static <T> NodeResult<T> failure(NodeId node, ToolError error) {
        return new NodeResult<>(node, Optional.empty(), Optional.of(error));
    }

    /** True if there is no error. */
    public boolean isSuccess() {
        return error.isEmpty();
    }
}
