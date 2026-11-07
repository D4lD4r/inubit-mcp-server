package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ClientCheck;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState.DetectionFailure;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WriteRefusedException;
import de.dadecker.inubit.mcp.domain.port.ClientCheckLookup;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>Feature 017 (FR-017, research D-4, D-5): client checks are kept per {@link ClientKey}; the
 * {@code CliRunner} launch guard reads them through the {@link ClientCheckLookup} port.
 *
 * <p>Feature 017 (FR-018, research D-24): the {@link WriteLock}. One fair lock per node orders
 * every state change of the node ({@code update}) against the hand-offs of writes; reads take no
 * lock.
 */
public final class NodeVersions implements VersionObserver, ClientCheckLookup, WriteLock {

    private static final Logger LOG = LoggerFactory.getLogger(NodeVersions.class);
    /** Set while this thread runs the action of a hand-off. */
    private static final ThreadLocal<Boolean> HANDING_OFF = ThreadLocal.withInitial(() -> false);

    private final Clock clock;
    private final Function<NodeId, VersionSetting> settings;
    private final Function<NodeId, Duration> bounds;
    private final ConcurrentMap<NodeId, NodeVersionState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<NodeId, ReentrantLock> locks = new ConcurrentHashMap<>();

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

    /** Whether the StartCLI client {@code key} of {@code node} should be probed now. */
    public boolean needsClientProbe(NodeId node, ClientKey key) {
        return state(node).needsClientProbe(clock.instant(), key);
    }

    /** Answered from one {@link NodeVersionState} snapshot of {@code node} (research D-4). */
    @Override
    public Recorded recorded(NodeId node, ClientKey key) {
        NodeVersionState state = state(node);
        Optional<ClientCheck> check = state.clientFor(key).<ClientCheck>map(result ->
                new ClientCheck.Checked(result.value()))
            .or(() -> state.clientFailureFor(key).map(failure -> new ClientCheck.Failed(
                failure.value())));
        if (check.isPresent()) {
            return new Recorded.ForClient(check.get());
        }
        return state.clients().isEmpty() && state.clientFailures().isEmpty()
            ? Recorded.Absent.NOTHING : Recorded.Absent.OTHER_CLIENTS_ONLY;
    }

    /** A successful detection of {@code version}. */
    @Override
    public void detected(NodeId node, InubitVersion version) {
        Objects.requireNonNull(version, "version");
        update(node, state -> state.withDetected(version, clock.instant()));
    }

    /** A failed detection; {@code endpointMissing} after an HTTP 404 (feature 012 FR-003). */
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

    /**
     * Feature 017 (research D-24): the mark belongs to {@code permit} as long as no other mark
     * comes; a permit of another node makes it foreign.
     */
    @Override
    public void staleAfterWrite(NodeId node, WritePermit permit) {
        Objects.requireNonNull(permit, "permit");
        update(node, state -> permit.node().equals(node) ? state.withStaleAfterWrite(permit)
            : state.withStale());
    }

    /** A successful probe of the client {@code probed.key()}. */
    public void clientProbed(NodeId node, ProbedClient probed) {
        Objects.requireNonNull(probed, "probed");
        update(node, state -> state.withClient(probed, clock.instant()));
    }

    /**
     * A failed or timed-out probe of the client {@code key} (e.g. {@code TIMEOUT},
     * {@code CLI_UNAVAILABLE}).
     */
    public void clientProbeFailed(NodeId node, ClientKey key, ErrorCode code) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(code, "code");
        update(node, state -> state.withClientFailure(key, code, clock.instant()));
    }

    /**
     * Research D-24: the version a write may be handed off on is the effective version, unless an
     * {@code AUTO} node is stale (then unknown) by a mark that is not only the permit's own: a
     * stale mark raised solely by responses to writes of this very permit object (the same
     * call, e.g. its rollback after its own unexpected response) leaves the effective version.
     * On a pinned or line node {@code stale} is ignored like the gate does
     * ({@link NodeVersionState#writableVersion}); only an observation clears it there, and a
     * contradicting observation is the effective version.
     */
    @Override
    public <T> T handOff(WritePermit permit, Function<Optional<InubitVersion>, T> action) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(action, "action");
        refuseInsideHandOff();
        NodeId node = permit.node();
        ReentrantLock lock = lockOf(node);
        lock.lock();
        try {
            NodeVersionState state = state(node);
            Optional<InubitVersion> current = state.stale()
                && state.setting() instanceof VersionSetting.Auto && !state.staleOnlyBy(permit)
                ? Optional.empty() : state.effectiveVersion();
            if (current.isEmpty() || !MajorMinor.of(current.get()).equals(permit.admittedLine())) {
                throw refusal(permit, current);
            }
            HANDING_OFF.set(true);
            try {
                return action.apply(current);
            } finally {
                HANDING_OFF.remove();
            }
        } finally {
            lock.unlock();
        }
    }

    /** The number of threads waiting for the lock of {@code node} (tests of the ordering). */
    int queueLength(NodeId node) {
        return lockOf(node).getQueueLength();
    }

    private static WriteRefusedException refusal(WritePermit permit,
        Optional<InubitVersion> current) {
        LOG.info("Write refused: node={} capability={} admitted=INUBIT {} current={}",
            permit.node(), permit.capability(), permit.admitted().raw(),
            current.map(v -> "INUBIT " + v.raw()).orElse("unknown"));
        return WriteRefusedException.of(permit, current);
    }

    private void update(NodeId node, UnaryOperator<NodeVersionState> change) {
        Objects.requireNonNull(node, "node");
        refuseInsideHandOff();
        ReentrantLock lock = lockOf(node);
        lock.lock();
        try {
            states.compute(node, (id, state) -> change.apply(state != null ? state : initial(id)));
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockOf(NodeId node) {
        return locks.computeIfAbsent(node, id -> new ReentrantLock(true));
    }

    private static void refuseInsideHandOff() {
        if (HANDING_OFF.get()) {
            throw new IllegalStateException("A hand-off must not hand off again or change the"
                + " version state of a node");
        }
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
