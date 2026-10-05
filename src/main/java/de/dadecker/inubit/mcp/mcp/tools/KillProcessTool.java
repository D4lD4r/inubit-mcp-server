package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ProcessControlService;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;

/**
 * {@code kill_process} (contracts/mcp-tools.md §8, US4, FR-018): removes one process instance
 * from the Queue Manager of one server, after the write checks and, by default, a server-side
 * two-step confirmation.
 */
public final class KillProcessTool extends ProcessControlTool {

    static final String DESCRIPTION = "Delete (kill) ONE process instance on ONE INUBIT {node}."
        + " Irreversible. Same two-step confirmation as restart_process.";

    public KillProcessTool(ProcessControlService service) {
        super(service, ProcessAction.KILL);
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    String title() {
        return "Kill an INUBIT process instance";
    }
}
