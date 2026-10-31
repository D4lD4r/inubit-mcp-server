package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability.Kind;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState.DetectionFailure;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState.Timed;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T006: the per-node version state (research D-4, data-model.md → NodeVersionState /
 * NodeVersions) with a fixed clock and per-node re-detection bounds.
 */
@Timeout(30)
class NodeVersionsTest {

    private static final NodeId AUTO = NodeId.parse("dev/node1");
    private static final NodeId SHORT_TTL = NodeId.parse("qa/node1");
    private static final NodeId PINNED = NodeId.parse("prod/node1");
    private static final NodeId V8_1 = NodeId.parse("prod/node2");
    private static final NodeId V9_X = NodeId.parse("prod/node3");
    private static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final InubitVersion V_8_1_17 = version("8.1.17");
    private static final InubitVersion V_8_1_18 = version("8.1.18");
    private static final InubitVersion V_9_0_7 = version("9.0.7");

    private final MutableClock clock = new MutableClock(T0);
    private final Map<NodeId, VersionSetting> settings = Map.of(
        AUTO, VersionSetting.AUTO,
        SHORT_TTL, VersionSetting.AUTO,
        PINNED, VersionSetting.parse("8.1.17").orElseThrow(),
        V8_1, VersionSetting.V8_1,
        V9_X, VersionSetting.V9_X);
    private final Map<NodeId, Duration> bounds = Map.of(AUTO, TTL, SHORT_TTL,
        Duration.ofMinutes(1), PINNED, TTL, V8_1, TTL, V9_X, TTL);
    private final NodeVersions versions = new NodeVersions(clock, settings::get, bounds::get);

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static ClientVersion client(String version, String from, String to) {
        return new ClientVersion(version(version), Optional.of(version(from)),
            Optional.of(version(to)));
    }

    /** T007: the port read used by the adapter layer (D-16) is the state's effective version. */
    @Test
    void theObserverPortAnswersTheEffectiveVersion() {
        VersionObserver observer = versions;
        assertThat(observer.effectiveVersion(AUTO)).isEmpty();
        assertThat(observer.effectiveVersion(PINNED)).contains(V_8_1_17);

        observer.detected(AUTO, V_8_1_17);
        clock.advance(Duration.ofMinutes(1));
        observer.observed(AUTO, V_9_0_7);

        assertThat(observer.effectiveVersion(AUTO)).contains(V_9_0_7)
            .isEqualTo(versions.state(AUTO).effectiveVersion());
    }

    // --- AUTO: detection on first use, re-detection before writes ---------------------------

    @Test
    void anAutoNodeWithoutVersionNeedsADetectionForReadsAndWrites() {
        NodeVersionState state = versions.state(AUTO);

        assertThat(state.setting()).isEqualTo(VersionSetting.AUTO);
        assertThat(state.effectiveVersion()).isEmpty();
        assertThat(versions.needsDetection(AUTO, Kind.READ)).isTrue();
        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isTrue();
        assertThat(versions.writableVersion(AUTO)).isEmpty();
    }

    @Test
    void aDetectionIsCachedForReadsAndForWritesWithinTheNodesBound() {
        versions.detected(AUTO, V_8_1_17);
        clock.advance(TTL);

        NodeVersionState state = versions.state(AUTO);
        assertThat(state.detected()).contains(new Timed<>(V_8_1_17, T0));
        assertThat(state.effectiveVersion()).contains(V_8_1_17);
        assertThat(versions.needsDetection(AUTO, Kind.READ)).isFalse();
        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isFalse();
        assertThat(versions.writableVersion(AUTO)).contains(V_8_1_17);
    }

    @Test
    void aVersionOlderThanTheBoundIsReDetectedBeforeAWriteButStillServesReads() {
        versions.detected(AUTO, V_8_1_17);
        clock.advance(TTL.plusSeconds(1));

        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isTrue();
        assertThat(versions.needsDetection(AUTO, Kind.READ)).isFalse();
        assertThat(versions.writableVersion(AUTO)).isEmpty();
        assertThat(versions.state(AUTO).effectiveVersion()).contains(V_8_1_17);

        versions.detected(AUTO, V_8_1_18);

        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isFalse();
        assertThat(versions.writableVersion(AUTO)).contains(V_8_1_18);
    }

