package de.dadecker.inubit.mcp.domain.model;

import java.util.Optional;

/**
 * Load of an INUBIT server from {@code /metrics} (data-model.md → LoadFigures). The percentages
 * are derived, rounded to one decimal, and empty if the maximum is not positive.
 */
public record LoadFigures(
    double usedMemoryMb,
    double freeMemoryMb,
    double maxMemoryMb,
    Optional<Double> memoryUsedPercent,
    long threadsInUse,
    long licensedThreads,
    long maxThreads,
    long blockingQueueEntries,
    long maxBlockingQueueSize,
    Optional<Double> blockingQueuePercent) {

    public LoadFigures {
        memoryUsedPercent = memoryUsedPercent == null ? Optional.empty() : memoryUsedPercent;
        blockingQueuePercent = blockingQueuePercent == null ? Optional.empty()
            : blockingQueuePercent;
    }

    /** Figures with both percentages derived from the absolute values. */
    public static LoadFigures of(double usedMemoryMb, double freeMemoryMb, double maxMemoryMb,
        long threadsInUse, long licensedThreads, long maxThreads, long blockingQueueEntries,
        long maxBlockingQueueSize) {
        return new LoadFigures(usedMemoryMb, freeMemoryMb, maxMemoryMb,
            percent(usedMemoryMb, maxMemoryMb), threadsInUse, licensedThreads, maxThreads,
            blockingQueueEntries, maxBlockingQueueSize,
            percent(blockingQueueEntries, maxBlockingQueueSize));
    }

    private static Optional<Double> percent(double value, double max) {
        if (!(max > 0)) {
            return Optional.empty();
        }
        return Optional.of(Math.round(value * 1000.0 / max) / 10.0);
    }
}
