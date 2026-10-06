package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.DevelopmentGuard.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pending server-side confirmations of the development tools (feature 004, research D-2,
 * data-model.md → WriteChallenge). Same code format and rules as the {@link ConfirmationRegistry}
 * of restart and kill, which stays for those tools, but bound to what a development preview
 * saw:
 *
 * <ul>
 *   <li>A code is 16 bytes from {@link SecureRandom}, URL-safe Base64 without padding (22 chars),
 *       in memory only, and expires {@code ttl} after it was issued.
 *   <li>It is bound to the capability (tool), the node and the {@code inputFingerprint} (SHA-256
 *       of the canonical tool inputs without the code, computed by the caller). Redeeming it for
 *       another capability, node or other inputs is {@code CONFIRMATION_INVALID}.
 *   <li>It carries the {@code previewState} (base commit, change-set hash and server-state hash,
 *       the tag preconditions, or endpoint and payload hash), which {@link #redeem} returns: the
 *       caller compares it with what it sees now and refuses with {@code CONFLICT} if the server
 *       changed since the preview.
 *   <li>Single use: the first redemption attempt removes it, matching or not; unknown, used and
 *       expired codes are {@code CONFIRMATION_INVALID}. Messages never echo the code, the inputs
 *       or the state.
 *   <li>At most {@link #MAX_PENDING} codes are pending; expired ones are swept on every issue and
 *       redemption.
 * </ul>
 */
public final class WriteChallengeRegistry {

    /** The random bytes of a code. */
    public static final int CODE_BYTES = 16;

    /** The most pending codes at a time. */
    public static final int MAX_PENDING = 1000;

    /** A newly issued code and when it expires. */
    public record Issued(String code, Instant expiresAt) {
        public Issued {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private record Pending(Capability capability, NodeId node, String inputFingerprint,
        String previewState, Instant expiresAt) {
    }

    private final Clock clock;
    private final SecureRandom random;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public WriteChallengeRegistry(Clock clock) {
        this(clock, new SecureRandom());
    }

    WriteChallengeRegistry(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Issues a code for {@code capability} on {@code node} with these inputs, as previewed.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if {@link #MAX_PENDING} unexpired
     *     codes are pending
     */
    public synchronized Issued issue(Capability capability, NodeId node,
        String inputFingerprint, String previewState, Duration ttl) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(previewState, "previewState");
        Objects.requireNonNull(ttl, "ttl");
        Instant now = clock.instant();
        sweep(now);
        if (pending.size() >= MAX_PENDING) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "There are too many pending confirmations (" + MAX_PENDING + "); no preview was"
                    + " issued and nothing was sent",
                "Many previews were requested without confirming them",
                "Confirm or let the pending previews expire (confirmationTtl), then retry")
                .withNode(node));
        }
        Instant expiresAt = now.plus(ttl);
        Pending entry = new Pending(capability, node, inputFingerprint, previewState, expiresAt);
        while (true) {
            byte[] bytes = new byte[CODE_BYTES];
            random.nextBytes(bytes);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (pending.putIfAbsent(code, entry) == null) {
                return new Issued(code, expiresAt);
            }
        }
    }

    /**
     * Redeems {@code code} for exactly this capability, node and inputs; the code is removed in
     * any case.
     *
     * @return the state the preview saw, for the caller's {@code CONFLICT} decision
     * @throws ToolErrorException {@code CONFIRMATION_INVALID} if the code is unknown, used,
     *     expired or bound to another capability, node or other inputs
     */
    public String redeem(String code, Capability capability, NodeId node,
        String inputFingerprint) {
        Instant now = clock.instant();
        Pending entry = code == null ? null : pending.remove(code);
        sweep(now);
        if (entry == null) {
            throw invalid(capability, node, "The confirmation code is unknown or was already used",
                "Codes are single use and do not survive a restart of the MCP server");
        }
        if (now.isAfter(entry.expiresAt())) {
            throw invalid(capability, node, "The confirmation code expired at "
                + entry.expiresAt(), "More time than confirmationTtl passed since the preview");
        }
        if (entry.capability() != capability || !entry.node().equals(node)) {
            throw invalid(capability, node, "The confirmation code was issued for another tool"
                    + " or node; it is now used up",
                "A code only confirms the exact call of its preview");
        }
        if (!entry.inputFingerprint().equals(inputFingerprint)) {
            throw invalid(capability, node, "The confirmation code was issued for other inputs;"
                    + " it is now used up",
                "The inputs of the call differ from those of the preview");
        }
        return entry.previewState();
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

    private static ToolErrorException invalid(Capability capability, NodeId node,
        String message, String likelyCause) {
        return new ToolErrorException(ToolError.of(ErrorCode.CONFIRMATION_INVALID,
            message + "; nothing was sent", likelyCause,
            "Call " + capability.toolName() + " again without confirmationCode to get a new"
                + " preview and code, then confirm with that code").withNode(node));
    }
}
