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
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T049 (FR-040 … FR-043, FR-045, research D-14, D-19, D-20, D-22;
 * contracts/mcp-tools-delta.md "set_temporary_log_level"): {@link LogLevelService#set}, the
 * twelve steps of D-14. A fake {@link LogLevelPort} (recording every lookup and StartCLI run;
 * each run advances the fake clock by {@link #RUN}), the real {@link LeaseStore} and
 * {@link NodeLogLevelLocks} in a temporary home (writes of the store can be made to fail), the
 * real {@link WriteGuard} over the version gate (every server pinned to 8.1), the real
 * {@link WriteChallengeRegistry} and {@link LogLevelAudit} over a recording audit log.
 */
class LogLevelServiceSetTest {

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

    private static final Instant NOW = Instant.parse("2026-11-12T08:15:30Z");
    private static final Duration CLI_TIMEOUT = Duration.ofMillis(500);
    private static final Duration TTL = Duration.ofMinutes(5);
    /** How long each fake StartCLI run takes. */
    private static final Duration RUN = Duration.ofSeconds(3);
    private static final LeaseOwner SELF =
        new LeaseOwner(4711, Optional.of(Instant.parse("2026-11-12T07:58:02.114Z")));
    private static final LeaseOwner OTHER = new LeaseOwner(4712, Optional.empty());
    private static final String ORDERS = "com.inubit.ibis.acme.Orders";
    private static final String INVOICES = "com.inubit.ibis.acme.Invoices";
    private static final String RAISED_MESSAGE_END = "; then the MCP server resets every temporary"
        + " log level on node1, also other users'. Levels can be lost earlier (INUBIT restart, log"
        + " configuration reload).";

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
    private final Map<NodeId, WritePolicy> policies = Map.of(
        TEST, policy(TEST, false, true, false, Confirmation.SERVER),
        DEV, policy(DEV, false, true, false, Confirmation.CLIENT),
        PROD, policy(PROD, true, true, false, Confirmation.SERVER),
        OFF, policy(OFF, false, false, false, Confirmation.CLIENT),
        ACME, policy(ACME, false, true, false, Confirmation.CLIENT));
    /** The servers whose group sets {@code unsupported.logLevelWrite: true}. */
    private final Set<NodeId> optedIn = Set.of(TEST, DEV, PROD, OFF);
    private LeaseStore store;

    private final List<ToolError> errors = new ArrayList<>();
    private final List<LogLevelResult> results = new ArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    private static WritePolicy policy(NodeId server, boolean production, boolean enabled,
        boolean productionOptIn, Confirmation confirmation) {
        return new WritePolicy(server, production, enabled, productionOptIn, confirmation, TTL,
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
        texts.forEach(text -> assertThat(text)
            .doesNotContain(TestSecrets.CONFIGURED_PASSWORD)
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE));
    }

    // --- US4-1: the set, confirmed by the client ------------------------------------------------

    @Test
    void aClientConfirmedSetRaisesEachLoggerAndRecordsTheLeaseFromTheExecutedChange() {
        LogLevelResult.Outcome result = execute(set(DEV, "DEBUG", ORDERS, INVOICES));

        assertThat(port.calls).containsExactly("answeringNode 8.1",
            "raise DEBUG " + ORDERS, "raise DEBUG " + INVOICES);
        assertThat(port.timeouts).containsOnly(CLI_TIMEOUT);
        Instant changed = NOW.plus(RUN).plus(RUN);
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(lease.generation()).as("write-ahead, then executed").isEqualTo(2);
        assertThat(lease.server()).isEqualTo(DEV);
        assertThat(lease.inubitNode()).isEqualTo("node1");
        assertThat(lease.loggers()).containsExactlyInAnyOrderEntriesOf(Map.of(
            new LoggerName(ORDERS), TemporaryLevel.DEBUG,
            new LoggerName(INVOICES), TemporaryLevel.DEBUG));
        assertThat(lease.setAt()).as("the end of the last run").isEqualTo(changed);
        assertThat(lease.expiresAt()).as("default 10 minutes from the change")
            .isEqualTo(changed.plus(Duration.ofMinutes(10)));
        assertThat(lease.owner()).isEqualTo(SELF);
        assertThat(lease.auditId()).isEqualTo(result.auditId());

        assertThat(result.node()).isEqualTo(DEV);
        assertThat(result.inubitNode()).isEqualTo("node1");
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.EXECUTED);
        assertThat(result.leaseId()).contains(lease.leaseId());
        assertThat(result.loggers()).containsExactly(
            logger(INVOICES, TemporaryLevel.DEBUG), logger(ORDERS, TemporaryLevel.DEBUG));
        assertThat(result.expiresAt()).contains(lease.expiresAt());
        assertThat(result.leaseOpen()).isTrue();
        assertThat(result.message()).isEqualTo("Raised on INUBIT node node1 until "
            + lease.expiresAt() + RAISED_MESSAGE_END);
        assertThat(result.warnings()).isEmpty();

        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        AuditRecord executed = auditLog.records.get(1);
        assertThat(executed.auditId()).isEqualTo(result.auditId());
        assertThat(executed.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        assertThat(executed.details()).containsExactly(
            Map.entry("leaseId", lease.leaseId().toString()),
            Map.entry("inubitNode", "node1"),
            Map.entry("expiresAt", lease.expiresAt().toString()),
            Map.entry("leaseOpen", true));
        assertThat(gateways.calls).containsExactly("checkAvailable dev/node1",
            "prepareCredentials dev/node1", "logLevel dev/node1");
    }

    @Test
    void theDurationCountsFromTheExecutedChange() {
        LogLevelResult.Outcome result = execute(new SetLevelRequest(DEV.value(), List.of(ORDERS),
            "TRACE", OptionalInt.of(60), Optional.empty(), Optional.empty()));

        assertThat(result.expiresAt()).contains(NOW.plus(RUN).plus(Duration.ofMinutes(60)));
        assertThat(onlyLease().loggers()).containsExactlyEntriesOf(
            Map.of(new LoggerName(ORDERS), TemporaryLevel.TRACE));
    }

    // --- confirmation by the MCP server (D-19) ---------------------------------------------------

    @Test
    void aServerConfirmedSetFirstIssuesAChallengeAndChangesNothing() throws Exception {
        LogLevelResult first = call(set(TEST, "DEBUG", ORDERS));

        LogLevelResult.Challenge challenge = (LogLevelResult.Challenge) first;
        assertThat(challenge.confirmationCode()).matches("[A-Za-z0-9_-]{22}");
        assertThat(challenge.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(challenge.preview()).isEqualTo(new LogLevelResult.Preview(TEST, "node1",
            List.of(logger(ORDERS, TemporaryLevel.DEBUG)), 10, Optional.empty()));
        assertThat(challenge.message()).isEqualTo("Nothing was changed. Call"
            + " set_temporary_log_level again with confirmationCode before " + NOW.plus(TTL)
            + " to raise the level; the later reset restores every temporary level on node1,"
            + " also other users'");
        assertThat(port.calls).containsExactly("answeringNode 8.1");
        assertThat(leasesFile()).doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED);
        assertThat(auditLog.records.get(0).step()).isEqualTo(AuditRecord.Step.PREVIEW);
        assertThat(lockFree(TEST)).as("the node lock is released with the challenge").isTrue();
    }

    @Test
    void theRedeemedCodeExecutesAndTheExpiryCountsFromTheExecutionNotThePreview() {
        String code = challenge(set(TEST, "DEBUG", ORDERS));
        clock.advance(Duration.ofMinutes(2));

        LogLevelResult.Outcome result = execute(confirmed(set(TEST, "DEBUG", ORDERS), code));

        assertThat(port.calls).containsExactly("answeringNode 8.1", "answeringNode 8.1",
            "raise DEBUG " + ORDERS);
        Instant changed = NOW.plus(Duration.ofMinutes(2)).plus(RUN);
        assertThat(result.expiresAt()).contains(changed.plus(Duration.ofMinutes(10)));
        assertThat(onlyLease().setAt()).isEqualTo(changed);
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void anotherAnsweringNodeThanAtThePreviewIsRefusedAndNothingIsChanged() {
        String code = challenge(set(TEST, "DEBUG", ORDERS));
        port.answering = "node2";

        ToolError error = refusal(confirmed(set(TEST, "DEBUG", ORDERS), code));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("The state of test/node1 changed since the preview"
            + " (preview: INUBIT node node1, no open lease; now: INUBIT node node2, no open lease);"
            + " nothing was changed");
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(leasesFile()).doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.REFUSED);
        assertThat(auditLog.records.get(1).step()).isEqualTo(AuditRecord.Step.EXECUTE);
    }

    @Test
    void aLeaseChangedSinceThePreviewIsRefusedAndNothingIsChanged() {
        Lease open = stored(activeLease(TEST, OTHER, NOW.plus(Duration.ofMinutes(30))));
        String code = challenge(set(TEST, "DEBUG", ORDERS));
        store.update(leases -> List.of(open.takenOverBy(SELF)), CLI_TIMEOUT);

        ToolError error = refusal(confirmed(set(TEST, "DEBUG", ORDERS), code));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("The state of test/node1 changed since the preview"
            + " (preview: INUBIT node node1, lease " + open.leaseId() + " ACTIVE generation 1;"
            + " now: INUBIT node node1, lease " + open.leaseId() + " ACTIVE generation 2);"
            + " nothing was changed");
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(onlyLease()).isEqualTo(open.takenOverBy(SELF));
    }

    @Test
    void thePreviewNamesTheOpenLease() {
        Lease open = stored(activeLease(TEST, OTHER, NOW.plus(Duration.ofMinutes(30))));

        LogLevelResult.Challenge challenge = (LogLevelResult.Challenge) call(
            set(TEST, "INFO", ORDERS));

        assertThat(challenge.preview().openLease()).contains(LeaseSummary.of(open));
    }

    @Test
    void aCodeForOtherInputsIsInvalidAndNothingIsChanged() {
        String code = challenge(set(TEST, "DEBUG", ORDERS));

        ToolError error = refusal(confirmed(set(TEST, "TRACE", ORDERS), code));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(leasesFile()).doesNotExist();
    }

    // --- US4-5: input checks ---------------------------------------------------------------------

    static Stream<Arguments> invalidInputs() {
        String password = "com.inubit.ibis.x-" + TestSecrets.CONFIGURED_PASSWORD;
        return Stream.of(
            Arguments.of(List.of(), "DEBUG", OptionalInt.empty(), Optional.empty(),
                "Invalid loggers: expected 1 to 5 names below com.inubit.ibis"),
            Arguments.of(List.of(ORDERS, INVOICES, ORDERS + "1", ORDERS + "2", ORDERS + "3",
                ORDERS + "4"), "DEBUG", OptionalInt.empty(), Optional.empty(),
                "Invalid loggers: expected 1 to 5 names below com.inubit.ibis"),
            Arguments.of(List.of(ORDERS, "org.apache.Foo"), "DEBUG", OptionalInt.empty(),
                Optional.empty(), "Invalid logger 2 (a name below com.inubit.ibis)"),
            Arguments.of(List.of("com.inubit.ibis"), "DEBUG", OptionalInt.empty(),
                Optional.empty(), "Invalid logger 1 (a name below com.inubit.ibis)"),
            Arguments.of(List.of(ORDERS + "$Inner"), "DEBUG", OptionalInt.empty(),
                Optional.empty(), "Invalid logger 1 (a name below com.inubit.ibis)"),
            Arguments.of(List.of(password), "DEBUG", OptionalInt.empty(), Optional.empty(),
                "Invalid logger 1 (a name below com.inubit.ibis)"),
            Arguments.of(List.of(ORDERS, INVOICES, ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.empty(), "Logger 3 repeats an earlier logger"),
            Arguments.of(List.of(ORDERS), "FATAL", OptionalInt.empty(), Optional.empty(),
                "Invalid level: expected TRACE, DEBUG, INFO, WARN or ERROR"),
            Arguments.of(List.of(ORDERS), "debug", OptionalInt.empty(), Optional.empty(),
                "Invalid level: expected TRACE, DEBUG, INFO, WARN or ERROR"),
            Arguments.of(List.of(ORDERS), "DEBUG", OptionalInt.of(0), Optional.empty(),
                "Invalid durationMinutes: expected 1 to 60"),
            Arguments.of(List.of(ORDERS), "DEBUG", OptionalInt.of(61), Optional.empty(),
                "Invalid durationMinutes: expected 1 to 60"),
            Arguments.of(List.of(ORDERS), "DEBUG", OptionalInt.empty(), Optional.of("short"),
                "Invalid confirmationCode: expected the 22-character code of a preview"));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void anInvalidInputIsRefusedBeforeAnythingIsSent(List<String> loggers, String level,
        OptionalInt duration, Optional<String> code, String message) {
        ToolError error = refusal(new SetLevelRequest(TEST.value(), loggers, level, duration,
            code, Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo(message);
        assertNothingSent();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- US4-6: another node answers -------------------------------------------------------------

    @Test
    void anOpenLeaseOnAnotherNodeRefusesNamingBothAndChangesNothing() {
        Lease open = stored(activeLease(DEV, SELF, NOW.plus(Duration.ofMinutes(30))));
        port.answering = "node2";

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo(
            "INUBIT node node2 answers for dev/node1, but the open lease was set on node1");
        assertThat(error.node()).contains(DEV);
        assertThat(port.calls).containsExactly("answeringNode 8.1");
        assertThat(onlyLease()).isEqualTo(open);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- US4-7: write guard and opt-in -----------------------------------------------------------

    static Stream<Arguments> guarded() {
        return Stream.of(
            Arguments.of(PROD, ErrorCode.PRODUCTION_PROTECTED),
            Arguments.of(OFF, ErrorCode.WRITE_DISABLED),
            Arguments.of(ACME, ErrorCode.NOT_CONFIGURED));
    }

    @ParameterizedTest
    @MethodSource("guarded")
    void theWriteGuardAndTheOptInRefuseBeforeAnythingIsSent(NodeId server, ErrorCode code) {
        ToolError error = refusal(set(server, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.node()).contains(server);
        assertNothingSent();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void credentialsStillBeingReadAreATimeoutAndNothingIsSent() {
        gateways.state = CredentialState.PENDING;

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).contains("cliTimeout");
        assertThat(port.calls).isEmpty();
        assertThat(leasesFile()).doesNotExist();
    }

    // --- US4-11 and the lease store (D-14 step 4) ------------------------------------------------

    @Test
    void aFailedResetOfTheServerRefusesTheSetNamingTheLease() {
        LeaseAttempt attempt = new LeaseAttempt(Instant.parse("2026-11-12T08:26:01Z"),
            AuditOutcome.FAILED, Optional.of(ErrorCode.UNREACHABLE),
            Optional.of(LeaseReason.NODE_UNREACHABLE));
        Lease failed = stored(activeLease(DEV, OTHER, NOW.minusSeconds(60)).failed(attempt));

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("The last reset of dev/node1 failed (lease "
            + failed.leaseId() + ", 2026-11-12T08:26:01Z: node unreachable); retry"
            + " reset_temporary_log_level first");
        assertThat(port.calls).as("refused before the own-node lookup").isEmpty();
        assertThat(onlyLease()).isEqualTo(failed);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void anUnreadableLeaseStoreRefusesTheSetAndStaysAsItIs() throws IOException {
        Files.createDirectories(directory());
        Files.writeString(leasesFile(), "not json");

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).startsWith("The lease store ");
        assertThat(port.calls).isEmpty();
        assertThat(Files.readString(leasesFile())).isEqualTo("not json");
    }

    // --- edge case: two sets for one node --------------------------------------------------------

    @Test
    void twoSetsMergeIntoOneLeaseWithTheUnionTheLastLevelAndTheLaterExpiry() {
        LogLevelResult.Outcome first = execute(new SetLevelRequest(DEV.value(),
            List.of(ORDERS), "DEBUG", OptionalInt.of(30), Optional.empty(), Optional.empty()));
        clock.advance(Duration.ofMinutes(1));

        LogLevelResult.Outcome second = execute(new SetLevelRequest(DEV.value(),
            List.of(INVOICES, ORDERS), "TRACE", OptionalInt.of(5), Optional.empty(),
            Optional.empty()));

        Lease lease = onlyLease();
        assertThat(second.leaseId()).isEqualTo(first.leaseId()).contains(lease.leaseId());
        assertThat(lease.loggers()).containsExactlyInAnyOrderEntriesOf(Map.of(
            new LoggerName(ORDERS), TemporaryLevel.TRACE,
            new LoggerName(INVOICES), TemporaryLevel.TRACE));
        assertThat(lease.expiresAt()).as("the first set's expiry is later")
            .isEqualTo(first.expiresAt().orElseThrow());
        assertThat(lease.generation()).isEqualTo(4);
        assertThat(lease.auditId()).isEqualTo(second.auditId());
        assertThat(second.loggers()).containsExactly(logger(INVOICES, TemporaryLevel.TRACE),
            logger(ORDERS, TemporaryLevel.TRACE));
    }

    @Test
    void aSetMergingIntoAnotherInstancesLeaseMakesThisInstanceTheOwner() {
        Instant later = NOW.plus(Duration.ofMinutes(45));
        Lease open = stored(activeLease(DEV, OTHER, later));

        LogLevelResult.Outcome result = execute(set(DEV, "INFO", INVOICES));

        Lease lease = onlyLease();
        assertThat(lease.leaseId()).isEqualTo(open.leaseId());
        assertThat(lease.owner()).isEqualTo(SELF);
        assertThat(lease.expiresAt()).isEqualTo(later);
        assertThat(lease.loggers()).containsExactlyInAnyOrderEntriesOf(Map.of(
            new LoggerName(ORDERS), TemporaryLevel.DEBUG,
            new LoggerName(INVOICES), TemporaryLevel.INFO));
        assertThat(result.expiresAt()).contains(later);
    }

    @Test
    void aMergeBeyondFiftyLoggersIsRefusedBeforeAnythingIsSent() {
        SortedMap<LoggerName, TemporaryLevel> full =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        for (int i = 0; i < Lease.MAX_LOGGERS; i++) {
            full.put(new LoggerName(ORDERS + i), TemporaryLevel.DEBUG);
        }
        Lease open = stored(new Lease(UUID.randomUUID(), 1, DEV, "node1", full, NOW,
            NOW.plus(Duration.ofMinutes(30)), SELF, LeaseState.ACTIVE, UUID.randomUUID(),
            Optional.empty(), Optional.empty()));

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS + "0", INVOICES));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("The open lease " + open.leaseId() + " of dev/node1"
            + " would hold more than 50 loggers; nothing was changed");
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(onlyLease()).isEqualTo(open);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- the node lock (D-12, D-14 step 3) -------------------------------------------------------

    @Test
    void aNodeLockHeldByAnotherInstanceRefusesAndNothingIsSent() throws Exception {
        ToolError error = whileAnotherInstanceHoldsTheLock(DEV,
            () -> refusal(set(DEV, "DEBUG", ORDERS)));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).isEqualTo("Another log-level change for dev/node1 is running");
        assertThat(port.calls).isEmpty();
        assertThat(leasesFile()).doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void theNodeLockIsReleasedAfterAnExecution() throws Exception {
        execute(set(DEV, "DEBUG", ORDERS));

        assertThat(lockFree(DEV)).isTrue();
    }

    // --- failures after PENDING (D-14 steps 7, 8, 10, 11) ----------------------------------------

    @Test
    void aFailedSecondRunResetsTheNodeAtOnceAndEndsTheLease() {
        port.failingRaise = 2;

        LogLevelResult.Outcome result = execute(set(DEV, "DEBUG", ORDERS, INVOICES));

        assertThat(port.calls).containsExactly("answeringNode 8.1", "raise DEBUG " + ORDERS,
            "raise DEBUG " + INVOICES, "resetAll");
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET);
        assertThat(lease.endedAt()).contains(NOW.plus(RUN).plus(RUN).plus(RUN));
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.FAILED);
        assertThat(result.leaseId()).contains(lease.leaseId());
        assertThat(result.leaseOpen()).isFalse();
        assertThat(result.expiresAt()).isEmpty();
        assertThat(result.message()).isEqualTo("Raising the log level on INUBIT node node1"
            + " failed (UNREACHABLE: StartCLI could not reach dev/node1); every temporary log"
            + " level on node1 was reset at once, also other users', and lease "
            + lease.leaseId() + " ended");
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(auditLog.records.get(1).reason())
            .contains("UNREACHABLE: StartCLI could not reach dev/node1");
    }

    @Test
    void aFailedRunOnAMergedLeaseSaysTheEarlierLoggersAreResetToo() {
        Lease open = stored(activeLease(DEV, SELF, NOW.plus(Duration.ofMinutes(30))));
        port.failingRaise = 1;

        LogLevelResult.Outcome result = execute(set(DEV, "DEBUG", INVOICES));

        assertThat(onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(result.message()).endsWith("and lease " + open.leaseId()
            + " ended, also for the loggers of earlier sets");
    }

    @Test
    void aFailedRunWhoseResetFailsTooLeavesTheLeaseResetFailed() {
        port.failingRaise = 1;
        port.resetFailure = new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            "StartCLI did not finish within cliTimeout", "slow", "retry").withNode(DEV));

        LogLevelResult.Outcome result = execute(set(DEV, "DEBUG", ORDERS));

        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.lastAttempt()).as("timed from the start of the reset run, after the"
            + " failed raise; not from its end").contains(new LeaseAttempt(NOW.plus(RUN),
            AuditOutcome.FAILED, Optional.of(ErrorCode.TIMEOUT),
            Optional.of(LeaseReason.NODE_UNREACHABLE)));
        assertThat(clock.instant()).isEqualTo(NOW.plus(RUN).plus(RUN));
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.FAILED);
        assertThat(result.leaseOpen()).isTrue();
        assertThat(result.expiresAt()).contains(lease.expiresAt());
        assertThat(result.message()).isEqualTo("Raising the log level on INUBIT node node1"
            + " failed (UNREACHABLE: StartCLI could not reach dev/node1), and the reset failed"
            + " too (TIMEOUT: StartCLI did not finish within cliTimeout); the levels may stay"
            + " raised on node1: the MCP server retries the reset of lease " + lease.leaseId()
            + " at least every minute");
    }

    @Test
    void aFailedLeaseUpdateAfterTheChangeResetsTheNodeAtOnce() {
        files.failingWrites.add(2);

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).isEqualTo(
            "The levels were raised but the lease could not be recorded; the node was reset");
        assertThat(error.node()).contains(DEV);
        assertThat(port.calls).containsExactly("answeringNode 8.1", "raise DEBUG " + ORDERS,
            "resetAll");
        Lease lease = onlyLease();
        assertThat(lease.state()).as("the third write records the reset")
            .isEqualTo(LeaseState.RESET);
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(auditLog.records.get(1).details()).containsExactly(
            Map.entry("leaseId", lease.leaseId().toString()),
            Map.entry("inubitNode", "node1"),
            Map.entry("expiresAt", lease.expiresAt().toString()),
            Map.entry("leaseOpen", false));
    }

    @Test
    void anUnrecordedChangeWhoseResetCannotBeRecordedEitherAuditsTheOpenWriteAheadLease() {
        files.failingWrites.addAll(Set.of(2, 3));

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(port.calls).endsWith("resetAll");
        Lease lease = onlyLease();
        assertThat(lease.state()).as("the write-ahead lease stays").isEqualTo(LeaseState.ACTIVE);
        assertThat(lease.generation()).isEqualTo(1);
        assertThat(auditLog.records.get(1).details()).containsExactly(
            Map.entry("leaseId", lease.leaseId().toString()),
            Map.entry("inubitNode", "node1"),
            Map.entry("expiresAt", lease.expiresAt().toString()),
            Map.entry("leaseOpen", true));
    }

    // --- finding r1-2: a reset the lease cannot record -------------------------------------------

    @Test
    void aFailedRunWhoseResetCannotBeRecordedSaysTheLeaseStaysOpen() {
        port.failingRaise = 1;
        files.failingWrites.add(2);

        LogLevelResult.Outcome result = execute(set(DEV, "DEBUG", ORDERS));

        assertThat(port.calls).endsWith("resetAll");
        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.FAILED);
        assertThat(result.leaseOpen()).isTrue();
        assertThat(result.expiresAt()).contains(lease.expiresAt());
        assertThat(result.message()).isEqualTo("Raising the log level on INUBIT node node1"
            + " failed (UNREACHABLE: StartCLI could not reach dev/node1); every temporary log"
            + " level on node1 was reset at once, also other users', but lease "
            + lease.leaseId() + " could not record the reset and stays open: the MCP server"
            + " resets node1 again at its expiry (" + lease.expiresAt() + ")");
        assertThat(auditLog.records.get(1).details()).containsEntry("leaseOpen", true);
    }

    @Test
    void aFailedRunWhoseFailedResetCannotBeRecordedNamesTheExpiry() {
        port.failingRaise = 1;
        port.resetFailure = new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            "StartCLI did not finish within cliTimeout", "slow", "retry").withNode(DEV));
        files.failingWrites.add(2);

        LogLevelResult.Outcome result = execute(set(DEV, "DEBUG", ORDERS));

        Lease lease = onlyLease();
        assertThat(lease.state()).as("the write-ahead lease stays").isEqualTo(LeaseState.ACTIVE);
        assertThat(result.leaseOpen()).isTrue();
        assertThat(result.expiresAt()).contains(lease.expiresAt());
        assertThat(result.message()).isEqualTo("Raising the log level on INUBIT node node1"
            + " failed (UNREACHABLE: StartCLI could not reach dev/node1), and the reset failed"
            + " too (TIMEOUT: StartCLI did not finish within cliTimeout); the levels may stay"
            + " raised on node1: lease " + lease.leaseId() + " could not record the failed"
            + " reset and stays open; the MCP server resets node1 at its expiry ("
            + lease.expiresAt() + ")");
    }

    // --- finding r1-4: an unexpected failure of a run or of the reset ----------------------------

    @Test
    void anUnexpectedFailureOfASecondRunIsAFailedRunAndResetsAtOnce() {
        port.failingRaise = 2;
        port.raiseFailure = new IllegalStateException("unexpected");

        LogLevelResult.Outcome result = execute(set(DEV, "DEBUG", ORDERS, INVOICES));

        assertThat(port.calls).containsExactly("answeringNode 8.1", "raise DEBUG " + ORDERS,
            "raise DEBUG " + INVOICES, "resetAll");
        assertThat(onlyLease().state()).isEqualTo(LeaseState.RESET);
        assertThat(result.outcome()).isEqualTo(LogLevelResult.Status.FAILED);
        assertThat(result.message()).startsWith("Raising the log level on INUBIT node node1"
            + " failed (INTERNAL: StartCLI failed unexpectedly (IllegalStateException));");
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void anUnexpectedFailureOfTheResetLeavesTheLeaseResetFailed() {
        port.failingRaise = 1;
        port.resetFailure = new IllegalStateException("unexpected");

        LogLevelResult.Outcome result = execute(set(DEV, "DEBUG", ORDERS));

        Lease lease = onlyLease();
        assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(lease.lastAttempt().orElseThrow().code()).contains(ErrorCode.INTERNAL);
        assertThat(lease.lastAttempt().orElseThrow().reason()).contains(LeaseReason.CLI_FAILED);
        assertThat(result.leaseOpen()).isTrue();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void anUnwritablePendingRecordIsInternalAndNothingIsSent() {
        auditLog.failing = true;

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).isEqualTo(
            "The audit record could not be written; nothing was sent to INUBIT");
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(leasesFile()).doesNotExist();
    }

    @Test
    void anUnwritableWriteAheadLeaseIsInternalAndNothingIsSent() {
        files.failingWrites.add(1);

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).startsWith("The lease of dev/node1 could not be recorded"
            + " before the change (INTERNAL: The lease store ");
        assertThat(error.message()).endsWith("); nothing was sent to INUBIT");
        assertThat(error.node()).contains(DEV);
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(leasesFile()).doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void anUnexpectedWriteAheadFailureNamesTheLeaseStoreNotStartCli() {
        files.failingWrites.add(1);
        files.unexpected = new IllegalStateException("unexpected");

        ToolError error = refusal(set(DEV, "DEBUG", ORDERS));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).isEqualTo("The lease of dev/node1 could not be recorded"
            + " before the change (INTERNAL: The lease store failed unexpectedly"
            + " (IllegalStateException)); nothing was sent to INUBIT");
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void aBusyStoreLockAtTheWriteAheadIsInternalAndNothingIsSent() throws Exception {
        Lease other = stored(activeLease(TEST, SELF, NOW.plus(Duration.ofMinutes(30))));
        LeaseStore otherInstance = new LeaseStore(directory(), PrivateFiles.forHost(), clock);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> otherInstance.update(leases -> {
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return leases;
        }, Duration.ofSeconds(5)));
        holder.start();
        held.await();
        ToolError error;
        try {
            error = refusal(set(DEV, "DEBUG", ORDERS));
        } finally {
            release.countDown();
            holder.join();
        }

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).startsWith("The lease of dev/node1 could not be recorded"
            + " before the change (PRECONDITION_FAILED: Another change of the temporary log"
            + " level leases is in progress");
        assertThat(error.node()).contains(DEV);
        assertThat(port.raisedOrReset()).isEmpty();
        assertThat(store.read()).containsExactly(other);
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private LogLevelService service() {
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
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

    private static SetLevelRequest set(NodeId server, String level, String... loggers) {
        return new SetLevelRequest(server.value(), List.of(loggers), level, OptionalInt.empty(),
            Optional.empty(), Optional.empty());
    }

    private static SetLevelRequest confirmed(SetLevelRequest request, String code) {
        return new SetLevelRequest(request.node(), request.loggers(), request.level(),
            request.durationMinutes(), Optional.of(code), request.mcpClient());
    }

    private LogLevelResult call(SetLevelRequest request) {
        try {
            LogLevelResult result = service().set(request);
            results.add(result);
            return result;
        } catch (ToolErrorException e) {
            errors.add(e.error());
            throw e;
        }
    }

    private LogLevelResult.Outcome execute(SetLevelRequest request) {
        return (LogLevelResult.Outcome) call(request);
    }

    private String challenge(SetLevelRequest request) {
        return ((LogLevelResult.Challenge) call(request)).confirmationCode();
    }

    private ToolError refusal(SetLevelRequest request) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> call(request));
        assertThat(exception).as("expected a refusal").isNotNull();
        return exception.error();
    }

    private void assertNothingSent() {
        assertThat(port.calls).as("port calls").isEmpty();
        assertThat(gateways.calls).as("gateway calls").isEmpty();
        assertThat(home.resolve(".inubit-mcp")).as("nothing created").doesNotExist();
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

    private static Lease activeLease(NodeId server, LeaseOwner owner, Instant expiresAt) {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        loggers.put(new LoggerName(ORDERS), TemporaryLevel.DEBUG);
        return new Lease(UUID.randomUUID(), 1, server, "node1", loggers,
            NOW.minus(Duration.ofMinutes(5)), expiresAt, owner, LeaseState.ACTIVE,
            UUID.randomUUID(), Optional.empty(), Optional.empty());
    }

    private static LeaseSummary.Logger logger(String name, TemporaryLevel level) {
        return new LeaseSummary.Logger(new LoggerName(name), level);
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
        /** The 1-based number of the raise that fails; 0: none. */
        int failingRaise;
        RuntimeException raiseFailure = new ToolErrorException(ToolError.of(
            ErrorCode.UNREACHABLE, "StartCLI could not reach dev/node1", "down", "retry")
            .withNode(DEV));
        RuntimeException resetFailure;
        private int raises;

        @Override
        public String answeringNode(InubitVersion version, long deadlineNanos) {
            calls.add("answeringNode " + version.raw());
            return answering;
        }

        @Override
        public void raise(TemporaryLevel level, LoggerName name, InubitVersion version,
            Duration timeout) {
            calls.add("raise " + level + " " + name.value());
            timeouts.add(timeout);
            clock.advance(RUN);
            if (++raises == failingRaise) {
                throw raiseFailure;
            }
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
        CredentialState state = CredentialState.READY;

        @Override
        public CredentialState prepareCredentials(NodeId server, Duration bound) {
            calls.add("prepareCredentials " + server);
            return state;
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

    /**
     * The host's file system, except that the lease store's writes (its private temporary files)
     * with a number in {@link #failingWrites} fail; counted from 1.
     */
    private static final class SwitchableFiles implements PrivateFileSystem {

        final Set<Integer> failingWrites = new HashSet<>();
        /** Thrown by a failing write instead of an {@link IOException}, if set. */
        RuntimeException unexpected;
        private final PrivateFileSystem host = PrivateFiles.forHost();
        private int writes;

        @Override
        public Path createFile(Path file) throws IOException {
            if (file.getFileName().toString().endsWith(".tmp")
                && failingWrites.contains(++writes)) {
                if (unexpected != null) {
                    throw unexpected;
                }
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
