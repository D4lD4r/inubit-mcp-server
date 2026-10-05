package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of an INUBIT log (data-model.md → LogEntry). Size bounds: {@code message} at most
 * 2,000 chars, each {@code fields} value at most 200 chars (both with the truncation marker), and
 * the whole entry at most {@code ResultLimiter.MAX_ITEM_CHARS} serialized; mappers enforce them.
 *
 * @param timestamp   the log type's time field; empty for {@code webserviceManager}
 * @param rawSeverity the INUBIT value the severity was derived from, e.g. {@code false} or
 *                    {@code Waiting}
 * @param processId   {@code workflowId}, if the log type has one
 * @param fields      the remaining non-empty columns in INUBIT's order, rendered as strings
 */
public record LogEntry(
    NodeId node,
    LogType logType,
    Optional<Instant> timestamp,
    Severity severity,
    Optional<String> rawSeverity,
    Optional<String> workflow,
    Optional<String> module,
    Optional<String> processId,
    Optional<String> message,
    Map<String, String> fields) {

    public LogEntry {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(logType, "logType");
        Objects.requireNonNull(severity, "severity");
        timestamp = timestamp == null ? Optional.empty() : timestamp;
        rawSeverity = rawSeverity == null ? Optional.empty() : rawSeverity;
        workflow = workflow == null ? Optional.empty() : workflow;
        module = module == null ? Optional.empty() : module;
        processId = processId == null ? Optional.empty() : processId;
        message = message == null ? Optional.empty() : message;
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
