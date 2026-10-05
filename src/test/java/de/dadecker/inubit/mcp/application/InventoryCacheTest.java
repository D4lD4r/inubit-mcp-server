package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.InventoryCache.Key;
import de.dadecker.inubit.mcp.application.InventoryCache.Snapshot;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T086: the per-server inventory cache (FR-016b, research R-11) with a fixed clock. */
@Timeout(30)
class InventoryCacheTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final Key DIAGRAMS = new Key(DEV, InventoryKind.DIAGRAM, InventoryCache.ALL);

    private final MutableClock clock = new MutableClock(T0);
    private final Map<NodeId, Duration> ttls = Map.of(DEV, Duration.ofMinutes(10),
        QA1, Duration.ofMinutes(1));
    private final InventoryCache cache = new InventoryCache(clock, ttls::get);
    private final AtomicInteger loads = new AtomicInteger();

    private Supplier<String> loader(String value) {
        return () -> {
            loads.incrementAndGet();
            return value;
        };
    }

    @Test
    void aLoadIsCachedForTheServersTtlAndStatesWhenItWasCollected() {
        Snapshot<String> first = cache.get(DIAGRAMS, false, loader("v1"));
        clock.advance(Duration.ofMinutes(9));
        Snapshot<String> second = cache.get(DIAGRAMS, false, loader("v2"));

        assertThat(first.value()).isEqualTo("v1");
        assertThat(first.collectedAt()).isEqualTo(T0);
        assertThat(first.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(second).isEqualTo(first);
        assertThat(loads).hasValue(1);
    }

    @Test
    void aDiscardedSnapshotIsLoadedAgainButANewerOneIsKept() {
        // T126: an incomplete module usage index is served once, then built again
        Snapshot<String> first = cache.get(DIAGRAMS, false, loader("v1"));
        cache.discard(DIAGRAMS, first);
        Snapshot<String> second = cache.get(DIAGRAMS, false, loader("v2"));
        cache.discard(DIAGRAMS, first); // stale: the newer snapshot stays
        Snapshot<String> third = cache.get(DIAGRAMS, false, loader("v3"));

        assertThat(second.value()).isEqualTo("v2");
        assertThat(third).isEqualTo(second);
        assertThat(loads).hasValue(2);
    }

    @Test
    void anExpiredEntryIsLoadedAgain() {
        cache.get(DIAGRAMS, false, loader("v1"));
        clock.advance(Duration.ofMinutes(10));

        Snapshot<String> reloaded = cache.get(DIAGRAMS, false, loader("v2"));

        assertThat(reloaded.value()).isEqualTo("v2");
        assertThat(reloaded.collectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(loads).hasValue(2);
    }

    @Test
    void theTtlIsPerServer() {
        Key other = new Key(QA1, InventoryKind.DIAGRAM, InventoryCache.ALL);
        cache.get(other, false, loader("v1"));
        clock.advance(Duration.ofMinutes(1));

        assertThat(cache.get(other, false, loader("v2")).value()).isEqualTo("v2");
    }

    @Test
    void theKeyIsServerKindAndScope() {
        cache.get(DIAGRAMS, false, loader("diagrams"));
        Snapshot<String> modules = cache.get(new Key(DEV, InventoryKind.MODULE,
            InventoryCache.ALL), false, loader("modules"));
        Snapshot<String> group = cache.get(new Key(DEV, InventoryKind.DIAGRAM, "versions:G"),
            false, loader("group"));
        Snapshot<String> otherServer = cache.get(new Key(QA1, InventoryKind.DIAGRAM,
            InventoryCache.ALL), false, loader("qa"));

        assertThat(List.of(modules.value(), group.value(), otherServer.value()))
            .containsExactly("modules", "group", "qa");
        assertThat(cache.get(DIAGRAMS, false, loader("x")).value()).isEqualTo("diagrams");
        assertThat(loads).hasValue(4);
    }

    @Test
    void refreshReplacesTheEntry() {
        cache.get(DIAGRAMS, false, loader("v1"));
        clock.advance(Duration.ofMinutes(2));

        Snapshot<String> refreshed = cache.get(DIAGRAMS, true, loader("v2"));
        Snapshot<String> cached = cache.get(DIAGRAMS, false, loader("v3"));

        assertThat(refreshed.value()).isEqualTo("v2");
        assertThat(refreshed.collectedAt()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
        assertThat(cached).isEqualTo(refreshed);
        assertThat(loads).hasValue(2);
    }

    /** A load that blocks until {@link #release} and counts its calls. */
    private Supplier<String> blockingLoader(CountDownLatch loading, CountDownLatch release,
        String value) {
        return () -> {
            loads.incrementAndGet();
            loading.countDown();
            await(release);
            return value;
        };
    }

    /** Waits until {@code thread} is parked, i.e. blocked on the shared load. */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(thread.getState()).as("blocked on the load in flight")
            .isEqualTo(Thread.State.WAITING);
    }

    @Test
    void concurrentRequestsForTheSameKeyShareOneLoad() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<String> slow = blockingLoader(loading, release, "shared");
        List<Snapshot<String>> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread first = Thread.ofVirtual().start(() -> results.add(cache.get(DIAGRAMS, false,
            slow)));
        assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
        List<Thread> joiners = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            joiners.add(Thread.ofVirtual().start(() ->
                results.add(cache.get(DIAGRAMS, false, slow))));
        }
        for (Thread joiner : joiners) {
            awaitBlocked(joiner);
        }

        release.countDown();
        first.join(Duration.ofSeconds(10));
        for (Thread joiner : joiners) {
            joiner.join(Duration.ofSeconds(10));
        }

        assertThat(results).hasSize(8).allSatisfy(result ->
            assertThat(result).isEqualTo(results.get(0)));
        assertThat(results.get(0).value()).isEqualTo("shared");
        assertThat(loads).hasValue(1);
    }

    @Test
    void aRefreshJoinsTheLoadInFlight() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Snapshot<String>> loaded = new AtomicReference<>();
        AtomicReference<Snapshot<String>> refreshed = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> loaded.set(cache.get(DIAGRAMS, false,
            blockingLoader(loading, release, "in flight"))));
        assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
        Thread refresh = Thread.ofVirtual().start(() -> refreshed.set(cache.get(DIAGRAMS, true,
            loader("second load"))));
        awaitBlocked(refresh);

        release.countDown();
        first.join(Duration.ofSeconds(10));
        refresh.join(Duration.ofSeconds(10));

        assertThat(refreshed.get()).isEqualTo(loaded.get());
        assertThat(refreshed.get().value()).isEqualTo("in flight");
        assertThat(loads).as("its data is fresh, so no second load").hasValue(1);
    }

    @Test
    void aFailedLoadIsNotCachedAndReachesEveryWaiter() throws Exception {
        ToolErrorException failure = new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            "slow", "cause", "step"));
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> loaderFailure = new AtomicReference<>();
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                cache.get(DIAGRAMS, false, () -> {
                    loads.incrementAndGet();
                    loading.countDown();
                    await(release);
                    throw failure;
                });
            } catch (RuntimeException e) {
                loaderFailure.set(e);
            }
        });
        assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                cache.get(DIAGRAMS, false, loader("never"));
            } catch (RuntimeException e) {
                waiterFailure.set(e);
            }
        });
        awaitBlocked(waiter);

        release.countDown();
        first.join(Duration.ofSeconds(10));
        waiter.join(Duration.ofSeconds(10));

        assertThat(loaderFailure.get()).isSameAs(failure);
        assertThat(waiterFailure.get()).isSameAs(failure);
        assertThat(cache.get(DIAGRAMS, false, loader("ok")).value()).isEqualTo("ok");
        assertThat(loads).hasValue(2);
    }

    @Test
    void theFailureOfALoadIsThrownToItsCaller() {
        ToolErrorException failure = new ToolErrorException(ToolError.of(
            ErrorCode.CLI_UNAVAILABLE, "no cli", "cause", "step"));

        assertThatThrownBy(() -> cache.get(DIAGRAMS, false, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(cache.get(DIAGRAMS, false, loader("v")).value()).isEqualTo("v");
    }

    @Test
    void anInterruptedWaiterGetsATimeoutWhileTheLoadContinues() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread loader = Thread.ofVirtual().start(() -> cache.get(DIAGRAMS, false, () -> {
            loads.incrementAndGet();
            loading.countDown();
            await(release);
            return "late";
        }));
        assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
        AtomicReference<ToolError> error = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                cache.get(DIAGRAMS, false, loader("never"));
            } catch (ToolErrorException e) {
                error.set(e.error());
            }
        });
        awaitBlocked(waiter);
        waiter.interrupt();
        waiter.join(Duration.ofSeconds(10));
        release.countDown();
        loader.join(Duration.ofSeconds(10));

        assertThat(error.get().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(cache.get(DIAGRAMS, false, loader("x")).value()).isEqualTo("late");
        assertThat(loads).hasValue(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
