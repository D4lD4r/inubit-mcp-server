package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NodeVersionState.Timed;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Feature 017 (T015, FR-017, research D-5): a client check belongs to the client it checked; the
 * results and failures of a node are kept per {@link ClientKey}.
 */
class NodeVersionStateClientsTest {

    private static final Instant T0 = Instant.parse("2026-11-06T08:00:00Z");
    private static final ClientKey CLIENT_81 = new ClientKey("/opt/acme/inubit-8.1/client",
        "/opt/jdk-17");
    private static final ClientKey CLIENT_90 = new ClientKey("/opt/acme/inubit-9.0/client",
        "/opt/jdk-21");
    private static final ClientVersion VERSION_81 = client("8.1.17", "8.1.0", "8.1.99");
    private static final ClientVersion VERSION_90 = client("9.0.7", "9.0.0", "9.0.99");

    private final NodeVersionState initial = NodeVersionState.initial(VersionSetting.AUTO);

    private static ClientVersion client(String version, String from, String to) {
        return new ClientVersion(InubitVersion.parse(version).orElseThrow(),
            InubitVersion.parse(from), InubitVersion.parse(to));
    }

    @Test
    void initiallyNoClientIsKnownAndEveryKeyNeedsAProbe() {
        assertThat(initial.clients()).isEmpty();
        assertThat(initial.clientFailures()).isEmpty();
        assertThat(initial.clientFor(CLIENT_81)).isEmpty();
        assertThat(initial.clientFailureFor(CLIENT_81)).isEmpty();
        assertThat(initial.needsClientProbe(T0, CLIENT_81)).isTrue();
        assertThat(initial.needsClientProbe(T0, ClientKey.NONE)).isTrue();
    }

    @Test
    void aResultBelongsToItsKeyOnly() {
        NodeVersionState state = initial.withClient(new ProbedClient(CLIENT_81, VERSION_81), T0);

        assertThat(state.clientFor(CLIENT_81)).contains(new Timed<>(VERSION_81, T0));
        assertThat(state.needsClientProbe(T0, CLIENT_81)).isFalse();
        assertThat(state.clientFor(CLIENT_90)).isEmpty();
        assertThat(state.needsClientProbe(T0, CLIENT_90)).isTrue();
    }

    @Test
    void aResultIsKeptForTheProcessLifetimeAndALaterFailureOfItsKeyIsIgnored() {
        NodeVersionState state = initial.withClient(new ProbedClient(CLIENT_81, VERSION_81), T0)
            .withClientFailure(CLIENT_81, ErrorCode.TIMEOUT, T0.plus(Duration.ofDays(30)));

        assertThat(state.clientFor(CLIENT_81)).contains(new Timed<>(VERSION_81, T0));
        assertThat(state.clientFailureFor(CLIENT_81)).isEmpty();
        assertThat(state.needsClientProbe(T0.plus(Duration.ofDays(30)), CLIENT_81)).isFalse();
    }

    @Test
    void theBackOffAppliesPerKey() {
        NodeVersionState state = initial.withClientFailure(CLIENT_81, ErrorCode.TIMEOUT, T0);
        Instant later = T0.plus(Duration.ofSeconds(59));

        assertThat(state.clientFailureFor(CLIENT_81)).contains(new Timed<>(ErrorCode.TIMEOUT, T0));
        assertThat(state.needsClientProbe(later, CLIENT_81)).isFalse();
        assertThat(state.needsClientProbe(later, CLIENT_90))
            .as("another key is not in the back-off").isTrue();
        assertThat(state.needsClientProbe(T0.plus(NodeVersionState.RETRY_AFTER), CLIENT_81))
            .isTrue();
    }

    @Test
    void aResultOrFailureOfOneKeyNeverTouchesAnotherKeys() {
        NodeVersionState state = initial
            .withClient(new ProbedClient(CLIENT_81, VERSION_81), T0)
            .withClientFailure(CLIENT_90, ErrorCode.CLI_UNAVAILABLE, T0.plusSeconds(1));

        assertThat(state.clientFor(CLIENT_81)).contains(new Timed<>(VERSION_81, T0));
        assertThat(state.clientFailureFor(CLIENT_90))
            .contains(new Timed<>(ErrorCode.CLI_UNAVAILABLE, T0.plusSeconds(1)));

        NodeVersionState probed = state.withClient(new ProbedClient(CLIENT_90, VERSION_90),
            T0.plusSeconds(120));
        assertThat(probed.clientFor(CLIENT_90)).contains(new Timed<>(VERSION_90,
            T0.plusSeconds(120)));
        assertThat(probed.clientFailureFor(CLIENT_90)).as("cleared by its own result").isEmpty();
        assertThat(probed.clientFor(CLIENT_81)).contains(new Timed<>(VERSION_81, T0));
    }

    @Test
    void switchingFromAToBAndBackToAReusesTheResultOfA() {
        // the node's version changes 8.1 → 9.0 → 8.1: the 8.1 client is not probed again
        NodeVersionState state = initial
            .withClient(new ProbedClient(CLIENT_81, VERSION_81), T0)
            .withObserved(InubitVersion.parse("9.0.7").orElseThrow(), T0.plusSeconds(10))
            .withClient(new ProbedClient(CLIENT_90, VERSION_90), T0.plusSeconds(11))
            .withObserved(InubitVersion.parse("8.1.17").orElseThrow(), T0.plusSeconds(20));

        assertThat(state.needsClientProbe(T0.plusSeconds(21), CLIENT_81)).isFalse();
        assertThat(state.clientFor(CLIENT_81)).contains(new Timed<>(VERSION_81, T0));
        assertThat(state.clientFor(CLIENT_90)).contains(new Timed<>(VERSION_90,
            T0.plusSeconds(11)));
    }

    @Test
    void switchingKeepsTheBackOffOfA() {
        NodeVersionState state = initial
            .withClientFailure(CLIENT_81, ErrorCode.TIMEOUT, T0)
            .withClient(new ProbedClient(CLIENT_90, VERSION_90), T0.plusSeconds(5));

        assertThat(state.needsClientProbe(T0.plusSeconds(30), CLIENT_81)).isFalse();
        assertThat(state.clientFailureFor(CLIENT_81)).map(Timed::value)
            .contains(ErrorCode.TIMEOUT);
    }

    @Test
    void theOtherTransitionsKeepTheClientChecks() {
        NodeVersionState state = initial
            .withClient(new ProbedClient(CLIENT_81, VERSION_81), T0)
            .withClientFailure(CLIENT_90, ErrorCode.TIMEOUT, T0)
            .withDetected(InubitVersion.parse("8.1.17").orElseThrow(), T0)
            .withDetectionFailure(new NodeVersionState.DetectionFailure(ErrorCode.TIMEOUT, false),
                T0)
            .withStale();

        assertThat(state.clientFor(CLIENT_81)).isPresent();
        assertThat(state.clientFailureFor(CLIENT_90)).isPresent();
    }

    @Test
    void theMapsAreImmutableCopies() {
        NodeVersionState state = initial.withClient(new ProbedClient(CLIENT_81, VERSION_81), T0);

        assertThat(state.clients()).isUnmodifiable();
        assertThat(state.clientFailures()).isUnmodifiable();
        assertThat(initial.clients()).isEmpty();
        assertThat(Optional.of(state.clients().size())).contains(1);
    }
}
