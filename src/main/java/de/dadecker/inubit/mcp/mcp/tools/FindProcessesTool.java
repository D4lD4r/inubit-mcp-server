package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.DiagnosisService.ProcessRequest;
import de.dadecker.inubit.mcp.application.DiagnosisService;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code find_processes} (contracts/mcp-tools.md §3, US2): one page of process instances per
 * resolved server, newest first; a server that fails gets its {@code error} and possibly
 * {@code warnings} (feature 025, FR-030 (b)). Only an
 * invalid or unknown target and semantically invalid input fail the whole call, except that the
 * port's check of a server whose credentials are not fetched yet runs in that server's own branch,
 * so its {@code INVALID_INPUT} becomes that server's {@code error} (feature 010, research R-8
 * addendum).
 *
 * <p>T045b (research D-21): with {@code userColumn} the result also names the column, and each
 * process carries its {@code userColumnValue}; without it the result is unchanged.
 */
public final class FindProcessesTool implements ToolHandler {

    static final String DESCRIPTION = "Find process instances on INUBIT {nodes}, e.g. failed"
        + " (ERROR) or hanging ones, filtered by workflow, state, and time. Use the returned"
        + " processId and time range with `query_logs` to find the cause. userColumn (1 to 5)"
        + " adds that user-defined Queue Manager column to each process: the field"
        + " userDefined<i> of the REST queueLog row, the data of StartCLI `ps -u`.";

    private final DiagnosisService diagnosis;

    public FindProcessesTool(DiagnosisService diagnosis) {
        this.diagnosis = Objects.requireNonNull(diagnosis, "diagnosis");
    }

    @Override
    public String name() {
        return "find_processes";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Find INUBIT process instances", true);
    }

    /** The arguments are validated by the SDK against {@code find_processes.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        ProcessRequest request = new ProcessRequest(
            args.string("target"),
            args.strings("states").stream().map(ProcessState::valueOf)
                .collect(Collectors.toSet()),
            args.flag("hangingOnly"),
            args.optionalInteger("hangingThresholdMinutes"),
            args.optionalString("workflow"),
            args.optionalString("tag"),
            args.optionalString("since"),
            args.optionalString("until"),
            args.integer("offset", 0),
            args.integer("limit", 50),
            args.optionalInteger("userColumn"));
        NodePages pages = NodePages.of(diagnosis.findProcesses(request));
        return request.userColumn().<Object>map(
            column -> new WithUserColumn(column, pages.results())).orElse(pages);
    }

    /** The result shape {@code {userColumn, results}} of a call with {@code userColumn}. */
    record WithUserColumn(int userColumn, List<NodePages.Entry> results) {
    }
}
