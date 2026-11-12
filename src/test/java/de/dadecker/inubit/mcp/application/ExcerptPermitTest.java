package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Feature 021 T017 (research D-13, D-15): one excerpt at a time per process. */
@Timeout(10)
class ExcerptPermitTest {

    private static final Duration LONG = Duration.ofSeconds(10);

    private final ExcerptPermit permit = new ExcerptPermit();

    @Test
    void aSecondAcquirerWaitsUntilTheFirstReleases() throws Exception {
        ExcerptPermit.Held first = permit.tryAcquire(LONG).orElseThrow();
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        Thread waiting = Thread.ofPlatform().start(() -> {
            Optional<ExcerptPermit.Held> held = permit.tryAcquire(LONG);
            second.complete(held.isPresent());
            held.ifPresent(ExcerptPermit.Held::close);
        });
        awaitQueued(1);

        assertThat(second).isNotDone();
        first.close();
        waiting.join();

        assertThat(second.get()).isTrue();
    }

    @Test
    void waitingAcquirersGetThePermitInArrivalOrderEvenBeforeTheReleasingThread()
        throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            try (ExcerptPermit.Held held = permit.tryAcquire(LONG).orElseThrow()) {
                holding.countDown();
                await(release);
            }
            // with an unfair semaphore this acquire would barge ahead of the waiting ones
            takeAndRecord("holder again", order);
        });
        holding.await();
        Thread a = Thread.ofPlatform().start(() -> takeAndRecord("a", order));
        awaitQueued(1);
        Thread b = Thread.ofPlatform().start(() -> takeAndRecord("b", order));
        awaitQueued(2);

        release.countDown();
        holder.join();
        a.join();
        b.join();

        assertThat(order).containsExactly("a", "b", "holder again");
    }

    @Test
    void anExceptionInTheTryBlockReleasesThePermit() {
        assertThatThrownBy(() -> {
            try (ExcerptPermit.Held held = permit.tryAcquire(LONG).orElseThrow()) {
                throw new IllegalStateException("excerpt failed");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertExactlyOneFreePermit();
    }

    @Test
    void aWaitLongerThanTheBoundIsEmptyAndLeavesThePermitUntouched() {
        ExcerptPermit.Held first = permit.tryAcquire(LONG).orElseThrow();

        Optional<ExcerptPermit.Held> second = permit.tryAcquire(Duration.ofMillis(50));

        assertThat(second).isEmpty();
        first.close();
        assertExactlyOneFreePermit();
    }

    @Test
    void aZeroWaitTakesAFreePermitAndRefusesABusyOne() {
        Optional<ExcerptPermit.Held> first = permit.tryAcquire(Duration.ZERO);
        Optional<ExcerptPermit.Held> second = permit.tryAcquire(Duration.ZERO);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        first.orElseThrow().close();
        assertExactlyOneFreePermit();
    }

    @Test
    void anInterruptedWaitIsEmptyWithTheFlagKept() throws Exception {
        ExcerptPermit.Held first = permit.tryAcquire(LONG).orElseThrow();
        CompletableFuture<Optional<ExcerptPermit.Held>> result = new CompletableFuture<>();
        CompletableFuture<Boolean> flagKept = new CompletableFuture<>();
        Thread waiting = Thread.ofPlatform().start(() -> {
            result.complete(permit.tryAcquire(LONG));
            flagKept.complete(Thread.currentThread().isInterrupted());
        });
        awaitQueued(1);

        waiting.interrupt();
        waiting.join();

        assertThat(result.get()).isEmpty();
        assertThat(flagKept.get()).isTrue();
        first.close();
        assertExactlyOneFreePermit();
    }

    @Test
    void closingTwiceReleasesOnce() {
        ExcerptPermit.Held held = permit.tryAcquire(LONG).orElseThrow();

        held.close();
        held.close();

        assertExactlyOneFreePermit();
    }

    /** One {@code tryAcquire(ZERO)} succeeds, a second one while held does not. */
    private void assertExactlyOneFreePermit() {
        try (ExcerptPermit.Held held = permit.tryAcquire(Duration.ZERO).orElseThrow()) {
            assertThat(permit.tryAcquire(Duration.ZERO)).isEmpty();
        }
    }

    private void awaitQueued(int threads) {
        while (permit.queueLength() < threads) {
            Thread.onSpinWait();
        }
    }

    private void takeAndRecord(String name, List<String> order) {
        try (ExcerptPermit.Held held = permit.tryAcquire(LONG).orElseThrow()) {
            order.add(name);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
