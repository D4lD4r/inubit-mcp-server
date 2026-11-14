package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.OwnerOnlyFileLock;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseAttempt;
import de.dadecker.inubit.mcp.domain.model.node.LeaseReason;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LeaseSummary;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raises temporary log levels on one INUBIT node (feature 024, FR-040 … FR-043, FR-045,
 * research D-14, D-19, D-20, D-22). Nothing is sent to StartCLI before the {@code PENDING}
 * audit record and the write-ahead lease are written:
 *
 * <ol>
 *   <li>Input checks ({@code INVALID_INPUT}; a refused value is never echoed).
 *   <li>The {@link WriteGuard} with the opt-in {@value #OPT_IN_KEY}, then the server's login
 *       credentials within {@code cliTimeout}.
 *   <li>The node lock ({@link NodeLogLevelLocks}, wait {@code cliTimeout}), held until the result.
 *   <li>The lease store: a {@code RESET_FAILED} lease of the server refuses the set.
 *   <li>The own-node lookup; an open lease naming another node refuses the set, as does a merge
 *       beyond {@link Lease#MAX_LOGGERS} loggers.
 *   <li>Confirmation (D-19): {@code SERVER} without code → a challenge, nothing changed; with code
 *       → the code is redeemed and the answering node and the open lease must be those of the
 *       preview. {@code CLIENT} → at once.
 *   <li>The {@code PENDING} audit record.
 *   <li>The write-ahead lease: merged into the server's {@code ACTIVE} lease or a new one.
 *   <li>One StartCLI run per logger, in request order.
 *   <li>The lease records the executed change: the expiry counts from the end of the last run.
 *       If that record fails, the node is reset at once and the call is {@code INTERNAL}.
 *   <li>A failed run resets the node at once (every temporary level, also of a merged lease); the
 *       lease ends ({@code RESET}) or, if the reset fails too, is {@code RESET_FAILED}.
 *   <li>The final audit record and the result.
 * </ol>
 *
 * <p>Every refusal before {@code PENDING} is one {@code REFUSED} audit record.
 */
public final class LogLevelService {

    static final String SET_TOOL = LogLevelAudit.SET_TOOL;
    static final String OPT_IN_KEY = "unsupported.logLevelWrite";
    /** The default of {@code durationMinutes}. */
    static final int DEFAULT_MINUTES = 10;
    static final int MAX_MINUTES = 60;
    /** How long a lease update waits for the store lock (short: held only to read and write). */
    static final Duration STORE_WAIT = Duration.ofSeconds(2);

    private static final Logger LOG = LoggerFactory.getLogger(LogLevelService.class);
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final String LEVELS = "TRACE, DEBUG, INFO, WARN or ERROR";

    private final WriteGuard guard;
    private final Predicate<NodeId> optedIn;
    private final GatewayFactory gateways;
    private final WriteChallengeRegistry challenges;
    private final LeaseStore store;
    private final NodeLogLevelLocks locks;
    private final ProcessLiveness liveness;
    private final LogLevelAudit audit;
    private final Function<NodeId, Duration> cliTimeouts;
    private final Clock clock;
    private final LongSupplier nanoTime;

    /**
     * @param optedIn     whether the server's group sets {@value #OPT_IN_KEY}
     * @param gateways    the server's credentials and its log-level port
     * @param liveness    names this instance as the owner of the leases it writes
     * @param cliTimeouts the {@code cliTimeout} of a server: the node lock's wait, the own-node
     *                    lookup's deadline and the bound of each StartCLI run
     * @param clock       the times of the leases
     * @param nanoTime    the clock of the lookup deadline ({@link System#nanoTime()})
     */
    public LogLevelService(WriteGuard guard, Predicate<NodeId> optedIn, GatewayFactory gateways,
        WriteChallengeRegistry challenges, LeaseStore store, NodeLogLevelLocks locks,
        ProcessLiveness liveness, LogLevelAudit audit, Function<NodeId, Duration> cliTimeouts,
        Clock clock, LongSupplier nanoTime) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.optedIn = Objects.requireNonNull(optedIn, "optedIn");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.store = Objects.requireNonNull(store, "store");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.cliTimeouts = Objects.requireNonNull(cliTimeouts, "cliTimeouts");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * {@code set_temporary_log_level} as the class description says.
     *
     * @return the challenge of a preview, or the outcome ({@code EXECUTED}, or {@code FAILED}
     *     when a StartCLI run failed and the node was reset)
     * @throws ToolErrorException a refusal before anything was sent; {@code INTERNAL} if the
     *     {@code PENDING} record or the write-ahead lease could not be written (nothing sent) or
     *     the lease could not record the executed change (the node was reset)
     */
    public LogLevelResult set(SetLevelRequest request) {
        Objects.requireNonNull(request, "request");
        LogLevelAudit.ToolCall call = audit.toolCall(SET_TOOL,
            LogLevelAudit.Inputs.set(request.node(), request.loggers(), request.level(),
                request.durationMinutes(), request.confirmationCode()),
            request.mcpClient());
        Checked checked;
        WritePolicy policy = null;
        LogLevelPort port;
        try {
            checked = check(request);
            policy = guard.admit(request.node(), SET_TOOL, Capability.LOG_LEVEL_WRITE,
                Set.of(Channel.CLI), optedIn, OPT_IN_KEY);
            call.node(policy.node(), policy.account());
            prepareCredentials(policy.node());
            port = gateways.logLevel(policy.node());
        } catch (ToolErrorException e) {
            throw refuse(call, request, Optional.ofNullable(policy), e);
        }
        NodeId node = policy.node();
        Optional<WritePolicy> admitted = Optional.of(policy);
        OwnerOnlyFileLock.Held held;
        try {
            held = locks.acquire(node, cliTimeouts.apply(node));
        } catch (ToolErrorException e) {
            throw refuse(call, request, admitted, e);
        }
        try (held) {
            Observed observed;
            try {
                observed = observe(call, policy, port);
                requireRoom(node, observed.open(), checked);
                if (policy.confirmation() == WritePolicy.Confirmation.SERVER
                    && request.confirmationCode().isPresent()) {
                    confirm(request.confirmationCode().get(), node, checked, observed);
                }
            } catch (ToolErrorException e) {
                throw refuse(call, request, admitted, e);
            }
            if (policy.confirmation() == WritePolicy.Confirmation.SERVER
                && request.confirmationCode().isEmpty()) {
                return challenge(call, policy, checked, observed);
            }
            return execute(call, policy, port, checked, observed);
        }
    }

    // ---- steps 1 to 6 ----------------------------------------------------------------------

    /** The checked inputs of a set: the loggers in request order, one level, the duration. */
    private record Checked(List<LoggerName> loggers, TemporaryLevel level, int durationMinutes) {

        Duration duration() {
            return Duration.ofMinutes(durationMinutes);
        }

        SortedMap<LoggerName, TemporaryLevel> set() {
            SortedMap<LoggerName, TemporaryLevel> set =
                new TreeMap<>(Comparator.comparing(LoggerName::value));
            loggers.forEach(name -> set.put(name, level));
            return set;
        }

        List<LeaseSummary.Logger> requested() {
            return loggers.stream().map(name -> new LeaseSummary.Logger(name, level)).toList();
        }
    }

    /** What steps 4 and 5 saw: the server's open lease and the INUBIT node that answers. */
    private record Observed(Optional<Lease> open, String inubitNode) {

        /** The preview state of D-19, also the text of a refusal when it changed. */
        String state() {
            return "INUBIT node " + inubitNode + ", " + open.map(lease -> "lease "
                + lease.leaseId() + " " + lease.state() + " generation " + lease.generation())
                .orElse("no open lease");
        }
    }

    /** Step 1 (FR-041, D-11): every input as the schema says; no refused value is echoed. */
    private static Checked check(SetLevelRequest request) {
        List<String> given = request.loggers();
        if (given.isEmpty() || given.size() > Lease.MAX_SET_LOGGERS) {
            throw invalid("Invalid loggers: expected 1 to " + Lease.MAX_SET_LOGGERS
                + " names below com.inubit.ibis");
        }
        List<LoggerName> loggers = new ArrayList<>();
        for (int i = 0; i < given.size(); i++) {
            if (!LoggerName.isValid(given.get(i))) {
                throw invalid("Invalid logger " + (i + 1) + " (a name below com.inubit.ibis)");
            }
            LoggerName name = new LoggerName(given.get(i));
            if (loggers.contains(name)) {
                throw invalid("Logger " + (i + 1) + " repeats an earlier logger");
            }
            loggers.add(name);
        }
        TemporaryLevel level = null;
        for (TemporaryLevel candidate : TemporaryLevel.values()) {
            if (candidate.name().equals(request.level())) {
                level = candidate;
            }
        }
        if (level == null) {
            throw invalid("Invalid level: expected " + LEVELS);
        }
        int minutes = request.durationMinutes().orElse(DEFAULT_MINUTES);
        if (minutes < 1 || minutes > MAX_MINUTES) {
            throw invalid("Invalid durationMinutes: expected 1 to " + MAX_MINUTES);
        }
        if (request.confirmationCode().isPresent()
            && !CODE.matcher(request.confirmationCode().get()).matches()) {
            throw invalid("Invalid confirmationCode: expected the 22-character code of a preview");
        }
        return new Checked(List.copyOf(loggers), level, minutes);
    }

    /** Step 2, last check: the login credentials, read within {@code cliTimeout}. */
    private void prepareCredentials(NodeId node) {
        Duration budget = cliTimeouts.apply(node);
        if (gateways.prepareCredentials(node, budget) == CredentialState.PENDING) {
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, SET_TOOL + " on " + node
                    + " did not finish within cliTimeout (" + Durations.human(budget)
                    + "): the login credentials of " + node + " were not read in time",
                "The first read of the login credentials from their store took longer than"
                    + " cliTimeout; it continues, so a later call finds them",
                "Retry in a moment; raise cliTimeout of " + node + " if the credential store"
                    + " is just slow").withNode(node));
        }
    }

    /** Steps 4 and 5: the server's open lease, then the node that answers. */
    private Observed observe(LogLevelAudit.ToolCall call, WritePolicy policy,
        LogLevelPort port) {
        NodeId node = policy.node();
        Optional<Lease> open = openLease(store.read(), node);
        call.lease(open);
        Duration budget = cliTimeouts.apply(node);
        String inubitNode;
        try {
            inubitNode = port.answeringNode(policy.permit().admitted(),
                nanoTime.getAsLong() + budget.toNanos());
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(node));
        }
        call.inubitNode(inubitNode);
        if (open.isPresent() && !open.get().inubitNode().equals(inubitNode)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "INUBIT node " + inubitNode + " answers for " + node + ", but the open lease was"
                    + " set on " + open.get().inubitNode(),
                node + " is not reached on one node directly (a load balancer, or another node"
                    + " took over); the lease's reset would not reach the raised node",
                "Address the cluster node directly in the configuration, or wait until the lease "
                    + open.get().leaseId() + " ends at " + open.get().expiresAt())
                .withNode(node));
        }
        return new Observed(open, inubitNode);
    }

    /**
     * The open lease of {@code node}, if any (a set merges into it, so there is at most one).
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if it is {@code RESET_FAILED} (US4-11)
     */
    private static Optional<Lease> openLease(List<Lease> leases, NodeId node) {
        Optional<Lease> open = leases.stream()
            .filter(lease -> lease.server().equals(node) && lease.state().open())
            .findFirst();
        if (open.isPresent() && open.get().state() == LeaseState.RESET_FAILED) {
            Lease failed = open.get();
            LeaseAttempt attempt = failed.lastAttempt().orElseThrow();
            String reason = attempt.reason().map(LeaseReason::text)
                .or(() -> attempt.code().map(ErrorCode::name)).orElse("no reason recorded");
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The last reset of " + node + " failed (lease " + failed.leaseId() + ", "
                    + attempt.at() + ": " + reason + "); retry reset_temporary_log_level first",
                "A level stays raised until the reset succeeds; a new set waits for it (FR-043)",
                "Call reset_temporary_log_level with node " + node + "; if the node can no longer"
                    + " be reset, forget the lease with leaseId " + failed.leaseId()
                    + " and forget: true").withNode(node));
        }
        return open;
    }

    /** A merge must stay within {@link Lease#MAX_LOGGERS} loggers per lease. */
    private static void requireRoom(NodeId node, Optional<Lease> open, Checked checked) {
        if (open.isEmpty()) {
            return;
        }
        Set<LoggerName> union = new HashSet<>(open.get().loggers().keySet());
        union.addAll(checked.loggers());
        if (union.size() > Lease.MAX_LOGGERS) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The open lease " + open.get().leaseId() + " of " + node + " would hold more"
                    + " than " + Lease.MAX_LOGGERS + " loggers; nothing was changed",
                "Every set merges its loggers into the server's open lease",
                "Reset " + node + " with reset_temporary_log_level, then set the levels again")
                .withNode(node));
        }
    }

    /** Step 6 with a code: redeemed for these inputs, and the node's state as previewed. */
    private void confirm(String code, NodeId node, Checked checked, Observed observed) {
        String previewed = challenges.redeem(code, SET_TOOL, node, fingerprint(node, checked));
        if (!previewed.equals(observed.state())) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The state of " + node + " changed since the preview (preview: " + previewed
                    + "; now: " + observed.state() + "); nothing was changed",
                "Another node answers now, or another set, reset or MCP server instance changed"
                    + " the lease since the preview",
                "Call " + SET_TOOL + " again without confirmationCode for a new preview")
                .withNode(node));
        }
    }

    /** Step 6 without a code on {@code SERVER}: a code bound to the inputs and the state. */
    private LogLevelResult challenge(LogLevelAudit.ToolCall call, WritePolicy policy,
        Checked checked, Observed observed) {
        NodeId node = policy.node();
        WriteChallengeRegistry.Issued issued = challenges.issue(SET_TOOL, node,
            fingerprint(node, checked), observed.state(), policy.confirmationTtl());
        try {
            call.challengeIssued(issued.code(), "Preview of raising " + checked.loggers().size()
                + " logger(s) to " + checked.level() + " on INUBIT node " + observed.inubitNode()
                + " for " + checked.durationMinutes() + " minute(s), code valid until "
                + issued.expiresAt());
        } catch (ToolErrorException e) {
            challenges.discard(issued.code());
            throw e;
        }
        return new LogLevelResult.Challenge(issued.code(), issued.expiresAt(),
            new LogLevelResult.Preview(node, observed.inubitNode(), checked.requested(),
                checked.durationMinutes(), observed.open().map(LeaseSummary::of)),
            "Nothing was changed. Call " + SET_TOOL + " again with confirmationCode before "
                + issued.expiresAt() + " to raise the level; the later reset restores every"
                + " temporary level on " + observed.inubitNode() + ", also other users'");
    }

    /** D-19: SHA-256 of the canonical inputs without the code (loggers sorted by name). */
    private static String fingerprint(NodeId node, Checked checked) {
        String loggers = checked.loggers().stream().map(LoggerName::value).sorted()
            .map(name -> name + "=" + checked.level()).collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                String.join("\n", SET_TOOL, node.value(), loggers,
                    Integer.toString(checked.durationMinutes()))
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- steps 7 to 12 ---------------------------------------------------------------------

    private LogLevelResult execute(LogLevelAudit.ToolCall call, WritePolicy policy,
        LogLevelPort port, Checked checked, Observed observed) {
        NodeId node = policy.node();
        call.pending();
        Lease ahead;
        try {
            ahead = writeAhead(node, checked, observed.inubitNode(), call.auditId());
        } catch (RuntimeException e) {
            ToolError error = ToolError.of(ErrorCode.INTERNAL, "The lease of " + node
                    + " could not be recorded before the change ("
                    + text(failure(e, node, "The lease store"))
                    + "); nothing was sent to INUBIT",
                "The lease store is not writable, or another MCP server process of the profile"
                    + " held its lock beyond the wait",
                "Check ~/.inubit-mcp/<profile>/log-levels, then retry").withNode(node);
            call.outcome(AuditOutcome.FAILED, text(error));
            throw new ToolErrorException(error);
        }
        InubitVersion version = policy.permit().admitted();
        Duration timeout = cliTimeouts.apply(node);
        for (LoggerName logger : checked.loggers()) {
            try {
                port.raise(checked.level(), logger, version, timeout);
            } catch (RuntimeException e) {
                return failedRun(call, policy, observed, ahead, failure(e, node, "StartCLI"), port);
            }
        }
        Instant changed = clock.instant();
        Lease recorded;
        try {
            recorded = replace(ahead.leaseId(),
                lease -> lease.executed(changed, checked.duration()));
        } catch (RuntimeException e) {
            throw unrecorded(call, node, ahead, e, port, version, timeout);
        }
        call.lease(Optional.of(recorded));
        List<String> warnings = new ArrayList<>(policy.warnings());
        call.outcome(AuditOutcome.EXECUTED, "Raised " + checked.loggers().size()
            + " logger(s) to " + checked.level() + " on INUBIT node " + recorded.inubitNode()
            + " until " + recorded.expiresAt()).ifPresent(warnings::add);
        return new LogLevelResult.Outcome(node, recorded.inubitNode(),
            LogLevelResult.Status.EXECUTED, Optional.of(recorded.leaseId()), loggers(recorded),
            Optional.of(recorded.expiresAt()), true, "Raised on INUBIT node "
                + recorded.inubitNode() + " until " + recorded.expiresAt() + "; then the MCP"
                + " server resets every temporary log level on " + recorded.inubitNode()
                + ", also other users'. Levels can be lost earlier (INUBIT restart, log"
                + " configuration reload).",
            call.auditId(), warnings);
    }

    /**
     * Step 8: the write-ahead lease, merged into the server's {@code ACTIVE} lease or new; owned
     * by this instance. Under the store lock the server's open lease is looked up again.
     */
    private Lease writeAhead(NodeId node, Checked checked, String inubitNode, UUID auditId) {
        Instant now = clock.instant();
        UUID newId = UUID.randomUUID();
        List<Lease> stored = store.update(leases -> {
            Optional<Lease> open = openLease(leases, node);
            Lease written = open.isPresent()
                ? open.get().merge(checked.set(), now, checked.duration(), liveness.self(),
                    auditId)
                : new Lease(newId, 1, node, inubitNode, checked.set(), now,
                    now.plus(checked.duration()), liveness.self(), LeaseState.ACTIVE, auditId,
                    Optional.empty(), Optional.empty());
            List<Lease> after = new ArrayList<>(leases);
            open.ifPresent(after::remove);
            after.add(written);
            return after;
        }, STORE_WAIT);
        return openLease(stored, node).orElseThrow();
    }

    /** The stored lease {@code leaseId} changed by {@code change}, under the store lock. */
    private Lease replace(UUID leaseId, UnaryOperator<Lease> change) {
        List<Lease> stored = store.update(leases -> {
            if (leases.stream().noneMatch(lease -> lease.leaseId().equals(leaseId))) {
                throw new IllegalStateException("Lease " + leaseId + " is no longer stored");
            }
            return leases.stream()
                .map(lease -> lease.leaseId().equals(leaseId) ? change.apply(lease) : lease)
                .toList();
        }, STORE_WAIT);
        return stored.stream().filter(lease -> lease.leaseId().equals(leaseId)).findFirst()
            .orElseThrow();
    }

    /** Step 11: a run failed; the node is reset at once and the lease ends or is retried. */
    private LogLevelResult failedRun(LogLevelAudit.ToolCall call, WritePolicy policy,
        Observed observed, Lease ahead, ToolError failure, LogLevelPort port) {
        NodeId node = policy.node();
        Optional<ToolError> resetFailure = resetAll(port, node, policy.permit().admitted(),
            cliTimeouts.apply(node));
        Lease lease = recordReset(ahead, resetFailure).orElse(ahead);
        boolean open = lease.state().open();
        String inubitNode = ahead.inubitNode();
        String message = "Raising the log level on INUBIT node " + inubitNode + " failed ("
            + text(failure) + ")";
        if (resetFailure.isEmpty() && !open) {
            message += "; every temporary log level on " + inubitNode + " was reset at once,"
                + " also other users', and lease " + lease.leaseId() + " ended"
                + (observed.open().isPresent() ? ", also for the loggers of earlier sets" : "");
        } else if (resetFailure.isEmpty()) {
            message += "; every temporary log level on " + inubitNode + " was reset at once,"
                + " also other users', but lease " + lease.leaseId() + " could not record the"
                + " reset and stays open: the MCP server resets " + inubitNode + " again at its"
                + " expiry (" + lease.expiresAt() + ")";
        } else {
            message += ", and the reset failed too (" + text(resetFailure.get()) + "); the"
                + " levels may stay raised on " + inubitNode + ": "
                + (lease.state() == LeaseState.RESET_FAILED
                    ? "the MCP server retries the reset of lease " + lease.leaseId()
                        + " at least every minute"
                    : "lease " + lease.leaseId() + " could not record the failed reset and"
                        + " stays open; the MCP server resets " + inubitNode + " at its expiry ("
                        + lease.expiresAt() + ")");
        }
        call.lease(Optional.of(lease));
        List<String> warnings = new ArrayList<>(policy.warnings());
        call.outcome(AuditOutcome.FAILED, text(failure)).ifPresent(warnings::add);
        return new LogLevelResult.Outcome(node, inubitNode, LogLevelResult.Status.FAILED,
            Optional.of(lease.leaseId()), loggers(lease),
            open ? Optional.of(lease.expiresAt()) : Optional.empty(), open, message,
            call.auditId(), warnings);
    }

    /**
     * Step 10, record failed: the node is reset at once; the call is {@code INTERNAL}.
     *
     * @return the error to throw
     */
    private ToolErrorException unrecorded(LogLevelAudit.ToolCall call, NodeId node, Lease ahead,
        RuntimeException cause, LogLevelPort port, InubitVersion version, Duration timeout) {
        Optional<ToolError> resetFailure = resetAll(port, node, version, timeout);
        call.lease(Optional.of(recordReset(ahead, resetFailure).orElse(ahead)));
        String why = cause instanceof ToolErrorException failure ? failure.error().message()
            : cause.getClass().getSimpleName();
        ToolError error = resetFailure.isEmpty()
            ? ToolError.of(ErrorCode.INTERNAL,
                "The levels were raised but the lease could not be recorded; the node was reset",
                why, "Fix the lease store (~/.inubit-mcp/<profile>/log-levels), then set the"
                    + " level again")
            : ToolError.of(ErrorCode.INTERNAL,
                "The levels were raised but the lease could not be recorded, and the reset"
                    + " failed (" + text(resetFailure.get()) + "); the levels may stay raised on "
                    + ahead.inubitNode(),
                why, "Reset with reset_temporary_log_level for " + node + ", and fix the lease"
                    + " store (~/.inubit-mcp/<profile>/log-levels)");
        ToolError named = error.withNode(node);
        call.outcome(AuditOutcome.FAILED, text(named));
        return new ToolErrorException(named);
    }

    /** {@code trace --reset}; the failure, if any. */
    private static Optional<ToolError> resetAll(LogLevelPort port, NodeId node,
        InubitVersion version, Duration timeout) {
        try {
            port.resetAll(version, timeout);
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of(failure(e, node, "StartCLI"));
        }
    }

    /**
     * The error of a failed step; an unexpected exception is {@code INTERNAL}.
     *
     * @param what what failed, e.g. {@code StartCLI} or {@code The lease store}
     */
    private static ToolError failure(RuntimeException e, NodeId node, String what) {
        if (e instanceof ToolErrorException known) {
            return known.error();
        }
        LOG.error("Unexpected failure of {} on {}", SET_TOOL, node, e);
        return ToolError.of(ErrorCode.INTERNAL, what + " failed unexpectedly ("
                + e.getClass().getSimpleName() + ")", "An internal error of the INUBIT MCP server",
            "Check the MCP server log on stderr and report the problem").withNode(node);
    }

    /**
     * The lease after a reset at once: {@code RESET}, or {@code RESET_FAILED} with the attempt.
     * Best effort: if the store cannot record it, the write-ahead lease stays as it is (the
     * lease scheduler resets the node at its expiry).
     */
    private Optional<Lease> recordReset(Lease ahead, Optional<ToolError> resetFailure) {
        Instant at = clock.instant();
        try {
            return Optional.of(replace(ahead.leaseId(), lease -> resetFailure
                .map(failure -> lease.failed(new LeaseAttempt(at, AuditOutcome.FAILED,
                    Optional.of(failure.code()), Optional.of(reason(failure.code())))))
                .orElseGet(() -> lease.reset(at))));
        } catch (RuntimeException e) {
            LOG.error("Lease {} of {} could not record the reset after a failed set ({}); it stays"
                + " as written before the change", ahead.leaseId(), ahead.server(),
                e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static LeaseReason reason(ErrorCode code) {
        return code == ErrorCode.UNREACHABLE || code == ErrorCode.TIMEOUT
            ? LeaseReason.NODE_UNREACHABLE : LeaseReason.CLI_FAILED;
    }

    private static List<LeaseSummary.Logger> loggers(Lease lease) {
        return lease.loggers().entrySet().stream()
            .map(entry -> new LeaseSummary.Logger(entry.getKey(), entry.getValue()))
            .toList();
    }

    // ---- refusals --------------------------------------------------------------------------

    /**
     * Audits a refusal before {@code PENDING} as {@code REFUSED} and discards a presented code.
     *
     * @param policy the admitted policy, once known (decides {@code PREVIEW} or {@code EXECUTE})
     */
    private ToolErrorException refuse(LogLevelAudit.ToolCall call, SetLevelRequest request,
        Optional<WritePolicy> policy, ToolErrorException e) {
        request.confirmationCode().ifPresent(challenges::discard);
        boolean execute = request.confirmationCode().isPresent() || policy
            .map(admitted -> admitted.confirmation() == WritePolicy.Confirmation.CLIENT)
            .orElse(false);
        return call.refused(execute ? AuditRecord.Step.EXECUTE : AuditRecord.Step.PREVIEW,
            e.error());
    }

    private static ToolErrorException invalid(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            "set_temporary_log_level takes 1 to " + Lease.MAX_SET_LOGGERS + " distinct logger"
                + " names below com.inubit.ibis, a level (" + LEVELS + "), durationMinutes 1 to "
                + MAX_MINUTES + " and the confirmationCode of a preview",
            "Fix the input and call again"));
    }

    private static String text(ToolError error) {
        return error.code() + ": " + error.message();
    }
}
