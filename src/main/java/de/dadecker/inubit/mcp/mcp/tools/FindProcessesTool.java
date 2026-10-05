package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.DiagnosisService.ProcessRequest;
import de.dadecker.inubit.mcp.application.DiagnosisService;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code find_processes} (contracts/mcp-tools.md §3, US2): one page of process instances per
 * resolved server, newest first; a server that fails gets only its {@code error}. Only an
 * invalid or unknown target and semantically invalid input fail the whole call.
 */
public final class FindProcessesTool implements ToolHandler {

    static final String DESCRIPTION = "Find process instances on INUBIT {nodes}, e.g. failed"
        + " (ERROR) or hanging ones, filtered by workflow, state, and time. Use the returned"
        + " processId and time range with `query_logs` to find the cause.";

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
            args.integer("limit", 50));
        return NodePages.of(diagnosis.findProcesses(request));
    }
}
