package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

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
 */
public final class CredentialGuard {

    /** How long a rejection blocks all authenticated attempts of the server. */
    public static final Duration PAUSE = Duration.ofSeconds(60);

    private final NodeId server;
    private final CredentialVariables variables;
    private final Clock clock;
    /** The single attempt allowed while the credentials are unconfirmed. */
    private final Semaphore probe = new Semaphore(1, true);
    private volatile Instant rejectedAt;
    private volatile boolean confirmed;

    /** @param variables the credential variables of the node, named in {@code AUTH_FAILED} */
    public CredentialGuard(CredentialVariables variables, Clock clock) {
        this.variables = Objects.requireNonNull(variables, "variables");
        this.server = variables.node();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * A permit for one authenticated attempt; close it when the attempt is over.
     *
     * @throws ToolErrorException the cached {@code AUTH_FAILED} within {@link #PAUSE} after a
     *     rejection, or {@code TIMEOUT} if interrupted while waiting for another attempt
     */
    public Permit acquire() {
        refuseIfPaused();
        if (confirmed) {
            return new Permit(false);
        }
        try {
            probe.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "The call to " + server + " was cancelled while waiting for another login"
                    + " attempt",
                "The overall deadline of the request expired",
                "Retry").withNode(server));
        }
        try {
            refuseIfPaused();
        } catch (ToolErrorException e) {
            probe.release();
            throw e;
        }
        if (confirmed) {
            probe.release();
            return new Permit(false);
        }
        return new Permit(true);
    }

    /** {@link #fixCredentials(CredentialVariables)} for this guard's node. */
    public String fixCredentials() {
        return fixCredentials(variables);
    }

    /** True after an accepted attempt and no rejection since. */
    public boolean confirmed() {
        return confirmed;
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
            fixCredentials(variables))
            .withNode(server));
    }

    /**
     * The next step after rejected or missing credentials, naming the node's variables under the
     * profile's effective prefix, e.g. "Fix INUBIT_ACME_TEST_NODE1_PASSWORD (or
     * INUBIT_ACME_TEST_PASSWORD) and _USERNAME, then restart the MCP client …" (shared by the
     * cached and the direct {@code AUTH_FAILED} of REST and StartCLI).
     */
    public static String fixCredentials(CredentialVariables variables) {
        return "Fix " + variables.either(CredentialVariables.PASSWORD) + " and _USERNAME,"
            + " then restart the MCP client from a shell with the new values";
    }

    /** One authenticated attempt. */
    public final class Permit implements AutoCloseable {

        private final boolean exclusive;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(boolean exclusive) {
            this.exclusive = exclusive;
        }

        /** INUBIT accepted the credentials (a 2xx or 403 answer, a CLI login that worked). */
        public void accepted() {
            rejectedAt = null;
            confirmed = true;
        }

        /** INUBIT rejected the credentials (HTTP 401, CLI {@code LoginFailure}). */
        public void rejected() {
            confirmed = false;
            rejectedAt = clock.instant();
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
