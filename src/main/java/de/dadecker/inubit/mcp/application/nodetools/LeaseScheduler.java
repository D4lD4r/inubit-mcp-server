package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.nodetools.LogLevelAudit.Trigger;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.port.LeaseView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resets the temporary log levels of the profile when their leases expire, takes over the leases
 * of instances that no longer run and ends the leases of servers no longer configured (feature
 * 024, FR-043 … FR-045, SC-004, research D-16 "Scheduler").
 *
 * <p>A tick runs on one daemon thread ({@value #TICK_THREAD}) every {@link #TICK} after the end of
 * the previous one, the first at {@link #start()}; it only decides, so it is short. Under the
 * store lock (wait {@link LogLevelService#STORE_WAIT}; busy → the tick is skipped) it decides per
 * open lease:
 *
 * <ul>
 *   <li><b>forget</b>: the server is no longer configured and 7 days have passed since the expiry
 *       → {@code FORGOTTEN}, audited ({@code forget-after-7-days}); until then nothing is done
 *       (no connection data) and {@link #pendingForgetWarnings()} names it;
 *   <li><b>takeover</b>: another owner that no longer runs → this instance becomes the owner,
 *       audited ({@code takeover}), then as an own lease (creator decision of D-16: an unexpired
 *       lease is reset at its expiry, not at once);
 *   <li><b>cleanup</b>: an own lease that expired or whose reset failed, at most once per
 *       {@link #RETRY_AFTER} after its last attempt (failed, refused or not audited; less
 *       {@link #RETRY_TOLERANCE}, so a clock a little behind the tick does not skip a tick).
 * </ul>
 *
 * <p>A live other owner's lease is left alone. Ended leases are removed by the store 7 days after
 * their end. The decided cleanups ({@link LogLevelService#cleanup}) then start outside the store
 * lock, one task per node, the nodes in parallel; the tick does not wait for them. A node whose
 * cleanup still runs is left out until it ends, so a slow node never delays the others (SC-004).
 * A takeover or a forget whose audit record cannot be written is not done; one whose record was
 * written but whose lease could not be stored is logged as ERROR (the next tick tries again).
 */
public final class LeaseScheduler {

    /** The pause between the end of one tick and the start of the next. */
    static final Duration TICK = Duration.ofSeconds(30);
    /** A reset that failed, was refused or not audited is retried this long after it began. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(60);
    /** How much earlier than {@link #RETRY_AFTER} a retry is due (wall clock vs. tick timing). */
    static final Duration RETRY_TOLERANCE = Duration.ofSeconds(1);
    /** An unreadable lease store is logged as WARN at most this often (D-12). */
    static final Duration UNREADABLE_WARNING_EVERY = Duration.ofHours(1);
    static final String TICK_THREAD = "inubit-mcp-log-level-leases";

    private static final Logger LOG = LoggerFactory.getLogger(LeaseScheduler.class);

    private final LeaseStore store;
    private final LogLevelService service;
    private final ProcessLiveness liveness;
    private final LogLevelAudit audit;
    private final Predicate<NodeId> configured;
    private final Function<NodeId, Optional<String>> accounts;
    private final Clock clock;
    private final ScheduledExecutorService ticks;
    /** At most one task per node runs ({@link #inFlight}): bounded by the nodes with leases. */
    private final ExecutorService cleanups;
    /** The nodes whose cleanup task has started and not yet ended. */
    private final Set<NodeId> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    /** Until the first decision: the cleanups are the startup cleanup (D-17). */
    private boolean startup = true;
    private Instant unreadableWarned;

    /** A cleanup decided on {@code lease} as the decision stored it. */
    record Decision(Lease lease, Trigger trigger) {
    }

    /**
     * @param service    resets one lease ({@link LogLevelService#cleanup})
     * @param liveness   this instance and whether another owner still runs
     * @param audit      the records of takeovers and forgets
     * @param configured whether a server is in the configuration
     * @param accounts   the INUBIT username of a configured server, for the takeover record
     * @param clock      the time of the decisions
     */
    public LeaseScheduler(LeaseStore store, LogLevelService service, ProcessLiveness liveness,
        LogLevelAudit audit, Predicate<NodeId> configured,
        Function<NodeId, Optional<String>> accounts, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.service = Objects.requireNonNull(service, "service");
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.configured = Objects.requireNonNull(configured, "configured");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ticks = Executors.newSingleThreadScheduledExecutor(daemon(TICK_THREAD));
        this.cleanups = Executors.newCachedThreadPool(daemon("inubit-mcp-log-level-cleanup"));
    }

    /** Schedules the ticks; the first runs at once on the scheduler's thread. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("The lease scheduler is already started");
        }
        ticks.scheduleWithFixedDelay(this::tick, 0, TICK.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the ticks and waits at most {@code max} for a running tick and the cleanups started;
     * after that they are interrupted. Only the first call does anything (D-17: the shutdown hook
     * and the end of stdin may both call it).
     */
    public void shutdown(Duration max) {
        Objects.requireNonNull(max, "max");
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + max.toNanos();
        ticks.shutdown();
        boolean ended = awaitUntil(ticks, deadline);
        cleanups.shutdown();
        ended = awaitUntil(cleanups, deadline) && ended;
        if (!ended) {
            ticks.shutdownNow();
            cleanups.shutdownNow();
            LOG.warn("The lease scheduler did not stop within {}; its running cleanups were"
                + " interrupted", Durations.human(max));
        }
    }

    /**
     * One line per open lease of a server no longer configured: it is not reset and ends as
     * {@code FORGOTTEN} 7 days after its expiry (US4-10, FR-045). Empty if the store cannot be
     * read.
     */
    public List<String> pendingForgetWarnings() {
        if (!(store.leases() instanceof LeaseView.Snapshot.Leases stored)) {
            return List.of();
        }
        return stored.leases().stream()
            .filter(lease -> lease.state().open() && !configured.test(lease.server()))
            .map(lease -> "Temporary log level lease " + lease.leaseId() + " of " + lease.server()
                + " (INUBIT node " + lease.inubitNode() + "): the server is no longer configured,"
                + " so the level is not reset; the lease is forgotten automatically on "
                + forgetAt(lease).atZone(ZoneOffset.UTC).toLocalDate())
            .toList();
    }

    /**
     * One tick: the decisions, then their cleanups are started. Never throws, so later ticks still
     * run.
     *
     * @return the cleanup tasks it started
     */
    List<Future<?>> tick() {
        try {
            return cleanup(decide());
        } catch (RuntimeException e) {
            LOG.warn("Lease scheduler tick failed ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * The decisions of a tick, written under the store lock.
     *
     * @return the cleanups to run; empty if the tick was skipped
     */
    synchronized List<Decision> decide() {
        Instant now = clock.instant();
        LeaseOwner self = liveness.self();
        List<Decision> decisions = new ArrayList<>();
        List<String> audited = new ArrayList<>();
        try {
            store.update(leases -> {
                decisions.clear();
                audited.clear();
                return leases.stream().map(lease -> decide(lease, now, self, decisions, audited))
                    .toList();
            }, LogLevelService.STORE_WAIT);
        } catch (ToolErrorException e) {
            if (!audited.isEmpty()) {
                LOG.error("Audited lease change(s) not stored ({}): {}; the next tick decides"
                    + " again", e.error().message(), String.join("; ", audited));
            }
            skipped(e, now);
            return List.of();
        }
        audited.forEach(change -> LOG.info("Lease {}", change));
        startup = false;
        return List.copyOf(decisions);
    }

    /**
     * Starts {@code decisions}, one task per node, the nodes in parallel, and returns at once. A
     * node whose earlier task still runs is left out: its cleanup records its attempt, and a later
     * tick decides again.
     *
     * @return the started tasks
     */
    List<Future<?>> cleanup(List<Decision> decisions) {
        Map<NodeId, List<Decision>> perNode = decisions.stream()
            .collect(Collectors.groupingBy(decision -> decision.lease().server(),
                LinkedHashMap::new, Collectors.toList()));
        List<Future<?>> started = new ArrayList<>();
        perNode.forEach((node, ofNode) -> {
            if (!inFlight.add(node)) {
                return;
            }
            try {
                started.add(cleanups.submit(() -> {
                    try {
                        ofNode.forEach(this::cleanup);
                    } finally {
                        inFlight.remove(node);
                    }
                }));
            } catch (RejectedExecutionException e) {
                inFlight.remove(node);
                throw e;
            }
        });
        return started;
    }

    /**
     * @param audited the takeovers and forgets whose record this decision wrote, as text for the
     *                log
     */
    private Lease decide(Lease lease, Instant now, LeaseOwner self, List<Decision> decisions,
        List<String> audited) {
        if (!lease.state().open()) {
            return lease;
        }
        if (!configured.test(lease.server())) {
            if (now.isBefore(forgetAt(lease))) {
                return lease;
            }
            return recorded(Trigger.FORGET_AFTER_7_DAYS, lease.forgotten(now), Optional.empty(),
                audited,
                lease.server() + " is no longer configured: the lease ended without a reset 7 days"
                    + " after its expiry (" + lease.expiresAt() + "); the level may still be"
                    + " raised on INUBIT node " + lease.inubitNode() + " until its restart")
                .orElse(lease);
        }
        Lease own = lease;
        if (!lease.owner().equals(self)) {
            if (liveness.alive(lease.owner())) {
                return lease;
            }
            Optional<Lease> taken = recorded(Trigger.TAKEOVER, lease.takenOverBy(self),
                accounts.apply(lease.server()), audited, "owner pid " + lease.owner().pid()
                    + " no longer runs; taken over by pid " + self.pid());
            if (taken.isEmpty()) {
                return lease;
            }
            own = taken.get();
        }
        if (due(own, now)) {
            decisions.add(new Decision(own, startup ? Trigger.STARTUP
                : own.lastAttempt().isPresent() ? Trigger.RETRY : Trigger.EXPIRY));
        }
        return own;
    }

    /** Expired or failed, and no attempt within {@link #RETRY_AFTER} less the tolerance. */
    private static boolean due(Lease lease, Instant now) {
        boolean needsReset = lease.state() == LeaseState.RESET_FAILED
            || !now.isBefore(lease.expiresAt());
        return needsReset && lease.lastAttempt()
            .map(attempt -> !now.isBefore(attempt.at().plus(RETRY_AFTER).minus(RETRY_TOLERANCE)))
            .orElse(true);
    }

    /**
     * {@code changed} if its one audit record was written, which is then added to
     * {@code audited}; empty otherwise (ERROR logged by the audit).
     */
    private Optional<Lease> recorded(Trigger trigger, Lease changed, Optional<String> account,
        List<String> audited, String text) {
        LogLevelAudit.AutomaticReset record = audit.automaticReset(trigger, changed, account);
        try {
            record.changed(text);
        } catch (LogLevelAudit.NotWritableException e) {
            return Optional.empty();
        }
        audited.add(changed.leaseId() + " of " + changed.server() + ": " + text + " (audit "
            + record.auditId() + ")");
        return Optional.of(changed);
    }

    private void cleanup(Decision decision) {
        try {
            service.cleanup(decision.lease(), decision.trigger());
        } catch (RuntimeException e) {
            LOG.warn("Cleanup of lease {} on {} failed ({}); the next tick decides again",
                decision.lease().leaseId(), decision.lease().server(),
                e.getClass().getSimpleName());
        }
    }

    private void skipped(ToolErrorException e, Instant now) {
        if (store.leases() instanceof LeaseView.Snapshot.Unreadable unreadable) {
            if (unreadableWarned == null
                || !now.isBefore(unreadableWarned.plus(UNREADABLE_WARNING_EVERY))) {
                unreadableWarned = now;
                LOG.warn("The lease store cannot be read ({}); temporary log levels are not reset"
                    + " automatically until it is fixed or moved", unreadable.reason());
            }
        } else if (e.error().code() == ErrorCode.PRECONDITION_FAILED) {
            LOG.info("Lease scheduler tick skipped: {}", e.error().message());
        } else {
            LOG.warn("Lease scheduler tick skipped: {}", e.error().message());
        }
    }

    private static Instant forgetAt(Lease lease) {
        return lease.expiresAt().plus(LogLevelService.UNCONFIGURED_FORGET_AFTER);
    }

    private static boolean awaitUntil(ExecutorService executor, long deadline) {
        try {
            return executor.awaitTermination(Math.max(0, deadline - System.nanoTime()),
                TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
