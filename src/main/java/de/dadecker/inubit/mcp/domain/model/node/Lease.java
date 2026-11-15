package de.dadecker.inubit.mcp.domain.model.node;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A temporary log level lease: the loggers raised on one INUBIT node until {@code expiresAt}
 * (FR-043, research D-12 … D-14, data-model "Lease lifecycle"). Pure: every time is passed in.
 *
 * <p>Every transition returns a copy with {@code generation + 1}; a transition the lifecycle does
 * not allow throws {@link IllegalStateException} (a programming error of the caller).
 *
 * @param generation  1 for a new lease, incremented by every transition (compare-and-set, D-16)
 * @param inubitNode  the node that answered the set (name rule of {@link ClusterNode})
 * @param loggers     1 to {@value #MAX_LOGGERS}, sorted by name
 * @param expiresAt   after {@code setAt}
 * @param lastAttempt the last reset attempt that did not end the lease
 * @param endedAt     present exactly for {@link LeaseState#RESET} and {@link LeaseState#FORGOTTEN}
 */
public record Lease(
    UUID leaseId,
    long generation,
    NodeId server,
    String inubitNode,
    SortedMap<LoggerName, TemporaryLevel> loggers,
    Instant setAt,
    Instant expiresAt,
    LeaseOwner owner,
    LeaseState state,
    UUID auditId,
    Optional<LeaseAttempt> lastAttempt,
    Optional<Instant> endedAt) {

    public static final int MAX_LOGGERS = 50;
    /** The loggers of one set (FR-041). */
    public static final int MAX_SET_LOGGERS = 5;

    private static final Comparator<LoggerName> BY_NAME = Comparator.comparing(LoggerName::value);

    public Lease {
        Objects.requireNonNull(leaseId, "leaseId");
        if (generation < 1) {
            throw new IllegalArgumentException("Invalid generation: expected 1 or more");
        }
        Objects.requireNonNull(server, "server");
        ClusterNode.checkName(inubitNode);
        loggers = sorted(loggers);
        if (loggers.isEmpty() || loggers.size() > MAX_LOGGERS) {
            throw new IllegalArgumentException(
                "Invalid loggers: expected 1 to " + MAX_LOGGERS + " per lease");
        }
        Objects.requireNonNull(setAt, "setAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(setAt)) {
            throw new IllegalArgumentException("Invalid expiry: not after the set time");
        }
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(lastAttempt, "lastAttempt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isPresent() == state.open()) {
            throw new IllegalArgumentException(
                "Invalid end time: present exactly for RESET and FORGOTTEN");
        }
    }

    /**
     * Another set merged into this {@code ACTIVE} lease (D-14 step 8): the union of the loggers
     * with the set's level per named logger, the later expiry, the setter as owner and its audit id.
     * A new raise starts afresh: an earlier {@code lastAttempt} is cleared.
     */
    public Lease merge(Map<LoggerName, TemporaryLevel> set, Instant at, Duration duration,
        LeaseOwner setter, UUID setAuditId) {
        requireState(LeaseState.ACTIVE, "merge");
        if (set.isEmpty() || set.size() > MAX_SET_LOGGERS) {
            throw new IllegalArgumentException(
                "Invalid loggers: expected 1 to " + MAX_SET_LOGGERS + " per set");
        }
        SortedMap<LoggerName, TemporaryLevel> union = new TreeMap<>(BY_NAME);
        union.putAll(loggers);
        union.putAll(set);
        return new Lease(leaseId, generation + 1, server, inubitNode, union, at,
            later(expiresAt, at.plus(positive(duration))), setter, state, setAuditId,
            Optional.empty(), endedAt);
    }

    /**
     * The set's StartCLI runs succeeded at {@code at} (D-14 step 10, US4-1): the expiry counts from
     * then, never earlier than the expiry already recorded; {@code lastAttempt} is cleared.
     */
    public Lease executed(Instant at, Duration duration) {
        requireState(LeaseState.ACTIVE, "executed");
        return new Lease(leaseId, generation + 1, server, inubitNode, loggers, at,
            later(expiresAt, at.plus(positive(duration))), owner, state, auditId,
            Optional.empty(), endedAt);
    }

    /** The node was reset at {@code at}; the lease ends. */
    public Lease reset(Instant at) {
        requireOpen("reset");
        return new Lease(leaseId, generation + 1, server, inubitNode, loggers, setAt, expiresAt,
            owner, LeaseState.RESET, auditId, lastAttempt, Optional.of(at));
    }

    /** A reset failed or was refused (D-16): {@code RESET_FAILED} with the attempt. */
    public Lease failed(LeaseAttempt attempt) {
        requireOpen("failed");
        return new Lease(leaseId, generation + 1, server, inubitNode, loggers, setAt, expiresAt,
            owner, LeaseState.RESET_FAILED, auditId, Optional.of(attempt), endedAt);
    }

    /**
     * A reset attempt that leaves the state as it is (D-15 failed reset tool, D-16 step 4
     * {@link LeaseReason#AUDIT_NOT_WRITABLE}): only the attempt is recorded.
     */
    public Lease attempted(LeaseAttempt attempt) {
        requireOpen("attempted");
        return new Lease(leaseId, generation + 1, server, inubitNode, loggers, setAt, expiresAt,
            owner, state, auditId, Optional.of(attempt), endedAt);
    }

    /** The lease ends without a reset at {@code at} (forget tool, unconfigured server; FR-044). */
    public Lease forgotten(Instant at) {
        requireOpen("forgotten");
        return new Lease(leaseId, generation + 1, server, inubitNode, loggers, setAt, expiresAt,
            owner, LeaseState.FORGOTTEN, auditId, lastAttempt, Optional.of(at));
    }

    /** Another instance takes over the lease of an owner that no longer runs (D-13). */
    public Lease takenOverBy(LeaseOwner newOwner) {
        requireOpen("takenOverBy");
        return new Lease(leaseId, generation + 1, server, inubitNode, loggers, setAt, expiresAt,
            newOwner, state, auditId, lastAttempt, endedAt);
    }

    /** True if {@code other} is this lease in the same generation (compare-and-set, D-16). */
    public boolean sameGeneration(Lease other) {
        return leaseId.equals(other.leaseId) && generation == other.generation;
    }

    private void requireState(LeaseState required, String transition) {
        if (state != required) {
            throw new IllegalStateException(transition + " needs a " + required + " lease, not "
                + state);
        }
    }

    private void requireOpen(String transition) {
        if (!state.open()) {
            throw new IllegalStateException(transition + " needs an open lease, not " + state);
        }
    }

    private static SortedMap<LoggerName, TemporaryLevel> sorted(
        Map<LoggerName, TemporaryLevel> loggers) {
        SortedMap<LoggerName, TemporaryLevel> copy = new TreeMap<>(BY_NAME);
        loggers.forEach((name, level) -> copy.put(Objects.requireNonNull(name, "logger"),
            Objects.requireNonNull(level, "level")));
        return Collections.unmodifiableSortedMap(copy);
    }

    private static Duration positive(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Invalid duration: expected more than zero");
        }
        return duration;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
