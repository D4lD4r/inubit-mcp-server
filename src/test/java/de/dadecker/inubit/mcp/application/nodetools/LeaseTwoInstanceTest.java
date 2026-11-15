package de.dadecker.inubit.mcp.application.nodetools;

import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.INVOICES;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.NOW;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.ORDERS;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.TEST;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.TEST2;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.activeLease;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 024 T052 (FR-043, research D-12, D-13, D-16 "two-instance edge case"): two MCP server
 * instances of one profile on the same lease directory, each with its own {@link LeaseStore},
 * {@link NodeLogLevelLocks}, {@link LogLevelService}, {@link LeaseScheduler} and process identity
 * ({@link LeaseFixture#instance}). The fake INUBIT counts every {@code trace --reset}.
 */
class LeaseTwoInstanceTest {

    private static final LeaseOwner A_OWNER = new LeaseOwner(5001, Optional.empty());
    private static final LeaseOwner B_OWNER = new LeaseOwner(5002, Optional.empty());
    /** An earlier run that no longer runs. */
    private static final LeaseOwner GONE_OWNER = new LeaseOwner(4000, Optional.empty());

    @TempDir
    Path home;

    private LeaseFixture fixture;
    private LeaseFixture.Instance a;
    private LeaseFixture.Instance b;

    @BeforeEach
    void setUp() {
        fixture = new LeaseFixture(home);
        a = fixture.instance(A_OWNER);
        b = fixture.instance(B_OWNER);
    }

    @AfterEach
    void tearDown() {
        a.scheduler().shutdown(Duration.ofSeconds(5));
        b.scheduler().shutdown(Duration.ofSeconds(5));
    }

    @Test
    void theOtherInstanceLeavesALiveOwnersLeaseAlone() {
        a.set(TEST, ORDERS);
        Lease set = fixture.onlyLease();
        assertThat(set.owner()).isEqualTo(A_OWNER);

        fixture.clock.advance(Duration.ofMinutes(2));
        b.tick();

        assertThat(fixture.resets()).as("B leaves A's expired lease to A").isZero();
        assertThat(fixture.onlyLease()).isEqualTo(set);

        a.tick();

        assertThat(fixture.resets()).isEqualTo(1);
        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
    }

    @Test
    void aSetMergedWhileTheOtherDecidesATakeoverIsNotResetBeforeItsNewExpiry() {
        a.set(TEST, ORDERS);
        fixture.clock.advance(Duration.ofMinutes(2));
        fixture.running.remove(A_OWNER);

        List<LeaseScheduler.Decision> decided = b.scheduler().decide();
        assertThat(decided).singleElement().satisfies(decision ->
            assertThat(decision.lease().owner()).isEqualTo(B_OWNER));

        a.set(TEST, INVOICES);
        Lease merged = fixture.onlyLease();
        assertThat(merged.owner()).isEqualTo(A_OWNER);
        Instant newExpiry = merged.expiresAt();
        assertThat(newExpiry).isEqualTo(NOW.plus(Duration.ofMinutes(3)));

        LeaseFixture.finished(b.scheduler().cleanup(decided));

        assertThat(fixture.resets()).as("nothing sent for the changed generation").isZero();
        assertThat(fixture.onlyLease()).isEqualTo(merged);

        fixture.clock.advance(Duration.ofSeconds(30));
        b.tick();

        Lease taken = fixture.onlyLease();
        assertThat(fixture.resets()).as("the new expiry has not passed").isZero();
        assertThat(taken.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(taken.owner()).isEqualTo(B_OWNER);
        assertThat(taken.loggers().keySet()).extracting(LoggerName::value)
            .containsExactly(INVOICES, ORDERS);

        fixture.clock.advance(Duration.between(fixture.clock.instant(), newExpiry));
        b.tick();

        assertThat(fixture.resets()).isEqualTo(1);
        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
    }

    @Test
    void ticksOfBothInstancesAtOnceResetEachExpiryExactlyOnce() throws Exception {
        for (int round = 1; round <= 5; round++) {
            fixture.clock.advance(Duration.ofMinutes(1));
            Lease orphaned = activeLease(TEST, GONE_OWNER,
                fixture.clock.instant().minusSeconds(1));
            fixture.seed(orphaned);

            bothTickAtOnce();

            Lease lease = fixture.onlyLease();
            assertThat(lease.leaseId()).isEqualTo(orphaned.leaseId());
            assertThat(lease.state()).as("round " + round).isEqualTo(LeaseState.RESET);
            assertThat(lease.owner()).isIn(A_OWNER, B_OWNER);
            assertThat(fixture.resets()).as("round " + round).isEqualTo(round);
        }
        assertThat(fixture.auditLog.records).extracting(record -> record.inputs().get("trigger"))
            .filteredOn("takeover"::equals).hasSize(5);
    }

    @Test
    void setsAndTicksOfBothInstancesLoseAndDuplicateNoLease() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> setA = CompletableFuture.runAsync(() -> {
            await(start);
            a.set(TEST, ORDERS);
        });
        CompletableFuture<Void> setB = CompletableFuture.runAsync(() -> {
            await(start);
            b.set(TEST2, INVOICES);
        });
        CompletableFuture<Void> tickA = CompletableFuture.runAsync(() -> {
            await(start);
            a.tick();
        });
        CompletableFuture<Void> tickB = CompletableFuture.runAsync(() -> {
            await(start);
            b.tick();
        });
        start.countDown();
        CompletableFuture.allOf(setA, setB, tickA, tickB).get(30, TimeUnit.SECONDS);

        List<Lease> leases = fixture.leases();
        assertThat(leases).extracting(Lease::server).containsExactlyInAnyOrder(TEST, TEST2);
        assertThat(leases).allSatisfy(lease ->
            assertThat(lease.state()).isEqualTo(LeaseState.ACTIVE));

        fixture.clock.advance(Duration.ofMinutes(2));
        bothTickAtOnce();
        bothTickAtOnce();

        List<Lease> ended = fixture.leases();
        assertThat(ended).extracting(Lease::leaseId)
            .containsExactlyInAnyOrderElementsOf(leases.stream().map(Lease::leaseId).toList());
        assertThat(ended).allSatisfy(lease ->
            assertThat(lease.state()).isEqualTo(LeaseState.RESET));
        assertThat(fixture.calls).filteredOn(call -> call.endsWith(" resetAll"))
            .containsExactlyInAnyOrder("test/node1 resetAll", "test/node2 resetAll");
    }

    /** Starts the ticks of A and B together and waits for both. */
    private void bothTickAtOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> tickA = CompletableFuture.runAsync(() -> {
            await(start);
            a.tick();
        });
        CompletableFuture<Void> tickB = CompletableFuture.runAsync(() -> {
            await(start);
            b.tick();
        });
        start.countDown();
        CompletableFuture.allOf(tickA, tickB).get(30, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
