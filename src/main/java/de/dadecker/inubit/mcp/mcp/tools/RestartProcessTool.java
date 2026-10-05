package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ProcessControlService;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;

/**
 * {@code restart_process} (contracts/mcp-tools.md §7, US4, FR-018, FR-020): restarts one process
 * instance in {@code ERROR} on one server, after the write checks and, by default, a server-side
 * two-step confirmation.
 */
public final class RestartProcessTool extends ProcessControlTool {

    static final String DESCRIPTION = "Restart ONE process instance that is in ERROR state on ONE"
        + " INUBIT {node}. Changes production data flow. On {nodes} with `confirmationMode`"
        + " `SERVER` (the default), the first call only returns a preview and a"
        + " confirmationCode; call again with the code to execute.";

    public RestartProcessTool(ProcessControlService service) {
        super(service, ProcessAction.RESTART);
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    String title() {
        return "Restart an INUBIT process instance in ERROR";
    }
}
