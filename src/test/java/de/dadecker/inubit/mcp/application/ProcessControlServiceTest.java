package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.application.FakeProcessControl.row;
import static de.dadecker.inubit.mcp.application.FakeProcessControl.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.ProcessControlService.ControlRequest;
import de.dadecker.inubit.mcp.application.ProcessControlService.Response;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ConfirmationChallenge;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;
import de.dadecker.inubit.mcp.domain.model.ProcessControlResult;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * T104: the decision flow of {@code restart_process} / {@code kill_process}
 * (contracts/mcp-tools.md §7–8, FR-018 – FR-024, SC-005) with fakes for the ports, the audit log
 * and the clock.
 */
class ProcessControlServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST = NodeId.parse("test/inubit01");
    private static final NodeId STAGING = NodeId.parse("staging/inubit01");
    private static final NodeId PROD = NodeId.parse("prod/inubit01");
    private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
    private static final Instant SINCE = Instant.parse("2026-10-03T07:00:00Z");
    private static final String PID = "110190387";
    private static final List<NodeId> SERVERS = List.of(DEV, TEST, STAGING, PROD);

    private final MutableClock clock = new MutableClock(NOW);
    private final FakeProcessControl ports = new FakeProcessControl();
    private final RecordingAudit audit = new RecordingAudit();
    private final ConfirmationRegistry confirmations = new ConfirmationRegistry(clock);
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<NodeId, WritePolicy> policies = new HashMap<>(Map.of(
        DEV, policy(DEV, false, true, false, Confirmation.SERVER),
        TEST, policy(TEST, false, false, false, Confirmation.SERVER),
        STAGING, policy(STAGING, false, true, false, Confirmation.CLIENT),
        PROD, policy(PROD, true, true, false, Confirmation.SERVER)));

    private final ProcessControlService service = new ProcessControlService(
        new WriteGuard(new TargetResolver(SERVERS), policies::get, ports), policies::get, ports,
        confirmations, audit, clock,
        () -> UUID.fromString(String.format("00000000-0000-0000-0000-%012d",
            ids.incrementAndGet())), "acme");

    private static WritePolicy policy(NodeId server, boolean production, boolean enabled,
        boolean optIn, Confirmation confirmation) {
        return new WritePolicy(server, production, enabled, optIn, confirmation,
            Duration.ofMinutes(5), Optional.of("jdoe"));
    }

    private static ProcessInstance errorRow(NodeId server) {
        return row(server, PID, ProcessState.ERROR, "Error", SINCE, "Workflow-9001",
            "Throw Exception");
    }

    private static ControlRequest request(ProcessAction action, String server, String pid,
        Optional<String> code) {
        return new ControlRequest(action, server, pid, code, Optional.of("fixed the mapping"),
            Optional.of("claude-code/2.1.0"));
    }

    private static ControlRequest restart(String server, Optional<String> code) {
        return request(ProcessAction.RESTART, server, PID, code);
    }

    private static ControlRequest kill(String server, Optional<String> code) {
        return request(ProcessAction.KILL, server, PID, code);
    }

    private static ToolError errorOf(Executable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, () -> {
            try {
                call.execute();
            } catch (ToolErrorException e) {
                throw e;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    private ConfirmationChallenge challenge(ControlRequest request) {
        Response response = service.execute(request);
        assertThat(response).isInstanceOf(Response.Challenge.class);
        return ((Response.Challenge) response).challenge();
    }

    private ProcessControlResult result(ControlRequest request) {
        Response response = service.execute(request);
        assertThat(response).isInstanceOf(Response.Completed.class);
        return ((Response.Completed) response).result();
    }

    // --- SC-005: default configuration ------------------------------------------------------

    @Test
    void withWriteDisabledEveryAttemptIsRefusedAndAudited() {
        List<ControlRequest> attempts = new ArrayList<>();
        for (ProcessAction action : ProcessAction.values()) {
            attempts.add(request(action, "test/inubit01", PID, Optional.empty()));
            attempts.add(request(action, "test/inubit01", PID,
                Optional.of("AAAAAAAAAAAAAAAAAAAAAA")));
            attempts.add(new ControlRequest(action, "test/inubit01", "1", Optional.empty(),
                Optional.empty(), Optional.empty()));
        }

        for (ControlRequest attempt : attempts) {
            ToolError error = errorOf(() -> service.execute(attempt));
            assertThat(error.code()).isEqualTo(ErrorCode.WRITE_DISABLED);
            assertThat(error.nextStep()).contains("write.enabled: true");
        }

        assertThat(audit.records).hasSize(attempts.size()).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.node()).isEqualTo("test/inubit01");
            assertThat(record.group()).contains("test");
            assertThat(record.account()).contains("jdoe");
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("WRITE_DISABLED: "));
        });
        assertThat(ports.calls).isEmpty();
    }

    // --- SC-005: production without opt-in --------------------------------------------------

    @Test
    void productionWithoutOptInIsRefusedRegardlessOfTheOtherSettings() {
        List<WritePolicy> variants = List.of(
            policy(PROD, true, true, false, Confirmation.SERVER),
            policy(PROD, true, false, false, Confirmation.SERVER),
            policy(PROD, true, true, false, Confirmation.CLIENT));
        for (WritePolicy variant : variants) {
            policies.put(PROD, variant);
            for (ProcessAction action : ProcessAction.values()) {
                for (Optional<String> code : List.of(Optional.<String>empty(),
                    Optional.of("AAAAAAAAAAAAAAAAAAAAAA"))) {
                    assertThat(errorOf(() -> service.execute(request(action, "prod/inubit01",
                        PID, code))).code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
                }
            }
        }

        assertThat(audit.records).hasSize(12).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("PRODUCTION_PROTECTED: "));
        });
        assertThat(ports.calls).isEmpty();
    }

    // --- resolve -----------------------------------------------------------------------------

    @Test
    void aStageIsInvalidInputAndAudited() {
        ToolError error = errorOf(() -> service.execute(restart("dev", Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.node()).isEqualTo("dev");
            assertThat(record.capability()).isEqualTo("restart_process");
            assertThat(record.account()).isEmpty();
        });
        assertThat(ports.calls).isEmpty();
    }

    @Test
    void anUnknownServerIsEnvironmentUnknownAndAudited() {
        ToolError error = errorOf(() -> service.execute(kill("dev/inubit9", Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.node()).isEqualTo("dev/inubit9");
            assertThat(record.group()).contains("dev");
            assertThat(record.capability()).isEqualTo("kill_process");
            assertThat(record.account()).isEmpty();
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("TARGET_UNKNOWN: "));
        });
    }

    @Test
    void anIdThatIsNotAQueueManagerIdIsInvalidInputBeforeAnyRead() {
        ToolError error = errorOf(() -> service.execute(request(ProcessAction.KILL,
            "dev/node1", "7b0c4f1e-3a52-4d5e-9a40-1f2e3d4c5b6a", Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(audit.records).singleElement()
            .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        assertThat(ports.calls).isEmpty();
    }

    @Test
    void anIdWithLeadingZerosIsInvalidInput() {
        // review NIT: the Queue Manager id has no leading zeros; "0110190387" would name another
        // instance in INUBIT's eyes than the preview showed
        ToolError error = errorOf(() -> service.execute(request(ProcessAction.KILL,
            "dev/node1", "0" + PID, Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(ports.calls).isEmpty();
    }

    @Test
    void aTooLongReasonIsInvalidInput() {
        ControlRequest request = new ControlRequest(ProcessAction.KILL, "dev/node1", PID,
            Optional.empty(), Optional.of("x".repeat(501)), Optional.empty());

        assertThat(errorOf(() -> service.execute(request)).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(audit.records).singleElement().satisfies(record ->
            assertThat(record.inputs().get("reason")).hasSize(500));
        assertThat(ports.calls).isEmpty();
    }

    @Test
    void aCliThatIsNotAvailableIsRefusedAndAudited() {
        ports.unavailable = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No CLI home is configured for dev/node1", "cliHome is not set", "Set cliHome")
            .withNode(DEV));

        assertThat(errorOf(() -> service.execute(restart("dev/node1", Optional.empty())))
            .code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(ports.actions()).isEmpty();
    }

    // --- read state ----------------------------------------------------------------------------

    @Test
    void theStateIsReadThroughTheQueueManagerAndNoEntryIsNotFound() {
        ports.reading(rows());

        ToolError error = errorOf(() -> service.execute(kill("dev/node1", Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains(PID);
        assertThat(ports.calls).containsExactly("check dev/node1", "read dev/node1 " + PID);
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void restartingAnInstanceThatIsNotInErrorIsAPreconditionFailureWithTheActualState() {
        ports.reading(rows(row(DEV, PID, ProcessState.WAITING, "Waiting", SINCE, "W", "M")));

        ToolError error = errorOf(() -> service.execute(restart("dev/node1",
            Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("WAITING").contains(PID);
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("PRECONDITION_FAILED: ").contains("WAITING"));
        });
        assertThat(ports.actions()).isEmpty();
    }

    @Test
    void anIncompleteRowSetWithoutErrorRowIsAPreconditionFailure() {
        ports.reading(new ProcessRows(List.of(row(DEV, PID, ProcessState.WAITING, "Waiting",
            SINCE, "W", "M")), 150));

        ToolError error = errorOf(() -> service.execute(restart("dev/node1",
            Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("150");
    }

    @Test
    void aFailedStateReadIsRefusedAndAudited() {
        ports.readingFailure(new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
            "Connection refused", "down", "get_health").withNode(DEV)));

        assertThat(errorOf(() -> service.execute(kill("dev/node1", Optional.empty()))).code())
            .isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(ports.actions()).isEmpty();
    }

    @Test
    void killDoesNotRequireTheErrorState() {
        ports.reading(rows(row(DEV, PID, ProcessState.WAITING, "Waiting", SINCE, "W", "M")));

        ConfirmationChallenge challenge = challenge(kill("dev/node1", Optional.empty()));

        assertThat(challenge.preview().state()).isEqualTo(ProcessState.WAITING);
    }

    // --- server-side confirmation ------------------------------------------------------------

    @Test
    void withoutCodeTheFirstCallOnlyReturnsAPreviewAndACode() {
        ports.reading(rows(errorRow(DEV)));

        ConfirmationChallenge challenge = challenge(restart("dev/node1", Optional.empty()));

        assertThat(challenge.confirmationCode()).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(challenge.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(challenge.preview()).isEqualTo(new ConfirmationChallenge.Preview(DEV,
            ProcessAction.RESTART, PID, Optional.of("Workflow-9001"),
            Optional.of("Throw Exception"), ProcessState.ERROR, SINCE));
        assertThat(challenge.message()).contains("confirmationCode").contains("restart_process");
        assertThat(ports.actions()).isEmpty();
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.step()).isEqualTo(AuditRecord.Step.PREVIEW);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED);
            assertThat(record.node()).isEqualTo("dev/node1");
            assertThat(record.group()).contains("dev");
            assertThat(record.capability()).isEqualTo("restart_process");
            assertThat(record.account()).contains("jdoe");
            assertThat(record.mcpClient()).contains("claude-code/2.1.0");
            assertThat(record.timestamp()).isEqualTo(NOW);
            assertThat(record.inputs()).containsEntry("processId", PID)
                .containsEntry("reason", "fixed the mapping")
                .containsEntry(AuditRecord.ISSUED_CONFIRMATION_CODE,
                    challenge.confirmationCode());
        });
    }

    @Test
    void theRowInErrorIsPreviewedForAProcessWithSubWorkflows() {
        ports.reading(rows(
            row(DEV, PID, ProcessState.WAITING, "Waiting", SINCE.plusSeconds(60), "Parent", "Call"),
            row(DEV, PID, ProcessState.ERROR, "Error", SINCE, "Child", "Map")));

        ConfirmationChallenge challenge = challenge(restart("dev/node1", Optional.empty()));

        assertThat(challenge.preview().workflow()).contains("Child");
        assertThat(challenge.preview().state()).isEqualTo(ProcessState.ERROR);
    }

    @Test
    void withAValidCodeTheExecuteRecordIsWrittenBeforeTheCliCallAndTheStateIsReread() {
        ports.reading(rows(errorRow(DEV)), rows(errorRow(DEV)), rows());
        List<List<AuditOutcome>> auditedAtCall = new CopyOnWriteArrayList<>();
        ports.duringAction = () -> auditedAtCall.add(audit.outcomes());
        String code = challenge(restart("dev/node1", Optional.empty())).confirmationCode();
        clock.advance(Duration.ofMinutes(4));

        ProcessControlResult result = result(restart("dev/node1", Optional.of(code)));

        assertThat(auditedAtCall).containsExactly(List.of(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING));
        assertThat(result.outcome()).isEqualTo(ProcessControlResult.Outcome.EXECUTED);
        assertThat(result.node()).isEqualTo(DEV);
        assertThat(result.action()).isEqualTo(ProcessAction.RESTART);
        assertThat(result.processId()).isEqualTo(PID);
        assertThat(result.stateBefore()).isEqualTo(ProcessState.ERROR);
        assertThat(result.stateAfter()).contains(ProcessControlResult.NOT_IN_QUEUE);
        assertThat(result.message()).contains("Process restarted.");
        assertThat(ports.calls).containsExactly("check dev/node1", "read dev/node1 " + PID,
            "check dev/node1", "read dev/node1 " + PID, "restart dev/node1 " + PID,
            "read dev/node1 " + PID);
        assertThat(audit.records).hasSize(3);
        AuditRecord pending = audit.records.get(1);
        AuditRecord executed = audit.records.get(2);
        assertThat(pending.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        assertThat(pending.inputs()).containsEntry(AuditRecord.CONFIRMATION_CODE, code);
        assertThat(executed.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        assertThat(executed.outcome()).isEqualTo(AuditOutcome.EXECUTED);
        assertThat(executed.auditId()).isEqualTo(pending.auditId()).isEqualTo(result.auditId());
    }

    @Test
    void theReReadStateIsReportedWhenTheInstanceStaysInTheQueue() {
        ports.reading(rows(errorRow(DEV)), rows(row(DEV, PID, ProcessState.ACTIVE, "Processing",
            NOW, "Workflow-9001", "Throw Exception")));
        policies.put(DEV, policy(DEV, false, true, false, Confirmation.CLIENT));

        assertThat(result(restart("dev/node1", Optional.empty())).stateAfter())
            .contains("ACTIVE");
    }

    @Test
    void aFailedReReadLeavesStateAfterEmpty() {
        ports.reading(rows(errorRow(DEV)));
        ports.readingFailure(new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, "slow",
            "slow", "retry")));
        policies.put(DEV, policy(DEV, false, true, false, Confirmation.CLIENT));

        ProcessControlResult result = result(kill("dev/node1", Optional.empty()));

        assertThat(result.outcome()).isEqualTo(ProcessControlResult.Outcome.EXECUTED);
        assertThat(result.stateAfter()).isEmpty();
    }

    @Test
    void aFailingExecuteRecordStopsTheActionBeforeTheCliCall() {
        ports.reading(rows(errorRow(DEV)));
        String code = challenge(restart("dev/node1", Optional.empty())).confirmationCode();
        audit.failing = true;

        ToolError error = errorOf(() -> service.execute(restart("dev/node1",
            Optional.of(code))));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("audit");
        assertThat(ports.actions()).isEmpty();
    }

    @Test
    void aFailingChallengeRecordIssuesNoUsableCode() {
        ports.reading(rows(errorRow(DEV)));
        audit.failing = true;

        ToolError error = errorOf(() -> service.execute(restart("dev/node1",
            Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(confirmations.pending()).isZero();
    }

    @Test
    void aFailingRefusalRecordIsReportedAsInternal() {
        audit.failing = true;

        ToolError error = errorOf(() -> service.execute(restart("test/inubit01",
            Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("WRITE_DISABLED");
        assertThat(ports.calls).isEmpty();
    }

    // --- SC-005: no execution without a valid, unexpired, matching code ----------------------

    @Test
    void everyExecutionWithoutAValidUnexpiredMatchingCodeIsRefused() {
        ports.reading(rows(errorRow(DEV)));
        List<String> invalid = new ArrayList<>();
        invalid.add("AAAAAAAAAAAAAAAAAAAAAA"); // never issued
        String issued = challenge(restart("dev/node1", Optional.empty())).confirmationCode();
        invalid.add((issued.startsWith("A") ? "B" : "A") + issued.substring(1)); // altered
        invalid.add(challenge(kill("dev/node1", Optional.empty())).confirmationCode()); // action
        invalid.add(challenge(request(ProcessAction.RESTART, "dev/node1", "4711",
            Optional.empty())).confirmationCode()); // process
        String expired = challenge(restart("dev/node1", Optional.empty())).confirmationCode();
        String used = challenge(restart("dev/node1", Optional.empty())).confirmationCode();
        result(restart("dev/node1", Optional.of(used)));
        invalid.add(used); // already used
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        invalid.add(expired);
        int actionsBefore = ports.actions().size();
        audit.records.clear();

        for (String code : invalid) {
            ToolError error = errorOf(() -> service.execute(restart("dev/node1",
                Optional.of(code))));
            assertThat(error.code()).as(code).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        }

        assertThat(ports.actions()).hasSize(actionsBefore);
        assertThat(audit.records).hasSize(invalid.size()).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        });
    }

    @Test
    void aCodeOfAnotherServerIsRefused() {
        policies.put(TEST, policy(TEST, false, true, false, Confirmation.SERVER));
        ports.reading(rows(errorRow(DEV)));
        String code = challenge(restart("dev/node1", Optional.empty())).confirmationCode();

        assertThat(errorOf(() -> service.execute(restart("test/inubit01", Optional.of(code))))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(ports.actions()).isEmpty();
    }

    // --- concurrent state change ---------------------------------------------------------------

    @Test
    void anInstanceThatCompletedBetweenPreviewAndExecutionIsReportedAndNotTouched() {
        ports.reading(rows(errorRow(DEV)), rows());
        String code = challenge(restart("dev/node1", Optional.empty())).confirmationCode();

        ToolError error = errorOf(() -> service.execute(restart("dev/node1",
            Optional.of(code))));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).contains("no longer");
        assertThat(ports.actions()).isEmpty();
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.REFUSED);
        ports.reads.clear();
        ports.reading(rows(errorRow(DEV)));
        assertThat(errorOf(() -> service.execute(restart("dev/node1", Optional.of(code))))
            .code()).as("the presented code is used up").isEqualTo(ErrorCode.CONFIRMATION_INVALID);
    }

    @Test
    void anInstanceThatLeftTheErrorStateBetweenPreviewAndExecutionIsNotRestarted() {
        ports.reading(rows(errorRow(DEV)), rows(row(DEV, PID, ProcessState.ACTIVE, "Processing",
            NOW, "Workflow-9001", "Throw Exception")));
        String code = challenge(restart("dev/node1", Optional.empty())).confirmationCode();

        ToolError error = errorOf(() -> service.execute(restart("dev/node1",
            Optional.of(code))));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("ACTIVE");
        assertThat(ports.actions()).isEmpty();
    }

    // --- Phase 6 review W1: the code is bound to the previewed state --------------------------

    @Test
    void aKillConfirmedAfterTheInstanceLeftThePreviewedStateIsRefused() {
        ports.reading(rows(errorRow(DEV)), rows(row(DEV, PID, ProcessState.ACTIVE, "Processing",
            NOW, "Workflow-9001", "Throw Exception")));
        String code = challenge(kill("dev/node1", Optional.empty())).confirmationCode();

        ToolError error = errorOf(() -> service.execute(kill("dev/node1", Optional.of(code))));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("previewed").contains("ERROR").contains("ACTIVE");
        assertThat(ports.actions()).isEmpty();
        assertThat(audit.records).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("PRECONDITION_FAILED: "));
        });
    }

    @Test
    void aRestartConfirmedAfterTheInstanceErroredAgainIsRefused() {
        ports.reading(rows(errorRow(DEV)), rows(row(DEV, PID, ProcessState.ERROR, "Error",
            SINCE.plusSeconds(90), "Workflow-9001", "Throw Exception")));
        String code = challenge(restart("dev/node1", Optional.empty())).confirmationCode();

        ToolError error = errorOf(() -> service.execute(restart("dev/node1",
            Optional.of(code))));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains(SINCE.toString())
            .contains(SINCE.plusSeconds(90).toString());
        assertThat(ports.actions()).isEmpty();
    }

    @Test
    void aConfirmationForAnotherRowOfTheProcessIsRefused() {
        ports.reading(rows(errorRow(DEV)), rows(row(DEV, PID, ProcessState.ERROR, "Error",
            SINCE, "Workflow-9002", "Other Module")));
        String code = challenge(kill("dev/node1", Optional.empty())).confirmationCode();

        assertThat(errorOf(() -> service.execute(kill("dev/node1", Optional.of(code)))).code())
            .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(ports.actions()).isEmpty();
    }

    @Test
    void anUnchangedInstanceIsExecutedWithTheCode() {
        ports.reading(rows(errorRow(DEV)), rows(errorRow(DEV)), rows());
        String code = challenge(kill("dev/node1", Optional.empty())).confirmationCode();

        assertThat(result(kill("dev/node1", Optional.of(code))).outcome())
            .isEqualTo(ProcessControlResult.Outcome.EXECUTED);
        assertThat(ports.actions()).containsExactly("kill dev/node1 " + PID);
    }

    // --- Phase 6 review W3: unexpected failures before execution are audited ----------------

    @Test
    void anUnexpectedFailureOfTheStateReadIsAnAuditedInternalRefusal() {
        ports.reads.add(() -> {
            throw new IllegalStateException("boom");
        });

        ToolError error = errorOf(() -> service.execute(kill("dev/node1", Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).doesNotContain("boom");
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("INTERNAL: "));
        });
        assertThat(ports.actions()).isEmpty();
    }

    @Test
    void anUnexpectedFailureOfTheCliCheckIsAnAuditedInternalRefusal() {
        ports.unavailableUnexpectedly = new IllegalStateException("boom");

        assertThat(errorOf(() -> service.execute(kill("dev/node1", Optional.empty()))).code())
            .isEqualTo(ErrorCode.INTERNAL);
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void aSecondActionOnTheSameInstanceWhileOneRunsIsRefused() {
        policies.put(DEV, policy(DEV, false, true, false, Confirmation.CLIENT));
        ports.reading(rows(errorRow(DEV)));
        List<ToolError> nested = new CopyOnWriteArrayList<>();
        ports.duringAction = () -> nested.add(errorOf(() -> service.execute(kill("dev/node1",
            Optional.empty()))));

        ProcessControlResult result = result(restart("dev/node1", Optional.empty()));

        assertThat(result.outcome()).isEqualTo(ProcessControlResult.Outcome.EXECUTED);
        assertThat(nested).singleElement().satisfies(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED));
        assertThat(ports.actions()).containsExactly("restart dev/node1 " + PID);
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED,
            AuditOutcome.EXECUTED);
    }

    // --- client-side confirmation --------------------------------------------------------------

    @Test
    void clientConfirmationExecutesOnTheFirstCall() {
        ports.reading(rows(errorRow(STAGING)), rows());

        ProcessControlResult result = result(kill("staging/inubit01", Optional.empty()));

        assertThat(result.outcome()).isEqualTo(ProcessControlResult.Outcome.EXECUTED);
        assertThat(ports.actions()).containsExactly("kill staging/inubit01 " + PID);
        assertThat(audit.records).extracting(AuditRecord::step)
            .containsExactly(AuditRecord.Step.EXECUTE, AuditRecord.Step.EXECUTE);
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.EXECUTED);
    }

    // --- execution failures ------------------------------------------------------------------

    @Test
    void aFailedCliCallIsAFailedResultWithTheReReadState() {
        policies.put(DEV, policy(DEV, false, true, false, Confirmation.CLIENT));
        ports.reading(rows(errorRow(DEV)), rows(errorRow(DEV)));
        ports.action = () -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "StartCLI: No process found with id [110190387]!", "finished meanwhile",
                "Look the instance up again with find_processes").withNode(DEV));
        };

        ProcessControlResult result = result(restart("dev/node1", Optional.empty()));

        assertThat(result.outcome()).isEqualTo(ProcessControlResult.Outcome.FAILED);
        assertThat(result.message()).contains("NOT_FOUND").contains("No process found")
            .contains("find_processes");
        assertThat(result.stateAfter()).contains("ERROR");
        assertThat(audit.records).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith("NOT_FOUND: "));
        });
    }

    @Test
    void theExcerptOfUnrecognizedCliOutputIsPartOfTheFailedResult() {
        policies.put(DEV, policy(DEV, false, true, false, Confirmation.CLIENT));
        ports.reading(rows(errorRow(DEV)));
        ports.action = () -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                "Unrecognized StartCLI output (exit code 0)", "unknown", "See the excerpt")
                .withNode(DEV).withExcerpt("Process 110190387 maybe restarted?"));
        };

        ProcessControlResult result = result(restart("dev/node1", Optional.empty()));

        assertThat(result.outcome()).isEqualTo(ProcessControlResult.Outcome.FAILED);
        assertThat(result.message()).contains("UNEXPECTED_RESPONSE")
            .contains("Excerpt: Process 110190387 maybe restarted?");
    }

    @Test
    void anUnexpectedExceptionOfTheCliCallIsAFailedResult() {
        policies.put(DEV, policy(DEV, false, true, false, Confirmation.CLIENT));
        ports.reading(rows(errorRow(DEV)));
        ports.action = () -> {
            throw new IllegalStateException("boom");
        };

        ProcessControlResult result = result(kill("dev/node1", Optional.empty()));

        assertThat(result.outcome()).isEqualTo(ProcessControlResult.Outcome.FAILED);
        assertThat(result.message()).contains("INTERNAL");
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void aFailingFinalRecordIsReportedInTheResult() {
        policies.put(DEV, policy(DEV, false, true, false, Confirmation.CLIENT));
        ports.reading(rows(errorRow(DEV)));
        ports.duringAction = () -> audit.failing = true;

        ProcessControlResult result = result(kill("dev/node1", Optional.empty()));

        assertThat(result.outcome()).isEqualTo(ProcessControlResult.Outcome.EXECUTED);
        assertThat(result.message()).contains("audit record");
        assertThat(audit.outcomes()).containsExactly(AuditOutcome.PENDING);
    }

    @Test
    void theConfirmationCodeIsHandedToTheAuditOnlyAsInput() {
        ports.reading(rows(errorRow(DEV)));
        String code = challenge(kill("dev/node1", Optional.empty())).confirmationCode();

        result(kill("dev/node1", Optional.of(code)));

        assertThat(audit.records).allSatisfy(record -> {
            assertThat(record.reason().orElse("")).doesNotContain(code);
            assertThat(record.mcpClient()).contains("claude-code/2.1.0");
        });
    }

    /** Records every appended audit record; fails every append while {@code failing}. */
    private static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public void append(AuditRecord record) {
            if (failing) {
                throw new UncheckedIOException(new java.io.IOException("disk full"));
            }
            records.add(record);
        }

        List<AuditOutcome> outcomes() {
            return records.stream().map(AuditRecord::outcome).toList();
        }
    }
}
