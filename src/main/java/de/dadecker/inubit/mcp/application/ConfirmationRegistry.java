package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pending server-side confirmations (FR-022, research R-13, data-model.md →
 * PendingConfirmation). In memory only: a stdio server lives for one client session, and codes
 * that do not survive a restart are the safe behaviour.
 *
 * <ul>
 *   <li>A code is 16 bytes from {@link SecureRandom}, URL-safe Base64 without padding (22 chars).
 *   <li>It is bound to one server, action and process id, carries the {@link Previewed} state
 *       of the instance (returned on redemption, so that the caller can refuse if the instance
 *       changed since the preview), and expires {@code ttl} after it was issued.
 *   <li>At most {@link #MAX_PENDING} codes are pending; a further preview is
 *       {@code PRECONDITION_FAILED} after expired codes were swept (memory bound).
 *   <li>It is single use: the first redemption attempt removes it, whether the attempt matches
 *       or not; a concurrent second attempt finds nothing.
 *   <li>Unknown, used, expired or mismatching codes are {@code CONFIRMATION_INVALID}; the
 *       message never echoes the code.
 *   <li>Expired entries are swept lazily on every issue and redemption.
 * </ul>
 */
public final class ConfirmationRegistry {

    /** The random bytes of a code. */
    public static final int CODE_BYTES = 16;

    /** The most pending codes at a time (Phase 6 review W5). */
    public static final int MAX_PENDING = 1000;

    /**
     * The state of the instance row shown in the preview (Phase 6 review W1); the confirmation is
     * valid only while the instance still looks exactly like this.
     */
    public record Previewed(ProcessState state, String rawState, Instant since,
        Optional<String> workflow, Optional<String> module) {

        public Previewed {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(rawState, "rawState");
            Objects.requireNonNull(since, "since");
            workflow = workflow == null ? Optional.empty() : workflow;
            module = module == null ? Optional.empty() : module;
        }

        /** The identity and state of {@code row}. */
        public static Previewed of(ProcessInstance row) {
            return new Previewed(row.state(), row.rawState(), row.since(), row.workflow(),
                row.module());
        }
    }

    /** A newly issued code and when it expires. */
    public record Issued(String code, Instant expiresAt) {
        public Issued {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private record Pending(NodeId node, ProcessAction action, String processId,
        Previewed previewed, Instant expiresAt) {
    }

    private final Clock clock;
    private final SecureRandom random;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ConfirmationRegistry(Clock clock) {
        this(clock, new SecureRandom());
    }

    ConfirmationRegistry(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Issues a code for {@code action} on {@code processId} of {@code server}, as previewed.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if {@link #MAX_PENDING} unexpired
     *     codes are pending
     */
    public synchronized Issued issue(NodeId node, ProcessAction action, String processId,
        Previewed previewed, Duration ttl) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(processId, "processId");
        Instant now = clock.instant();
        sweep(now);
        if (pending.size() >= MAX_PENDING) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "There are too many pending confirmations (" + MAX_PENDING + "); no preview was"
                    + " issued and nothing was changed",
                "Many previews were requested without confirming them",
                "Confirm or let the pending previews expire (confirmationTtl), then retry")
                .withNode(node));
        }
        Instant expiresAt = now.plus(ttl);
        while (true) {
            byte[] bytes = new byte[CODE_BYTES];
            random.nextBytes(bytes);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (pending.putIfAbsent(code, new Pending(node, action, processId, previewed,
                expiresAt))
                == null) {
                return new Issued(code, expiresAt);
            }
        }
    }

    /**
     * Redeems {@code code} for exactly this server, action and process; the code is removed in
     * any case.
     *
     * @return the state of the instance as it was previewed
     *
     * @throws ToolErrorException {@code CONFIRMATION_INVALID} if the code is unknown, used,
     *     expired or bound to another server, action or process
     */
    public Previewed redeem(String code, NodeId node, ProcessAction action,
        String processId) {
        Instant now = clock.instant();
        Pending entry = code == null ? null : pending.remove(code);
        sweep(now);
        if (entry == null) {
            throw invalid(node, action, "The confirmation code is unknown or was already used",
                "Codes are single use and do not survive a restart of the MCP server");
        }
        if (now.isAfter(entry.expiresAt())) {
            throw invalid(node, action, "The confirmation code expired at " + entry.expiresAt(),
                "More time than the confirmation TTL passed since the preview");
        }
        if (!entry.node().equals(node) || entry.action() != action
            || !entry.processId().equals(processId)) {
            throw invalid(node, action, "The confirmation code was issued for another target,"
                    + " action or process instance; it is now used up",
                "A code only confirms the exact action of its preview");
        }
        return entry.previewed();
    }

    /** Removes {@code code} if it is pending (e.g. when a call that presented it is refused). */
    public void discard(String code) {
        if (code != null) {
            pending.remove(code);
        }
    }

    /** The number of pending, unexpired codes. */
    public int pending() {
        sweep(clock.instant());
        return pending.size();
    }

    private void sweep(Instant now) {
        pending.values().removeIf(entry -> now.isAfter(entry.expiresAt()));
    }

    private static ToolErrorException invalid(NodeId node, ProcessAction action,
        String message, String likelyCause) {
        return new ToolErrorException(ToolError.of(ErrorCode.CONFIRMATION_INVALID,
            message + "; nothing was changed", likelyCause,
            "Call " + action.capability() + " again without confirmationCode to get a new"
                + " preview and code, then confirm with that code").withNode(node));
    }
}
