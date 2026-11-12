package de.dadecker.inubit.mcp.application;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One log excerpt is built at a time per MCP server process (feature 021, research D-13): a fair
 * semaphore with one permit, so two calls on different nodes do not double the peak memory of a
 * 50 MiB scan. The process shares one instance. The wait is bounded by what is left of the call's
 * {@code cliExportTimeout} deadline (D-15); the caller maps an empty result to {@code TIMEOUT}.
 */
public final class ExcerptPermit {

    private final Semaphore semaphore = new Semaphore(1, true);

    /**
     * Takes the permit, waiting up to {@code wait} behind earlier callers (first come, first
     * served); {@link Duration#ZERO} takes a free permit without waiting.
     *
     * @return the held permit, or empty if it was not free within {@code wait} or the caller was
     *     interrupted while waiting (its flag set again)
     */
    public Optional<Held> tryAcquire(Duration wait) {
        try {
            if (semaphore.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                return Optional.of(new Held(semaphore));
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The number of callers waiting for the permit (for tests). */
    int queueLength() {
        return semaphore.getQueueLength();
    }

    /** The held permit; closing releases it once. */
    public static final class Held implements AutoCloseable {

        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Held(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
