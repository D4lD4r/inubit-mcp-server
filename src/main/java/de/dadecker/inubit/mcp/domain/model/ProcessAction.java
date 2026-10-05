package de.dadecker.inubit.mcp.domain.model;

/** The state-changing actions on one process instance (US4, FR-018). */
public enum ProcessAction {
    /** Restart an instance in {@code ERROR} ({@code processErrorStart <pid>}). */
    RESTART("restart_process"),
    /** Remove an instance from the Queue Manager ({@code kill <pid>}). */
    KILL("kill_process");

    private final String capability;

    ProcessAction(String capability) {
        this.capability = capability;
    }

    /** The MCP tool name of the action, as recorded in the audit log. */
    public String capability() {
        return capability;
    }
}
