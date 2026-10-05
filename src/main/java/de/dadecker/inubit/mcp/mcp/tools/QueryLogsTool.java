package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.DiagnosisService.LogRequest;
import de.dadecker.inubit.mcp.application.DiagnosisService;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code query_logs} (contracts/mcp-tools.md §4, US2): one page of log entries per resolved
 * server, newest first; a server that fails gets only its {@code error}. A filter that the log
 * type does not support fails the whole call with {@code INVALID_INPUT}.
 */
public final class QueryLogsTool implements ToolHandler {

    static final String DESCRIPTION = "Read INUBIT log entries (systemLog = workflow"
        + " executions, queueLog, auditLog, schedulerLog, connectionLog, keyManagerLog,"
        + " webserviceManager) filtered by time, workflow, severity, process ID, or text."
        + " `text` is a case-sensitive substring match in which `%` matches any sequence and `_`"
        + " any single character. Newest first, paginated.";

    private final DiagnosisService diagnosis;

    public QueryLogsTool(DiagnosisService diagnosis) {
        this.diagnosis = Objects.requireNonNull(diagnosis, "diagnosis");
    }

    @Override
    public String name() {
        return "query_logs";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Query INUBIT logs", true);
    }

    /** The arguments are validated by the SDK against {@code query_logs.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        String logName = args.string("logType");
        LogType logType = LogType.ofLogName(logName).orElseThrow(() ->
            new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "Unknown logType " + Names.quote(logName) + "; supported: "
                    + String.join(", ", LogType.logNames()),
                "processLog is not offered: it fails on INUBIT 8.1.17",
                "Use systemLog for workflow executions")));
        LogRequest request = new LogRequest(
            args.string("target"),
            logType,
            args.optionalString("since"),
            args.optionalString("until"),
            args.optionalString("workflow"),
            args.optionalString("processId"),
            args.strings("severity").stream().map(Severity::valueOf)
                .collect(Collectors.toSet()),
            args.optionalString("text"),
            args.integer("offset", 0),
            args.integer("limit", 50));
        return NodePages.of(diagnosis.queryLogs(request));
    }
}
