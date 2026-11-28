package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one server's call within a fan-out. Payload and error may coexist (e.g. a
 * {@code NOT_FOUND} error together with an item listing similar names); at least one is present.
 *
 * @param warnings feature 025 (FR-030 (b)): the warnings of a failed node, shown beside its error,
 *                 e.g. the unverified warning of a node whose attempt failed after contact; a
 *                 payload carries its own warnings. {@code null}: none
 */
public record NodeResult<T>(NodeId node, Optional<T> payload, Optional<ToolError> error,
    List<String> warnings) {

    public NodeResult {
        Objects.requireNonNull(node, "node");
        payload = payload == null ? Optional.empty() : payload;
        error = error == null ? Optional.empty() : error;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (payload.isEmpty() && error.isEmpty()) {
            throw new IllegalArgumentException("a node result needs a payload or an error");
        }
    }

    /** Without warnings. */
    public NodeResult(NodeId node, Optional<T> payload, Optional<ToolError> error) {
        this(node, payload, error, List.of());
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
