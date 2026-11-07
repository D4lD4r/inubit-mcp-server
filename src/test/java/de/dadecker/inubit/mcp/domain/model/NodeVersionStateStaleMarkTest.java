package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Feature 017 (FR-018, research D-24, review of stage 5): who caused the stale mark of a node. A
 * mark raised by a write's own response belongs to that write's permit object; any other mark is
 * foreign, and a foreign mark wins over an own one.
 */
class NodeVersionStateStaleMarkTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final NodeId NODE = NodeId.parse("test/node1");

    private final WritePermit permit = new WritePermit(NODE, Capability.DEVELOPMENT_WRITE,
        V8_1_17);
    private final WritePermit equalPermit = new WritePermit(NODE, Capability.DEVELOPMENT_WRITE,
        V8_1_17);
    private final NodeVersionState detected = NodeVersionState.initial(VersionSetting.AUTO)
        .withDetected(V8_1_17, NOW);

    @Test
    void aMarkOfAWritesOwnResponseBelongsToThatPermitObjectOnly() {
        NodeVersionState own = detected.withStaleAfterWrite(permit);

        assertThat(own.stale()).isTrue();
        assertThat(own.staleOnlyBy(permit)).isTrue();
        assertThat(own.staleOnlyBy(equalPermit)).as("compared by identity").isFalse();
    }

    @Test
    void aSecondMarkOfTheSamePermitKeepsItOwn() {
        assertThat(detected.withStaleAfterWrite(permit).withStaleAfterWrite(permit)
            .staleOnlyBy(permit)).isTrue();
    }

    @Test
    void aForeignMarkWinsOverAnOwnOneInEitherOrder() {
        assertThat(detected.withStaleAfterWrite(permit).withStale().staleOnlyBy(permit))
            .isFalse();
        assertThat(detected.withStale().withStaleAfterWrite(permit).staleOnlyBy(permit))
            .isFalse();
    }

    @Test
    void aMarkOfAnotherPermitIsForeignToBoth() {
        NodeVersionState both = detected.withStaleAfterWrite(permit)
            .withStaleAfterWrite(equalPermit);

        assertThat(both.stale()).isTrue();
        assertThat(both.staleOnlyBy(permit)).isFalse();
        assertThat(both.staleOnlyBy(equalPermit)).isFalse();
    }

    @Test
    void aForeignMarkBelongsToNoPermitAndANodeWithoutMarkIsNotOwned() {
        assertThat(detected.withStale().staleOnlyBy(permit)).isFalse();
        assertThat(detected.staleOnlyBy(permit)).isFalse();
    }

    @Test
    void aDetectionOrAnObservationClearsTheMarkAndItsOwner() {
        NodeVersionState redetected = detected.withStale().withStaleAfterWrite(permit)
            .withDetected(V8_1_17, NOW.plusSeconds(1));
        NodeVersionState observed = detected.withStale().withObserved(V8_1_17,
            NOW.plusSeconds(1));

        assertThat(redetected.stale()).isFalse();
        assertThat(redetected.staleOnlyBy(permit)).isFalse();
        assertThat(redetected.withStaleAfterWrite(permit).staleOnlyBy(permit))
            .as("a foreign mark before the detection is gone").isTrue();
        assertThat(observed.withStaleAfterWrite(permit).staleOnlyBy(permit)).isTrue();
    }

    @Test
    void otherReportsKeepTheMarkAndItsOwner() {
        NodeVersionState own = detected.withStaleAfterWrite(permit)
            .withDetectionFailure(new NodeVersionState.DetectionFailure(ErrorCode.TIMEOUT,
                false), NOW)
            .withClientFailure(ClientKey.NONE, ErrorCode.TIMEOUT, NOW);

        assertThat(own.staleOnlyBy(permit)).isTrue();
    }

    @Test
    void anOwnMarkStillMakesTheNextCallReDetect() {
        NodeVersionState own = detected.withStaleAfterWrite(permit);

        assertThat(own.needsDetection(Capability.Kind.WRITE, NOW, Duration.ofMinutes(10)))
            .isTrue();
        assertThat(own.writableVersion(NOW, Duration.ofMinutes(10))).isEmpty();
    }
}
