package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.CredentialStore.Lookup;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.infra.Secret;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Feature 011 T003 (research R-8, quickstart Q8): one credential store read at a time. */
class StoreGateTest {

    private final StoreGate gate = new StoreGate();

    @Test
    void passesTheTimeLeftAndReturnsTheResult() {
        List<Duration> seen = new ArrayList<>();

        Lookup lookup = gate.read(Duration.ofSeconds(60), left -> {
            seen.add(left);
            return new Lookup.Found(Secret.of("value-1"));
        });

        assertThat(lookup).isInstanceOf(Lookup.Found.class);
        assertThat(seen).singleElement().satisfies(left -> assertThat(left)
            .isPositive().isLessThanOrEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    void neverRunsTwoReadsAtTheSameTime() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Lookup>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> gate.read(Duration.ofSeconds(10), left -> {
                    maximum.accumulateAndGet(running.incrementAndGet(), Math::max);
                    sleep(Duration.ofMillis(20));
                    running.decrementAndGet();
                    return new Lookup.NotFound();
                })));
            }
            for (Future<Lookup> result : results) {
                assertThat(result.get()).isInstanceOf(Lookup.NotFound.class);
            }
        }
        assertThat(maximum.get()).isEqualTo(1);
    }

    @Test
    void aWaitingReadGoesBeforeALaterOneEvenFromTheReleasingThread() throws Exception {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            gate.read(Duration.ofSeconds(10), left -> {
                holding.countDown();
                await(release);
                return new Lookup.NotFound();
            });
            // with an unfair lock this read would barge ahead of the waiting one
            gate.read(Duration.ofSeconds(10), left -> {
                order.add("holder again");
                return new Lookup.NotFound();
            });
        });
        holding.await();
        Thread waiting = Thread.ofPlatform().start(() -> gate.read(Duration.ofSeconds(10),
            left -> {
                order.add("waiting");
                return new Lookup.NotFound();
            }));
        while (!gate.hasWaiters()) {
            Thread.onSpinWait();
        }

        release.countDown();
        holder.join();
        waiting.join();

        assertThat(order).containsExactly("waiting", "holder again");
    }

    @Test
    void theWaitForAnotherReadCountsAgainstTheTimeout() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> gate.read(Duration.ofSeconds(10), left -> {
            holding.countDown();
            await(release);
            return new Lookup.NotFound();
        }));
        holding.await();
        AtomicInteger readsRun = new AtomicInteger();

        Lookup second = gate.read(Duration.ofMillis(100), left -> {
            readsRun.incrementAndGet();
            return new Lookup.NotFound();
        });

        release.countDown();
        first.join();
        assertThat(second).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.TIMEOUT));
        assertThat(readsRun).hasValue(0);
    }

    @Test
    void anInterruptedWaitIsATimeoutWithTheFlagKept() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> gate.read(Duration.ofSeconds(10), left -> {
            holding.countDown();
            await(release);
            return new Lookup.NotFound();
        }));
        holding.await();
        Thread.currentThread().interrupt();

        Lookup lookup = gate.read(Duration.ofSeconds(5), left -> new Lookup.NotFound());

        assertThat(Thread.interrupted()).isTrue();
        release.countDown();
        first.join();
        assertThat(lookup).isInstanceOfSatisfying(Lookup.Failed.class,
            failed -> assertThat(failed.kind()).isEqualTo(FailureKind.TIMEOUT));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
