package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A validated {@code query_logs} query for one server (contracts/mcp-tools.md §4, research R-9).
 *
 * @param processId  digits → {@code workflowId}, anything else (UUID) → {@code globalPId}
 * @param severities requested severities; empty = no severity filter
 * @param text       case-sensitive {@code LIKE} pattern; {@code %} and {@code _} are wildcards
 */
public record LogQuery(
    LogType logType,
    Optional<Instant> since,
    Optional<Instant> until,
    Optional<String> workflow,
    Optional<String> processId,
    Set<Severity> severities,
    Optional<String> text,
    int offset,
    int limit) {

    public LogQuery {
        Objects.requireNonNull(logType, "logType");
        since = since == null ? Optional.empty() : since;
        until = until == null ? Optional.empty() : until;
        workflow = workflow == null ? Optional.empty() : workflow;
        processId = processId == null ? Optional.empty() : processId;
        severities = severities.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(severities));
        text = text == null ? Optional.empty() : text;
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset >= 0 and limit >= 1 required");
        }
    }
}
