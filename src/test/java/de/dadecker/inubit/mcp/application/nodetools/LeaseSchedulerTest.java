package de.dadecker.inubit.mcp.application.nodetools;

import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.GONE;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.NOW;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.TEST;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.TEST2;
import static de.dadecker.inubit.mcp.application.nodetools.LeaseFixture.activeLease;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseAttempt;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseReason;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T052 (FR-043 … FR-045, SC-004, research D-12, D-16, D-22): the
 * {@link LeaseScheduler} of one instance. Ticks are driven by calling {@link LeaseScheduler#tick()}
 * with the fake clock of {@link LeaseFixture}; only the start and shutdown tests use the
 * scheduler's own threads.
 */
class LeaseSchedulerTest {

    private static final LeaseOwner SELF =
        new LeaseOwner(4711, Optional.of(Instant.parse("2026-11-12T07:58:02.114Z")));
    /** An earlier run of the MCP server that no longer runs. */
    private static final LeaseOwner GONE_OWNER = new LeaseOwner(4000, Optional.empty());
    /** Another instance of the profile that still runs. */
    private static final LeaseOwner OTHER = new LeaseOwner(4712, Optional.empty());
    private static final Duration TICK = Duration.ofSeconds(30);

    @TempDir
    Path home;

    private LeaseFixture fixture;
    private LeaseFixture.Instance self;

    private final Logger logger = (Logger) LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        fixture = new LeaseFixture(home);
        self = fixture.instance(SELF);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        self.scheduler().shutdown(Duration.ofSeconds(5));
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }

    /** Leak check: no credential in a log line or an audit record. */
    @AfterEach
    void noLeak() {
        List<String> texts = new ArrayList<>();
        appender.list.forEach(event -> texts.add(event.getFormattedMessage()));
        fixture.auditLog.records.forEach(record -> texts.add(record.toString()));
        texts.forEach(text -> assertThat(text)
            .doesNotContain(TestSecrets.CONFIGURED_PASSWORD)
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE));
    }

    // --- expiry (US4-2, SC-004) ------------------------------------------------------------------

    @Test
    void anOwnLeaseIsResetOnTheFirstTickAfterItsExpiry() {
        Lease seeded = activeLease(TEST, SELF, NOW.plus(Duration.ofSeconds(10)));
        fixture.seed(seeded);

        tick();
        assertThat(fixture.calls).as("not expired yet").isEmpty();
        assertThat(fixture.onlyLease()).isEqualTo(seeded);

        fixture.clock.advance(TICK);
        tick();

        assertThat(fixture.calls).containsExactly("test/node1 answeringNode",
            "test/node1 resetAll");
        Lease lease = fixture.onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET);
        assertThat(lease.endedAt()).contains(NOW.plus(TICK));
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(triggers()).containsOnly("expiry");
    }

    @Test
    void theFirstTickIsTheStartupCleanup() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1))));

        tick();

        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(triggers()).containsExactly("startup", "startup");
    }

    @Test
    void aResetLeaseIsNotResetAgain() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1))));
        tick();

        fixture.clock.advance(TICK);
        tick();
        fixture.clock.advance(TICK);
        tick();

        assertThat(fixture.resets()).isEqualTo(1);
        assertThat(fixture.auditLog.records).hasSize(2);
    }

    // --- orphaned leases (US4-3, creator decision of D-16) -------------------------------------

    @Test
    void anOrphanedExpiredLeaseIsTakenOverAndResetAtStart() {
        Lease seeded = activeLease(TEST, GONE_OWNER, NOW.minus(Duration.ofMinutes(5)));
        fixture.seed(seeded);

        tick();

        assertThat(fixture.calls).containsExactly("test/node1 answeringNode",
            "test/node1 resetAll");
        Lease lease = fixture.onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET);
        assertThat(lease.owner()).isEqualTo(SELF);
        assertThat(lease.generation()).as("takeover, then reset").isEqualTo(3);
        assertThat(fixture.auditLog.records).extracting(record -> record.inputs().get("trigger"))
            .containsExactly("takeover", "startup", "startup");
        AuditRecord takeover = fixture.auditLog.records.get(0);
        assertThat(takeover.outcome()).isEqualTo(AuditOutcome.EXECUTED);
        assertThat(takeover.node()).isEqualTo("test/node1");
        assertThat(takeover.account()).contains("jdoe");
        assertThat(takeover.inputs()).containsEntry("leaseId", seeded.leaseId().toString());
        assertThat(takeover.reason().orElseThrow()).contains("4000").contains("4711");
    }

    @Test
    void anOrphanedUnexpiredLeaseIsTakenOverAtOnceAndResetAtItsExpiry() {
        Instant expiry = NOW.plus(Duration.ofMinutes(5));
        Lease seeded = activeLease(TEST, GONE_OWNER, expiry);
        fixture.seed(seeded);

        tick();

        assertThat(fixture.calls).as("the raise the operator asked for runs on").isEmpty();
        Lease taken = fixture.onlyLease();
        assertThat(taken.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(taken.owner()).isEqualTo(SELF);
        assertThat(taken.generation()).isEqualTo(2);
        assertThat(taken.expiresAt()).isEqualTo(expiry);
        assertThat(triggers()).containsExactly("takeover");

        fixture.clock.advance(Duration.ofMinutes(4));
        tick();
        assertThat(fixture.calls).isEmpty();

        fixture.clock.advance(Duration.ofMinutes(1));
        tick();

        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(fixture.resets()).isEqualTo(1);
        assertThat(triggers()).containsExactly("takeover", "expiry", "expiry");
    }

    @Test
    void anOrphanedFailedLeaseIsTakenOverAndRetriedAtOnce() {
        Lease failed = activeLease(TEST, GONE_OWNER, NOW.minus(Duration.ofMinutes(5)))
            .failed(new LeaseAttempt(
                NOW.minus(Duration.ofMinutes(4)), AuditOutcome.FAILED,
                Optional.of(ErrorCode.UNREACHABLE), Optional.of(LeaseReason.NODE_UNREACHABLE)));
        fixture.seed(failed);

        tick();

        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(fixture.onlyLease().owner()).isEqualTo(SELF);
        assertThat(triggers()).containsExactly("takeover", "startup", "startup");
    }

    @Test
    void aLiveOtherOwnersLeaseIsLeftAlone() {
        fixture.running.add(OTHER);
        Lease seeded = activeLease(TEST, OTHER, NOW.minus(Duration.ofMinutes(5)));
        fixture.seed(seeded);

        tick();
        fixture.clock.advance(TICK);
        tick();

        assertThat(fixture.calls).isEmpty();
        assertThat(fixture.auditLog.records).isEmpty();
        assertThat(fixture.onlyLease()).isEqualTo(seeded);
    }

    @Test
    void aTakeoverWhoseLeaseCannotBeStoredIsLoggedAsAnErrorNamingItsRecord() {
        Lease seeded = activeLease(TEST, GONE_OWNER, NOW.plus(Duration.ofMinutes(5)));
        fixture.seed(seeded);
        LeaseFixture.Instance unwritable = fixture.instance(
            new LeaseOwner(4713, Optional.empty()), PrivateFiles.failingFiles());

        unwritable.tick();
        unwritable.scheduler().shutdown(Duration.ofSeconds(5));

        assertThat(fixture.onlyLease()).isEqualTo(seeded);
        AuditRecord takeover = fixture.auditLog.records.get(0);
        assertThat(triggers()).containsExactly("takeover");
        assertThat(lines(Level.ERROR)).anySatisfy(line -> assertThat(line)
            .startsWith("Audited lease change(s) not stored")
            .contains(seeded.leaseId().toString())
            .contains(takeover.auditId().toString()));
        assertThat(lines(Level.INFO)).noneSatisfy(line ->
            assertThat(line).contains("taken over"));
    }

    @Test
    void aTakeoverWhoseRecordCannotBeWrittenLeavesTheLeaseUnchanged() {
        Lease seeded = activeLease(TEST, GONE_OWNER, NOW.minus(Duration.ofMinutes(5)));
        fixture.seed(seeded);
        fixture.auditLog.failing = true;

        tick();

        assertThat(fixture.calls).isEmpty();
        assertThat(fixture.onlyLease()).isEqualTo(seeded);
        assertThat(lines(Level.ERROR)).isNotEmpty();

        fixture.auditLog.failing = false;
        fixture.clock.advance(TICK);
        tick();

        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(triggers()).containsExactly("takeover", "expiry", "expiry");
    }

    // --- retries: 60 s after the last attempt (FR-043, D-16, D-22) ----------------------------

    @Test
    void aFailedResetIsRetried60SecondsAfterItsLastAttemptNotOnTheTickBetween() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofSeconds(5))));
        fixture.resetFailure = new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
            "node1 is not reachable", "down", "start it").withNode(TEST));

        tick();
        Lease failed = fixture.onlyLease();
        assertThat(failed.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(failed.lastAttempt().orElseThrow().at()).isEqualTo(NOW);
        assertThat(failed.lastAttempt().orElseThrow().reason())
            .contains(LeaseReason.NODE_UNREACHABLE);
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);

        fixture.clock.advance(TICK);
        tick();
        assertThat(fixture.resets()).as("30 s: not yet").isEqualTo(1);
        assertThat(fixture.auditLog.records).hasSize(2);

        fixture.clock.advance(TICK.minusSeconds(2));
        tick();
        assertThat(fixture.resets()).as("58 s: not yet").isEqualTo(1);

        fixture.clock.advance(Duration.ofSeconds(1));
        tick();
        assertThat(fixture.resets()).as("59 s: due, one second of tolerance").isEqualTo(2);
        assertThat(fixture.auditLog.records).as("at most two records per minute").hasSize(4);
        assertThat(triggers()).containsExactly("startup", "startup", "retry", "retry");
        assertThat(fixture.onlyLease().lastAttempt().orElseThrow().at())
            .isEqualTo(NOW.plus(Duration.ofSeconds(59)));

        fixture.resetFailure = null;
        fixture.clock.advance(Duration.ofSeconds(60));
        tick();
        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(fixture.resets()).isEqualTo(3);
    }

    /**
     * A real StartCLI failure takes seconds; the attempt is timed from its start, so the retry
     * comes on the tick 60 s after the start, neither on the one between nor one tick later.
     */
    @Test
    void aSlowFailedResetIsRetriedOnTheTick60SecondsAfterItsAttemptStarted() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofSeconds(5))));
        fixture.resetFailure = new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            "node1 did not answer", "slow", "wait").withNode(TEST));
        fixture.onReset = server -> fixture.clock.advance(Duration.ofSeconds(5));

        tick();
        assertThat(fixture.resets()).isEqualTo(1);
        assertThat(fixture.onlyLease().lastAttempt().orElseThrow().at())
            .as("the start of the attempt, not its end").isEqualTo(NOW);

        tickAt(NOW.plus(TICK));
        assertThat(fixture.resets()).as("30 s: not yet").isEqualTo(1);

        tickAt(NOW.plus(TICK.multipliedBy(2)));
        assertThat(fixture.resets()).as("the tick 60 s after the start").isEqualTo(2);
        assertThat(fixture.onlyLease().lastAttempt().orElseThrow().at())
            .isEqualTo(NOW.plus(TICK.multipliedBy(2)));

        tickAt(NOW.plus(TICK.multipliedBy(3)));
        assertThat(fixture.resets()).isEqualTo(2);
        tickAt(NOW.plus(TICK.multipliedBy(4)));
        assertThat(fixture.resets()).isEqualTo(3);
        assertThat(fixture.auditLog.records).as("two records per attempt").hasSize(6);
    }

    @Test
    void aCleanupWhosePendingRecordCannotBeWrittenIsRetriedAfter60Seconds() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofSeconds(5))));
        fixture.auditLog.failing = true;

        tick();
        Lease kept = fixture.onlyLease();
        assertThat(fixture.calls).as("fail closed").isEmpty();
        assertThat(kept.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(kept.lastAttempt().orElseThrow().reason())
            .contains(LeaseReason.AUDIT_NOT_WRITABLE);

        fixture.auditLog.failing = false;
        fixture.clock.advance(TICK);
        tick();
        assertThat(fixture.calls).as("30 s: not yet").isEmpty();
        assertThat(fixture.auditLog.records).isEmpty();

        fixture.clock.advance(TICK);
        tick();
        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(triggers()).containsExactly("retry", "retry");
    }

    @Test
    void aRefusedCleanupIsRetriedAfter60Seconds() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofSeconds(5))));
        fixture.withoutCredentials.add(TEST);

        tick();
        Lease refused = fixture.onlyLease();
        assertThat(fixture.calls).isEmpty();
        assertThat(refused.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(refused.lastAttempt().orElseThrow().reason())
            .contains(LeaseReason.PRECONDITION);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);

        fixture.clock.advance(TICK);
        tick();
        assertThat(fixture.auditLog.records).as("30 s: not yet").hasSize(1);

        fixture.clock.advance(TICK.minusSeconds(1));
        tick();
        assertThat(outcomes()).as("59 s: due, one second of tolerance")
            .containsExactly(AuditOutcome.REFUSED, AuditOutcome.REFUSED);

        fixture.withoutCredentials.remove(TEST);
        fixture.clock.advance(Duration.ofSeconds(60));
        tick();
        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
    }

    // --- unconfigured servers (US4-10, FR-044) -------------------------------------------------

    @Test
    void aLeaseOfAnUnconfiguredServerIsForgotten7DaysAfterItsExpiryWithAWarningUntilThen() {
        Instant expiry = NOW.minus(Duration.ofMinutes(1));
        Lease seeded = activeLease(GONE, SELF, expiry);
        fixture.seed(seeded);

        tick();
        assertThat(fixture.calls).as("no connection data: not reset").isEmpty();
        assertThat(fixture.auditLog.records).isEmpty();
        assertThat(fixture.onlyLease()).isEqualTo(seeded);
        assertThat(self.scheduler().pendingForgetWarnings()).singleElement().asString()
            .contains(seeded.leaseId().toString()).contains("acme/node9")
            .contains("no longer configured").contains("2026-11-19");

        fixture.clock.advance(Duration.ofDays(7).minusMinutes(1).minusSeconds(1));
        tick();
        assertThat(fixture.onlyLease()).as("a second before").isEqualTo(seeded);

        fixture.clock.advance(Duration.ofSeconds(1));
        tick();

        Lease forgotten = fixture.onlyLease();
        assertThat(forgotten.state()).isEqualTo(LeaseState.FORGOTTEN);
        assertThat(forgotten.endedAt()).contains(expiry.plus(Duration.ofDays(7)));
        assertThat(fixture.calls).isEmpty();
        AuditRecord record = fixture.auditLog.records.get(0);
        assertThat(fixture.auditLog.records).hasSize(1);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.EXECUTED);
        assertThat(record.node()).isEqualTo("acme/node9");
        assertThat(record.capability()).isEqualTo("reset_temporary_log_level");
        assertThat(record.account()).isEmpty();
        assertThat(record.inputs()).containsExactly(
            Map.entry("trigger", "forget-after-7-days"),
            Map.entry("leaseId", seeded.leaseId().toString()));
        assertThat(self.scheduler().pendingForgetWarnings()).isEmpty();
    }

    @Test
    void aForgetWhoseRecordCannotBeWrittenLeavesTheLeaseOpen() {
        Lease seeded = activeLease(GONE, SELF, NOW.minus(Duration.ofDays(8)));
        fixture.seed(seeded);
        fixture.auditLog.failing = true;

        tick();

        assertThat(fixture.onlyLease()).isEqualTo(seeded);
    }

    // --- retention (FR-045) ----------------------------------------------------------------------

    @Test
    void endedLeasesAreRemoved7DaysAfterTheirEnd() {
        Lease ended = activeLease(TEST, SELF, NOW.minus(Duration.ofDays(8)))
            .reset(NOW.minus(Duration.ofDays(7)).plusSeconds(10));
        fixture.seed(ended);

        tick();
        assertThat(fixture.leases()).containsExactly(ended);

        fixture.clock.advance(TICK);
        tick();
        assertThat(fixture.leases()).isEmpty();
        assertThat(fixture.calls).isEmpty();
        assertThat(fixture.auditLog.records).isEmpty();
    }

    // --- robustness ------------------------------------------------------------------------------

    @Test
    void aBusyStoreLockSkipsTheTick() throws Exception {
        Lease seeded = activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1)));
        fixture.seed(seeded);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
            fixture.store().update(leases -> {
                held.countDown();
                await(release);
                return leases;
            }, Duration.ofSeconds(5)));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            tick();
        } finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }

        assertThat(fixture.calls).isEmpty();
        assertThat(fixture.onlyLease()).isEqualTo(seeded);
        assertThat(lines(Level.INFO)).anySatisfy(line ->
            assertThat(line).startsWith("Lease scheduler tick skipped:"));

        fixture.clock.advance(TICK);
        tick();
        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
    }

    @Test
    void anUnreadableStoreIsWarnedAboutOncePerHourAndNeverOverwritten() throws Exception {
        Files.createDirectories(fixture.directory());
        Path file = fixture.directory().resolve(LeaseStore.FILE);
        Files.writeString(file, "{ damaged", StandardCharsets.UTF_8);

        tick();
        fixture.clock.advance(TICK);
        tick();
        fixture.clock.advance(Duration.ofMinutes(59));
        tick();
        assertThat(unreadableWarnings()).hasSize(1);

        fixture.clock.advance(Duration.ofSeconds(30));
        tick();
        assertThat(unreadableWarnings()).hasSize(2);
        assertThat(Files.readString(file)).isEqualTo("{ damaged");
        assertThat(self.scheduler().pendingForgetWarnings()).isEmpty();
    }

    @Test
    void aCleanupThatThrowsEndsNeitherTheTickNorTheOtherCleanups() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1))),
            activeLease(TEST2, SELF, NOW.minus(Duration.ofMinutes(1))));
        fixture.brokenCredentials.add(TEST);

        assertThatCode(this::tick).doesNotThrowAnyException();

        assertThat(fixture.calls).containsExactly("test/node2 answeringNode",
            "test/node2 resetAll");
        assertThat(lease(TEST).state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(lease(TEST2).state()).isEqualTo(LeaseState.RESET);
        assertThat(lines(Level.WARN)).anySatisfy(line -> assertThat(line)
            .contains("test/node1").contains("IllegalStateException"));

        fixture.brokenCredentials.clear();
        fixture.clock.advance(TICK);
        tick();
        assertThat(lease(TEST).state()).isEqualTo(LeaseState.RESET);
    }

    @Test
    void theCleanupsOfDifferentNodesRunInParallel() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1))),
            activeLease(TEST2, SELF, NOW.minus(Duration.ofMinutes(1))));
        CountDownLatch both = new CountDownLatch(2);
        List<Boolean> metTheOther = new CopyOnWriteArrayList<>();
        fixture.onReset = server -> {
            both.countDown();
            metTheOther.add(await(both));
        };

        tick();

        assertThat(metTheOther).as("each reset ran while the other did").containsExactly(true,
            true);
        assertThat(lease(TEST).state()).isEqualTo(LeaseState.RESET);
        assertThat(lease(TEST2).state()).isEqualTo(LeaseState.RESET);
    }

    @Test
    void aSlowNodeDelaysNeitherTheTicksNorTheResetOfAnotherNode() {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1))),
            activeLease(TEST2, SELF, NOW.plus(Duration.ofSeconds(10))));
        CountDownLatch resetting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fixture.onReset = server -> {
            if (server.equals(TEST)) {
                resetting.countDown();
                await(release);
            }
        };

        List<Future<?>> first = self.scheduler().tick();
        try {
            assertThat(await(resetting)).isTrue();
            assertThat(lease(TEST).state()).as("the first tick did not wait for node1")
                .isEqualTo(LeaseState.ACTIVE);

            fixture.clock.advance(TICK);
            tick();

            assertThat(lease(TEST2).state()).as("node2 expired meanwhile: reset on the next tick")
                .isEqualTo(LeaseState.RESET);
            assertThat(lease(TEST).state()).isEqualTo(LeaseState.ACTIVE);
            assertThat(fixture.calls).filteredOn("test/node1 resetAll"::equals)
                .as("node1's running cleanup is not started again").hasSize(1);
            assertThat(lines(Level.INFO)).noneSatisfy(line ->
                assertThat(line).startsWith("Cleanup of lease"));
        } finally {
            release.countDown();
        }
        LeaseFixture.finished(first);
        assertThat(lease(TEST).state()).isEqualTo(LeaseState.RESET);
    }

    // --- start and shutdown (real threads) -----------------------------------------------------

    @Test
    void startRunsTheFirstTickAtOnceWithoutBlocking() throws Exception {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1))));

        self.scheduler().start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.onlyLease().state() != LeaseState.RESET && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(triggers()).containsExactly("startup", "startup");
    }

    @Test
    void shutdownWaitsForARunningCleanupAndIsIdempotent() throws Exception {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1))));
        CountDownLatch resetting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fixture.onReset = server -> {
            resetting.countDown();
            await(release);
        };
        self.scheduler().start();
        assertThat(resetting.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() ->
            self.scheduler().shutdown(Duration.ofSeconds(5)));
        Thread.sleep(100);
        assertThat(shutdown).as("waits for the running reset").isNotDone();
        release.countDown();
        shutdown.get(5, TimeUnit.SECONDS);

        assertThat(fixture.onlyLease().state()).isEqualTo(LeaseState.RESET);
        long start = System.nanoTime();
        self.scheduler().shutdown(Duration.ofSeconds(5));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("second call returns at once")
            .isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void shutdownWaitsAtMostTheGivenTime() throws Exception {
        fixture.seed(activeLease(TEST, SELF, NOW.minus(Duration.ofMinutes(1))));
        CountDownLatch resetting = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        fixture.onReset = server -> {
            resetting.countDown();
            await(never);
        };
        self.scheduler().start();
        assertThat(resetting.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        self.scheduler().shutdown(Duration.ofMillis(300));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        never.countDown();
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** One tick; returns when the cleanups it started have ended. */
    private void tick() {
        self.tick();
    }

    /** Moves the clock forward to {@code at}, then ticks. */
    private void tickAt(Instant at) {
        fixture.clock.advance(Duration.between(fixture.clock.instant(), at));
        tick();
    }

    private Lease lease(NodeId server) {
        return fixture.leases().stream().filter(lease -> lease.server().equals(server))
            .findFirst().orElseThrow();
    }

    private List<AuditOutcome> outcomes() {
        return fixture.auditLog.records.stream().map(AuditRecord::outcome).toList();
    }

    private List<String> triggers() {
        return fixture.auditLog.records.stream().map(record -> record.inputs().get("trigger"))
            .toList();
    }

    private List<String> lines(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> unreadableWarnings() {
        return lines(Level.WARN).stream()
            .filter(line -> line.startsWith("The lease store cannot be read")).toList();
    }

    /**
     * Waits for {@code latch} up to 5 s (an interrupt ends the wait).
     *
     * @return whether it opened
     */
    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
