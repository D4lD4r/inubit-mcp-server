package de.dadecker.inubit.mcp.domain.model;

import java.util.Optional;

/**
 * JVM memory of an INUBIT 6.1 node in MB, from {@code free -m} in the StartCLI health session
 * (feature 011, FR-008).
 */
public record MemoryFigures(long usedMb, long freeMb, long maxMb) {

    public MemoryFigures {
        if (usedMb < 0 || freeMb < 0 || maxMb < 0) {
            throw new IllegalArgumentException("memory figures must not be negative");
        }
    }

    /** Used of maximum memory in percent, one decimal; empty if the maximum is not positive. */
    public Optional<Double> usedPercent() {
        return LoadFigures.percent(usedMb, maxMb);
    }
}
