package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseAttempt;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseReason;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LeaseSummary;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T050 (FR-044, research D-15, D-19, D-20, D-22; contracts/mcp-tools-delta.md
 * "reset_temporary_log_level"): {@link LogLevelService#reset}, the {@code node} form (reset of the
 * answering INUBIT node, the lease ends) and the {@code leaseId} + {@code forget} form (a
 * {@code RESET_FAILED} lease ends as {@code FORGOTTEN} without contacting INUBIT). The fixture is
 * that of {@link LogLevelServiceSetTest}: a fake {@link LogLevelPort}, the real {@link LeaseStore}
 * and {@link NodeLogLevelLocks} in a temporary home (store writes can be made to fail), the real
 * {@link WriteGuard} over the version gate (every server pinned to 8.1, every version-setting
 * lookup recorded), the real {@link WriteChallengeRegistry} and {@link LogLevelAudit}.
 */
class LogLevelServiceResetTest {

    /** Confirmation by the MCP server (the default). */
    private static final NodeId TEST = NodeId.parse("test/node1");
    /** Confirmation left to the client. */
    private static final NodeId DEV = NodeId.parse("dev/node1");
    /** Production without {@code write.productionOptIn}. */
    private static final NodeId PROD = NodeId.parse("prod/node1");
    /** {@code write.enabled: false}. */
    private static final NodeId OFF = NodeId.parse("test/node2");
    /** Write access, but no {@code unsupported.logLevelWrite}. */
    private static final NodeId ACME = NodeId.parse("acme/node1");
    /** No longer configured. */
    private static final NodeId GONE = NodeId.parse("acme/node9");

    private static final Instant NOW = Instant.parse("2026-11-12T08:15:30Z");
    private static final Instant EXPIRY = NOW.plus(Duration.ofMinutes(10));
    private static final Duration CLI_TIMEOUT = Duration.ofMillis(500);
    private static final Duration TTL = Duration.ofMinutes(5);
    /** How long each fake StartCLI run takes. */
    private static final Duration RUN = Duration.ofSeconds(3);
    private static final LeaseOwner SELF =
        new LeaseOwner(4711, Optional.of(Instant.parse("2026-11-12T07:58:02.114Z")));
    private static final LeaseOwner OTHER = new LeaseOwner(4712, Optional.empty());
    private static final String ORDERS = "com.inubit.ibis.acme.Orders";
    private static final LeaseAttempt OTHER_NODE = new LeaseAttempt(
        Instant.parse("2026-11-12T08:10:01Z"), AuditOutcome.REFUSED, Optional.empty(),
        Optional.of(LeaseReason.OTHER_NODE_ANSWERS));

    /** {@code log_level_write} supported on 8.1. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Map.of(Capability.LOG_LEVEL_WRITE, SupportState.SUPPORTED), Map.of(), false)),
        Map.of());

    @TempDir
    Path home;

    private final MutableClock clock = new MutableClock(NOW);
    private final FakePort port = new FakePort();
    private final FakeGateways gateways = new FakeGateways();
    private final RecordingAudit auditLog = new RecordingAudit();
    private final SwitchableFiles files = new SwitchableFiles();
    private final WriteChallengeRegistry challenges = new WriteChallengeRegistry(clock);
    private final AtomicLong nanoTime = new AtomicLong(7_000_000_000L);
    /** Every version-setting lookup of the version gate. */
    private final List<NodeId> gateLookups = new CopyOnWriteArrayList<>();
    private final Map<NodeId, WritePolicy> policies = Map.of(
        TEST, policy(TEST, false, true, Confirmation.SERVER),
        DEV, policy(DEV, false, true, Confirmation.CLIENT),
        PROD, policy(PROD, true, true, Confirmation.SERVER),
        OFF, policy(OFF, false, false, Confirmation.CLIENT),
        ACME, policy(ACME, false, true, Confirmation.CLIENT));
    /** The servers whose group sets {@code unsupported.logLevelWrite: true}. */
    private final Set<NodeId> optedIn = Set.of(TEST, DEV, PROD, OFF);
    private LeaseStore store;

    private final List<ToolError> errors = new ArrayList<>();
    private final List<LogLevelResult> results = new ArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    private static WritePolicy policy(NodeId server, boolean production, boolean enabled,
        Confirmation confirmation) {
        return new WritePolicy(server, production, enabled, false, confirmation, TTL,
            Optional.of("jdoe"));
    }

