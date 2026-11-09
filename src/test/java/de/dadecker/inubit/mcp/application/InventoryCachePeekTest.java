package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.InventoryCache.Key;
import de.dadecker.inubit.mcp.application.InventoryCache.Snapshot;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T015: {@link InventoryCache#peek} serves a fresh snapshot without loading, and
 * {@link PartnerSnapshot} with its cache scopes (research D-6).
 */
@Timeout(30)
class InventoryCachePeekTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final Key PARTNERS =
        new Key(DEV, InventoryKind.DIAGRAM, PartnerSnapshot.partnersScope("owner"));
    private static final DiagramRef ORDERS = new DiagramRef("Orders", "Shop");

    private final MutableClock clock = new MutableClock(T0);
    private final InventoryCache cache =
        new InventoryCache(clock, Map.of(DEV, Duration.ofMinutes(10))::get);

    @Test
    void anAbsentKeyIsEmpty() {
        assertThat(cache.peek(PARTNERS)).isEmpty();
    }

    @Test
    void aFreshEntryIsReturnedAsCached() {
        Snapshot<String> loaded = cache.get(PARTNERS, false, () -> "v1");
        clock.advance(Duration.ofMinutes(9));

        assertThat(cache.<String>peek(PARTNERS)).containsSame(loaded);
    }

    @Test
    void anExpiredEntryIsEmptyAndTheNextGetLoadsAgain() {
        cache.get(PARTNERS, false, () -> "v1");
        clock.advance(Duration.ofMinutes(10));

        assertThat(cache.peek(PARTNERS)).isEmpty();
        assertThat(cache.get(PARTNERS, false, () -> "v2").value()).isEqualTo("v2");
    }

    @Test
    void aFailedLoadIsEmpty() {
        assertThatThrownBy(() -> cache.get(PARTNERS, false, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(cache.peek(PARTNERS)).isEmpty();
    }

    @Test
    void aLoadInFlightIsEmptyWithoutWaitingForIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Snapshot<String>> load = CompletableFuture.supplyAsync(
            () -> cache.get(PARTNERS, false, () -> {
                started.countDown();
                await(release);
                return "v1";
            }));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(cache.peek(PARTNERS)).isEmpty();

        release.countDown();
        Snapshot<String> loaded = load.get(10, TimeUnit.SECONDS);
        assertThat(cache.<String>peek(PARTNERS)).containsSame(loaded);
    }

    @Test
    void theScopesFollowResearchD6() {
        assertThat(PartnerSnapshot.systemDiagramsScope("owner")).isEqualTo("systemdiagrams:owner");
        assertThat(PartnerSnapshot.partnersScope("owner")).isEqualTo("partners:owner");
        assertThat(PartnerSnapshot.partnersScope("owner", ORDERS))
            .isEqualTo("partners:owner|Shop/Orders");
    }

    @Test
    void aPartnerSnapshotIsImmutable() {
        List<SystemDiagramPartners> diagrams =
            new ArrayList<>(List.of(new SystemDiagramPartners(ORDERS, List.of())));
        Set<DiagramRef> covered = new HashSet<>(Set.of(ORDERS));

        PartnerSnapshot snapshot = new PartnerSnapshot(diagrams, covered);
        diagrams.clear();
        covered.clear();

        assertThat(snapshot.diagrams()).hasSize(1);
        assertThat(snapshot.covered()).containsExactly(ORDERS);
        assertThatThrownBy(() -> snapshot.covered().add(new DiagramRef("Other", "Shop")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
