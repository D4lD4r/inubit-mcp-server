package de.dadecker.inubit.mcp.domain.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The INUBIT logs offered by {@code query_logs} (FR-011, contracts/mcp-tools.md §4).
 * {@code processLog} is deliberately missing: the endpoint fails on 8.1.17 (research S-5);
 * workflow executions are in {@link #SYSTEM_LOG}.
 */
public enum LogType {
    SYSTEM_LOG("systemLog"),
    QUEUE_LOG("queueLog"),
    CONNECTION_LOG("connectionLog"),
    SCHEDULER_LOG("schedulerLog"),
    AUDIT_LOG("auditLog"),
    KEY_MANAGER_LOG("keyManagerLog"),
    WEBSERVICE_MANAGER("webserviceManager");

    private final String logName;

    LogType(String logName) {
        this.logName = logName;
    }

    /** The INUBIT log name, e.g. {@code systemLog}. */
    public String logName() {
        return logName;
    }

    /** The log type of an INUBIT log name (case-sensitive). */
    public static Optional<LogType> ofLogName(String logName) {
        return Arrays.stream(values()).filter(type -> type.logName.equals(logName)).findFirst();
    }

    /** All log names, in declaration order. */
    public static List<String> logNames() {
        return Arrays.stream(values()).map(LogType::logName).toList();
    }

    @Override
    public String toString() {
        return logName;
    }
}
