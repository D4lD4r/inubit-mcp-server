package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.config.CredentialStore.Lookup;
import de.dadecker.inubit.mcp.config.FailureKind;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Serialises the reads of the credential store process-wide (feature 011, research R-8): at most
 * one entry is read at a time, so at most one access dialog is open, also during a fan-out over many
 * nodes. The lock is fair (first come, first served). The wait for the lock counts against the
 * caller's timeout; a caller that does not get the lock in time gets {@code TIMEOUT} without reading.
 */
public final class StoreGate {

    private final ReentrantLock lock = new ReentrantLock(true);

    /**
     * Runs {@code read} under the lock and passes it the time left of {@code timeout}.
     *
     * @return the read's result, or {@code Failed(TIMEOUT)} if the lock was not obtained within
     *     {@code timeout} or the caller was interrupted while waiting (its flag set again)
     */
    public Lookup read(Duration timeout, Function<Duration, Lookup> read) {
        long start = System.nanoTime();
        try {
            if (!lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return new Lookup.Failed(FailureKind.TIMEOUT,
                    "another credential store read did not end within " + timeout.toSeconds()
                        + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Lookup.Failed(FailureKind.TIMEOUT, "interrupted");
        }
        try {
            Duration left = timeout.minusNanos(System.nanoTime() - start);
            if (left.isNegative() || left.isZero()) {
                return new Lookup.Failed(FailureKind.TIMEOUT, "no time left for the read");
            }
            return read.apply(left);
        } finally {
            lock.unlock();
        }
    }

    /** Whether a read waits for the lock (for tests). */
    boolean hasWaiters() {
        return lock.hasQueuedThreads();
    }
}
