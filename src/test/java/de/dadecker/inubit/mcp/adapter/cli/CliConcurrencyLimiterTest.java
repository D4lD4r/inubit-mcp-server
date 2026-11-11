package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T019 (feature 012, research D-9, FR-015): at most two concurrent StartCLI runs per node pinned
 * to a CLI-only version; a further run waits up to {@code cliTimeout}, then {@code TIMEOUT}.
 * T007 (feature 020, research D-14, FR-016): helper runs take the same per-node slots on every
 * version line; StartCLI runs on 8.1/9.0 still take none.
 */
@Timeout(30)
class CliConcurrencyLimiterTest {

    private static final Duration BOUND = Duration.ofMillis(200);

    private final CliConcurrencyLimiter limiter =
        new CliConcurrencyLimiter(CapabilityMatrix.production());

    private static EffectiveNodeConfig node(String id, VersionSetting version) {
        return TestNodeConfig.node().id(id).versionLine(version).cliTimeout(BOUND).build();
    }

    /** Outcome of one acquisition: the held permit or the error, and when it was decided. */
    private record Attempt(CliConcurrencyLimiter.Permit permit, ToolErrorException error,
        long elapsedNanos) {
    }

    @Test
    void fiveParallelAcquisitionsOnA61NodeAdmitTwoAndTimeOutTheRest() throws Exception {
        EffectiveNodeConfig server = node("legacy/node1", VersionSetting.V6_1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    long begin = System.nanoTime();
                    try {
                        // the permit is held, not closed: the run "hangs"
                        return new Attempt(limiter.acquire(server), null,
                            System.nanoTime() - begin);
                    } catch (ToolErrorException e) {
                        return new Attempt(null, e, System.nanoTime() - begin);
                    }
                }));
            }
            start.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(10, TimeUnit.SECONDS));
            }

            List<Attempt> admitted = attempts.stream().filter(a -> a.permit() != null).toList();
            List<Attempt> refused = attempts.stream().filter(a -> a.error() != null).toList();
            assertThat(admitted).hasSize(2);
            assertThat(refused).hasSize(3).allSatisfy(attempt -> {
                assertThat(attempt.error().error().code()).isEqualTo(ErrorCode.TIMEOUT);
                assertThat(attempt.error().error().nextStep()).contains("cliTimeout");
                assertThat(attempt.elapsedNanos()).isGreaterThanOrEqualTo(BOUND.toNanos());
            });
            admitted.forEach(attempt -> attempt.permit().close());
        }
    }

    @Test
    void aClosedPermitAdmitsTheNextRunAndClosingTwiceReleasesOnce() {
        EffectiveNodeConfig server = node("legacy/node1", VersionSetting.V6_1);
        CliConcurrencyLimiter.Permit first = limiter.acquire(server);
        CliConcurrencyLimiter.Permit second = limiter.acquire(server);

        first.close();
        first.close();

        CliConcurrencyLimiter.Permit third = limiter.acquire(server);
        assertThatThrownBy(() -> limiter.acquire(server))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
        second.close();
        third.close();
    }

    @Test
    void theLimitIsPerNode() {
        EffectiveNodeConfig one = node("legacy/node1", VersionSetting.V6_1);
        EffectiveNodeConfig two = node("legacy/node2", VersionSetting.V6_1);
        limiter.acquire(one);
        limiter.acquire(one);

        try (CliConcurrencyLimiter.Permit other = limiter.acquire(two)) {
            assertThat(other).isNotNull();
        }
    }

    @Test
    void an81NodeGetsNoLimiter() {
        EffectiveNodeConfig server = node("prod/node1", VersionSetting.V8_1);
        List<CliConcurrencyLimiter.Permit> held = new ArrayList<>();

        // with a limit, the third acquisition would throw TIMEOUT
        for (int i = 0; i < 5; i++) {
            held.add(limiter.acquire(server));
        }

        assertThat(held).hasSize(5);
        held.forEach(CliConcurrencyLimiter.Permit::close);
    }

    @Test
    void aThirdHelperRunOnAn81NodeWaitsForTheGivenBoundAndTimesOut() {
        EffectiveNodeConfig server = node("prod/node1", VersionSetting.V8_1);
        Duration wait = Duration.ofMillis(150);
        CliConcurrencyLimiter.Permit first = limiter.acquireForHelper(server, wait);
        CliConcurrencyLimiter.Permit second = limiter.acquireForHelper(server, wait);

        long begin = System.nanoTime();
        assertThatThrownBy(() -> limiter.acquireForHelper(server, wait))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT);
                assertThat(e.error().node()).contains(server.id());
                assertThat(e.error().message())
                    .isEqualTo("No StartCLI slot on prod/node1 became free within 150 ms");
                assertThat(e.error().nextStep()).contains("cliTimeout");
                TestSecrets.assertNoLeak(e.error().message() + e.error().likelyCause()
                    + e.error().nextStep());
            });
        assertThat(System.nanoTime() - begin).isGreaterThanOrEqualTo(wait.toNanos());

        first.close();
        try (CliConcurrencyLimiter.Permit third = limiter.acquireForHelper(server, wait)) {
            assertThat(third).isNotNull();
        }
        second.close();
    }

    @Test
    void a61StartCliRunAndHelperRunsShareTheSlots() {
        EffectiveNodeConfig server = node("legacy/node1", VersionSetting.V6_1);
        CliConcurrencyLimiter.Permit startCli = limiter.acquire(server);
        CliConcurrencyLimiter.Permit helper = limiter.acquireForHelper(server, BOUND);

        assertThatThrownBy(() -> limiter.acquireForHelper(server, BOUND))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThatThrownBy(() -> limiter.acquire(server))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));

        helper.close();
        try (CliConcurrencyLimiter.Permit next = limiter.acquire(server)) {
            assertThat(next).isNotNull();
        }
        startCli.close();
    }

    @Test
    void acquireOnAn81NodeStaysANoOpWhileHelperRunsHoldTheSlots() {
        EffectiveNodeConfig server = node("prod/node1", VersionSetting.V8_1);
        CliConcurrencyLimiter.Permit first = limiter.acquireForHelper(server, BOUND);
        CliConcurrencyLimiter.Permit second = limiter.acquireForHelper(server, BOUND);

        // a slot-taking StartCLI run would throw TIMEOUT here
        try (CliConcurrencyLimiter.Permit startCli = limiter.acquire(server)) {
            startCli.close();
        }
        first.close();
        first.close();
        // the no-op permit released nothing: exactly one slot is free again
        CliConcurrencyLimiter.Permit third = limiter.acquireForHelper(server, BOUND);
        assertThatThrownBy(() -> limiter.acquireForHelper(server, BOUND))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
        second.close();
        third.close();
    }

    @Test
    void anInterruptedHelperWaiterGetsTimeoutAndKeepsItsInterruptFlag() {
        EffectiveNodeConfig server = node("prod/node1", VersionSetting.V8_1);
        limiter.acquireForHelper(server, BOUND);
        limiter.acquireForHelper(server, BOUND);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> limiter.acquireForHelper(server, BOUND))
                .isInstanceOfSatisfying(ToolErrorException.class,
                    e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anInterruptedWaiterGetsTimeoutAndKeepsItsInterruptFlag() {
        EffectiveNodeConfig server = node("legacy/node1", VersionSetting.V6_1);
        limiter.acquire(server);
        limiter.acquire(server);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> limiter.acquire(server))
                .isInstanceOfSatisfying(ToolErrorException.class,
                    e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