    @Test
    void theBoundIsThePerNodeInventoryCacheTtl() {
        versions.detected(AUTO, V_8_1_17);
        versions.detected(SHORT_TTL, V_8_1_17);
        clock.advance(Duration.ofMinutes(2));

        assertThat(versions.needsDetection(SHORT_TTL, Kind.WRITE)).isTrue();
        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isFalse();
    }

    @Test
    void staleThenFailedReDetectionLeavesNoWritableVersionWhileReadsKeep81UntilAnObservation() {
        versions.detected(AUTO, V_8_1_17);
        clock.advance(Duration.ofMinutes(1));
        versions.stale(AUTO);

        assertThat(versions.state(AUTO).stale()).isTrue();
        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isTrue();
        assertThat(versions.needsDetection(AUTO, Kind.READ)).isFalse();

        versions.detectionFailed(AUTO, ErrorCode.UNREACHABLE);
        Instant failedAt = clock.instant();
        clock.advance(Duration.ofSeconds(10));

        NodeVersionState afterFailure = versions.state(AUTO);
        assertThat(afterFailure.detectionFailure())
            .contains(new Timed<>(new DetectionFailure(ErrorCode.UNREACHABLE, false), failedAt));
        assertThat(versions.writableVersion(AUTO)).as("unknown for writes").isEmpty();
        assertThat(afterFailure.effectiveVersion()).as("reads").contains(V_8_1_17);
        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).as("60 s back-off").isFalse();

        clock.advance(Duration.ofSeconds(20));
        versions.observed(AUTO, V_8_1_17);

