package de.dadecker.inubit.mcp.domain.model;

import java.util.Optional;

/**
 * Load of an INUBIT server from {@code /metrics} (data-model.md → LoadFigures). The percentages
 * are derived, rounded to one decimal, and empty if the maximum is not positive. The thread and
 * blocking queue figures are always present on 8.1 and absent on 6.1, which reports memory only
 * (feature 011, FR-008).
 */
public record LoadFigures(
    double usedMemoryMb,
    double freeMemoryMb,
    double maxMemoryMb,
    Optional<Double> memoryUsedPercent,
    Optional<Long> threadsInUse,
    Optional<Long> licensedThreads,
    Optional<Long> maxThreads,
    Optional<Long> blockingQueueEntries,
    Optional<Long> maxBlockingQueueSize,
    Optional<Double> blockingQueuePercent) {

    public LoadFigures {
        memoryUsedPercent = orEmpty(memoryUsedPercent);
        threadsInUse = orEmpty(threadsInUse);
        licensedThreads = orEmpty(licensedThreads);
        maxThreads = orEmpty(maxThreads);
        blockingQueueEntries = orEmpty(blockingQueueEntries);
        maxBlockingQueueSize = orEmpty(maxBlockingQueueSize);
        blockingQueuePercent = orEmpty(blockingQueuePercent);
    }

    /** Figures with both percentages derived from the absolute values. */
    public static LoadFigures of(double usedMemoryMb, double freeMemoryMb, double maxMemoryMb,
        long threadsInUse, long licensedThreads, long maxThreads, long blockingQueueEntries,
        long maxBlockingQueueSize) {
        return new LoadFigures(usedMemoryMb, freeMemoryMb, maxMemoryMb,
            percent(usedMemoryMb, maxMemoryMb), Optional.of(threadsInUse),
            Optional.of(licensedThreads), Optional.of(maxThreads),
            Optional.of(blockingQueueEntries), Optional.of(maxBlockingQueueSize),
            percent(blockingQueueEntries, maxBlockingQueueSize));
    }

    /** The memory figures of a 6.1 node, without thread or blocking queue figures. */
    public static LoadFigures of(MemoryFigures memory) {
        return new LoadFigures(memory.usedMb(), memory.freeMb(), memory.maxMb(),
            memory.usedPercent(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    static Optional<Double> percent(double value, double max) {
        if (!(max > 0)) {
            return Optional.empty();
        }
        return Optional.of(Math.round(value * 1000.0 / max) / 10.0);
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
