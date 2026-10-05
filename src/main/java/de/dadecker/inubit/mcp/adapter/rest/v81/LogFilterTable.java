package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.Severity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The per-log-type filter table of INUBIT 8.1.17 (contracts/mcp-tools.md §4, spike S-5) and the
 * severity table (data-model.md → LogEntry). It is the allowlist of the fields sent in a
 * {@code <logRequest>}: INUBIT answers unknown or non-filterable fields with HTTP 400 and
 * invalid status values with HTTP 500.
 *
 * @param timeField      the field of {@code since}/{@code until} and of the sorting; empty for
 *                       {@code webserviceManager}
 * @param workflowField  the field of {@code workflow} ({@code EQUAL}), if supported
 * @param processIds     {@code processId} is supported ({@code workflowId}/{@code globalPId})
 * @param severityField  the success/status field the severity is derived from, if any
 * @param severityValues the raw values per severity; a severity missing here is rejected
 * @param textField      the field of {@code text} ({@code LIKE})
 * @param timeColumns    other columns with epoch milliseconds, rendered as ISO-8601
 */
record LogFilterTable(
    LogType logType,
    Optional<String> timeField,
    Optional<String> workflowField,
    boolean processIds,
    Optional<String> severityField,
    Map<Severity, List<String>> severityValues,
    String textField,
    Set<String> timeColumns) {

    static final String WORKFLOW_NAME = "workflowName";
    static final String WORKFLOW_ID = "workflowId";
    static final String GLOBAL_PID = "globalPId";
    static final String MODULE_NAME = "moduleName";
    static final String MESSAGE = "message";

    private static final Map<Severity, List<String>> SUCCESS = severities(
        List.of("false"), List.of(), List.of("true"));
    private static final Map<Severity, List<String>> QUEUE_STATUS = severities(
        List.of("Error"), List.of("Waiting", "Retry"), List.of("Queued", "Processing"));

    LogFilterTable {
        severityValues = Map.copyOf(severityValues);
        timeColumns = Set.copyOf(timeColumns);
    }

    /** The row of {@code logType}. */
    static LogFilterTable of(LogType logType) {
        return switch (logType) {
            case SYSTEM_LOG -> new LogFilterTable(logType, Optional.of("startTime"),
                Optional.of(WORKFLOW_NAME), true, Optional.of("success"), SUCCESS, MESSAGE,
                Set.of("endTime"));
            case QUEUE_LOG -> new LogFilterTable(logType, Optional.of("startTime"),
                Optional.of(WORKFLOW_NAME), true, Optional.of("status"), QUEUE_STATUS,
                MODULE_NAME, Set.of("nextStartTime"));
            case AUDIT_LOG -> new LogFilterTable(logType, Optional.of("time"), Optional.empty(),
                false, Optional.of("success"), SUCCESS, MESSAGE, Set.of());
            case SCHEDULER_LOG -> new LogFilterTable(logType, Optional.of("nextStartTime"),
                Optional.of(WORKFLOW_NAME), false, Optional.empty(), Map.of(), MODULE_NAME,
                Set.of());
            case CONNECTION_LOG -> new LogFilterTable(logType, Optional.of("lastConnection"),
                Optional.empty(), false, Optional.empty(), Map.of(), "systemType", Set.of());
            case KEY_MANAGER_LOG -> new LogFilterTable(logType, Optional.of("validity"),
                Optional.empty(), false, Optional.empty(), Map.of(), "name", Set.of());
            case WEBSERVICE_MANAGER -> new LogFilterTable(logType, Optional.empty(),
                Optional.of(WORKFLOW_NAME), false, Optional.empty(), Map.of(), MODULE_NAME,
                Set.of());
        };
    }

    /** The supported severities in table order. */
    List<Severity> supportedSeverities() {
        return List.of(Severity.values()).stream().filter(severityValues::containsKey).toList();
    }

    /**
     * The supported filters for messages, e.g. {@code since/until (startTime), workflow
     * (workflowName), …}.
     */
    String supportedFilters() {
        List<String> filters = new ArrayList<>();
        timeField.ifPresent(field -> filters.add("since/until (" + field + ")"));
        workflowField.ifPresent(field -> filters.add("workflow (" + field + ")"));
        if (processIds) {
            filters.add("processId (" + WORKFLOW_ID + " for digits, " + GLOBAL_PID
                + " otherwise)");
        }
        severityField.ifPresent(field -> filters.add("severity " + supportedSeverities()
            + " (" + field + ")"));
        filters.add("text (" + textField + ", LIKE)");
        return String.join(", ", filters);
    }

    private static Map<Severity, List<String>> severities(List<String> error, List<String> warn,
        List<String> info) {
        Map<Severity, List<String>> values = new EnumMap<>(Severity.class);
        if (!error.isEmpty()) {
            values.put(Severity.ERROR, error);
        }
        if (!warn.isEmpty()) {
            values.put(Severity.WARN, warn);
        }
        if (!info.isEmpty()) {
            values.put(Severity.INFO, info);
        }
        return values;
    }
}
