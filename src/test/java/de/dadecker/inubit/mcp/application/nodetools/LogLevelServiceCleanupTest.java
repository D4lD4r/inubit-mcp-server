package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.application.nodetools.LogLevelAudit.Trigger;
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
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T051 (FR-043, research D-16 "Cleanup of one lease", D-22):
 * {@link LogLevelService#cleanup}, the automatic reset of one lease the lease scheduler or the
 * shutdown decided on. The fixture is that of {@link LogLevelServiceResetTest}: a fake
 * {@link LogLevelPort}, the real {@link LeaseStore} and {@link NodeLogLevelLocks} in a temporary
 * home, the real {@link WriteGuard} over the version gate (every server pinned to 8.1 except
 * {@link #OLD}, every version-setting lookup recorded) and the real {@link LogLevelAudit}.
 */
class LogLevelServiceCleanupTest {

    /** Write access and the opt-in. */
    private static final NodeId TEST = NodeId.parse("test/node1");
    /** {@code write.enabled: false}. */
    private static final NodeId OFF = NodeId.parse("test/node2");
    /** Pinned to 6.1, where {@code log_level_write} is not supported. */
    private static final NodeId OLD = NodeId.parse("test/node3");
    /** Production without {@code write.productionOptIn}. */
    private static final NodeId PROD = NodeId.parse("prod/node1");
    /** Write access, but no {@code unsupported.logLevelWrite}. */
    private static final NodeId ACME = NodeId.parse("acme/node1");
    /** No longer configured. */
    private static final NodeId GONE = NodeId.parse("acme/node9");

    private static final Instant NOW = Instant.parse("2026-11-12T08:25:30Z");
    /** The decided leases expired a minute ago. */
    private static final Instant EXPIRED = NOW.minus(Duration.ofMinutes(1));
    private static final Duration CLI_TIMEOUT = Duration.ofMillis(500);
    /** How long each fake StartCLI reset takes. */
    private static final Duration RUN = Duration.ofSeconds(3);
    private static final LeaseOwner SELF =
        new LeaseOwner(4711, Optional.of(Instant.parse("2026-11-12T07:58:02.114Z")));
    private static final LeaseOwner OTHER = new LeaseOwner(4712, Optional.empty());
    private static final String ORDERS = "com.inubit.ibis.acme.Orders";
    private static final String INVOICES = "com.inubit.ibis.acme.Invoices";

    /** {@code log_level_write} supported on 8.1 only. */
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
    private final AtomicLong nanoTime = new AtomicLong(7_000_000_000L);
    /** Every version-setting lookup of the version gate. */
    private final List<NodeId> gateLookups = new CopyOnWriteArrayList<>();
    private final Map<NodeId, WritePolicy> policies = Map.of(
        TEST, policy(TEST, false, true),
        OFF, policy(OFF, false, false),
        OLD, policy(OLD, false, true),
        PROD, policy(PROD, true, true),
        ACME, policy(ACME, false, true));
    /** The servers whose group sets {@code unsupported.logLevelWrite: true}. */
    private final Set<NodeId> optedIn = Set.of(TEST, OFF, OLD, PROD);
    private LeaseStore store;

    private final Logger logger = (Logger) LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    private static WritePolicy policy(NodeId server, boolean production, boolean enabled) {
        return new WritePolicy(server, production, enabled, false, Confirmation.SERVER,
            Duration.ofMinutes(5), Optional.of("jdoe"));
    }

    @BeforeEach
    void setUp() {
        store = new LeaseStore(directory(), PrivateFiles.forHost(), clock);
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

    /** Leak check of every cleanup: no credential in a log line or an audit record. */
    @AfterEach
    void noLeak() {
        List<String> texts = new ArrayList<>();
        appender.list.forEach(event -> texts.add(event.getFormattedMessage()));
        auditLog.records.forEach(record -> texts.add(record.toString()));
        store.read().forEach(lease -> texts.add(lease.toString()));
        texts.forEach(text -> assertThat(text)
            .doesNotContain(TestSecrets.CONFIGURED_PASSWORD)
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE));
    }

    // --- the reset -------------------------------------------------------------------------------

    @Test
    void anExpiredLeaseIsResetAndEndsWithAPendingAndAnExecutedRecord() throws Exception {
        Lease decided = stored(activeLease(TEST));

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).containsExactly("answeringNode 8.1", "resetAll");
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET);
        assertThat(lease.endedAt()).contains(NOW.plus(RUN));
        assertThat(lease.generation()).isEqualTo(decided.generation() + 1);
        assertThat(lease.loggers()).isEqualTo(decided.loggers());

        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(auditLog.records).extracting(AuditRecord::auditId)
            .containsOnly(auditLog.records.get(0).auditId());
        assertThat(auditLog.records).allSatisfy(record -> {
            assertThat(record.capability()).isEqualTo("reset_temporary_log_level");
            assertThat(record.node()).isEqualTo("test/node1");
            assertThat(record.account()).contains("jdoe");
            assertThat(record.inputs()).containsExactly(Map.entry("trigger", "expiry"),
                Map.entry("leaseId", decided.leaseId().toString()));
        });
        assertThat(auditLog.records.get(0).reason()).isEmpty();
        assertThat(auditLog.records.get(1).reason()).contains("every temporary log level on"
            + " INUBIT node node1 was reset; lease " + decided.leaseId() + " ended");
        assertThat(gateways.calls).containsExactly("checkAvailable test/node1",
            "prepareCredentials test/node1", "logLevel test/node1");
        assertThat(lockFree(TEST)).as("the node lock is released").isTrue();
    }

    @Test
    void aFailedLeaseIsRetriedAndEnds() {
        Lease decided = stored(activeLease(TEST).failed(new LeaseAttempt(EXPIRED,
            AuditOutcome.FAILED, Optional.of(ErrorCode.UNREACHABLE),
            Optional.of(LeaseReason.NODE_UNREACHABLE))));

        service().cleanup(decided, Trigger.RETRY);

        assertThat(port.calls).containsExactly("answeringNode 8.1", "resetAll");
        assertThat(onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(auditLog.records.get(0).inputs()).containsEntry("trigger", "retry");
    }

    @Test
    void theLookupAndTheResetShareOneDeadlineOfCliTimeout() {
        Lease decided = stored(activeLease(TEST));
        port.lookupTakes = Duration.ofMillis(200);

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.deadlines).containsExactly(7_000_000_000L + CLI_TIMEOUT.toNanos());
        assertThat(port.timeouts).containsExactly(Duration.ofMillis(300));
    }

    @Test
    void aLookupThatUsesUpTheDeadlineSendsNoResetAndTheLeaseFails() {
        Lease decided = stored(activeLease(TEST));
        port.lookupTakes = CLI_TIMEOUT;

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).containsExactly("answeringNode 8.1");
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.lastAttempt()).contains(new LeaseAttempt(NOW, AuditOutcome.FAILED,
            Optional.of(ErrorCode.TIMEOUT), Optional.of(LeaseReason.NODE_UNREACHABLE)));
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    /** Owner decision (FR-043): the cleanup is no write tool call. */
    @ParameterizedTest
    @ValueSource(strings = {"test/node2", "acme/node1", "prod/node1"})
    void theCleanupRunsAlsoWithWriteToolsOff(String server) {
        Lease decided = stored(activeLease(NodeId.parse(server)));

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).containsExactly("answeringNode 8.1", "resetAll");
        assertThat(onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    // --- cleanup preconditions -------------------------------------------------------------------

    @Test
    void aMissingClientFailsTheLeaseWithOneRefusedRecordAndSendsNothing() {
        Lease decided = stored(activeLease(TEST));
        gateways.withoutClient.add(TEST);

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).isEmpty();
        assertPreconditionFailed(decided, ErrorCode.CLI_UNAVAILABLE);
        assertThat(auditLog.records.get(0).reason()).contains("cleanup precondition not met:"
            + " CLI_UNAVAILABLE: no StartCLI client for test/node1");
    }

    @Test
    void missingCredentialsFailTheLeaseWithOneRefusedRecordAndSendNothing() {
        Lease decided = stored(activeLease(TEST));
        gateways.withoutCredentials.add(TEST);

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).isEmpty();
        assertThat(gateways.calls).doesNotContain("logLevel test/node1");
        assertPreconditionFailed(decided, ErrorCode.AUTH_FAILED);
    }

    @Test
    void anUnsupportedVersionFailsTheLeaseAsAPrecondition() {
        Lease decided = stored(activeLease(OLD));

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).isEmpty();
        assertPreconditionFailed(decided, ErrorCode.UNSUPPORTED_VERSION);
    }

    @Test
    void anUnconfiguredServerFailsTheLeaseAsAPrecondition() {
        Lease decided = stored(activeLease(GONE));

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).isEmpty();
        assertPreconditionFailed(decided, ErrorCode.TARGET_UNKNOWN);
        assertThat(auditLog.records.get(0).account()).isEmpty();
    }

    @Test
    void anUnwritableRefusedRecordIsLoggedAndRecordedAsAuditNotWritable() {
        Lease decided = stored(activeLease(TEST));
        gateways.withoutClient.add(TEST);
        auditLog.failing = true;

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).isEmpty();
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.lastAttempt()).contains(new LeaseAttempt(NOW, AuditOutcome.REFUSED,
            Optional.of(ErrorCode.CLI_UNAVAILABLE), Optional.of(LeaseReason.AUDIT_NOT_WRITABLE)));
        assertThat(auditLog.records).isEmpty();
        assertThat(lines(Level.ERROR)).isNotEmpty();
    }

    // --- fail closed: PENDING --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnwritablePendingRecordSendsNothingAndKeepsTheState(boolean failedBefore) {
        Lease active = activeLease(TEST);
        Lease decided = stored(failedBefore ? active.failed(new LeaseAttempt(EXPIRED,
            AuditOutcome.FAILED, Optional.of(ErrorCode.UNREACHABLE),
            Optional.of(LeaseReason.NODE_UNREACHABLE))) : active);
        auditLog.failing = true;

        service().cleanup(decided, failedBefore ? Trigger.RETRY : Trigger.EXPIRY);

        assertThat(port.calls).as("not even the own-node lookup").isEmpty();
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(decided.state());
        assertThat(lease.generation()).isEqualTo(decided.generation() + 1);
        assertThat(lease.lastAttempt()).contains(new LeaseAttempt(NOW, AuditOutcome.FAILED,
            Optional.empty(), Optional.of(LeaseReason.AUDIT_NOT_WRITABLE)));
        assertThat(auditLog.records).isEmpty();
        assertThat(lines(Level.ERROR)).anySatisfy(line -> assertThat(line).contains("PENDING")
            .contains(decided.leaseId().toString()));
    }

    // --- the outcome -----------------------------------------------------------------------------

    @Test
    void anotherAnsweringNodeFailsTheLeaseAndRunsNoReset() {
        Lease decided = stored(activeLease(TEST));
        port.answering = "node2";

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).containsExactly("answeringNode 8.1");
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.lastAttempt()).contains(new LeaseAttempt(NOW, AuditOutcome.REFUSED,
            Optional.empty(), Optional.of(LeaseReason.OTHER_NODE_ANSWERS)));
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
        assertThat(auditLog.records.get(1).reason()).contains("other node answers: INUBIT node"
            + " node2 answers for test/node1, but the lease was set on node1");
    }

    @Test
    void aFailedResetFailsTheLeaseAsNodeUnreachable() {
        Lease decided = stored(activeLease(TEST));
        port.resetFailure = new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
            "StartCLI could not reach test/node1", "down", "retry").withNode(TEST));

        service().cleanup(decided, Trigger.EXPIRY);

        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.generation()).isEqualTo(decided.generation() + 1);
        assertThat(lease.lastAttempt()).contains(new LeaseAttempt(NOW.plus(RUN),
            AuditOutcome.FAILED, Optional.of(ErrorCode.UNREACHABLE),
            Optional.of(LeaseReason.NODE_UNREACHABLE)));
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(auditLog.records.get(1).reason()).contains("node unreachable: UNREACHABLE:"
            + " StartCLI could not reach test/node1");
    }

    @Test
    void aLeaseChangedBeforeTheFinalStepKeepsItsStateAndTheRecordSaysSo() {
        Lease decided = stored(activeLease(TEST));
        port.onReset = () -> store.update(leases -> leases.stream()
            .map(lease -> lease.takenOverBy(OTHER)).toList(), CLI_TIMEOUT);

        service().cleanup(decided, Trigger.EXPIRY);

        assertThat(port.calls).containsExactly("answeringNode 8.1", "resetAll");
        Lease lease = onlyLease();
        assertThat(lease).isEqualTo(decided.takenOverBy(OTHER));
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(auditLog.records.get(1).reason().orElseThrow())
            .endsWith("; lease changed meanwhile, the outcome was not recorded");
    }

    // --- a set during the reset decision (two-instance edge case) --------------------------------

    @Test
    void aSetMergedAfterTheDecisionStopsTheCleanupBeforeAnythingIsSent() {
        Lease decided = stored(activeLease(TEST));
        Lease merged = stored(decided.merge(Map.of(new LoggerName(INVOICES),
            TemporaryLevel.TRACE), NOW, Duration.ofMinutes(10), OTHER, UUID.randomUUID()));

        service().cleanup(decided, Trigger.EXPIRY);

        assertNothingContacted();
        Lease lease = onlyLease();
        assertThat(lease).isEqualTo(merged);
        assertThat(lease.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(lease.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(lease.loggers()).containsOnlyKeys(new LoggerName(ORDERS),
            new LoggerName(INVOICES));
        assertThat(auditLog.records).isEmpty();
    }

    /** A reset or forget ended the lease since the decision: its generation moved on. */
    @Test
    void aLeaseEndedSinceTheDecisionIsNotResetAgain() {
        Lease decided = activeLease(TEST);
        Lease ended = stored(decided.reset(EXPIRED));

        service().cleanup(decided, Trigger.EXPIRY);

        assertNothingContacted();
        assertThat(onlyLease()).isEqualTo(ended);
        assertThat(auditLog.records).isEmpty();
    }

    /** A cleanup decided on an ended lease (same generation, not open) sends nothing. */
    @Test
    void aCleanupDecidedOnAnEndedLeaseSendsNothing() {
        Lease ended = stored(activeLease(TEST).reset(EXPIRED));

        service().cleanup(ended, Trigger.EXPIRY);

        assertNothingContacted();
        assertThat(onlyLease()).isEqualTo(ended);
        assertThat(auditLog.records).isEmpty();
    }

    @Test
    void aSetHoldingTheNodeLockSkipsTheCleanupWithoutContactingInubit() throws Exception {
        Lease decided = stored(activeLease(TEST));
        NodeLogLevelLocks other = new NodeLogLevelLocks(directory(), PrivateFiles.forHost());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (var lock = other.acquire(TEST, Duration.ofSeconds(5))) {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        held.await();
        try {
            service().cleanup(decided, Trigger.EXPIRY);
        } finally {
            release.countDown();
            holder.join();
        }

        assertNothingContacted();
        assertThat(onlyLease()).isEqualTo(decided);
        assertThat(auditLog.records).isEmpty();
        assertThat(lines(Level.INFO)).anySatisfy(line -> assertThat(line).isEqualTo("Cleanup of"
            + " lease " + decided.leaseId() + " on test/node1 skipped: Another log-level change"
            + " for test/node1 is running"));
        assertThat(lines(Level.WARN)).as("busy is no warning").isEmpty();
    }

    /** A lock file that cannot be used is no busy lock: skipped too, but with a WARN. */
    @Test
    void anUnusableNodeLockFileSkipsTheCleanupWithAWarning() throws IOException {
        Lease decided = stored(activeLease(TEST));
        Files.createDirectories(directory().resolve(NodeLogLevelLocks.DIRECTORY)
            .resolve("test.node1.lock"));

        service().cleanup(decided, Trigger.EXPIRY);

        assertNothingContacted();
        assertThat(onlyLease()).isEqualTo(decided);
        assertThat(auditLog.records).isEmpty();
        assertThat(lines(Level.WARN)).anySatisfy(line -> assertThat(line).startsWith("Cleanup"
            + " of lease " + decided.leaseId() + " on test/node1 skipped: the node lock cannot"
            + " be used ("));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private LogLevelService service() {
        NodeVersions versions = new NodeVersions(clock, node -> {
            gateLookups.add(node);
            return node.equals(OLD) ? VersionSetting.V6_1 : VersionSetting.V8_1;
        }, node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), MATRIX);
        WriteGuard guard = new WriteGuard(new TargetResolver(List.of(TEST, OFF, OLD, PROD, ACME)),
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
        return new LogLevelService(guard, optedIn::contains, gateways,
            new WriteChallengeRegistry(clock), store,
            new NodeLogLevelLocks(directory(), PrivateFiles.forHost()), liveness,
            new LogLevelAudit(auditLog, clock, "acme", UUID::randomUUID), node -> CLI_TIMEOUT,
            clock, nanoTime::get);
    }

    /** {@code RESET_FAILED} {@code PRECONDITION}, one {@code REFUSED} record, nothing sent. */
    private void assertPreconditionFailed(Lease decided, ErrorCode code) {
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.generation()).isEqualTo(decided.generation() + 1);
        assertThat(lease.lastAttempt()).contains(new LeaseAttempt(NOW, AuditOutcome.REFUSED,
            Optional.of(code), Optional.of(LeaseReason.PRECONDITION)));
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(auditLog.records.get(0).reason().orElseThrow())
            .startsWith("cleanup precondition not met: " + code + ": ");
    }

    /** No lookup, no StartCLI run, no gateway, no version gate. */
    private void assertNothingContacted() {
        assertThat(port.calls).as("port calls").isEmpty();
        assertThat(gateways.calls).as("gateway calls").isEmpty();
        assertThat(gateLookups).as("gate calls").isEmpty();
    }

    private List<AuditOutcome> outcomes() {
        return auditLog.records.stream().map(AuditRecord::outcome).toList();
    }

    private List<String> lines(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private Path directory() {
        return LeaseStore.defaultDirectory(home, "acme");
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

    private static Lease activeLease(NodeId server) {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        loggers.put(new LoggerName(ORDERS), TemporaryLevel.DEBUG);
        return new Lease(UUID.randomUUID(), 1, server, "node1", loggers,
            EXPIRED.minus(Duration.ofMinutes(10)), EXPIRED, SELF, LeaseState.ACTIVE,
            UUID.randomUUID(), Optional.empty(), Optional.empty());
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

    /**
     * Records every lookup and reset; a lookup advances the nano clock by {@link #lookupTakes},
     * each reset the clock by {@link #RUN}.
     */
    private final class FakePort implements LogLevelPort {

        final List<String> calls = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        String answering = "node1";
        Duration lookupTakes = Duration.ZERO;
        RuntimeException resetFailure;
        /** Runs at the start of the reset, e.g. a change of the lease by another instance. */
        Runnable onReset = () -> { };

        @Override
        public String answeringNode(InubitVersion version, long deadlineNanos) {
            calls.add("answeringNode " + version.raw());
            deadlines.add(deadlineNanos);
            nanoTime.addAndGet(lookupTakes.toNanos());
            return answering;
        }

        @Override
        public void raise(TemporaryLevel level, LoggerName name, InubitVersion version,
            Duration timeout) {
            throw new AssertionError("a cleanup raises nothing");
        }

        @Override
        public void resetAll(InubitVersion version, Duration timeout) {
            calls.add("resetAll");
            timeouts.add(timeout);
            onReset.run();
            clock.advance(RUN);
            if (resetFailure != null) {
                throw resetFailure;
            }
        }
    }

    private final class FakeGateways implements GatewayFactory {

        final List<String> calls = new ArrayList<>();
        final Set<NodeId> withoutClient = new HashSet<>();
        final Set<NodeId> withoutCredentials = new HashSet<>();

        @Override
        public CredentialState prepareCredentials(NodeId server, Duration bound) {
            calls.add("prepareCredentials " + server);
            if (withoutCredentials.contains(server)) {
                throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                    "No login credentials for " + server, "none stored", "store them")
                    .withNode(server));
            }
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
                    if (withoutClient.contains(server)) {
                        throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                            "no StartCLI client for " + server, "not installed", "install it")
                            .withNode(server));
                    }
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

    /** Records every appended audit record; fails every append while {@code failing}. */
    private static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new ArrayList<>();
        boolean failing;

        @Override
        public void append(AuditRecord record) {
            if (failing) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
            records.add(record);
        }
    }
}
