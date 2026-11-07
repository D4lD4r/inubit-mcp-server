package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T041: the write lock of {@link NodeVersions} (feature 017, FR-018, research D-24). A write is
 * handed off only while the node's known major.minor equals the version it was admitted on; a
 * version change of the node waits for a running hand-off and is applied before any hand-off
 * queued after it. Every concurrent test waits on latches or lock queues with a bound; none
 * sleeps.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WriteLockTest {

    private static final NodeId AUTO = NodeId.parse("test/node1");
    private static final NodeId OTHER = NodeId.parse("test/node2");
    private static final NodeId PINNED = NodeId.parse("prod/node1");
    private static final NodeId V9_0 = NodeId.parse("prod/node4");
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00Z"));
    private final NodeVersions versions = new NodeVersions(clock,
        Map.of(AUTO, VersionSetting.AUTO, OTHER, VersionSetting.AUTO,
            PINNED, VersionSetting.parse("8.1.17").orElseThrow(),
            V9_0, VersionSetting.V9_0)::get,
        node -> Duration.ofMinutes(10));
    private final WriteLock lock = versions;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static WritePermit permit(NodeId node, String admitted) {
        return new WritePermit(node, Capability.DEVELOPMENT_WRITE, version(admitted));
    }

    private static ToolError refusal(Runnable call) {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(e).as("refused").isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        return e.error();
    }

    /** Bounded wait for a condition another thread establishes (no sleep). */
    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition reached in time").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @Test
    void onTheAdmittedVersionTheActionRunsWithThatVersion() {
        versions.detected(AUTO, version("8.1.17"));

        String result = lock.handOff(permit(AUTO, "8.1.17"), current -> "sent on "
            + current.orElseThrow().raw());

        assertThat(result).isEqualTo("sent on 8.1.17");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"8.1.17, 9.0.7, 8.1", "9.0.7, 9.1.0, 9.0", "9.0.7, 8.1.17, 9.0"})
    void aChangeToAnotherMajorMinorRefusesWithoutRunningTheAction(String admitted, String now,
        String line) {
        versions.detected(AUTO, version(admitted));
        versions.observed(AUTO, version(now));
        AtomicBoolean ran = new AtomicBoolean();

        ToolError error = refusal(() -> lock.handOff(permit(AUTO, admitted), current -> {
            ran.set(true);
            return null;
        }));

        assertThat(ran).isFalse();
        assertThat(error.message()).isEqualTo("INUBIT on test/node1 changed from " + admitted
            + " to " + now + " during this call; the write was admitted for INUBIT " + line
            + "; nothing was sent");
        assertThat(error.likelyCause()).isEqualTo("A parallel call found that test/node1 runs"
            + " another INUBIT version");
        assertThat(error.nextStep()).isEqualTo("Call the tool again; it is judged on the"
            + " version INUBIT runs now");
        assertThat(error.node()).contains(AUTO);
    }

    @Test
    void aStaleAutoNodeRefusesWithoutRunningTheAction() {
        versions.detected(AUTO, version("8.1.17"));
        versions.stale(AUTO);
        AtomicBoolean ran = new AtomicBoolean();

        ToolError error = refusal(() -> lock.handOff(permit(AUTO, "8.1.17"), current -> {
            ran.set(true);
            return null;
        }));

        assertThat(ran).isFalse();
        assertThat(error.message()).isEqualTo("INUBIT on test/node1 may run another version"
            + " than 8.1.17 since this call was admitted; nothing was sent");
        assertThat(error.likelyCause()).isEqualTo("A parallel call found that test/node1 runs"
            + " another INUBIT version");
        assertThat(error.nextStep()).isEqualTo("Call the tool again; it is judged on the"
            + " version INUBIT runs now");
        assertThat(error.node()).contains(AUTO);
    }

    @Test
    void aStaleMarkOfTheWritesOwnResponseLetsTheSamePermitHandOffOnTheKnownVersion() {
        // review of stage 5: e.g. the rollback after the call's own import answered unexpectedly
        versions.detected(AUTO, version("8.1.17"));
        WritePermit own = permit(AUTO, "8.1.17");
        versions.staleAfterWrite(AUTO, own);

        String result = lock.handOff(own, current -> "sent on " + current.orElseThrow().raw());

        assertThat(result).isEqualTo("sent on 8.1.17");
        assertThat(versions.state(AUTO).stale()).as("the next call re-detects").isTrue();
    }

    @Test
    void anOwnStaleMarkRefusesEveryOtherPermitEvenAnEqualOne() {
        versions.detected(AUTO, version("8.1.17"));
        versions.staleAfterWrite(AUTO, permit(AUTO, "8.1.17"));

        ToolError error = refusal(() -> lock.handOff(permit(AUTO, "8.1.17"), current -> null));

        assertThat(error.message()).isEqualTo("INUBIT on test/node1 may run another version"
            + " than 8.1.17 since this call was admitted; nothing was sent");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"own then foreign", "foreign then own", "own then another permit's"})
    void aForeignMarkBesidesTheOwnOneRefuses(String order) {
        versions.detected(AUTO, version("8.1.17"));
        WritePermit own = permit(AUTO, "8.1.17");
        switch (order) {
            case "own then foreign" -> {
                versions.staleAfterWrite(AUTO, own);
                versions.stale(AUTO);
            }
            case "foreign then own" -> {
                versions.stale(AUTO);
                versions.staleAfterWrite(AUTO, own);
            }
            default -> {
                versions.staleAfterWrite(AUTO, own);
                versions.staleAfterWrite(AUTO, permit(AUTO, "8.1.17"));
            }
        }
        AtomicBoolean ran = new AtomicBoolean();

        ToolError error = refusal(() -> lock.handOff(own, current -> {
            ran.set(true);
            return null;
        }));

        assertThat(ran).isFalse();
        assertThat(error.message()).isEqualTo("INUBIT on test/node1 may run another version"
            + " than 8.1.17 since this call was admitted; nothing was sent");
    }

    @Test
    void anOwnMarkOfAPermitOfAnotherNodeIsForeign() {
        versions.detected(AUTO, version("8.1.17"));
        WritePermit other = permit(OTHER, "8.1.17");
        versions.staleAfterWrite(AUTO, other);

        assertThat(versions.state(AUTO).stale()).isTrue();
        assertThat(versions.state(AUTO).staleOnlyBy(other)).isFalse();
    }

    @Test
    void afterAnOwnMarkTheMajorMinorIsStillCompared() {
        versions.detected(AUTO, version("8.1.17"));
        WritePermit own = permit(AUTO, "8.1.17");
        versions.staleAfterWrite(AUTO, own);
        versions.detected(AUTO, version("9.0.7"));

        ToolError error = refusal(() -> lock.handOff(own, current -> null));

        assertThat(error.message()).startsWith("INUBIT on test/node1 changed from 8.1.17 to"
            + " 9.0.7 during this call");
    }

    @Test
    void anUnknownVersionRefusesWithoutRunningTheAction() {
        AtomicBoolean ran = new AtomicBoolean();

        ToolError error = refusal(() -> lock.handOff(permit(AUTO, "8.1.17"), current -> {
            ran.set(true);
            return null;
        }));

        assertThat(ran).isFalse();
        assertThat(error.message()).isEqualTo("INUBIT on test/node1 may run another version"
            + " than 8.1.17 since this call was admitted; nothing was sent");
    }

    @Test
    void aPatchChangeRunsTheActionWithTheNewVersion() {
        versions.detected(AUTO, version("9.0.6"));
        versions.observed(AUTO, version("9.0.7"));

        Optional<InubitVersion> seen = lock.handOff(permit(AUTO, "9.0.6"), current -> current);

        assertThat(seen).contains(version("9.0.7"));
    }

    @Test
    void aPinnedOrLineNodeIsJudgedLikeTheGateWhichIgnoresStaleThere() {
        // the gate admits writes on a non-AUTO node with its configured version even when
        // stale (NodeVersionState#writableVersion); only an observation clears stale there
        versions.stale(PINNED);
        versions.stale(V9_0);

        String pinned = lock.handOff(permit(PINNED, "8.1.17"), current -> current.orElseThrow()
            .raw());
        String line = lock.handOff(new WritePermit(V9_0, Capability.E2E_TEST, version("9.0")),
            current -> current.orElseThrow().raw());

        assertThat(pinned).isEqualTo("8.1.17");
        assertThat(line).isEqualTo("9.0");
    }

    @Test
    void anObservationContradictingThePinRefuses() {
        versions.observed(PINNED, version("9.0.7"));

        ToolError error = refusal(() -> lock.handOff(new WritePermit(PINNED,
            Capability.PROCESS_CONTROL, version("8.1.17")), current -> null));

        assertThat(error.message()).isEqualTo("INUBIT on prod/node1 changed from 8.1.17 to"
            + " 9.0.7 during this call; the write was admitted for INUBIT 8.1; nothing was sent");
    }

    @Test
    void aRefusalCarriesNoSecretsOrAddresses() {
        versions.detected(AUTO, version("8.1.17"));
        versions.observed(AUTO, version("9.0.7"));

        ToolError error = refusal(() -> lock.handOff(permit(AUTO, "8.1.17"), current -> null));

        String text = error.message() + error.likelyCause() + error.nextStep();
        assertThat(text).doesNotContain("http", "://", "password", "user", "@", "example");
    }

    @Test
    void aVersionChangeFromAnotherThreadWaitsUntilTheActionReturns() throws Exception {
        versions.detected(AUTO, version("8.1.17"));
        CountDownLatch inAction = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Optional<InubitVersion>> write = threads.submit(() -> lock.handOff(
            permit(AUTO, "8.1.17"), current -> {
                inAction.countDown();
                awaitLatch(release);
                return versions.effectiveVersion(AUTO);
            }));
        assertThat(inAction.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        List<Runnable> changes = List.of(() -> versions.observed(AUTO, version("9.0.7")),
            () -> versions.detected(AUTO, version("9.0.7")), () -> versions.stale(AUTO));
        List<Future<?>> pending = changes.stream().<Future<?>>map(threads::submit).toList();
        await(() -> versions.queueLength(AUTO) == changes.size());

        assertThat(pending).noneMatch(Future::isDone);
        release.countDown();
        assertThat(write.get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("seen inside the action")
            .contains(version("8.1.17"));
        for (Future<?> change : pending) {
            change.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }
        assertThat(versions.effectiveVersion(AUTO)).contains(version("9.0.7"));
    }

    @Test
    void aVersionChangeQueuedBeforeAHandOffIsAppliedBeforeIt() throws Exception {
        versions.detected(AUTO, version("8.1.17"));
        CountDownLatch inAction = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<String> first = threads.submit(() -> lock.handOff(permit(AUTO, "8.1.17"),
            current -> {
                inAction.countDown();
                awaitLatch(release);
                return "A sent";
            }));
        assertThat(inAction.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        Future<?> observation = threads.submit(() -> versions.observed(AUTO, version("9.0.7")));
        await(() -> versions.queueLength(AUTO) == 1);
        AtomicBoolean secondRan = new AtomicBoolean();
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> lock.handOff(
            permit(AUTO, "8.1.17"), current -> {
                secondRan.set(true);
                return "B sent";
            }), threads);
        await(() -> versions.queueLength(AUTO) == 2);

        release.countDown();

        assertThat(first.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("A sent");
        observation.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(WAIT.toSeconds(), TimeUnit.SECONDS))
            .hasCauseInstanceOf(ToolErrorException.class)
            .cause().hasMessageContaining("changed from 8.1.17 to 9.0.7");
        assertThat(secondRan).isFalse();
    }

    @Test
    void handOffsOnDifferentNodesRunInParallel() throws Exception {
        versions.detected(AUTO, version("8.1.17"));
        versions.detected(OTHER, version("9.0.7"));
        CountDownLatch bothInside = new CountDownLatch(2);
        Future<Boolean> first = threads.submit(() -> lock.handOff(permit(AUTO, "8.1.17"),
            current -> {
                bothInside.countDown();
                return awaitLatch(bothInside);
            }));
        Future<Boolean> second = threads.submit(() -> lock.handOff(permit(OTHER, "9.0.7"),
            current -> {
                bothInside.countDown();
                return awaitLatch(bothInside);
            }));

        assertThat(first.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anUpdateFromInsideAHandOffIsRefusedAndTheLockIsReleased() throws Exception {
        versions.detected(AUTO, version("8.1.17"));

        assertThatThrownBy(() -> lock.handOff(permit(AUTO, "8.1.17"), current -> {
            versions.observed(AUTO, version("9.0.7"));
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lock.handOff(permit(AUTO, "8.1.17"), current -> {
            versions.stale(OTHER);
            return null;
        })).isInstanceOf(IllegalStateException.class);

        assertThat(versions.effectiveVersion(AUTO)).contains(version("8.1.17"));
        threads.submit(() -> versions.observed(AUTO, version("8.1.18")))
            .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(versions.effectiveVersion(AUTO)).contains(version("8.1.18"));
    }

    @Test
    void aHandOffFromInsideAHandOffIsRefusedAndTheLockIsReleased() throws Exception {
        versions.detected(AUTO, version("8.1.17"));
        versions.detected(OTHER, version("8.1.17"));

        assertThatThrownBy(() -> lock.handOff(permit(AUTO, "8.1.17"), current ->
            lock.handOff(permit(AUTO, "8.1.17"), inner -> "nested")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lock.handOff(permit(AUTO, "8.1.17"), current ->
            lock.handOff(permit(OTHER, "8.1.17"), inner -> "nested")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(threads.submit(() -> lock.handOff(permit(AUTO, "8.1.17"), current -> "again"))
            .get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("again");
        versions.observed(AUTO, version("8.1.18"));
    }

    @Test
    void anExceptionOfTheActionReleasesTheLock() throws Exception {
        versions.detected(AUTO, version("8.1.17"));

        assertThatThrownBy(() -> lock.handOff(permit(AUTO, "8.1.17"), current -> {
            throw new IllegalArgumentException("launch failed");
        })).hasMessage("launch failed");

        threads.submit(() -> versions.observed(AUTO, version("8.1.18")))
            .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        String after = lock.handOff(permit(AUTO, "8.1.17"), current -> "after");
        assertThat(after).isEqualTo("after");
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
