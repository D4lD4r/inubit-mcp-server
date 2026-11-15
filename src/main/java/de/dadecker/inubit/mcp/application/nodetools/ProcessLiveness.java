package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;

/**
 * Who owns a lease and whether that owner still runs (research D-13). The real implementation
 * asks the operating system; tests use a fake. Only processes on this host are seen (a profile
 * directory shared between machines is not supported).
 */
public interface ProcessLiveness {

    /** This MCP server instance: its pid and, if the operating system reports it, its start time. */
    LeaseOwner self();

    /**
     * Whether {@code owner} still runs: its pid is alive and, when both the recorded and the live
     * start time are known, they are equal within one second. When either is unknown the pid alone
     * decides, so a reused pid can delay a takeover until that process ends.
     */
    boolean alive(LeaseOwner owner);
}
