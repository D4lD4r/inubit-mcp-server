package de.dadecker.inubit.mcp.domain.port;

/**
 * Restart and kill of single process instances on one INUBIT server (US4; 8.1: StartCLI
 * {@code processErrorStart <pid>} and {@code kill <pid>}, research R-6, R-7). The process id is
 * the Queue Manager id ({@code workflowId}, 1–19 digits without leading zero). Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id.
 */
public interface ProcessControlPort {

    /**
     * Checks without launching anything that the actions can run: a usable CLI installation and
     * credentials.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    void checkAvailable();

    /**
     * Restarts the instance in {@code ERROR}.
     *
     * @return INUBIT's confirmation text
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException e.g.
     *     {@code NOT_FOUND}, {@code AUTH_FAILED}, {@code TIMEOUT}, {@code UNEXPECTED_RESPONSE};
     *     {@code INVALID_INPUT} for an id that is not a Queue Manager id (nothing launched)
     */
    String restart(String processId);

    /** Removes the instance from the Queue Manager; as {@link #restart}. */
    String kill(String processId);
}
