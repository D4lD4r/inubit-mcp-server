package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A write lock that records the permit of every hand-off and delegates to the real lock (feature
 * 017, research D-24). {@link #changeBefore} runs a version change right before a chosen hand-off
 * takes the lock, i.e. after the call was admitted and before its write is handed off.
 */
public final class RecordingWriteLock implements WriteLock {

    private final WriteLock delegate;
    private final List<WritePermit> permits = new CopyOnWriteArrayList<>();
    private final AtomicInteger handOffs = new AtomicInteger();
    private Predicate<WritePermit> trigger = permit -> false;
    private Runnable change = () -> { };

    public RecordingWriteLock(WriteLock delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Runs {@code change} once, right before the {@code handOff}-th hand-off from now (1-based)
     * takes the lock.
     */
    public RecordingWriteLock changeBefore(int handOff, Runnable change) {
        int at = handOffs.get() + handOff;
        return changeBefore(permit -> handOffs.get() == at, change);
    }

    /** Runs {@code change} once, right before the first hand-off whose permit matches. */
    public synchronized RecordingWriteLock changeBefore(Predicate<WritePermit> first,
        Runnable change) {
        this.trigger = Objects.requireNonNull(first, "first");
        this.change = Objects.requireNonNull(change, "change");
        return this;
    }

    /** The permits of all hand-offs so far, refused ones included. */
    public List<WritePermit> permits() {
        return List.copyOf(permits);
    }

    @Override
    public <T> T handOff(WritePermit permit, Function<Optional<InubitVersion>, T> action) {
        permits.add(permit);
        handOffs.incrementAndGet();
        Runnable due = null;
        synchronized (this) {
            if (trigger.test(permit)) {
                due = change;
                trigger = any -> false;
            }
        }
        if (due != null) {
            due.run();
        }
        return delegate.handOff(permit, action);
    }
}
