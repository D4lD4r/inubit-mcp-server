package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.nodetools.LogLevelAudit.Inputs;
import de.dadecker.inubit.mcp.application.nodetools.LogLevelAudit.Trigger;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseAttempt;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseReason;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T048 (research D-22, Constitution VII): the audit records of the log-level tools
 * and of the automatic resets.
 */
class LogLevelAuditTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000048");
    private static final UUID LEASE_ID = UUID.fromString("3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01");
    private static final Instant NOW = Instant.parse("2026-11-12T08:15:30Z");
    private static final Instant EXPIRES = Instant.parse("2026-11-12T08:25:31Z");
    private static final String ORDERS = "com.inubit.ibis.acme.Orders";
    private static final String BILLING = "com.inubit.ibis.acme.Billing";
    private static final String CODE = "Zk3p0Qx8LmN2vB7cR1tY4w";
    private static final ToolError NOT_CONFIGURED = ToolError.of(ErrorCode.NOT_CONFIGURED,
        "set_temporary_log_level is not enabled for group test: unsupported.logLevelWrite is not"
            + " set", "cause", "next");

    private final RecordingAudit port = new RecordingAudit();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(LogLevelAudit.class);

    @TempDir
    Path temp;

    @BeforeEach
    void captureLogs() {
        logs.start();
        auditLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        auditLogger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void aSetPreviewIsOneChallengeRecordWithTheInputsInTheirFixedOrder() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS, BILLING), "DEBUG", OptionalInt.of(15),
                Optional.empty()), Optional.of("claude-code/2.0"));
        call.node(NODE, Optional.of("jdoe"));
        call.inubitNode("node1");

        call.challengeIssued(CODE, "Preview of set_temporary_log_level on test/node1");

        assertThat(port.records).hasSize(1);
        AuditRecord record = port.records.get(0);
        assertThat(record.auditId()).isEqualTo(ID).isEqualTo(call.auditId());
        assertThat(record.timestamp()).isEqualTo(NOW);
        assertThat(record.profile()).isEqualTo("default");
        assertThat(record.node()).isEqualTo("test/node1");
        assertThat(record.group()).contains("test");
        assertThat(record.capability()).isEqualTo("set_temporary_log_level");
        assertThat(record.step()).isEqualTo(AuditRecord.Step.PREVIEW);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED);
        assertThat(record.reason()).contains("Preview of set_temporary_log_level on test/node1");
        assertThat(record.account()).contains("jdoe");
        assertThat(record.mcpClient()).contains("claude-code/2.0");
        assertThat(record.inputs()).containsExactly(
            entry("node", "test/node1"),
            entry("loggers", ORDERS + "=DEBUG," + BILLING + "=DEBUG"),
            entry("durationMinutes", "15"),
            entry(AuditRecord.ISSUED_CONFIRMATION_CODE, CODE));
        assertThat(record.details()).containsExactly(Map.entry("inubitNode", "node1"));
    }

    @Test
    void aSetExecutionIsPendingThenExecutedWithOneAuditIdAndTheLeaseDetailsInTheirFixedOrder() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "TRACE", OptionalInt.empty(),
                Optional.of(CODE)), Optional.empty());
        call.node(NODE, Optional.of("jdoe"));
        call.inubitNode("node1");

        call.pending();
        call.lease(Optional.of(lease(LeaseState.ACTIVE)));
        Optional<String> warning = call.outcome(AuditOutcome.EXECUTED, "Raised on node1");

        assertThat(warning).isEmpty();
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(port.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(port.records).extracting(AuditRecord::step)
            .containsOnly(AuditRecord.Step.EXECUTE);
        assertThat(port.records.get(0).reason()).isEmpty();
        assertThat(port.records.get(0).details()).containsExactly(
            Map.entry("inubitNode", "node1"));
        AuditRecord executed = port.records.get(1);
        assertThat(executed.inputs()).containsExactly(
            entry("node", "test/node1"),
            entry("loggers", ORDERS + "=TRACE"),
            entry(AuditRecord.CONFIRMATION_CODE, CODE));
        assertThat(executed.reason()).contains("Raised on node1");
        assertThat(executed.details()).containsExactly(
            Map.entry("leaseId", LEASE_ID.toString()),
            Map.entry("inubitNode", "node1"),
            Map.entry("expiresAt", EXPIRES.toString()),
            Map.entry("leaseOpen", true));
    }

    @Test
    void aResetWithEveryInputRecordsThemInTheirFixedOrder() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.RESET_TOOL,
            Inputs.reset(Optional.of("test/node1"), Optional.of(LEASE_ID.toString()),
                Optional.of(true), Optional.of(CODE)), Optional.empty());
        ToolError invalid = ToolError.of(ErrorCode.INVALID_INPUT,
            "Invalid input combination: node, or leaseId with forget", "cause", "next");

        ToolErrorException refused = call.refused(AuditRecord.Step.EXECUTE, invalid);

        assertThat(refused.error()).isEqualTo(invalid);
        AuditRecord record = port.records.get(0);
        assertThat(record.capability()).isEqualTo("reset_temporary_log_level");
        assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(record.reason()).contains("INVALID_INPUT: Invalid input combination: node, or"
            + " leaseId with forget");
        assertThat(record.node()).isEqualTo("test/node1");
        assertThat(record.group()).contains("test");
        assertThat(record.inputs()).containsExactly(
            entry("node", "test/node1"),
            entry("leaseId", LEASE_ID.toString()),
            entry("forget", "true"),
            entry(AuditRecord.CONFIRMATION_CODE, CODE));
    }

    @Test
    void aForgetNamesTheServerOfTheLeaseOnceKnownAndAResetWithoutLeaseRecordsItClosed() {
        LogLevelAudit.ToolCall unknownLease = audit().toolCall(LogLevelAudit.RESET_TOOL,
            Inputs.reset(Optional.empty(), Optional.of("no-such-lease"), Optional.of(true),
                Optional.empty()), Optional.empty());
        unknownLease.refused(AuditRecord.Step.PREVIEW, ToolError.of(ErrorCode.INVALID_INPUT,
            "Unknown lease no-such-lease", "cause", "next"));

        LogLevelAudit.ToolCall forget = audit().toolCall(LogLevelAudit.RESET_TOOL,
            Inputs.reset(Optional.empty(), Optional.of(LEASE_ID.toString()), Optional.of(true),
                Optional.of(CODE)), Optional.empty());
        forget.node(NODE, Optional.empty());
        forget.lease(Optional.of(lease(LeaseState.RESET_FAILED)));
        forget.pending();
        forget.lease(Optional.of(lease(LeaseState.RESET_FAILED).forgotten(NOW)));
        forget.outcome(AuditOutcome.EXECUTED, "Forgotten");

        LogLevelAudit.ToolCall noLease = audit().toolCall(LogLevelAudit.RESET_TOOL,
            Inputs.reset(Optional.of("test/node1"), Optional.empty(), Optional.empty(),
                Optional.empty()), Optional.empty());
        noLease.node(NODE, Optional.of("jdoe"));
        noLease.inubitNode("node1");
        noLease.lease(Optional.empty());
        noLease.pending();
        noLease.outcome(AuditOutcome.EXECUTED, "Reset");

        AuditRecord refused = port.records.get(0);
        assertThat(refused.node()).isEqualTo("-");
        assertThat(refused.group()).isEmpty();
        assertThat(refused.step()).isEqualTo(AuditRecord.Step.PREVIEW);
        assertThat(refused.inputs()).containsExactly(
            entry("leaseId", "no-such-lease"),
            entry("forget", "true"));
        AuditRecord forgotten = port.records.get(2);
        assertThat(forgotten.node()).isEqualTo("test/node1");
        assertThat(forgotten.group()).contains("test");
        assertThat(forgotten.details()).containsEntry("leaseOpen", false);
        AuditRecord reset = port.records.get(4);
        assertThat(reset.details()).containsExactly(
            Map.entry("inubitNode", "node1"),
            Map.entry("leaseOpen", false));
    }

    @Test
    void theLoggerInputCarriesOnlyNamesAndLevelsAndIsBounded() {
        String longName = "com.inubit.ibis." + "x".repeat(LoggerName.MAX_CHARS);
        List<String> many = Stream.generate(() -> longName).limit(20).toList();
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", many, "DEBUG", OptionalInt.of(10), Optional.empty()),
            Optional.empty());

        call.refused(AuditRecord.Step.PREVIEW, ToolError.of(ErrorCode.INVALID_INPUT,
            "Invalid loggers: at most 5", "cause", "next"));

        String loggers = port.records.get(0).inputs().get("loggers");
        assertThat(loggers).hasSize(LogLevelAudit.MAX_AUDITED_LOGGERS).startsWith(longName
            + "=DEBUG,");
        assertThat(Arrays.stream(loggers.split(",")))
            .allSatisfy(item -> assertThat(longName + "=DEBUG").startsWith(item));
        String fiveAtMaximum = String.join(",", Stream.generate(
            () -> "com.inubit.ibis." + "x".repeat(LoggerName.MAX_CHARS - 16) + "=ERROR")
            .limit(Lease.MAX_SET_LOGGERS).toList());
        assertThat(fiveAtMaximum.length()).isLessThanOrEqualTo(LogLevelAudit.MAX_AUDITED_LOGGERS);
    }

    @Test
    void aSetWithoutLoggersRecordsTheRequestedLevelOnItsOwn() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(), "VERBOSE", OptionalInt.of(10), Optional.empty()),
            Optional.empty());

        call.refused(AuditRecord.Step.PREVIEW, ToolError.of(ErrorCode.INVALID_INPUT,
            "Invalid loggers: at least 1", "cause", "next"));

        assertThat(port.records.get(0).inputs()).containsExactly(
            entry("node", "test/node1"),
            entry("level", "VERBOSE"),
            entry("durationMinutes", "10"));
    }

    @Test
    void aChallengeThatCannotBeWrittenIsNotIssued() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.empty()), Optional.empty());
        call.node(NODE, Optional.of("jdoe"));
        port.failing = true;

        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> call.challengeIssued(CODE, "Preview"));

        assertThat(thrown.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(thrown.error().message()).contains("the preview was not issued");
        assertThat(thrown.error().node()).contains(NODE);
    }

    @Test
    void aRefusalWhoseRecordCannotBeWrittenBecomesInternalNamingTheRefusal() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.empty()), Optional.empty());
        port.failing = true;

        ToolErrorException refused = call.refused(AuditRecord.Step.PREVIEW, NOT_CONFIGURED);

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(refused.error().message()).contains("NOT_CONFIGURED")
            .contains("nothing was changed");
    }

    @Test
    void aPendingRecordThatCannotBeWrittenThrowsInternalAndNothingMaySend() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.of(CODE)), Optional.empty());
        call.node(NODE, Optional.of("jdoe"));
        port.failing = true;

        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class, call::pending);

        assertThat(thrown.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(thrown.error().message()).contains("nothing was sent to INUBIT");
        assertThat(thrown.error().node()).contains(NODE);
        assertThat(errors()).hasSize(1);
        assertThatThrownBy(() -> call.outcome(AuditOutcome.EXECUTED, "Raised"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnwritableFinalRecordBecomesAWarningNamingThePendingRecord() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.of(CODE)), Optional.empty());
        call.pending();
        port.failing = true;

        Optional<String> warning = call.outcome(AuditOutcome.FAILED, "CLI_FAILED: exit 1");

        assertThat(warning).hasValueSatisfying(text -> assertThat(text).contains(ID.toString()));
        assertThat(errors()).hasSize(1);
    }

    @Test
    void theOrderOfTheToolRecordsIsEnforced() {
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.of(CODE)), Optional.empty());
        assertThatThrownBy(() -> call.outcome(AuditOutcome.EXECUTED, "Raised"))
            .isInstanceOf(IllegalStateException.class);
        call.pending();
        assertThatThrownBy(call::pending).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> call.refused(AuditRecord.Step.EXECUTE, NOT_CONFIGURED))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> call.challengeIssued(CODE, "Preview"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> call.outcome(AuditOutcome.PENDING, "Raised"))
            .isInstanceOf(IllegalArgumentException.class);
        call.outcome(AuditOutcome.EXECUTED, "Raised");
        assertThatThrownBy(() -> call.outcome(AuditOutcome.FAILED, "again"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> audit().toolCall("restart_process", Inputs.reset(
            Optional.of("test/node1"), Optional.empty(), Optional.empty(), Optional.empty()),
            Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void theTriggersCarryTheNamesOfD22() {
        assertThat(Arrays.stream(Trigger.values()).map(Trigger::wireName)).containsExactly(
            "expiry", "retry", "takeover", "startup", "shutdown", "forget-after-7-days");
    }

    @Test
    void anAutomaticResetIsPendingThenTheOutcomeWithTriggerAndLeaseIdInTheirFixedOrder() {
        LogLevelAudit.AutomaticReset reset = audit().automaticReset(Trigger.EXPIRY,
            lease(LeaseState.ACTIVE), Optional.of("jdoe"));

        reset.pending();
        reset.outcome(AuditOutcome.FAILED, LeaseReason.NODE_UNREACHABLE.text());

        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(port.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(reset.auditId()).isEqualTo(ID);
        assertThat(port.records).allSatisfy(record -> {
            assertThat(record.capability()).isEqualTo("reset_temporary_log_level");
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
            assertThat(record.node()).isEqualTo("test/node1");
            assertThat(record.group()).contains("test");
            assertThat(record.account()).contains("jdoe");
            assertThat(record.mcpClient()).isEmpty();
            assertThat(record.timestamp()).isEqualTo(NOW);
            assertThat(record.inputs()).containsExactly(
                entry("trigger", "expiry"),
                entry("leaseId", LEASE_ID.toString()));
        });
        assertThat(port.records.get(0).reason()).isEmpty();
        assertThat(port.records.get(1).reason()).contains("node unreachable");
    }

    @Test
    void aFailedPreconditionIsOneRefusedRecordAndAnotherAnsweringNodeClosesThePendingOne() {
        audit().automaticReset(Trigger.RETRY, lease(LeaseState.RESET_FAILED), Optional.empty())
            .refused(LeaseReason.PRECONDITION.text());
        LogLevelAudit.AutomaticReset otherNode = audit().automaticReset(Trigger.SHUTDOWN,
            lease(LeaseState.ACTIVE), Optional.of("jdoe"));
        otherNode.pending();
        otherNode.outcome(AuditOutcome.REFUSED, LeaseReason.OTHER_NODE_ANSWERS.text()
            + ": node2 answers, the lease names node1");

        assertThat(port.records).extracting(AuditRecord::outcome).containsExactly(
            AuditOutcome.REFUSED, AuditOutcome.PENDING, AuditOutcome.REFUSED);
        assertThat(port.records.get(0).inputs()).containsEntry("trigger", "retry");
        assertThat(port.records.get(0).reason()).contains("cleanup precondition not met");
        assertThat(port.records.get(2).reason())
            .contains("other node answers: node2 answers, the lease names node1");
    }

    @Test
    void aPendingResetRecordThatCannotBeWrittenSurfacesAsAnExceptionAndIsLogged() {
        LogLevelAudit.AutomaticReset reset = audit().automaticReset(Trigger.STARTUP,
            lease(LeaseState.ACTIVE), Optional.of("jdoe"));
        port.failing = true;

        assertThatThrownBy(reset::pending).isInstanceOf(LogLevelAudit.NotWritableException.class)
            .hasMessageContaining(ID.toString());

        assertThat(port.records).isEmpty();
        assertThat(errors()).hasSize(1);
        assertThat(errors().get(0).getFormattedMessage()).contains(LEASE_ID.toString())
            .contains("nothing was sent");
        assertThatThrownBy(() -> reset.outcome(AuditOutcome.EXECUTED, "reset"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRefusalOrFinalResetRecordThatCannotBeWrittenSurfacesAsAnException() {
        port.failing = true;
        LogLevelAudit.AutomaticReset refused = audit().automaticReset(Trigger.RETRY,
            lease(LeaseState.RESET_FAILED), Optional.empty());
        assertThatThrownBy(() -> refused.refused(LeaseReason.PRECONDITION.text()))
            .isInstanceOf(LogLevelAudit.NotWritableException.class);

        port.failing = false;
        LogLevelAudit.AutomaticReset executed = audit().automaticReset(Trigger.EXPIRY,
            lease(LeaseState.ACTIVE), Optional.empty());
        executed.pending();
        port.failing = true;
        assertThatThrownBy(() -> executed.outcome(AuditOutcome.EXECUTED, "reset"))
            .isInstanceOf(LogLevelAudit.NotWritableException.class);
        assertThat(errors()).hasSize(2);
    }

    @Test
    void aTakeoverAndAForgetAfterSevenDaysAreOneExecutedRecordWithoutPending() {
        audit().automaticReset(Trigger.TAKEOVER, lease(LeaseState.ACTIVE), Optional.of("jdoe"))
            .changed("owner 4711 not running; taken over by 4712");
        audit().automaticReset(Trigger.FORGET_AFTER_7_DAYS, lease(LeaseState.RESET_FAILED),
            Optional.empty()).changed(LeaseReason.SERVER_NOT_CONFIGURED.text());

        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.EXECUTED, AuditOutcome.EXECUTED);
        assertThat(port.records).extracting(record -> record.inputs().get("trigger"))
            .containsExactly("takeover", "forget-after-7-days");
        assertThatThrownBy(() -> audit().automaticReset(Trigger.TAKEOVER,
            lease(LeaseState.ACTIVE), Optional.empty()).pending())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> audit().automaticReset(Trigger.EXPIRY,
            lease(LeaseState.ACTIVE), Optional.empty()).changed("x"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> {
            LogLevelAudit.AutomaticReset reset = audit().automaticReset(Trigger.EXPIRY,
                lease(LeaseState.ACTIVE), Optional.empty());
            reset.pending();
            reset.refused("late");
        }).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theAuditLogStoresEveryConfirmationCodeOnlyAsASha256Prefix() throws IOException {
        AuditLog file = new AuditLog(temp, new SecretScrubber(), PrivateFiles.forHost());
        String issued = "Aa1Bb2Cc3Dd4Ee5Ff6Gg7H";
        LogLevelAudit audit = new LogLevelAudit(file, new MutableClock(NOW), "default",
            UUID::randomUUID);
        LogLevelAudit.ToolCall preview = audit.toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.empty()), Optional.empty());
        preview.challengeIssued(issued, "Preview");
        LogLevelAudit.ToolCall execute = audit.toolCall(LogLevelAudit.RESET_TOOL,
            Inputs.reset(Optional.of("test/node1"), Optional.empty(), Optional.empty(),
                Optional.of(CODE)), Optional.empty());
        execute.pending();

        String text;
        try (Stream<Path> files = Files.walk(temp)) {
            List<Path> written = files.filter(Files::isRegularFile).toList();
            assertThat(written).hasSize(1);
            text = Files.readString(written.get(0), StandardCharsets.UTF_8);
        }
        assertThat(text).doesNotContain(issued).doesNotContain(CODE)
            .contains(sha256Prefix(issued)).contains(sha256Prefix(CODE))
            .contains("\"loggers\":\"" + ORDERS + "=DEBUG\"");
    }

    @Test
    void neitherTheCauseOfAWriteFailureNorAnythingButLoggerNamesAndLevelsLeaks() {
        String secret = "password=Hunter2";
        port.failing = true;
        port.failure = new UncheckedIOException(new IOException("disk full at /home/" + secret));
        LogLevelAudit.ToolCall call = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.empty(),
                Optional.of(CODE)), Optional.empty());
        ToolErrorException pending = catchThrowableOfType(ToolErrorException.class,
            call::pending);
        LogLevelAudit.AutomaticReset reset = audit().automaticReset(Trigger.EXPIRY,
            lease(LeaseState.ACTIVE), Optional.empty());
        LogLevelAudit.NotWritableException notWritable = catchThrowableOfType(
            LogLevelAudit.NotWritableException.class, reset::pending);

        assertThat(pending.error().toString()).doesNotContain("Hunter2");
        assertThat(pending.getCause()).isNull();
        assertThat(notWritable.getMessage()).doesNotContain("Hunter2");
        assertThat(notWritable.getCause()).isNull();
        assertThat(logs.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("Hunter2")
                .doesNotContain(CODE).doesNotContain(ORDERS);
            assertThat(event.getThrowableProxy()).isNull();
        });

        port.failing = false;
        LogLevelAudit.ToolCall written = audit().toolCall(LogLevelAudit.SET_TOOL,
            Inputs.set("test/node1", List.of(ORDERS), "DEBUG", OptionalInt.of(5),
                Optional.of(CODE)), Optional.empty());
        written.node(NODE, Optional.of("jdoe"));
        written.pending();
        written.lease(Optional.of(lease(LeaseState.ACTIVE)));
        written.outcome(AuditOutcome.EXECUTED, "Raised");
        assertThat(port.records).allSatisfy(record -> {
            assertThat(record.toString()).doesNotContain("Hunter2");
            assertThat(record.inputs().keySet()).containsExactly("node", "loggers",
                "durationMinutes", AuditRecord.CONFIRMATION_CODE);
            assertThat(record.inputs().get("loggers")).isEqualTo(ORDERS + "=DEBUG");
        });
    }

    private LogLevelAudit audit() {
        List<UUID> ids = new ArrayList<>(List.of(ID));
        return new LogLevelAudit(port, new MutableClock(NOW), "default", () -> ids.remove(0));
    }

    private static Lease lease(LeaseState state) {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        loggers.put(new LoggerName(ORDERS), TemporaryLevel.DEBUG);
        Lease active = new Lease(LEASE_ID, 1, NODE, "node1", loggers, NOW, EXPIRES,
            new LeaseOwner(4711, Optional.empty()), LeaseState.ACTIVE, ID, Optional.empty(),
            Optional.empty());
        return switch (state) {
            case ACTIVE -> active;
            case RESET_FAILED -> active.failed(new LeaseAttempt(NOW, AuditOutcome.FAILED,
                Optional.of(ErrorCode.INTERNAL), Optional.of(LeaseReason.NODE_UNREACHABLE)));
            case RESET, FORGOTTEN -> throw new IllegalArgumentException("not used: " + state);
        };
    }

    private List<ILoggingEvent> errors() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    private static String sha256Prefix(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return Map.entry(key, value);
    }

    /** Records every appended audit record; fails every append while {@code failing}. */
    private static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new ArrayList<>();
        boolean failing;
        RuntimeException failure = new UncheckedIOException(new IOException("disk full"));

        @Override
        public void append(AuditRecord record) {
            if (failing) {
                throw failure;
            }
            records.add(record);
        }
    }
}
