package de.dadecker.inubit.mcp.domain.model;

/**
 * Number of process instances per state on an INUBIT 6.1 node, read in the StartCLI health
 * session (feature 011, FR-008, research D-6).
 */
public record ProcessCounts(long error, long processing, long waiting, long queued) {

    public ProcessCounts {
        if (error < 0 || processing < 0 || waiting < 0 || queued < 0) {
            throw new IllegalArgumentException("process counts must not be negative");
        }
    }
}
