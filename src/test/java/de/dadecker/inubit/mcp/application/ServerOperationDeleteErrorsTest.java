package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.entry;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ChangeOutcome;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ChangeResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.port.ServerOperationPort.ErrorProcesses;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.RecordingAuditPort;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T060, US4 AS4, FR-017, research D-14, spec edge case "nothing to do"):
 * {@code operate_server} {@code deleteErrorProcesses} – the preview of the processes in state
 * ERROR (count, at most 50 ids, the workflows), the set read again right before sending, the
 * count INUBIT reports, the empty set and the count-only set of more than 10,000 rows.
 */
class ServerOperationDeleteErrorsTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST_CLIENT = NodeId.parse("test/node2");
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String ACTION = "deleteErrorProcesses";
    private static final String REASON = "clean up after the outage";
    private static final String QUEUE_LOG = "GET /log/queueLog ERROR";

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\tprocessdelete error\tWRITE\tD\tUS4\treachable:operate_server/deleteErrorProcesses\tplanned\t-
        CLI\ttrace on|off\tWRITE\tA\tUS4\treachable:operate_server/setTrace\tplanned\t-
        """;

    private static WritePolicy policy(NodeId node, Confirmation confirmation) {
        return new WritePolicy(node, false, true, false, confirmation, Duration.ofMinutes(5),
            Optional.of("jdoe"));
    }

    private final Map<NodeId, WritePolicy> policies = Map.of(
        DEV, policy(DEV, Confirmation.SERVER),
        TEST_CLIENT, policy(TEST_CLIENT, Confirmation.CLIENT));

    private final FakeServerOperations server = new FakeServerOperations();
    private final RecordingAuditPort records = new RecordingAuditPort();
    private final AtomicLong ids = new AtomicLong();
    private final WriteChallengeRegistry registry =
        new WriteChallengeRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
    private final ServerOperationService service = service();

    private ServerOperationService service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions),
            new CapabilityMatrix(List.of(),
                Map.of(Capability.SERVER_OPERATION, SupportState.SUPPORTED)),
            InterfaceCoverage.load(new StringReader(LIST)));
        ChangeAudit audit = new ChangeAudit(records, clock, "acme",
            () -> new UUID(0, ids.incrementAndGet()));
        ChangeGuard guard = new ChangeGuard(new TargetResolver(List.copyOf(policies.keySet())),
            policies::get, (node, area) -> area == ActionSpec.Area.OPERATION, server, gate,
            audit);
        return new ServerOperationService(guard, new ChangeLocks(),
            new ChangeChallenges(registry), audit, server, clock);
    }

    private static List<String> processIds(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> String.format("P-%03d", i))
            .toList();
    }

    private ServerOperationService.Response call(NodeId node, Optional<String> code) {
        return service.execute(new ServerOperationService.Request(node.value(), ACTION, Map.of(),
            REASON, code, Optional.of("client/1.0")));
    }

    private ChangePreview preview() {
        ServerOperationService.Response response = call(DEV, Optional.empty());
        assertThat(response).isInstanceOf(ServerOperationService.Response.Preview.class);
        return ((ServerOperationService.Response.Preview) response).preview();
    }

    private ChangeResult confirm(ChangePreview preview) {
        ServerOperationService.Response response = call(DEV,
            Optional.of(preview.confirmation().orElseThrow().code()));
        assertThat(response).isInstanceOf(ServerOperationService.Response.Completed.class);
        return ((ServerOperationService.Response.Completed) response).result();
    }

    private List<AuditOutcome> outcomes() {
        return records.records.stream().map(AuditRecord::outcome).toList();
    }

    // --- AS4 ----------------------------------------------------------------------------------

    @Test
    void as4ThePreviewNamesTheCountTheIdsAndTheWorkflowsAndTheResultTheCountINUBITReports() {
        List<String> forty = processIds(40);
        server.errorProcesses = new ErrorProcesses(40, forty, List.of("billing", "orders"));

        ChangePreview preview = preview();

        assertThat(preview.area()).isEqualTo("operation");
        assertThat(preview.target()).isEqualTo("processes in state ERROR");
        assertThat(preview.before())
            .isEqualTo("40 processes in state ERROR in the workflows billing, orders");
        assertThat(preview.requested()).isEqualTo("delete every process in state ERROR");
        assertThat(preview.affected()).contains(new ChangePreview.Affected(40, forty, false));
        assertThat(preview.effects()).containsExactly(
            "The processes in state ERROR are deleted and cannot be restored.",
            "processdelete error deletes every process in state ERROR when it runs, also one that"
                + " entered the state after the preview; the result names the count INUBIT"
                + " reports.");
        assertThat(preview.backup())
            .isEqualTo("none: the processes are not exported before they are deleted");
        assertThat(preview.confirmation()).isPresent();
        assertThat(server.switches()).isEmpty();

        server.calls.clear();
        server.deletedCount = OptionalLong.of(41);
        ChangeResult result = confirm(preview);

        // after the admission's check the set is read again right before sending, and once more
        // afterwards
        assertThat(server.calls).containsExactly("check dev/node1", QUEUE_LOG,
            "processdelete error", QUEUE_LOG);
        assertThat(server.permits).singleElement().satisfies(permit -> {
            assertThat(permit.node()).isEqualTo(DEV);
            assertThat(permit.capability()).isEqualTo(Capability.SERVER_OPERATION);
        });
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.target()).isEqualTo("processes in state ERROR");
        assertThat(result.before()).isEqualTo(preview.before());
        assertThat(result.affectedCount()).isEqualTo(OptionalInt.of(41));
        assertThat(result.inubitAnswer()).isEqualTo("INUBIT reports 41 processes deleted");
        assertThat(result.after()).contains("no process in state ERROR");
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void moreThanFiftyIdsAreCountedAndTheFirstFiftyListed() {
        List<String> sixty = processIds(60);
        server.errorProcesses = new ErrorProcesses(60, sixty, List.of("billing"));

        ChangePreview preview = preview();

        assertThat(preview.affected()).contains(new ChangePreview.Affected(60,
            sixty.subList(0, ChangePreview.Affected.MAX_IDS), true));
        assertThat(preview.before())
            .isEqualTo("60 processes in state ERROR in the workflow billing");
    }

    /** A process with sub-workflows has several Queue Manager rows (research D-14). */
    @Test
    void moreRowsThanProcessesAreNamedNextToTheProcessCount() {
        server.errorProcesses = new ErrorProcesses(3, List.of("P-001", "P-002"),
            List.of("orders", "orders-sub"));

        ChangePreview preview = preview();

        assertThat(preview.before()).isEqualTo("2 processes in state ERROR (3 Queue Manager"
            + " rows) in the workflows orders, orders-sub");
        assertThat(preview.affected()).contains(new ChangePreview.Affected(2,
            List.of("P-001", "P-002"), false));
    }

    @Test
    void moreThanFiftyWorkflowsAreCutWithTheirCount() {
        List<String> workflows = IntStream.rangeClosed(1, 52)
            .mapToObj(i -> String.format("wf%02d", i)).toList();
        server.errorProcesses = new ErrorProcesses(52, processIds(52), workflows);

        assertThat(preview().before()).endsWith(", wf50 and 2 more");
    }

    // --- empty set ----------------------------------------------------------------------------

    @Test
    void anEmptySetIsNothingToDoAuditedAndNothingIsSent() {
        ServerOperationService.Response response = call(DEV, Optional.empty());

        assertThat(response).isInstanceOfSatisfying(
            ServerOperationService.Response.Completed.class, completed -> {
                ChangeResult result = completed.result();
                assertThat(result.outcome()).isEqualTo(ChangeOutcome.NOTHING_TO_DO);
                assertThat(result.before()).isEqualTo("no process in state ERROR");
                assertThat(result.after()).contains("no process in state ERROR");
                assertThat(result.inubitAnswer()).isEqualTo("nothing was sent");
                assertThat(result.affectedCount()).isEmpty();
                assertThat(result.auditId()).isEqualTo(records.records.get(0).auditId());
            });
        assertThat(server.switches()).isEmpty();
        assertThat(records.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.EXECUTED);
            assertThat(record.details()).containsExactly(entry("nothing-to-do", true));
            assertThat(record.reason()).contains("No process is in state ERROR on dev/node1;"
                + " nothing was sent");
        });
    }

    @Test
    void aSetEmptiedSinceThePreviewIsNothingToDoAndTheCodeIsUsedUp() {
        server.errorProcesses = new ErrorProcesses(2, processIds(2), List.of("orders"));
        ChangePreview preview = preview();
        server.errorProcesses = new ErrorProcesses(0, List.of(), List.of());

        assertThat(confirm(preview).outcome()).isEqualTo(ChangeOutcome.NOTHING_TO_DO);
        assertThat(server.switches()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.EXECUTED);

        server.errorProcesses = new ErrorProcesses(2, processIds(2), List.of("orders"));
        ToolErrorException reused = catchThrowableOfType(ToolErrorException.class,
            () -> confirm(preview));
        assertThat(reused.error().code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(server.switches()).isEmpty();
    }

    @Test
    void underClientConfirmationAnEmptySetIgnoresAPassedCode() {
        ServerOperationService.Response response = call(TEST_CLIENT, Optional.of("not-a-code"));

        assertThat(response).isInstanceOfSatisfying(
            ServerOperationService.Response.Completed.class, completed -> assertThat(
                completed.result().outcome()).isEqualTo(ChangeOutcome.NOTHING_TO_DO));
        assertThat(server.switches()).isEmpty();
    }

    @Test
    void aCountWithoutIdsIsPreviewedByTheCountAndDeletedNotNothingToDo() {
        // V81ServerOperationAdapter: the first page is empty while INUBIT reports a total
        server.errorProcesses = new ErrorProcesses(3, List.of(), List.of());

        ChangePreview preview = preview();

        assertThat(preview.affected()).contains(new ChangePreview.Affected(3, List.of(), true));
        assertThat(preview.before()).isEqualTo("3 Queue Manager rows in state ERROR; INUBIT"
            + " listed none of their process ids and workflows");
        ChangeResult result = confirm(preview);
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(server.switches()).containsExactly("processdelete error");
    }

    @Test
    void anEmptySetWithAnInvalidCodeIsConfirmationInvalid() {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> call(DEV, Optional.of("not-a-code")));

        assertThat(exception.error().code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- changed set (FR-017) -----------------------------------------------------------------

    @Test
    void aSetChangedSinceThePreviewIsAConflictWithANewPreviewAndNothingIsSent() {
        server.errorProcesses = new ErrorProcesses(2, List.of("P-001", "P-002"),
            List.of("orders"));
        ChangePreview preview = preview();
        // the same count, another process
        server.errorProcesses = new ErrorProcesses(2, List.of("P-001", "P-003"),
            List.of("billing", "orders"));

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> confirm(preview));

        assertThat(exception).isInstanceOfSatisfying(ChangeChallenges.Conflict.class,
            conflict -> {
                assertThat(conflict.error().code()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(conflict.preview().affected()).contains(new ChangePreview.Affected(2,
                    List.of("P-001", "P-003"), false));
                assertThat(conflict.preview().before())
                    .isEqualTo("2 processes in state ERROR in the workflows billing, orders");
                assertThat(conflict.preview().confirmation()).isPresent()
                    .isNotEqualTo(preview.confirmation());
            });
        assertThat(server.switches()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.REFUSED);

        // the new preview's code deletes the set it showed
        ChangeResult result = confirm(((ChangeChallenges.Conflict) exception).preview());
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(server.switches()).containsExactly("processdelete error");
    }

    @Test
    void theCodeKeepsAHashOfTheIdsNotTheIds() {
        server.errorProcesses = new ErrorProcesses(2, List.of("P-001", "P-002"),
            List.of("orders"));
        ChangePreview preview = preview();

        String kept = registry.redeem(preview.confirmation().orElseThrow().code(),
            ServerOperationService.TOOL, DEV, ChangeChallenges.fingerprint(Map.of("action",
                ACTION)));

        assertThat(kept).matches(ACTION + "=[0-9a-f]{64}").doesNotContain("P-00");
    }

    // --- more than 10,000 rows: the count only (D-14) -----------------------------------------

    @Test
    void moreThanTenThousandRowsArePreviewedAndComparedByTheirCountOnly() {
        server.errorProcesses = ErrorProcesses.countOnly(12_345);

        ChangePreview preview = preview();

        assertThat(preview.affected())
            .contains(new ChangePreview.Affected(12_345, List.of(), true));
        assertThat(preview.before()).isEqualTo("12345 Queue Manager rows in state ERROR; with"
            + " more than 10000 rows the process ids and workflows are not listed");

        server.errorProcesses = ErrorProcesses.countOnly(12_346);
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> confirm(preview));
        assertThat(exception).isInstanceOf(ChangeChallenges.Conflict.class);
        assertThat(server.switches()).isEmpty();

        ChangeResult result = confirm(((ChangeChallenges.Conflict) exception).preview());
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.affectedCount()).isEqualTo(OptionalInt.of(12_346));
    }

    // --- CLIENT confirmation and the lock key ------------------------------------------------

    @Test
    void underClientConfirmationTheFirstCallDeletesOnTheKeyOfTheAction() {
        server.errorProcesses = new ErrorProcesses(1, List.of("P-001"), List.of("orders"));
        AtomicReference<ToolError> second = new AtomicReference<>();
        AtomicReference<ServerOperationService.Response> other = new AtomicReference<>();
        server.duringSwitch = () -> {
            server.duringSwitch = () -> { };
            second.set(catchThrowableOfType(ToolErrorException.class,
                () -> call(TEST_CLIENT, Optional.empty())).error());
            other.set(service.execute(new ServerOperationService.Request(TEST_CLIENT.value(),
                "setTrace", Map.of("on", true), REASON, Optional.empty(), Optional.empty())));
        };

        ServerOperationService.Response response = call(TEST_CLIENT, Optional.empty());

        assertThat(response).isInstanceOfSatisfying(
            ServerOperationService.Response.Completed.class, completed -> assertThat(
                completed.result().affectedCount()).isEqualTo(OptionalInt.of(1)));
        // node|OPERATION|deleteErrorProcesses is held; node|OPERATION|setTrace is not
        assertThat(second.get().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(other.get()).isInstanceOf(ServerOperationService.Response.Completed.class);
        assertThat(server.switches()).containsExactly("processdelete error", "trace on");
    }

    // --- failures -------------------------------------------------------------------------------

    @Test
    void aFailedDeletionIsAFailedResultWithTheSetReadAgain() {
        server.errorProcesses = new ErrorProcesses(1, List.of("P-001"), List.of("orders"));
        server.switchFailure = new ToolErrorException(ToolError.of(
            ErrorCode.UNEXPECTED_RESPONSE, "INUBIT answered in an unknown form", "cause", "next")
            .withNode(DEV));

        ChangeResult result = confirm(preview());

        assertThat(result.outcome()).isEqualTo(ChangeOutcome.FAILED);
        assertThat(result.affectedCount()).isEmpty();
        assertThat(result.after()).contains("1 process in state ERROR in the workflow orders");
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void whileMaintenanceModeIsOnTheSetCannotBeReadAndTheCallIsRefused() {
        server.maintenance = true;

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> call(DEV, Optional.empty()));

        assertThat(exception.error().code()).isEqualTo(ErrorCode.MAINTENANCE_MODE);
        assertThat(server.switches()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    // --- leak check (SC-005) --------------------------------------------------------------------

    @Test
    void neitherPreviewNorResultNorAuditRepeatASecretOfINUBITsAnswers() {
        server.errorProcesses = new ErrorProcesses(1, List.of("P-001"),
            List.of("orders " + TestSecrets.FREE_TEXT_SECRETS));

        ChangePreview preview = preview();
        ChangeResult result = confirm(preview);

        TestSecrets.assertNoLeak(preview.toString());
        TestSecrets.assertNoLeak(result.toString());
        TestSecrets.assertNoLeak(records.records.toString());
    }
}