        NodeVersionState afterObservation = versions.state(AUTO);
        assertThat(afterObservation.stale()).isFalse();
        assertThat(afterObservation.detectionFailure()).isEmpty();
        assertThat(versions.writableVersion(AUTO)).contains(V_8_1_17);
        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isFalse();
    }

    @Test
    void aSuppressedReDetectionCountsAsUnknownForWritesUntilTheBackOffEnds() {
        versions.detected(AUTO, V_8_1_17);
        versions.stale(AUTO);
        versions.detectionFailed(AUTO, ErrorCode.TIMEOUT);
        clock.advance(Duration.ofSeconds(59));

        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isFalse();
        assertThat(versions.writableVersion(AUTO)).isEmpty();

        clock.advance(Duration.ofSeconds(1));

        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isTrue();
    }

    @Test
    void aFailedFirstDetectionIsRetriedOnlyAfter60Seconds() {
        versions.detectionFailed(AUTO, ErrorCode.AUTH_FAILED);
        clock.advance(Duration.ofSeconds(59));

        assertThat(versions.state(AUTO).effectiveVersion()).isEmpty();
        assertThat(versions.needsDetection(AUTO, Kind.READ)).isFalse();
        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isFalse();

        clock.advance(Duration.ofSeconds(1));

        assertThat(versions.needsDetection(AUTO, Kind.READ)).isTrue();
    }

    @Test
    void aSuccessfulDetectionClearsStaleAndTheFailure() {
        versions.detected(AUTO, V_8_1_17);
        versions.stale(AUTO);
        versions.detectionFailed(AUTO, ErrorCode.UNREACHABLE);
        clock.advance(Duration.ofSeconds(60));
        versions.detected(AUTO, V_8_1_17);

        NodeVersionState state = versions.state(AUTO);
        assertThat(state.stale()).isFalse();
        assertThat(state.detectionFailure()).isEmpty();
        assertThat(versions.writableVersion(AUTO)).contains(V_8_1_17);
    }

    /** Feature 011 T011: a detection answered with HTTP 404 keeps that flag (FR-003). */
    @Test
    void aFailedDetectionKeepsWhetherTheEndpointWasMissingUntilADetectionSucceeds() {
        versions.detectionFailed(AUTO, ErrorCode.UNEXPECTED_RESPONSE, true);

        assertThat(versions.state(AUTO).detectionFailure()).contains(new Timed<>(
            new DetectionFailure(ErrorCode.UNEXPECTED_RESPONSE, true), T0));

        clock.advance(Duration.ofSeconds(60));
        versions.detectionFailed(AUTO, ErrorCode.UNEXPECTED_RESPONSE);

        assertThat(versions.state(AUTO).detectionFailure()).as("another cause replaces it")
            .contains(new Timed<>(new DetectionFailure(ErrorCode.UNEXPECTED_RESPONSE, false),
                clock.instant()));

        clock.advance(Duration.ofSeconds(60));
        versions.detectionFailed(AUTO, ErrorCode.UNEXPECTED_RESPONSE, true);
        versions.detected(AUTO, V_8_1_17);

        assertThat(versions.state(AUTO).detectionFailure()).isEmpty();
    }

    @Test
    void onAutoAnObservationReplacesTheDetectionAndRestartsTheBound() {
        versions.detected(AUTO, V_8_1_17);
        clock.advance(Duration.ofMinutes(8));
        versions.observed(AUTO, V_9_0_7);
        clock.advance(Duration.ofMinutes(8));

        NodeVersionState state = versions.state(AUTO);
        assertThat(state.observed()).contains(new Timed<>(V_9_0_7, T0.plus(Duration.ofMinutes(8))));
        assertThat(state.effectiveVersion()).contains(V_9_0_7);
        assertThat(state.pinContradiction()).as("AUTO has no pin").isFalse();
        assertThat(versions.needsDetection(AUTO, Kind.WRITE)).isFalse();
        assertThat(versions.writableVersion(AUTO)).contains(V_9_0_7);
    }

    @Test
    void theMostRecentOfDetectionAndObservationIsEffective() {
        versions.observed(AUTO, V_8_1_17);
        clock.advance(Duration.ofMinutes(1));
        versions.detected(AUTO, V_8_1_18);

        assertThat(versions.state(AUTO).effectiveVersion()).contains(V_8_1_18);
    }

    // --- pinned nodes and contradictions (FR-001, FR-012) -----------------------------------

    @Test
    void aPinnedNodeUsesThePinAndNeverNeedsADetection() {
        NodeVersionState state = versions.state(PINNED);

        assertThat(state.effectiveVersion()).contains(V_8_1_17);
        assertThat(versions.needsDetection(PINNED, Kind.READ)).isFalse();
        clock.advance(TTL.multipliedBy(3));
        versions.stale(PINNED);
        assertThat(versions.needsDetection(PINNED, Kind.WRITE)).isFalse();
        assertThat(versions.writableVersion(PINNED)).contains(V_8_1_17);
    }

    @Test
    void anObservationOverridesThePinAndAPatchOnlyDifferenceIsNoContradiction() {
        versions.observed(PINNED, V_8_1_18);

        NodeVersionState state = versions.state(PINNED);
        assertThat(state.effectiveVersion()).contains(V_8_1_18);
        assertThat(state.pinContradiction()).isFalse();
    }

    @Test
    void aContradictionIsSetByAnObservationAndClearedByALaterMatchingOne() {
        versions.observed(PINNED, V_9_0_7);

        assertThat(versions.state(PINNED).pinContradiction()).isTrue();
        assertThat(versions.state(PINNED).effectiveVersion()).contains(V_9_0_7);

        versions.detected(PINNED, V_8_1_17);
        assertThat(versions.state(PINNED).pinContradiction())
            .as("only get_health's observation counts").isTrue();

        versions.observed(PINNED, V_8_1_18);
        assertThat(versions.state(PINNED).pinContradiction()).isFalse();
    }

    @Test
    void aV81LineIsEffectiveAs81AndContradictedByAnotherMinor() {
        assertThat(versions.state(V8_1).effectiveVersion()).hasValueSatisfying(v -> {
            assertThat(v.major()).isEqualTo(8);
            assertThat(v.minor()).isEqualTo(1);
            assertThat(v.patch()).isEmpty();
        });
        assertThat(versions.needsDetection(V8_1, Kind.READ)).isFalse();

        versions.observed(V8_1, V_8_1_18);
        assertThat(versions.state(V8_1).pinContradiction()).isFalse();
        versions.observed(V8_1, version("8.2.0"));
        assertThat(versions.state(V8_1).pinContradiction()).isTrue();
    }

    @Test
    void aV9XLineGivesNoVersionAndIsContradictedOnlyByAnotherMajor() {
        assertThat(versions.state(V9_X).effectiveVersion()).as("no major.minor").isEmpty();
        assertThat(versions.needsDetection(V9_X, Kind.READ)).isFalse();
        assertThat(versions.needsDetection(V9_X, Kind.WRITE)).isFalse();

        versions.observed(V9_X, version("9.3.1"));
        assertThat(versions.state(V9_X).pinContradiction()).isFalse();
        assertThat(versions.state(V9_X).effectiveVersion()).contains(version("9.3.1"));

        versions.observed(V9_X, V_8_1_17);
        assertThat(versions.state(V9_X).pinContradiction()).isTrue();
    }

    // --- StartCLI client (FR-002) ----------------------------------------------------------

    @Test
    void aClientResultIsKeptForTheProcessLifetime() {
        assertThat(versions.needsClientProbe(AUTO)).isTrue();

        ClientVersion client = client("8.1.17", "4.0.1", "8.1.17");
        versions.clientProbed(AUTO, client);
        clock.advance(Duration.ofDays(30));
        versions.clientProbeFailed(AUTO, ErrorCode.TIMEOUT);

        NodeVersionState state = versions.state(AUTO);
        assertThat(state.client()).contains(new Timed<>(client, T0));
        assertThat(state.clientFailure()).isEmpty();
        assertThat(versions.needsClientProbe(AUTO)).isFalse();
    }

    @Test
    void aClientWithoutRangeIsASuccessWithAnEmptyRange() {
        ClientVersion noRange = new ClientVersion(V_8_1_17, Optional.empty(), Optional.empty());
        versions.clientProbed(AUTO, noRange);
        clock.advance(Duration.ofHours(1));

        assertThat(versions.state(AUTO).client()).map(Timed::value).contains(noRange);
        assertThat(versions.needsClientProbe(AUTO)).isFalse();
    }

    @Test
    void aFailedOrTimedOutProbeIsRetriedOnlyAfter60Seconds() {
        versions.clientProbeFailed(AUTO, ErrorCode.TIMEOUT);
        clock.advance(Duration.ofSeconds(59));

        assertThat(versions.state(AUTO).clientFailure())
            .contains(new Timed<>(ErrorCode.TIMEOUT, T0));
        assertThat(versions.needsClientProbe(AUTO)).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(versions.needsClientProbe(AUTO)).isTrue();

        versions.clientProbed(AUTO, client("8.1.17", "4.0.1", "8.1.17"));
        assertThat(versions.state(AUTO).clientFailure()).isEmpty();
        assertThat(versions.needsClientProbe(AUTO)).isFalse();
    }

    // --- nodes and threads -----------------------------------------------------------------

    @Test
    void nodesAreIndependent() {
        versions.detected(AUTO, V_9_0_7);
        versions.stale(AUTO);
        versions.clientProbeFailed(AUTO, ErrorCode.CLI_UNAVAILABLE);

        NodeVersionState other = versions.state(SHORT_TTL);
        assertThat(other.effectiveVersion()).isEmpty();
        assertThat(other.stale()).isFalse();
        assertThat(other.clientFailure()).isEmpty();
    }

    @Test
    void concurrentReportsOnOneNodeLoseNoUpdate() throws Exception {
        Map<NodeId, VersionSetting> many = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            many.put(NodeId.parse("load/node" + i), VersionSetting.AUTO);
        }
        NodeVersions shared = new NodeVersions(clock, many::get, node -> TTL);
        ClientVersion client = client("8.1.17", "4.0.1", "8.1.17");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (NodeId node : many.keySet()) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    shared.detected(node, V_8_1_17);
                    return null;
                }));
                tasks.add(pool.submit(() -> {
                    start.await();
                    shared.clientProbed(node, client);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get();
            }
        } finally {
            pool.shutdownNow();
        }

        for (NodeId node : many.keySet()) {
            NodeVersionState state = shared.state(node);
            assertThat(state.detected()).as(node.toString()).isPresent();
            assertThat(state.client()).as(node.toString()).isPresent();
        }
    }
}