    @BeforeEach
    void setUp() {
        store = new LeaseStore(directory(), files, clock);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }

    /** Leak check of every call: no credential in an error, a result or a log line. */
    @AfterEach
    void noLeak() {
        List<String> texts = new ArrayList<>();
        errors.forEach(error -> texts.add(error.toString()));
        results.forEach(result -> texts.add(result.toString()));
        appender.list.forEach(event -> texts.add(event.getFormattedMessage()));
        auditLog.records.forEach(record -> texts.add(record.toString()));
        texts.forEach(text -> assertThat(text)
            .doesNotContain(TestSecrets.CONFIGURED_PASSWORD)
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE));
    }

    // --- US4-4 and US4-8: reset of a node -------------------------------------------------------

    @Test
    void aClientConfirmedResetResetsTheNodeAndEndsItsLease() throws Exception {
        Lease open = stored(activeLease(DEV, OTHER));

        LogLevelResult.Outcome result = execute(reset(DEV));

        assertThat(port.calls).containsExactly("answeringNode 8.1", "resetAll");
        assertThat(port.timeouts).containsOnly(CLI_TIMEOUT);
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET);
        assertThat(lease.endedAt()).contains(NOW.plus(RUN));
        assertThat(lease.generation()).isEqualTo(open.generation() + 1);

        assertThat(result.node()).isEqualTo(DEV);
        assertThat(result.inubitNode()).isEqualTo("node1");
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.EXECUTED);
        assertThat(result.leaseId()).contains(open.leaseId());
        assertThat(result.leaseOpen()).as("a lease was open").isTrue();
        assertThat(result.expiresAt()).isEmpty();
        assertThat(result.message()).isEqualTo("Every temporary log level on INUBIT node node1"
            + " was reset, also other users'; the lease ended");
        assertThat(result.warnings()).isEmpty();

        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        AuditRecord executed = auditLog.records.get(1);
        assertThat(executed.auditId()).isEqualTo(result.auditId());
        assertThat(executed.capability()).isEqualTo("reset_temporary_log_level");
        assertThat(executed.inputs()).containsExactly(Map.entry("node", "dev/node1"));
        assertThat(executed.details()).containsEntry("leaseId", open.leaseId().toString())
            .containsEntry("inubitNode", "node1");
        assertThat(gateways.calls).containsExactly("checkAvailable dev/node1",
            "prepareCredentials dev/node1", "logLevel dev/node1");
        assertThat(lockFree(DEV)).as("the node lock is released").isTrue();
    }

    @Test
    void aResetWithoutALeaseStillResetsTheNodeAndSaysNoLeaseWasOpen() {
        LogLevelResult.Outcome result = execute(reset(DEV));

        assertThat(port.calls).containsExactly("answeringNode 8.1", "resetAll");
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.EXECUTED);
        assertThat(result.inubitNode()).isEqualTo("node1");
        assertThat(result.leaseId()).isEmpty();
        assertThat(result.leaseOpen()).isFalse();
        assertThat(result.message()).isEqualTo("No lease was open for dev/node1; every temporary"
            + " log level on INUBIT node node1 was reset, also other users'");
        assertThat(leasesFile()).as("no lease is created").doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(auditLog.records.get(1).details()).containsEntry("inubitNode", "node1")
            .containsEntry("leaseOpen", false);
    }

    @Test
    void aResetEndsALeaseWhoseEarlierResetFailed() {
        Lease failed = stored(activeLease(DEV, OTHER).failed(OTHER_NODE));

        LogLevelResult.Outcome result = execute(reset(DEV));

        assertThat(onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(result.leaseId()).contains(failed.leaseId());
        assertThat(result.leaseOpen()).isTrue();
    }

    // --- confirmation by the MCP server (D-19) ---------------------------------------------------

    @Test
    void aServerConfirmedResetFirstIssuesAChallengeNamingTheLeaseAndSendsNoReset()
        throws Exception {
        Lease open = stored(activeLease(TEST, SELF));

        LogLevelResult.Challenge challenge = (LogLevelResult.Challenge) call(reset(TEST));

        assertThat(challenge.confirmationCode()).matches("[A-Za-z0-9_-]{22}");
        assertThat(challenge.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(challenge.preview()).isEqualTo(new LogLevelResult.ResetPreview(TEST, "node1",
            Optional.of(LeaseSummary.of(open))));
        assertThat(challenge.message()).isEqualTo("Nothing was changed. Call"
            + " reset_temporary_log_level again with confirmationCode before " + NOW.plus(TTL)
            + " to reset every temporary log level on INUBIT node node1, also other users'; lease "
            + open.leaseId() + " ends");
        assertThat(port.calls).containsExactly("answeringNode 8.1");
        assertThat(onlyLease()).isEqualTo(open);
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED);
        assertThat(auditLog.records.get(0).step()).isEqualTo(AuditRecord.Step.PREVIEW);
        assertThat(lockFree(TEST)).as("the node lock is released with the challenge").isTrue();
    }

    @Test
    void theChallengeWithoutALeaseSaysSo() {
        LogLevelResult.Challenge challenge = (LogLevelResult.Challenge) call(reset(TEST));

        assertThat(challenge.preview().openLease()).isEmpty();
        assertThat(challenge.message()).endsWith("also other users'; no lease is open for"
            + " test/node1");
    }

    @Test
    void theChallengeWithAnUnreadableLeaseStoreSaysItCannotBeRead() throws IOException {
        Files.createDirectories(directory());
        Files.writeString(leasesFile(), "not json");

        LogLevelResult.Challenge challenge = (LogLevelResult.Challenge) call(reset(TEST));

        assertThat(challenge.preview().openLease()).isEmpty();
        assertThat(challenge.message()).contains("also other users'; no lease can be updated:"
            + " the lease store cannot be read (")
            .doesNotContain("no lease is open");
        assertThat(port.raisedOrReset()).isEmpty();
    }

    @Test
    void theRedeemedCodeResetsTheNode() {
        Lease open = stored(activeLease(TEST, SELF));
        String code = challenge(reset(TEST));

        LogLevelResult.Outcome result = execute(confirmed(reset(TEST), code));

        assertThat(port.calls).containsExactly("answeringNode 8.1", "answeringNode 8.1",
            "resetAll");
        assertThat(result.leaseId()).contains(open.leaseId());
        assertThat(onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void aLeaseChangedSinceThePreviewRefusesTheResetAndSendsNothing() {
        String code = challenge(reset(TEST));
        Lease opened = stored(activeLease(TEST, SELF));

        ToolError error = refusal(confirmed(reset(TEST), code));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("The state of test/node1 changed since the preview"
            + " (preview: INUBIT node node1, no open lease; now: INUBIT node node1, lease "
            + opened.leaseId() + " ACTIVE generation 1); nothing was changed");
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(onlyLease()).isEqualTo(opened);
    }

    // --- US4-6: another node answers -------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = LeaseState.class, names = {"ACTIVE", "RESET_FAILED"})
    void anOpenLeaseOnAnotherNodeRefusesTheResetNamingBothAndSendsNoReset(LeaseState state) {
        Lease active = activeLease(DEV, SELF);
        Lease open = stored(state == LeaseState.ACTIVE ? active : active.failed(OTHER_NODE));
        port.answering = "node2";

        ToolError error = refusal(reset(DEV));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo(
            "INUBIT node node2 answers for dev/node1, but the open lease was set on node1");
        assertThat(error.node()).contains(DEV);
        assertThat(port.calls).containsExactly("answeringNode 8.1");
        assertThat(onlyLease()).isEqualTo(open);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- US4-7: write guard and opt-in, for reset and for forget --------------------------------

    static Stream<Arguments> guarded() {
        return Stream.of(
            Arguments.of(PROD, ErrorCode.PRODUCTION_PROTECTED),
            Arguments.of(OFF, ErrorCode.WRITE_DISABLED),
            Arguments.of(ACME, ErrorCode.NOT_CONFIGURED));
    }

    @ParameterizedTest
    @MethodSource("guarded")
    void theWriteGuardAndTheOptInRefuseTheResetBeforeAnythingIsSent(NodeId server,
        ErrorCode code) {
        ToolError error = refusal(reset(server));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.node()).contains(server);
        assertNothingSent();
        assertThat(home.resolve(".inubit-mcp")).as("nothing created").doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @ParameterizedTest
    @MethodSource("guarded")
    void theWriteGuardAndTheOptInRefuseTheForgetOfThatServersLease(NodeId server,
        ErrorCode code) {
        Lease failed = stored(activeLease(server, OTHER).failed(OTHER_NODE));

        ToolError error = refusal(forget(failed.leaseId()));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.node()).contains(server);
        assertNothingSent();
        assertThat(onlyLease()).isEqualTo(failed);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(auditLog.records.get(0).node()).isEqualTo(server.value());
    }

    // --- failures of the reset -------------------------------------------------------------------

    @Test
    void aFailedResetKeepsTheLeaseAndRecordsTheAttempt() {
        Lease open = stored(activeLease(DEV, SELF));
        port.resetFailure = unreachable();

        LogLevelResult.Outcome result = execute(reset(DEV));

        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(lease.generation()).isEqualTo(open.generation() + 1);
        assertThat(lease.lastAttempt()).contains(new LeaseAttempt(NOW.plus(RUN),
            AuditOutcome.FAILED, Optional.of(ErrorCode.UNREACHABLE),
            Optional.of(LeaseReason.NODE_UNREACHABLE)));
        assertThat(lease.expiresAt()).isEqualTo(open.expiresAt());

        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.FAILED);
        assertThat(result.leaseId()).contains(open.leaseId());
        assertThat(result.leaseOpen()).isTrue();
        assertThat(result.expiresAt()).contains(EXPIRY);
        assertThat(result.message()).isEqualTo("Resetting the temporary log levels on INUBIT node"
            + " node1 failed (UNREACHABLE: StartCLI could not reach dev/node1); the levels may"
            + " stay raised: lease " + open.leaseId() + " stays open and the MCP server resets"
            + " node1 at its expiry (" + EXPIRY + ")");
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void aFailedResetOfAFailedLeaseKeepsItForTheRetries() {
        stored(activeLease(DEV, SELF).failed(OTHER_NODE));
        port.resetFailure = unreachable();

        LogLevelResult.Outcome result = execute(reset(DEV));

        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.lastAttempt().orElseThrow().code()).contains(ErrorCode.UNREACHABLE);
        assertThat(result.message()).endsWith("stays open and the MCP server retries the reset of"
            + " node1 at least every minute");
    }

    @Test
    void aFailedResetWithoutALeaseSaysNoLeaseWasOpen() {
        port.resetFailure = unreachable();

        LogLevelResult.Outcome result = execute(reset(DEV));

        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.FAILED);
        assertThat(result.leaseOpen()).isFalse();
        assertThat(result.message()).endsWith("; the levels may stay raised: no lease was open for"
            + " dev/node1, so the MCP server does not retry");
        assertThat(leasesFile()).doesNotExist();
    }

    @Test
    void aResetTheLeaseCannotRecordIsExecutedAndSaysTheLeaseStaysOpen() {
        Lease open = stored(activeLease(DEV, SELF));
        files.failingWrites.add(1);

        LogLevelResult.Outcome result = execute(reset(DEV));

        assertThat(port.calls).contains("resetAll");
        assertThat(onlyLease()).isEqualTo(open);
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.EXECUTED);
        assertThat(result.leaseOpen()).isTrue();
        assertThat(result.expiresAt()).contains(EXPIRY);
        assertThat(result.message()).isEqualTo("Every temporary log level on INUBIT node node1"
            + " was reset, also other users', but lease " + open.leaseId() + " could not record"
            + " the end and stays open: the MCP server resets node1 at its expiry ("
            + EXPIRY + ")");
    }

    @Test
    void anUnreadableLeaseStoreStillResetsTheNodeAndSaysNoLeaseCouldBeUpdated()
        throws IOException {
        Files.createDirectories(directory());
        Files.writeString(leasesFile(), "not json");

        LogLevelResult.Outcome result = execute(reset(DEV));

        assertThat(port.calls).containsExactly("answeringNode 8.1", "resetAll");
        assertThat(result.leaseOpen()).isFalse();
        assertThat(result.message()).startsWith("Every temporary log level on INUBIT node node1"
            + " was reset, also other users'; no lease could be updated: the lease store cannot"
            + " be read (");
        assertThat(Files.readString(leasesFile())).isEqualTo("not json");
    }

    @Test
    void anUnwritablePendingRecordSendsNoReset() {
        auditLog.failing = true;

        ToolError error = refusal(reset(DEV));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(port.raisedOrReset()).isEmpty();
    }

    @Test
    void aNodeLockHeldByAnotherInstanceRefusesTheResetAndSendsNothing() throws Exception {
        ToolError error = whileAnotherInstanceHoldsTheLock(DEV, () -> refusal(reset(DEV)));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("Another log-level change for dev/node1 is running");
        assertThat(port.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- US4-9: forget ---------------------------------------------------------------------------

    @Test
    void aClientConfirmedForgetEndsTheFailedLeaseWithoutContactingInubit() throws Exception {
        Lease failed = stored(activeLease(DEV, OTHER).failed(OTHER_NODE));

        LogLevelResult.Outcome result = execute(forget(failed.leaseId()));

        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.FORGOTTEN);
        assertThat(lease.endedAt()).contains(NOW);
        assertThat(lease.generation()).isEqualTo(failed.generation() + 1);

        assertThat(result.node()).isEqualTo(DEV);
        assertThat(result.inubitNode()).isEqualTo("node1");
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.EXECUTED);
        assertThat(result.leaseId()).contains(failed.leaseId());
        assertThat(result.expiresAt()).isEmpty();
        assertThat(result.message()).isEqualTo("Lease " + failed.leaseId() + " ended as FORGOTTEN"
            + " without contacting INUBIT; the level may still be raised on node1 until its"
            + " restart");
        assertThat(result.warnings()).containsExactly(
            "The temporary log level may still be raised on INUBIT node node1 until its restart");

        assertNothingSent();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        AuditRecord executed = auditLog.records.get(1);
        assertThat(executed.node()).isEqualTo("dev/node1");
        assertThat(executed.account()).contains("jdoe");
        assertThat(executed.inputs()).containsExactly(
            Map.entry("leaseId", failed.leaseId().toString()), Map.entry("forget", "true"));
        assertThat(lockFree(DEV)).isTrue();
    }

    @Test
    void aServerConfirmedForgetFirstIssuesAChallengeThenForgets() {
        Lease failed = stored(activeLease(TEST, OTHER).failed(OTHER_NODE));

        LogLevelResult.Challenge challenge =
            (LogLevelResult.Challenge) call(forget(failed.leaseId()));

        assertThat(challenge.preview()).isEqualTo(new LogLevelResult.ResetPreview(TEST, "node1",
            Optional.of(LeaseSummary.of(failed))));
        assertThat(challenge.message()).isEqualTo("Nothing was changed. Call"
            + " reset_temporary_log_level again with confirmationCode before " + NOW.plus(TTL)
            + " to end lease " + failed.leaseId() + " as FORGOTTEN without contacting INUBIT;"
            + " the level may still be raised on node1 until its restart");
        assertThat(onlyLease()).isEqualTo(failed);

        execute(confirmed(forget(failed.leaseId()), challenge.confirmationCode()));

        assertThat(onlyLease().state()).isEqualTo(LeaseState.FORGOTTEN);
        assertNothingSent();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @ParameterizedTest
    @EnumSource(value = LeaseState.class, names = {"ACTIVE", "RESET"})
    void onlyAFailedLeaseCanBeForgotten(LeaseState state) {
        Lease active = activeLease(DEV, SELF);
        Lease lease = stored(state == LeaseState.ACTIVE ? active : active.reset(NOW));

        ToolError error = refusal(forget(lease.leaseId()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Lease " + lease.leaseId() + " is " + state
            + "; only a lease whose reset failed can be forgotten");
        assertNothingSent();
        assertThat(onlyLease()).isEqualTo(lease);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void theGuardsAreCheckedBeforeTheLeaseState() {
        Lease active = stored(activeLease(OFF, SELF));

        ToolError error = refusal(forget(active.leaseId()));

        assertThat(error.code()).as("D-15: the guards first, then the state")
            .isEqualTo(ErrorCode.WRITE_DISABLED);
        assertNothingSent();
        assertThat(onlyLease()).isEqualTo(active);
    }

    @Test
    void aForgetAgainstAnUnreadableLeaseStoreIsRefusedAndTheStoreStaysAsItIs()
        throws IOException {
        Files.createDirectories(directory());
        Files.writeString(leasesFile(), "not json");

        ToolError error = refusal(forget(UUID.fromString("3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01")));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).startsWith("The lease store ");
        assertNothingSent();
        assertThat(Files.readString(leasesFile())).isEqualTo("not json");
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void anUnknownLeaseCannotBeForgotten() {
        UUID unknown = UUID.fromString("3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01");

        ToolError error = refusal(forget(unknown));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("No lease with id " + unknown + " is stored");
        assertNothingSent();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(auditLog.records.get(0).node()).isEqualTo(LogLevelAudit.NO_NODE);
    }

    @Test
    void theLeaseOfAServerNoLongerConfiguredCannotBeForgotten() {
        Lease failed = stored(activeLease(GONE, OTHER).failed(OTHER_NODE));

        ToolError error = refusal(forget(failed.leaseId()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Lease " + failed.leaseId() + " of acme/node9 cannot"
            + " be forgotten: acme/node9 is no longer configured; the lease ends automatically 7"
            + " days after its expiry, on 2026-11-19");
        assertNothingSent();
        assertThat(onlyLease()).isEqualTo(failed);
        assertThat(auditLog.records.get(0).node()).isEqualTo("acme/node9");
    }

    @Test
    void aForgetWhileAnotherInstanceHoldsTheNodeLockIsRefusedAndTheLeaseStays()
        throws Exception {
        Lease failed = stored(activeLease(DEV, OTHER).failed(OTHER_NODE));

        ToolError error = whileAnotherInstanceHoldsTheLock(DEV,
            () -> refusal(forget(failed.leaseId())));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("Another log-level change for dev/node1 is running");
        assertThat(onlyLease()).isEqualTo(failed);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void aLeaseChangedBetweenTheConfirmationAndTheForgetIsNotForgotten() {
        Lease failed = stored(activeLease(DEV, OTHER).failed(OTHER_NODE));
        // another instance takes the lease over while this call writes its PENDING record
        auditLog.onPending = () -> store.update(leases -> List.of(leases.get(0)
            .takenOverBy(SELF)), CLI_TIMEOUT);

        ToolError error = refusal(forget(failed.leaseId()));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("Lease " + failed.leaseId() + " changed since the"
            + " confirmation (generation " + failed.generation() + ", now "
            + (failed.generation() + 1) + "); nothing was forgotten");
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.owner()).isEqualTo(SELF);
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
        assertNothingSent();
    }

    @Test
    void aLeaseChangedBetweenThePreviewAndTheConfirmationIsNotForgotten() {
        Lease failed = stored(activeLease(TEST, OTHER).failed(OTHER_NODE));
        String code = challenge(forget(failed.leaseId()));
        Lease taken = stored(failed.takenOverBy(SELF));

        ToolError error = refusal(confirmed(forget(failed.leaseId()), code));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).startsWith("The state of test/node1 changed since the preview");
        assertThat(onlyLease()).isEqualTo(taken);
        assertNothingSent();
    }

    // --- input combinations (D-15) ---------------------------------------------------------------

    static Stream<Arguments> invalidCombinations() {
        String id = "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01";
        return Stream.of(
            Arguments.of(Optional.of("dev/node1"), Optional.of(id), Optional.of(true)),
            Arguments.of(Optional.of("dev/node1"), Optional.of(id), Optional.empty()),
            Arguments.of(Optional.of("dev/node1"), Optional.empty(), Optional.of(true)),
            Arguments.of(Optional.empty(), Optional.empty(), Optional.of(true)),
            Arguments.of(Optional.empty(), Optional.of(id), Optional.empty()),
            Arguments.of(Optional.empty(), Optional.of(id), Optional.of(false)),
            Arguments.of(Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @ParameterizedTest
    @MethodSource("invalidCombinations")
    void everyOtherInputCombinationIsInvalid(Optional<String> node, Optional<String> leaseId,
        Optional<Boolean> forget) {
        ToolError error = refusal(new ResetLevelRequest(node, leaseId, forget, Optional.empty(),
            Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo(
            "Invalid input: expected either node, or leaseId with forget: true");
        assertNothingSent();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void anInvalidLeaseIdIsRefusedWithoutEchoingIt() {
        ToolError error = refusal(new ResetLevelRequest(Optional.empty(),
            Optional.of("3F2B-not-a-lease"), Optional.of(true), Optional.empty(),
            Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo(
            "Invalid leaseId: expected the id of a lease (a lowercase UUID)");
        assertNothingSent();
    }

    @Test
    void anInvalidConfirmationCodeIsRefusedForBothForms() {
        Lease failed = stored(activeLease(DEV, OTHER).failed(OTHER_NODE));

        for (ResetLevelRequest request : List.of(confirmed(reset(DEV), "short"),
            confirmed(forget(failed.leaseId()), "short"))) {
            ToolError error = refusal(request);

            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.message()).isEqualTo(
                "Invalid confirmationCode: expected the 22-character code of a preview");
        }
        assertNothingSent();
        assertThat(onlyLease()).isEqualTo(failed);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private LogLevelService service() {
        NodeVersions versions = new NodeVersions(clock, node -> {
            gateLookups.add(node);
            return VersionSetting.V8_1;
        }, node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), MATRIX);
        WriteGuard guard = new WriteGuard(new TargetResolver(List.of(TEST, DEV, PROD, OFF, ACME)),
            policies::get, gateways, gate);
        ProcessLiveness liveness = new ProcessLiveness() {
            @Override
            public LeaseOwner self() {
                return SELF;
            }

            @Override
            public boolean alive(LeaseOwner owner) {
                return true;
            }
        };
        return new LogLevelService(guard, optedIn::contains, gateways, challenges, store,
            new NodeLogLevelLocks(directory(), PrivateFiles.forHost()), liveness,
            new LogLevelAudit(auditLog, clock, "acme", UUID::randomUUID), node -> CLI_TIMEOUT,
            clock, nanoTime::get);
    }

    private static ResetLevelRequest reset(NodeId server) {
        return new ResetLevelRequest(Optional.of(server.value()), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ResetLevelRequest forget(UUID leaseId) {
        return new ResetLevelRequest(Optional.empty(), Optional.of(leaseId.toString()),
            Optional.of(true), Optional.empty(), Optional.empty());
    }

    private static ResetLevelRequest confirmed(ResetLevelRequest request, String code) {
        return new ResetLevelRequest(request.node(), request.leaseId(), request.forget(),
            Optional.of(code), request.mcpClient());
    }

    private LogLevelResult call(ResetLevelRequest request) {
        try {
            LogLevelResult result = service().reset(request);
            results.add(result);
            return result;
        } catch (ToolErrorException e) {
            errors.add(e.error());
            throw e;
        }
    }

    private LogLevelResult.Outcome execute(ResetLevelRequest request) {
        return (LogLevelResult.Outcome) call(request);
    }

    private String challenge(ResetLevelRequest request) {
        return ((LogLevelResult.Challenge) call(request)).confirmationCode();
    }

    private ToolError refusal(ResetLevelRequest request) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> call(request));
        assertThat(exception).as("expected a refusal").isNotNull();
        return exception.error();
    }

    /** No lookup, no StartCLI run, no gateway, no version gate. */
    private void assertNothingSent() {
        assertThat(port.calls).as("port calls").isEmpty();
        assertThat(gateways.calls).as("gateway calls").isEmpty();
        assertThat(gateLookups).as("gate calls").isEmpty();
    }

    private List<AuditOutcome> outcomes() {
        return auditLog.records.stream().map(AuditRecord::outcome).toList();
    }

    private Path directory() {
        return LeaseStore.defaultDirectory(home, "acme");
    }

    private Path leasesFile() {
        return directory().resolve(LeaseStore.FILE);
    }

    private Lease onlyLease() {
        List<Lease> leases = store.read();
        assertThat(leases).hasSize(1);
        return leases.get(0);
    }

    private Lease stored(Lease lease) {
        store.update(leases -> List.of(lease), CLI_TIMEOUT);
        return lease;
    }

    private static Lease activeLease(NodeId server, LeaseOwner owner) {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        loggers.put(new LoggerName(ORDERS), TemporaryLevel.DEBUG);
        return new Lease(UUID.randomUUID(), 1, server, "node1", loggers,
            NOW.minus(Duration.ofMinutes(5)), EXPIRY, owner, LeaseState.ACTIVE,
            UUID.randomUUID(), Optional.empty(), Optional.empty());
    }

    private static ToolErrorException unreachable() {
        return new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
            "StartCLI could not reach dev/node1", "down", "retry").withNode(DEV));
    }

    /** Whether another instance of the profile could take the node lock of {@code server}. */
    private boolean lockFree(NodeId server) throws Exception {
        NodeLogLevelLocks other = new NodeLogLevelLocks(directory(), PrivateFiles.forHost());
        return CompletableFuture.supplyAsync(() -> {
            try (var held = other.acquire(server, Duration.ofMillis(200))) {
                return true;
            } catch (ToolErrorException e) {
                return false;
            }
        }).get();
    }

    private <T> T whileAnotherInstanceHoldsTheLock(NodeId server, Supplier<T> action)
        throws Exception {
        NodeLogLevelLocks other = new NodeLogLevelLocks(directory(), PrivateFiles.forHost());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (var lock = other.acquire(server, Duration.ofSeconds(5))) {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        held.await();
        try {
            return action.get();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    /** Records every lookup and StartCLI run; each run advances the clock by {@link #RUN}. */
    private final class FakePort implements LogLevelPort {

        final List<String> calls = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        String answering = "node1";
        RuntimeException resetFailure;

        @Override
        public String answeringNode(InubitVersion version, long deadlineNanos) {
            calls.add("answeringNode " + version.raw());
            return answering;
        }

        @Override
        public void raise(TemporaryLevel level, LoggerName name, InubitVersion version,
            Duration timeout) {
            throw new AssertionError("a reset raises nothing");
        }

        @Override
        public void resetAll(InubitVersion version, Duration timeout) {
            calls.add("resetAll");
            timeouts.add(timeout);
            clock.advance(RUN);
            if (resetFailure != null) {
                throw resetFailure;
            }
        }

        List<String> raisedOrReset() {
            return calls.stream().filter(call -> !call.startsWith("answeringNode")).toList();
        }
    }

    private final class FakeGateways implements GatewayFactory {

        final List<String> calls = new ArrayList<>();

        @Override
        public CredentialState prepareCredentials(NodeId server, Duration bound) {
            calls.add("prepareCredentials " + server);
            return CredentialState.READY;
        }

        @Override
        public LogLevelPort logLevel(NodeId server) {
            calls.add("logLevel " + server);
            return port;
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            return new ProcessControlPort() {
                @Override
                public void checkAvailable() {
                    calls.add("checkAvailable " + server);
                }

                @Override
                public String restart(String processId, WritePermit permit) {
                    throw new AssertionError("not expected");
                }

                @Override
                public String kill(String processId, WritePermit permit) {
                    throw new AssertionError("not expected");
                }
            };
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public InventoryPort inventory(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            throw new AssertionError("not expected");
        }
    }

    /**
     * Records every appended audit record; fails every append while {@code failing}; runs
     * {@code onPending} once when a {@code PENDING} record is appended.
     */
    private static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new ArrayList<>();
        boolean failing;
        Runnable onPending;

        @Override
        public void append(AuditRecord record) {
            if (failing) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
            records.add(record);
            if (record.outcome() == AuditOutcome.PENDING && onPending != null) {
                Runnable once = onPending;
                onPending = null;
                once.run();
            }
        }
    }

    /**
     * The host's file system, except that the lease store's writes (its private temporary files)
     * with a number in {@link #failingWrites} fail; counted from 1 after the fixture's writes.
     */
    private static final class SwitchableFiles implements PrivateFileSystem {

        final Set<Integer> failingWrites = new HashSet<>();
        private final PrivateFileSystem host = PrivateFiles.forHost();
        private int writes;

        @Override
        public Path createFile(Path file) throws IOException {
            if (file.getFileName().toString().endsWith(".tmp") && !failingWrites.isEmpty()
                && failingWrites.contains(++writes)) {
                throw new IOException("injected failure of write " + writes);
            }
            return host.createFile(file);
        }

        @Override
        public Path createDirectory(Path directory) throws IOException {
            return host.createDirectory(directory);
        }

        @Override
        public boolean createDirectories(Path directory) throws IOException {
            return host.createDirectories(directory);
        }

        @Override
        public Path createTempDirectory(Path parent, String prefix) throws IOException {
            return host.createTempDirectory(parent, prefix);
        }

        @Override
        public void tightenOnPosix(Path path) throws IOException {
            host.tightenOnPosix(path);
        }

        @Override
        public List<String> check(Path path) throws IOException {
            return host.check(path);
        }

        @Override
        public Optional<String> othersMayAccess(Path path) throws IOException {
            return host.othersMayAccess(path);
        }

        @Override
        public boolean ownedByCurrentUser(Path path) throws IOException {
            return host.ownedByCurrentUser(path);
        }

        @Override
        public Optional<String> currentUserSid() {
            return host.currentUserSid();
        }

        @Override
        public FileChannel openLockFile(Path file) throws IOException {
            return host.openLockFile(file);
        }

        @Override
        public FileChannel openLockFileNoFollow(Path file) throws IOException {
            return host.openLockFileNoFollow(file);
        }
    }
}
