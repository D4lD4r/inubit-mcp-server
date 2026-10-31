package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-server credential circuit breaker against INUBIT account lockout (Phase 3 review M2,
 * research R-12). One instance per server, shared by its REST client and its StartCLI calls; the
 * unauthenticated calls ({@code /healthcheck}, {@code /ready}, the maintenance probe) never use
 * it.
 *
 * <ul>
 *   <li>Every authenticated attempt takes a {@link Permit} and reports whether INUBIT
 *       {@linkplain Permit#accepted() accepted} or {@linkplain Permit#rejected() rejected} the
 *       credentials (HTTP 401, CLI {@code LoginFailure}); other outcomes (timeouts, 5xx) report
 *       nothing.
 *   <li>After a rejection, every attempt fails for {@link #PAUSE} with a cached
 *       {@code AUTH_FAILED} without network or CLI access.
 *   <li>While the credentials are not {@linkplain #confirmed() confirmed}, only one attempt is in
 *       flight at a time; the others wait for its outcome. Together: at most one failed login per
 *       server per {@link #PAUSE}. Once confirmed, attempts run in parallel.
 * </ul>
 *
 * <p>Re-read on rejection (feature 010, research R-10, FR-010; only with a {@link Rereadable},
 * i.e. for AWS nodes): a rejection re-reads the node's credentials once instead of pausing at
 * once. {@code CHANGED} allows exactly one further attempt (grace) without a pause;
 * {@code UNCHANGED} or {@code FAILED}, and a rejection of the grace attempt, pause for
 * {@link #PAUSE}. At most two failed logins per server per {@link #PAUSE}. Rejections of attempts
 * that started before a handled rejection (simultaneous rejections) share its outcome and
 * re-read nothing; new attempts wait while a re-read runs. A client uses the guard
 * {@linkplain #bind bound} to the credentials it sends, so that a caller still holding a client
 * with replaced credentials never logs in with them.
 */
public final class CredentialGuard {

    /** How long a rejection blocks all authenticated attempts of the server. */
    public static final Duration PAUSE = Duration.ofSeconds(60);

    /** The node's re-readable credentials (feature 010); {@link NodeSecrets} of an AWS node. */
    public interface Rereadable {

        /** Fetches the credentials again; {@code CHANGED} holds the new ones first. */
        NodeSecrets.Reread reread();

        /** The credentials held now; a new instance only after {@code CHANGED}. */
        Optional<NodeCredentials> held();
    }

    private final NodeId server;
    private final SecretLabels labels;
    private final Clock clock;
    /** Empty without re-read (unauthenticated clients): the behaviour of feature 009. */
    private final Optional<Rereadable> secrets;
    /** The guard holding the state; {@code this} unless this is a {@link #bind bound} view. */
    private final CredentialGuard root;
    /** The credentials of the attempts through this view; {@code null} for the root. */
    private final NodeCredentials bound;

    // state, used through the root only
    /** The single attempt allowed while the credentials are unconfirmed. */
    private final Semaphore probe = new Semaphore(1, true);
    private volatile Instant rejectedAt;
    private volatile boolean confirmed;
    /** Guards {@link #round}, {@link #grace} and {@link #rereading}. */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition rereadDone = lock.newCondition();
    /** Counts the rejections that started a re-read; an attempt remembers the current one. */
    private long round;
    /** The one attempt after a {@code CHANGED} re-read is allowed. */
    private boolean grace;
    private boolean rereading;

    /** @param labels the names of the node's secrets, named in {@code AUTH_FAILED} */
    public CredentialGuard(SecretLabels labels, Clock clock) {
        this(labels, clock, Optional.empty());
    }

    /** With re-read on rejection of {@code secrets} (an AWS node, research R-10). */
    public CredentialGuard(SecretLabels labels, Clock clock, Rereadable secrets) {
        this(labels, clock, Optional.of(secrets));
    }

    private CredentialGuard(SecretLabels labels, Clock clock, Optional<Rereadable> secrets) {
        this.labels = Objects.requireNonNull(labels, "labels");
        this.server = labels.node();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secrets = secrets;
        this.root = this;
        this.bound = null;
    }

    private CredentialGuard(CredentialGuard root, NodeCredentials bound) {
        this.labels = root.labels;
        this.server = root.server;
        this.clock = root.clock;
        this.secrets = root.secrets;
        this.root = root;
        this.bound = Objects.requireNonNull(bound, "bound");
    }

    /**
     * This guard for a client that sends {@code credentials}: the same state, but with re-read,
     * an attempt fails at once once the node holds other credentials.
     */
    public CredentialGuard bind(NodeCredentials credentials) {
        return new CredentialGuard(root, credentials);
    }

    /**
     * A permit for one authenticated attempt; close it when the attempt is over. Waits while
     * the credentials are re-read.
     *
     * @throws ToolErrorException the cached {@code AUTH_FAILED} within {@link #PAUSE} after a
     *     rejection, {@code AUTH_FAILED} if this view's credentials were replaced, or
     *     {@code TIMEOUT} if interrupted while waiting for another attempt or a re-read
     */
    public Permit acquire() {
        return root.acquire(bound);
    }

    private Permit acquire(NodeCredentials used) {
        long attemptRound = admit(used);
        if (confirmed) {
            return new Permit(false, attemptRound);
        }
        try {
            probe.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cancelled("another login attempt");
        }
        try {
            attemptRound = admit(used);
        } catch (ToolErrorException e) {
            probe.release();
            throw e;
        }
        if (confirmed) {
            probe.release();
            return new Permit(false, attemptRound);
        }
        return new Permit(true, attemptRound);
    }

    /**
     * Waits for a running re-read, then refuses an attempt with replaced credentials or within
     * the pause.
     *
     * @return the round the attempt belongs to
     */
    private long admit(NodeCredentials used) {
        lock.lock();
        try {
            while (rereading) {
                rereadDone.await();
            }
            refuseIfReplaced(used);
            refuseIfPaused();
            return round;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cancelled("its credentials to be re-read");
        } finally {
            lock.unlock();
        }
    }

    private ToolErrorException cancelled(String waitingFor) {
        return new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            "The call to " + server + " was cancelled while waiting for " + waitingFor,
            "The overall deadline of the request expired",
            "Retry").withNode(server));
    }

    /**
     * The next step after rejected or missing credentials of this guard's node,
     * {@link SecretLabels#fixCredentials()} (shared by the cached and the direct
     * {@code AUTH_FAILED} of REST and StartCLI).
     */
    public String fixCredentials() {
        return labels.fixCredentials();
    }

    /** True after an accepted attempt and no rejection since. */
    public boolean confirmed() {
        return root.confirmed;
    }

    private void refuseIfReplaced(NodeCredentials used) {
        if (used == null || secrets.isEmpty() || secrets.get().held().orElse(null) == used) {
            return;
        }
        throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
            "INUBIT login to " + server + ": not attempted with the previous username and"
                + " password; they were replaced by new values from AWS Parameter Store after a"
                + " rejected login",
            "The credentials changed in AWS Parameter Store during this call",
            "Retry the call; it uses the new values")
            .withNode(server));
    }

    private void refuseIfPaused() {
        Instant rejected = rejectedAt;
        if (rejected == null) {
            return;
        }
        Duration remaining = Duration.between(clock.instant(), rejected.plus(PAUSE));
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }
        long seconds = (remaining.toMillis() + 999) / 1000;
        throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
            "INUBIT login to " + server + ": authentication failed recently; not retried for "
                + seconds + " s to avoid account lockout",
            "Wrong username or password, or the account is locked",
            fixCredentials())
            .withNode(server));
    }

    /** Re-reads the credentials (outside the lock) and settles the outcome. */
    private void reread() {
        NodeSecrets.Reread outcome = NodeSecrets.Reread.FAILED;
        try {
            outcome = secrets.orElseThrow().reread();
        } finally {
            lock.lock();
            try {
                rereading = false;
                if (outcome == NodeSecrets.Reread.CHANGED) {
                    grace = true;
                } else {
                    rejectedAt = clock.instant();
                }
                rereadDone.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /** One authenticated attempt. */
    public final class Permit implements AutoCloseable {

        private final boolean exclusive;
        /** The {@link CredentialGuard#round} when the attempt was admitted. */
        private final long round;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(boolean exclusive, long round) {
            this.exclusive = exclusive;
            this.round = round;
        }

        /**
         * INUBIT accepted the credentials (a 2xx or 403 answer, a CLI login that worked). An
         * attempt from before a handled rejection says nothing about the credentials held now
         * and changes nothing.
         */
        public void accepted() {
            lock.lock();
            try {
                if (rereading || round != CredentialGuard.this.round) {
                    return;
                }
                rejectedAt = null;
                grace = false;
                confirmed = true;
            } finally {
                lock.unlock();
            }
        }

        /**
         * INUBIT rejected the credentials (HTTP 401, CLI {@code LoginFailure}). Without re-read,
         * and for the grace attempt: pause. Otherwise re-read once, on this thread; a rejection
         * of an attempt from before a handled rejection shares its outcome.
         */
        public void rejected() {
            lock.lock();
            try {
                if (rereading || round != CredentialGuard.this.round) {
                    return;
                }
                confirmed = false;
                if (secrets.isEmpty() || grace) {
                    grace = false;
                    rejectedAt = clock.instant();
                    return;
                }
                CredentialGuard.this.round++;
                rereading = true;
            } finally {
                lock.unlock();
            }
            reread();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && exclusive) {
                probe.release();
            }
        }
    }

    @Override
    public String toString() {
        return "CredentialGuard[" + server + "]";
    }
}
