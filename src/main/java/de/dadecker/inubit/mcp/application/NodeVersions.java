package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState.DetectionFailure;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The version state of every node of the server process (research D-4, data-model.md →
 * NodeVersions): one source for {@code list_nodes}, the version gate, {@code get_health} and
 * audit. Fed by detections, {@code get_health} observations, stale triggers and client probes;
 * the time of each report comes from the injected clock. It owns the 60 s back-off after a failed
 * detection or client probe ({@link NodeVersionState#RETRY_AFTER}).
 *
 * <p>Thread-safe: each report replaces the node's immutable {@link NodeVersionState} atomically.
 * A purpose is a {@link Capability.Kind} ({@code READ} or {@code WRITE}). The adapter layer reports
 * and reads through the {@link VersionObserver} port.
 */
public final class NodeVersions implements VersionObserver {

    private final Clock clock;
    private final Function<NodeId, VersionSetting> settings;
    private final Function<NodeId, Duration> bounds;
    private final ConcurrentMap<NodeId, NodeVersionState> states = new ConcurrentHashMap<>();

    /**
     * @param settings the configured {@code versionLine} of each node
     * @param bounds   the re-detection bound of each node: its {@code inventory.cacheTtl} (D-10)
     */
    public NodeVersions(Clock clock, Function<NodeId, VersionSetting> settings,
        Function<NodeId, Duration> bounds) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
    }

    /** The current state of {@code node}. */
    public NodeVersionState state(NodeId node) {
        NodeVersionState state = states.get(node);
        return state != null ? state : initial(node);
    }

    /** {@link NodeVersionState#effectiveVersion()} of {@code node}. */
    @Override
    public Optional<InubitVersion> effectiveVersion(NodeId node) {
        return state(node).effectiveVersion();
    }

    /** Whether the gate has to detect before a call with {@code purpose} on {@code node}. */
    public boolean needsDetection(NodeId node, Capability.Kind purpose) {
        return state(node).needsDetection(purpose, clock.instant(), bound(node));
    }

    /** The version a write on {@code node} may rely on; empty means unknown. */
    public Optional<InubitVersion> writableVersion(NodeId node) {
        return state(node).writableVersion(clock.instant(), bound(node));
    }

    /** Whether the StartCLI client of {@code node} should be probed now. */
    public boolean needsClientProbe(NodeId node) {
        return state(node).needsClientProbe(clock.instant());
    }

    /** A successful detection of {@code version}. */
    @Override
    public void detected(NodeId node, InubitVersion version) {
        Objects.requireNonNull(version, "version");
        update(node, state -> state.withDetected(version, clock.instant()));
    }

    /** A failed detection; {@code endpointMissing} after an HTTP 404 (feature 011 FR-003). */
    @Override
    public void detectionFailed(NodeId node, ErrorCode code, boolean endpointMissing) {
        DetectionFailure failure = new DetectionFailure(code, endpointMissing);
        update(node, state -> state.withDetectionFailure(failure, clock.instant()));
    }

    /** The version {@code get_health} read from the node. */
    @Override
    public void observed(NodeId node, InubitVersion version) {
        Objects.requireNonNull(version, "version");
        update(node, state -> state.withObserved(version, clock.instant()));
    }

    /** A response indicated that the node may run another version (D-10). */
    @Override
    public void stale(NodeId node) {
        update(node, NodeVersionState::withStale);
    }

    /** A successful client probe. */
    public void clientProbed(NodeId node, ClientVersion client) {
        Objects.requireNonNull(client, "client");
        update(node, state -> state.withClient(client, clock.instant()));
    }

    /** A failed or timed-out client probe (e.g. {@code TIMEOUT}, {@code CLI_UNAVAILABLE}). */
    public void clientProbeFailed(NodeId node, ErrorCode code) {
        Objects.requireNonNull(code, "code");
        update(node, state -> state.withClientFailure(code, clock.instant()));
    }

    private void update(NodeId node, UnaryOperator<NodeVersionState> change) {
        states.compute(Objects.requireNonNull(node, "node"),
            (id, state) -> change.apply(state != null ? state : initial(id)));
    }

    private NodeVersionState initial(NodeId node) {
        return NodeVersionState.initial(Objects.requireNonNull(settings.apply(node),
            () -> "no version setting for " + node));
    }

    private Duration bound(NodeId node) {
        return Objects.requireNonNull(bounds.apply(node), () -> "no re-detection bound for "
            + node);
    }
}
