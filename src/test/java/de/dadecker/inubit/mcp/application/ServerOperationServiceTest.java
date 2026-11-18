package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ChangeOutcome;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ChangeResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.RecordingAuditPort;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 022 (T059, US4 AS1–AS3, AS5–AS11, research D-12–D-14, D-17, D-22): the switch actions
 * of {@code operate_server} – admission, preview with the state before and the fixed effect
 * sentences, confirmation, the state read again afterwards, the lock per action and the audit
 * records.
 */
class ServerOperationServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST_READ_ONLY = NodeId.parse("test/node1");
    private static final NodeId TEST_CLIENT = NodeId.parse("test/node2");
    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String REASON = "night window";

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\tmaintenance on|off\tWRITE\tA\tUS4\treachable:operate_server/setMaintenance\tplanned\t-
        CLI\tmaintenance -eq true|false\tWRITE\tA\tUS4\treachable:operate_server/setMaintenance\tplanned\t-
        REST\tPOST /ready/{status}\tWRITE\tA\tUS4\treachable:operate_server/setReadiness\tplanned\t-
        CLI\ttrace on|off\tWRITE\tA\tUS4\treachable:operate_server/setTrace\tplanned\t-
        CLI\ttrace -l\tWRITE\tA\tUS4\treachable:operate_server/setTraceLevel\tplanned\t-
        CLI\ttriggerMidnightTask\tWRITE\tA\tUS4\treachable:operate_server/triggerMidnightTask\tplanned\t-
        """;

    private static WritePolicy policy(NodeId node, boolean production, boolean enabled,
        Confirmation confirmation) {
        return new WritePolicy(node, production, enabled, false, confirmation,
            Duration.ofMinutes(5), Optional.of("jdoe"));
    }

    private final Map<NodeId, WritePolicy> policies = Map.of(
        DEV, policy(DEV, false, true, Confirmation.SERVER),
        TEST_READ_ONLY, policy(TEST_READ_ONLY, false, false, Confirmation.SERVER),
        TEST_CLIENT, policy(TEST_CLIENT, false, true, Confirmation.CLIENT),
        PROD, policy(PROD, true, true, Confirmation.SERVER));

    private final FakeServerOperations server = new FakeServerOperations();
    private final RecordingAuditPort records = new RecordingAuditPort();
    /** Every audit record the service tried to write, also those {@code records} refused. */
    private final List<AuditRecord> attempted = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private ServerOperationService service = service(new NodeVersions(Clock.systemUTC(),
        node -> VersionSetting.V8_1, node -> Duration.ofMinutes(10)));

    /**
     * The service over {@code versions}; the version detection of {@link #DEV} (on {@code AUTO})
     * fails with {@code MAINTENANCE_MODE}.
     */
    private ServerOperationService service(NodeVersions versions) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions).fail(DEV, ErrorCode.MAINTENANCE_MODE),
            new CapabilityMatrix(List.of(),
                Map.of(Capability.SERVER_OPERATION, SupportState.SUPPORTED)),
            InterfaceCoverage.load(new StringReader(LIST)));
        ChangeAudit audit = new ChangeAudit(record -> {
            attempted.add(record);
            records.append(record);
        }, clock, "acme", () -> new UUID(0, ids.incrementAndGet()));
        ChangeGuard guard = new ChangeGuard(new TargetResolver(List.copyOf(policies.keySet())),
            policies::get, (node, area) -> area == ActionSpec.Area.OPERATION, server, gate,
            audit);
        return new ServerOperationService(guard, new ChangeLocks(),
            new ChangeChallenges(new WriteChallengeRegistry(clock)), audit, server, clock);
    }

    /** Every node on {@code versionLine: AUTO} with {@code cacheTtl} 10 minutes. */
    private static NodeVersions autoVersions(Clock clock) {
        return new NodeVersions(clock, node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    }

    private static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            fields.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return fields;
    }

    private ServerOperationService.Response call(NodeId node, String action,
        Map<String, Object> fields, Optional<String> code) {
        return service.execute(new ServerOperationService.Request(node.value(), action, fields,
            REASON, code, Optional.of("client/1.0")));
    }

    private ChangePreview preview(NodeId node, String action, Map<String, Object> fields) {
        ServerOperationService.Response response = call(node, action, fields, Optional.empty());
        assertThat(response).isInstanceOf(ServerOperationService.Response.Preview.class);
        return ((ServerOperationService.Response.Preview) response).preview();
    }

    private ServerOperationService.Response.Completed confirm(ChangePreview preview,
        Map<String, Object> fields) {
        ServerOperationService.Response response = call(preview.node(), preview.action(),
            fields, Optional.of(preview.confirmation().orElseThrow().code()));
        assertThat(response).isInstanceOf(ServerOperationService.Response.Completed.class);
        return (ServerOperationService.Response.Completed) response;
    }

    private ChangeResult previewAndConfirm(String action, Map<String, Object> fields) {
        return confirm(preview(DEV, action, fields), fields).result();
    }

    private ToolError refusal(Runnable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(exception).as("expected a refusal").isNotNull();
        return exception.error();
    }

    private List<AuditOutcome> outcomes() {
        return records.records.stream().map(AuditRecord::outcome).toList();
    }

    // --- AS1–AS3: admission and the two calls -------------------------------------------------

    @Test
    void as1WithoutWriteEnablementTheCallIsRefusedNamingTheSetting() {
        ToolError error = refusal(() -> call(TEST_READ_ONLY, "setMaintenance",
            fields("on", true), Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.WRITE_DISABLED);
        assertThat(error.nextStep()).contains("write.enabled: true");
        assertThat(server.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void as2ThePreviewNamesTheStatesAndTheLoadAndTheCodeSwitchesAndReReads() {
        ChangePreview preview = preview(DEV, "setMaintenance", fields("on", true));

        assertThat(preview.node()).isEqualTo(DEV);
        assertThat(preview.tool()).isEqualTo("operate_server");
        assertThat(preview.area()).isEqualTo("operation");
        assertThat(preview.target()).isEqualTo("maintenance mode");
        assertThat(preview.before()).isEqualTo("off (running: 3 workflow threads in use;"
            + " queued: 12 entries in the blocking queue)");
        assertThat(preview.requested()).isEqualTo("on");
        assertThat(preview.affected()).isEmpty();
        assertThat(preview.backup()).isEqualTo("none: nothing is removed or replaced");
        assertThat(preview.confirmation()).isPresent();
        assertThat(server.switches()).isEmpty();
        assertThat(records.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED);
            assertThat(record.inputs()).containsEntry("action", "setMaintenance")
                .containsEntry("on", "true").containsEntry("reason", REASON);
        });

        ServerOperationService.Response.Completed completed =
            confirm(preview, fields("on", true));
        ChangeResult result = completed.result();

        assertThat(server.switches()).containsExactly("maintenance on");
        assertThat(server.permits).singleElement().satisfies(permit -> {
            assertThat(permit.node()).isEqualTo(DEV);
            assertThat(permit.capability()).isEqualTo(Capability.SERVER_OPERATION);
        });
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.target()).isEqualTo("maintenance mode");
        assertThat(result.before()).startsWith("off");
        assertThat(result.inubitAnswer()).isEqualTo("Updated Successfully");
        assertThat(result.after()).contains("on");
        assertThat(result.restartNeeded()).isEqualTo(ChangeResult.RestartNeeded.FALSE);
        assertThat(completed.warnings()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(records.records.get(2).auditId()).isEqualTo(records.records.get(1).auditId())
            .isEqualTo(result.auditId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"setMaintenance", "setReadiness", "setTrace", "setTraceLevel",
        "triggerMidnightTask"})
    void as3AProductionServerWithoutOptInRefusesEveryAction(String action) {
        Map<String, Object> fields = switch (action) {
            case "setMaintenance", "setTrace" -> fields("on", true);
            case "setReadiness" -> fields("ready", false);
            case "setTraceLevel" -> fields("level", "debug");
            default -> fields();
        };

        assertThat(refusal(() -> call(PROD, action, fields, Optional.empty())).code())
            .isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(server.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- AS5: the trace level -------------------------------------------------------------------

    /** T054: the level before and after is {@code TracingLevel} of the system information. */
    @Test
    void as5TheResultNamesTheLevelBeforeAndAfterFromTheSystemInformation() {
        server.traceLevel = TraceLevel.ERROR;

        ChangePreview preview = preview(DEV, "setTraceLevel", fields("level", "debug"));
        assertThat(preview.before()).isEqualTo("error");
        assertThat(preview.requested()).isEqualTo("debug");
        assertThat(preview.effects())
            .containsExactly("The trace level stays debug until it is set back.");
        assertThat(preview.warnings()).isEmpty();

        ChangeResult result = confirm(preview, fields("level", "debug")).result();

        assertThat(server.switches()).containsExactly("trace -l debug");
        assertThat(server.calls).containsExactly("check dev/node1", "GET /system",
            "check dev/node1", "GET /system", "trace -l debug", "GET /system");
        assertThat(result.before()).isEqualTo("error");
        assertThat(result.inubitAnswer()).isEqualTo("trace level debug");
        assertThat(result.after()).contains("debug");
        assertThat(result.reReadFailed()).isEmpty();
    }

    /** A {@code TracingLevel} outside the list is never guessed: unknown, with a warning. */
    @Test
    void as5AnUnknownTracingLevelIsUnknownWithAWarning() {
        server.tracingLevelReported = Optional.of("verbose");
        String unknown = "INUBIT's system information names no known trace level";

        ChangePreview preview = preview(DEV, "setTraceLevel", fields("level", "debug"));

        assertThat(preview.before()).isEqualTo("unknown: " + unknown);
        assertThat(preview.warnings())
            .containsExactly("The current trace level cannot be shown: " + unknown);

        ServerOperationService.Response.Completed completed =
            confirm(preview, fields("level", "debug"));

        assertThat(completed.result().before()).isEqualTo("unknown: " + unknown);
        assertThat(completed.result().inubitAnswer()).isEqualTo("trace level debug");
        assertThat(completed.result().after()).isEmpty();
        assertThat(completed.result().reReadFailed()).contains(unknown);
    }

    // --- AS6, AS7: maintenance mode -------------------------------------------------------------

    @Test
    void as6MaintenanceModeIsSwitchedOffWhileRestAnswersServiceUnavailable() {
        server.maintenance = true;

        ChangePreview preview = preview(DEV, "setMaintenance", fields("on", false));
        assertThat(preview.before()).isEqualTo("on");
        assertThat(preview.requested()).isEqualTo("off");

        ChangeResult result = confirm(preview, fields("on", false)).result();

        assertThat(server.switches()).containsExactly("maintenance off");
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.after()).contains("off");
        assertThat(server.calls).noneMatch(call -> call.startsWith("GET "));
    }

    /**
     * AS6 on {@code versionLine: AUTO}: the version detected more than {@code cacheTtl} ago cannot
     * be detected again while REST answers 503, so switching maintenance off is judged on it.
     */
    @Test
    void as6OnAutoMaintenanceIsSwitchedOffOnTheLastKnownVersionWhileDetectionFails() {
        MutableClock clock = new MutableClock(NOW);
        NodeVersions versions = autoVersions(clock);
        versions.detected(DEV, InubitVersion.parse("8.1.17").orElseThrow());
        clock.advance(Duration.ofMinutes(11));
        service = service(versions);
        server.maintenance = true;

        ChangePreview preview = preview(DEV, "setMaintenance", fields("on", false));
        ChangeResult result = confirm(preview, fields("on", false)).result();

        assertThat(versions.state(DEV).detectionFailure()).map(failure -> failure.value().code())
            .contains(ErrorCode.MAINTENANCE_MODE);
        assertThat(server.switches()).containsExactly("maintenance off");
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        // the node's write lock hands the switch off on that version
        Optional<InubitVersion> handedOff = versions.handOff(server.permits.get(0),
            version -> version);
        assertThat(handedOff).map(InubitVersion::raw).contains("8.1.17");
    }

    @Test
    void onAutoWithoutAnyKnownVersionSwitchingMaintenanceOffIsRefusedNamingThePin() {
        service = service(autoVersions(new MutableClock(NOW)));
        server.maintenance = true;

        ToolError error = refusal(() -> call(DEV, "setMaintenance", fields("on", false),
            Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("unknown (MAINTENANCE_MODE at ");
        assertThat(error.nextStep()).startsWith("Set versionLine of " + DEV)
            .doesNotContain("get_health");
        assertThat(server.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"setTrace", "setMaintenanceOn"})
    void onAutoNoOtherActionIsJudgedOnTheLastKnownVersion(String variant) {
        MutableClock clock = new MutableClock(NOW);
        NodeVersions versions = autoVersions(clock);
        versions.detected(DEV, InubitVersion.parse("8.1.17").orElseThrow());
        clock.advance(Duration.ofMinutes(11));
        service = service(versions);
        server.maintenance = true;

        ToolError error = refusal(() -> call(DEV, variant.equals("setTrace") ? "setTrace"
            : "setMaintenance", fields("on", true), Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(server.calls).isEmpty();
    }

    /** {@code MAINTENANCE_MODE} (REST answering 503) for the state read counts as "on". */
    @Test
    void aMaintenanceModeErrorOfTheStateReadCountsAsOn() {
        server.maintenance = true;
        server.maintenanceReadFailure = new ToolErrorException(ToolError.of(
            ErrorCode.MAINTENANCE_MODE, "dev/node1 is in maintenance mode", "HTTP 503", "Wait")
            .withNode(DEV));

        ChangePreview preview = preview(DEV, "setMaintenance", fields("on", false));

        assertThat(preview.before()).isEqualTo("on");
    }

    @Test
    void as7ThePreviewNamesTheEqValueThatIsSentAndWhatItMeans() {
        ChangePreview withTrue = preview(DEV, "setMaintenance",
            fields("on", true, "executeQueued", true));
        ChangePreview withFalse = preview(DEV, "setMaintenance",
            fields("on", true, "executeQueued", false));
        ChangePreview without = preview(DEV, "setMaintenance", fields("on", true));

        String maintenance = "In maintenance mode the process engine executes no processes, and"
            + " INUBIT's REST API answers every request with \"service unavailable\".";
        assertThat(withTrue.requested()).isEqualTo("on with -eq true");
        assertThat(withTrue.effects()).containsExactly(maintenance, "-eq true is sent: the"
            + " processes already queued are still executed while maintenance mode is on.");
        assertThat(withFalse.requested()).isEqualTo("on with -eq false");
        assertThat(withFalse.effects()).containsExactly(maintenance, "-eq false is sent: the"
            + " processes already queued are not executed while maintenance mode is on.");
        assertThat(without.effects()).containsExactly(maintenance, "-eq is not sent: the"
            + " processes already queued are not executed while maintenance mode is on; only"
            + " -eq true executes them.");

        confirm(withTrue, fields("on", true, "executeQueued", true));
        assertThat(server.switches()).containsExactly("maintenance on -eq true");
    }

    @Test
    void switchingMaintenanceOffNamesTheMaintenanceEffectOnly() {
        server.maintenance = true;

        assertThat(preview(DEV, "setMaintenance", fields("on", false)).effects())
            .containsExactly("In maintenance mode the process engine executes no processes, and"
                + " INUBIT's REST API answers every request with \"service unavailable\".");
    }

    // --- AS8, AS10: debug (excluded) and trace ------------------------------------------------

    /** T054: {@code debug on|off} had no observable server effect, so it is no action. */
    @Test
    void as8SetDebugIsAnUnknownActionRefusedBeforeAnythingIsRead() {
        ToolError error = refusal(() -> call(DEV, "setDebug", fields("on", true),
            Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("The action is not one of setMaintenance,"
            + " setReadiness, setTrace, setTraceLevel, triggerMidnightTask, deleteErrorProcesses;"
            + " nothing was changed");
        assertThat(server.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void as10TheResultNamesTheTraceStateBeforeAndAfter() {
        ChangePreview preview = preview(DEV, "setTrace", fields("on", true));
        assertThat(preview.target()).isEqualTo("trace");
        assertThat(preview.before()).isEqualTo("off");
        assertThat(preview.effects())
            .containsExactly("The trace stays on until it is switched off.");

        ChangeResult result = confirm(preview, fields("on", true)).result();

        assertThat(server.switches()).containsExactly("trace on");
        assertThat(result.before()).isEqualTo("off");
        assertThat(result.inubitAnswer()).isEqualTo("trace on was sent; INUBIT answered without"
            + " text");
        assertThat(result.after()).contains("on");
    }

    @Test
    void whileMaintenanceModeIsOnTheTraceStateIsUnknownWithAWarning() {
        server.maintenance = true;

        ChangePreview preview = preview(DEV, "setTrace", fields("on", false));

        assertThat(preview.before()).isEqualTo("unknown: maintenance mode is on and INUBIT's"
            + " REST API answers \"service unavailable\"");
        assertThat(preview.effects())
            .containsExactly("The trace stays off until it is switched on.");
        assertThat(preview.warnings()).hasSize(1);
    }

    // --- AS9: readiness ---------------------------------------------------------------------

    @Test
    void as9ThePreviewNamesBothReadinessStatesAndTheMaintenanceRuleAndTheResultReReads() {
        ChangePreview preview = preview(DEV, "setReadiness", fields("ready", false));

        assertThat(preview.target()).isEqualTo("readiness");
        assertThat(preview.before()).isEqualTo("READY");
        assertThat(preview.requested()).isEqualTo("NOT READY");
        assertThat(preview.effects()).containsExactly(
            "The readiness stays NOT READY while maintenance mode is on.");

        server.calls.clear();
        ChangeResult result = confirm(preview, fields("ready", false)).result();

        assertThat(server.calls).containsExactly("GET /ready", "POST /ready/off", "GET /ready");
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.inubitAnswer()).isEqualTo("INUBIT accepted POST /ready/off");
        assertThat(result.before()).isEqualTo("READY");
        assertThat(result.after()).contains("NOT READY");
    }

    /**
     * A refused answer of the readiness switch (the adapter's {@code UNEXPECTED_RESPONSE} for a
     * 503 without NOT READY, research D-22) is a failed result with the state read again.
     */
    @Test
    void as9AnUnexpectedReadinessAnswerIsAFailedResultWithTheStateReadAgain() {
        server.switchFailure = new ToolErrorException(ToolError.of(
            ErrorCode.UNEXPECTED_RESPONSE, "INUBIT answered POST /ready/off with HTTP 503 but"
                + " not with the state NOT READY", "cause", "next").withNode(DEV));

        ChangeResult result = previewAndConfirm("setReadiness", fields("ready", false));

        assertThat(result.outcome()).isEqualTo(ChangeOutcome.FAILED);
        assertThat(result.inubitAnswer()).startsWith("UNEXPECTED_RESPONSE: INUBIT answered"
            + " POST /ready/off with HTTP 503");
        assertThat(result.after()).contains("READY");
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    // --- AS11: midnight task -------------------------------------------------------------------

    @Test
    void as11ThePreviewNamesTheStartTimeAndTheResultTheAnswerAndTheTimeSent() {
        ChangePreview preview = preview(DEV, "triggerMidnightTask", fields());

        assertThat(preview.target()).isEqualTo("midnight task");
        assertThat(preview.before())
            .isEqualTo("start time (MidnightTaskTriggerTime): Sat Oct 31 00:00:00 CET 2026");
        assertThat(preview.requested()).isEqualTo("run now");
        assertThat(preview.effects()).containsExactly(
            "The daily midnight task runs at once instead of at its configured start time.",
            "The vendor does not document what the midnight task does.");

        ChangeResult result = confirm(preview, fields()).result();

        assertThat(server.switches()).containsExactly("triggerMidnightTask");
        assertThat(result.inubitAnswer())
            .isEqualTo("triggerMidnightTask was sent; INUBIT answered without text");
        assertThat(result.after()).contains("sent at 2026-11-17T10:00:00Z");
    }

    @Test
    void as11AStartTimeINUBITDoesNotReportIsSaidSo() {
        server.midnightTaskTriggerTime = Optional.empty();

        assertThat(preview(DEV, "triggerMidnightTask", fields()).before())
            .isEqualTo("start time not reported by INUBIT");
    }

    // --- CLIENT confirmation, locks, conflicts -------------------------------------------------

    @Test
    void clientConfirmationOnANonProductionServerExecutesOnTheFirstCall() {
        ServerOperationService.Response response = call(TEST_CLIENT, "setTrace",
            fields("on", true), Optional.empty());

        assertThat(response).isInstanceOfSatisfying(
            ServerOperationService.Response.Completed.class, completed -> {
                assertThat(completed.result().outcome()).isEqualTo(ChangeOutcome.EXECUTED);
                assertThat(completed.result().after()).contains("on");
            });
        assertThat(server.switches()).containsExactly("trace on");
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void aSecondCallOnTheSameKeyIsPreconditionFailedAndAnotherActionIsNot() {
        AtomicReference<ToolError> second = new AtomicReference<>();
        AtomicReference<ServerOperationService.Response> other = new AtomicReference<>();
        server.duringSwitch = () -> {
            server.duringSwitch = () -> { };
            second.set(refusal(() -> call(TEST_CLIENT, "setTrace", fields("on", false),
                Optional.empty())));
            other.set(call(TEST_CLIENT, "setTraceLevel", fields("level", "info"),
                Optional.empty()));
        };

        call(TEST_CLIENT, "setTrace", fields("on", true), Optional.empty());

        assertThat(second.get().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(other.get()).isInstanceOf(ServerOperationService.Response.Completed.class);
        assertThat(server.switches()).containsExactly("trace on", "trace -l info");
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED,
            AuditOutcome.PENDING, AuditOutcome.EXECUTED, AuditOutcome.EXECUTED);
    }

    @Test
    void theKeyIsReleasedAfterTheCall() {
        call(TEST_CLIENT, "setTrace", fields("on", true), Optional.empty());

        assertThat(call(TEST_CLIENT, "setTrace", fields("on", false), Optional.empty()))
            .isInstanceOf(ServerOperationService.Response.Completed.class);
    }

    @Test
    void aStateChangedSinceThePreviewIsAConflictWithAFreshPreviewAndNothingIsSent() {
        ChangePreview preview = preview(DEV, "setTrace", fields("on", true));
        server.trace = true;

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> confirm(preview, fields("on", true)));

        assertThat(exception).isInstanceOfSatisfying(ChangeChallenges.Conflict.class,
            conflict -> {
                assertThat(conflict.error().code()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(conflict.preview().before()).isEqualTo("on");
                assertThat(conflict.preview().confirmation()).isPresent()
                    .isNotEqualTo(preview.confirmation());
            });
        assertThat(server.switches()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.REFUSED);
    }

    @Test
    void aCodeForOtherInputsIsConfirmationInvalidAndNothingIsSent() {
        ChangePreview preview = preview(DEV, "setTrace", fields("on", true));

        ToolError error = refusal(() -> call(DEV, "setTraceLevel", fields("level", "debug"),
            Optional.of(preview.confirmation().orElseThrow().code())));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(server.switches()).isEmpty();
        assertThat(records.records.get(1).outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(records.records.get(1).step()).isEqualTo(AuditRecord.Step.EXECUTE);
    }

    // --- failures -------------------------------------------------------------------------------

    @Test
    void aFailedSwitchIsAFailedResultWithTheStateReadAgain() {
        server.switchFailure = new ToolErrorException(ToolError.of(
            ErrorCode.UNEXPECTED_RESPONSE, "INUBIT answered in an unknown form", "cause", "next")
            .withNode(DEV));

        ChangeResult result = previewAndConfirm("setTrace", fields("on", true));

        assertThat(result.outcome()).isEqualTo(ChangeOutcome.FAILED);
        assertThat(result.inubitAnswer())
            .isEqualTo("UNEXPECTED_RESPONSE: INUBIT answered in an unknown form");
        assertThat(result.after()).contains("off");
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void aFailedReadBeforeTheChangeRefusesTheCall() {
        server.maintenanceReadFailure = new ToolErrorException(ToolError.of(
            ErrorCode.CLI_UNAVAILABLE, "StartCLI failed", "cause", "next").withNode(DEV));

        assertThat(refusal(() -> call(DEV, "setMaintenance", fields("on", true),
            Optional.empty())).code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void aFailedReReadIsNamedInTheResult() {
        server.duringSwitch = () -> server.maintenanceReadFailure = new ToolErrorException(
            ToolError.of(ErrorCode.TIMEOUT, "maintenance -s timed out", "cause", "next")
                .withNode(DEV));

        ChangeResult result = previewAndConfirm("setMaintenance", fields("on", true));

        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.after()).isEmpty();
        assertThat(result.reReadFailed()).contains("TIMEOUT: maintenance -s timed out");
    }

    @Test
    void aPreviewWhoseAuditRecordCannotBeWrittenIsInternalAndIssuesNoCode() {
        records.failing.add(AuditOutcome.CHALLENGE_ISSUED);

        assertThat(refusal(() -> call(DEV, "setTrace", fields("on", true), Optional.empty()))
            .code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(records.records).isEmpty();

        // the code of the unwritten record was withdrawn
        String code = attempted.get(0).inputs().get(AuditRecord.ISSUED_CONFIRMATION_CODE);
        assertThat(code).isNotBlank();
        assertThat(refusal(() -> call(DEV, "setTrace", fields("on", true), Optional.of(code)))
            .code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(server.switches()).isEmpty();
    }

    @Test
    void anUnwrittenPendingRecordSendsNothing() {
        records.failing.add(AuditOutcome.PENDING);

        assertThat(refusal(() -> call(TEST_CLIENT, "setTrace", fields("on", true),
            Optional.empty())).code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(server.switches()).isEmpty();
    }

    @Test
    void anUnwrittenFinalRecordIsAWarningOfTheResult() {
        records.failing.add(AuditOutcome.EXECUTED);

        ServerOperationService.Response response = call(TEST_CLIENT, "setTrace",
            fields("on", true), Optional.empty());

        assertThat(((ServerOperationService.Response.Completed) response).warnings())
            .singleElement().asString().contains("final audit record could not be written");
    }

    // --- input validation (FR-020) ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"executeQueuedWithOff", "unknownAction", "missingField",
        "unknownField", "wrongType", "unknownLevel", "reasonWithNewline"})
    void anInvalidInputIsRefusedBeforeAnythingIsReadAndAudited(String variant) {
        String action = switch (variant) {
            case "unknownAction" -> "restartServer";
            case "unknownLevel" -> "setTraceLevel";
            default -> "setMaintenance";
        };
        Map<String, Object> fields = switch (variant) {
            case "executeQueuedWithOff" -> fields("on", false, "executeQueued", true);
            case "missingField" -> fields("executeQueued", true);
            case "unknownField" -> fields("on", true, "force", true);
            case "wrongType" -> fields("on", "yes");
            case "unknownLevel" -> fields("level", "trace");
            default -> fields("on", true);
        };
        String reason = variant.equals("reasonWithNewline") ? "line one\nline two" : REASON;

        ToolError error = refusal(() -> service.execute(new ServerOperationService.Request(
            DEV.value(), action, fields, reason, Optional.empty(), Optional.empty())));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(server.calls).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- leak check (SC-005) --------------------------------------------------------------------

    @Test
    void neitherPreviewNorResultNorAuditRepeatASecretOfINUBITsAnswers() {
        server.midnightTaskTriggerTime = Optional.of("00:00 " + TestSecrets.FREE_TEXT_SECRETS);

        ChangePreview preview = preview(DEV, "triggerMidnightTask", fields());
        ServerOperationService.Response.Completed completed = confirm(preview, fields());

        TestSecrets.assertNoLeak(preview.toString());
        TestSecrets.assertNoLeak(completed.toString());
        TestSecrets.assertNoLeak(records.records.toString());
    }
}
