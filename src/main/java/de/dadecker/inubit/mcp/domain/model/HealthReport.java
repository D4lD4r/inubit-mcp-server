package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Health of one server (data-model.md → HealthReport, FR-005 – FR-008).
 *
 * @param checkedAt   the INUBIT healthcheck timestamp, or the MCP server's clock if INUBIT gave
 *                    none
 * @param reachable   true if {@code /healthcheck} gave any HTTP response
 * @param unavailable parts that could not be obtained, with the reason (FR-008)
 * @param error       set when {@code reachable=false}
 * @param warnings    e.g. maintenance mode, an unsupported version line (FR-029)
 */
public record HealthReport(
    NodeId node,
    GroupId group,
    Instant checkedAt,
    boolean reachable,
    HealthStatus status,
    Optional<Boolean> ready,
    Optional<String> readyMessage,
    Optional<Boolean> maintenanceMode,
    Optional<String> version,
    Optional<SystemInfo> systemInfo,
    Optional<LoadFigures> load,
    List<UnavailablePart> unavailable,
    Optional<ToolError> error,
    List<String> warnings) {

    public HealthReport {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(status, "status");
        ready = orEmpty(ready);
        readyMessage = orEmpty(readyMessage);
        maintenanceMode = orEmpty(maintenanceMode);
        version = orEmpty(version);
        systemInfo = orEmpty(systemInfo);
        load = orEmpty(load);
        unavailable = List.copyOf(unavailable);
        error = orEmpty(error);
        warnings = List.copyOf(warnings);
        if (!reachable && error.isEmpty()) {
            throw new IllegalArgumentException("an unreachable node's report needs an error");
        }
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
