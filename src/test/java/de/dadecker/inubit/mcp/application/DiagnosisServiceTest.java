package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.DiagnosisService.LogRequest;
import de.dadecker.inubit.mcp.application.DiagnosisService.ProcessRequest;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T069: the use cases behind {@code find_processes} and {@code query_logs} with fake ports and a
 * fixed clock (FR-009 – FR-013, research R-8, R-9).
 */
@Timeout(30)
class DiagnosisServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final NodeId QA2 = NodeId.parse("qa/node2");
    private static final List<NodeId> SERVERS = List.of(DEV, QA1, QA2);
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private final MutableClock clock = new MutableClock(NOW);
    private final Map<NodeId, FakeProcesses> processes = new ConcurrentHashMap<>();
    private final Map<NodeId, FakeLogs> logs = new ConcurrentHashMap<>();
    private final Map<NodeId, Duration> thresholds = new HashMap<>(Map.of(
        DEV, Duration.ofMinutes(90), QA1, Duration.ofMinutes(60), QA2, Duration.ofMinutes(60)));
    private ResultLimiter limiter = ResultLimiter.withDefaults();
    private Function<Object, Integer> sizes = item -> 100;

    private FakeProcesses processes(NodeId server) {
        return processes.computeIfAbsent(server, s -> new FakeProcesses());
    }

    private FakeLogs logs(NodeId server) {
        return logs.computeIfAbsent(server, s -> new FakeLogs());
    }

    private DiagnosisService service() {
        GatewayFactory gateways = new GatewayFactory() {
            @Override
            public Gateway forServer(NodeId server) {
                throw new AssertionError("US2 must not wait for a version detection");
            }

            @Override
            public MonitoringPort monitoring(NodeId server) {
                throw new AssertionError("US2 does not use the monitoring");
            }

            @Override
            public ProcessQueryPort processes(NodeId server) {
                return DiagnosisServiceTest.this.processes(server);
            }

            @Override
            public LogPort logs(NodeId server) {
                return DiagnosisServiceTest.this.logs(server);
            }

            @Override
            public InventoryPort inventory(NodeId server) {
                throw new AssertionError("no inventory here");
            }

            @Override
            public ProcessControlPort processControl(NodeId server) {
                throw new AssertionError("no process control here");
            }

            @Override
            public Optional<Gateway> knownGateway(NodeId server) {
                return Optional.empty();
            }
        };
        return new DiagnosisService(gateways, new TargetResolver(SERVERS), new FanOut(),
            limiter, server -> TIMEOUT, thresholds::get, clock, item -> sizes.apply(item));
    }

    private static ProcessRequest processRequest(String target) {
        return new ProcessRequest(target, Set.of(), false, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), 0, 50);
    }

    private static ProcessRequest withStates(ProcessRequest r, Set<ProcessState> states,
        boolean hangingOnly, Optional<Integer> threshold) {
        return new ProcessRequest(r.target(), states, hangingOnly, threshold, r.workflow(),
            r.tag(), r.since(), r.until(), r.offset(), r.limit());
    }

    private static ProcessRequest withTimes(ProcessRequest r, Optional<String> since,
        Optional<String> until) {
        return new ProcessRequest(r.target(), r.states(), r.hangingOnly(),
            r.hangingThresholdMinutes(), r.workflow(), r.tag(), since, until, r.offset(),
            r.limit());
    }

    private static ProcessRequest withPaging(ProcessRequest r, int offset, int limit) {
        return new ProcessRequest(r.target(), r.states(), r.hangingOnly(),
            r.hangingThresholdMinutes(), r.workflow(), r.tag(), r.since(), r.until(), offset,
            limit);
    }

    private static LogRequest logRequest(String target, LogType type) {
        return new LogRequest(target, type, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Set.of(), Optional.empty(), 0, 50);
    }

    private static ProcessInstance instance(NodeId server, String id, ProcessState state,
        Instant since) {
        // deliberately wrong derived fields: the service computes them (FR-010)
        return new ProcessInstance(server, id, Optional.of(id), state,
            state.rawValues().isEmpty() ? "Suspended" : state.rawValues().get(0), since,
            Duration.ZERO, false, Optional.of("W"), Optional.of("M"), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of("OWNERS"), Optional.of("normal"));
    }

    private static <T> Page<T> page(List<T> items, int offset, int limit, long total,
        boolean truncated) {
        int next = offset + items.size();
        return new Page<>(items, offset, limit, OptionalLong.of(total), false, truncated,
            next < total ? OptionalInt.of(next) : OptionalInt.empty());
    }

    private static LogEntry entry(NodeId server, long millis) {
        return new LogEntry(server, LogType.SYSTEM_LOG, Optional.of(Instant.ofEpochMilli(millis)),
            Severity.ERROR, Optional.of("false"), Optional.of("W"), Optional.empty(),
            Optional.of("1"), Optional.of("failed"), Map.of());
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    private static <T> T single(List<NodeResult<T>> results) {
        assertThat(results).hasSize(1);
        assertThat(results.get(0).error()).isEmpty();
        return results.get(0).payload().orElseThrow();
    }

    // --- time range --------------------------------------------------------------------------

    @Test
    void aDurationSinceMeansNowMinusTheDurationAndIsoTimestampsAreAccepted() {
        DiagnosisService service = service();

        service.findProcesses(withTimes(processRequest("dev/node1"), Optional.of("PT24H"),
            Optional.of("2026-10-01T13:30:00+02:00")));
        service.findProcesses(withTimes(processRequest("dev/node1"),
            Optional.of("2026-09-30T08:00:00Z"), Optional.empty()));

        List<ProcessQuery> queries = processes(DEV).queries();
        assertThat(queries.get(0).since()).contains(NOW.minus(Duration.ofHours(24)));
        assertThat(queries.get(0).until()).contains(Instant.parse("2026-10-01T11:30:00Z"));
        assertThat(queries.get(0).now()).isEqualTo(NOW);
        assertThat(queries.get(1).since()).contains(Instant.parse("2026-09-30T08:00:00Z"));
        assertThat(queries.get(1).until()).isEmpty();
    }

    @Test
    void anUnparseableOrInvertedTimeRangeIsInvalidInputBeforeAnyCall() {
        DiagnosisService service = service();

        ToolError garbage = errorOf(() -> service.findProcesses(withTimes(
            processRequest("dev/node1"), Optional.of("yesterday"), Optional.empty())));
        ToolError noOffset = errorOf(() -> service.queryLogs(new LogRequest("dev/node1",
            LogType.SYSTEM_LOG, Optional.of("2026-10-01T08:00:00"), Optional.empty(),
            Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), 0, 5)));
        ToolError inverted = errorOf(() -> service.findProcesses(withTimes(
            processRequest("dev/node1"), Optional.of("PT1H"), Optional.of("PT2H"))));
        ToolError negative = errorOf(() -> service.findProcesses(withTimes(
            processRequest("dev/node1"), Optional.of("-PT1H"), Optional.empty())));

        for (ToolError error : List.of(garbage, noOffset, inverted, negative)) {
            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThat(garbage.message()).contains("since", "yesterday");
        assertThat(garbage.nextStep()).contains("PT24H", "2026-10-01T08:00:00Z");
        assertThat(inverted.message()).contains("since", "until");
        assertThat(processes(DEV).queries()).isEmpty();
        assertThat(logs(DEV).queries()).isEmpty();
    }

    @Test
    void timesOutsideTheEpochMillisecondRangeAreInvalidInputBeforeAnyCall() {
        // review D3
        DiagnosisService service = service();
        for (String value : List.of("+999999999-12-31T23:59:59Z", "P106751991167300D",
            "-999999999-01-01T00:00:00Z", "PT9223372036854775807S")) {
            ToolError processes = errorOf(() -> service.findProcesses(withTimes(
                processRequest("dev/node1"), Optional.of(value), Optional.empty())));
            ToolError logs = errorOf(() -> service.queryLogs(new LogRequest("dev/node1",
                LogType.SYSTEM_LOG, Optional.empty(), Optional.of(value), Optional.empty(),
                Optional.empty(), Set.of(), Optional.empty(), 0, 5)));
            assertThat(processes.code()).as(value).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(logs.code()).as(value).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThat(processes(DEV).queries()).isEmpty();
        assertThat(logs(DEV).queries()).isEmpty();
    }

    // --- hanging -------------------------------------------------------------------------------

    @Test
    void hangingIsDecidedOnTheUntruncatedTimeInState() {
        // review D4: QA1 threshold 60 min; 60 min 0.5 s is longer
        processes(QA1).answer = query -> page(List.of(instance(QA1, "1",
            ProcessState.WAITING, NOW.minus(Duration.ofMinutes(60)).minusMillis(500))),
            0, 50, 1, false);

        ProcessInstance item = single(service().findProcesses(processRequest("qa/node1")))
            .items().get(0);

        assertThat(item.timeInState()).isEqualTo(Duration.ofMinutes(60));
        assertThat(item.hanging()).isTrue();
    }

    @Test
    void aProcessQueryThatCannotBeSentFailsTheWholeCallBeforeAnyQuery() {
        // review D5, D8: checked on every resolved server before the fan-out
        processes(QA2).validator = query -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "workflow contains U+0001, which XML does not allow", "cause", "next"));
        };

        ToolError error = errorOf(() -> service().findProcesses(new ProcessRequest("qa",
            Set.of(), false, Optional.empty(), Optional.of("W\u0001"), Optional.empty(),
            Optional.empty(), Optional.empty(), 0, 5)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.node()).isEmpty();
        assertThat(processes(QA1).validated()).hasSize(1);
        assertThat(processes(QA1).queries()).isEmpty();
        assertThat(processes(QA2).queries()).isEmpty();
    }

    @Test
    void hangingIsANonFinalStateLongerThanTheThreshold() {
        processes(QA1).answer = query -> page(List.of(
            instance(QA1, "1", ProcessState.WAITING, NOW.minus(Duration.ofHours(2))),
            instance(QA1, "2", ProcessState.ERROR, NOW.minus(Duration.ofHours(3))),
            instance(QA1, "3", ProcessState.QUEUED, NOW.minus(Duration.ofMinutes(30))),
            instance(QA1, "4", ProcessState.ACTIVE, NOW.minus(Duration.ofMinutes(61))),
            instance(QA1, "5", ProcessState.OTHER, NOW.minus(Duration.ofHours(5)))),
            0, 50, 5, false);

        Page<ProcessInstance> page = single(service().findProcesses(
            processRequest("qa/node1")));

        assertThat(page.items()).extracting(ProcessInstance::processId)
            .containsExactly("3", "4", "1", "2", "5");
        Map<String, Boolean> hanging = new HashMap<>();
        page.items().forEach(item -> hanging.put(item.processId(), item.hanging()));
        assertThat(hanging).containsEntry("1", true).containsEntry("2", false)
            .containsEntry("3", false).containsEntry("4", true).containsEntry("5", false);
        assertThat(page.items()).filteredOn(item -> item.processId().equals("1"))
            .singleElement().satisfies(item ->
                assertThat(item.timeInState()).isEqualTo(Duration.ofHours(2)));
    }

    @Test
    void theThresholdComesFromTheRequestElseTheServerElseSixtyMinutes() {
        thresholds.remove(QA2);
        DiagnosisService service = service();
        Function<ProcessQuery, Page<ProcessInstance>> answer = query -> page(List.of(
            instance(DEV, "1", ProcessState.WAITING, NOW.minus(Duration.ofMinutes(75)))),
            0, 50, 1, false);
        processes(DEV).answer = answer;

        Page<ProcessInstance> byServer = single(service.findProcesses(
            processRequest("dev/node1")));
        Page<ProcessInstance> byRequest = single(service.findProcesses(withStates(
            processRequest("dev/node1"), Set.of(), false, Optional.of(30))));
        service.findProcesses(processRequest("qa/node2"));

        assertThat(processes(DEV).queries()).extracting(ProcessQuery::hangingThreshold)
            .containsExactly(Duration.ofMinutes(90), Duration.ofMinutes(30));
        assertThat(processes(QA2).queries().get(0).hangingThreshold())
            .isEqualTo(Duration.ofMinutes(60));
        assertThat(byServer.items().get(0).hanging()).as("75 min < 90 min").isFalse();
        assertThat(byRequest.items().get(0).hanging()).as("75 min > 30 min").isTrue();
    }

    @Test
    void hangingOnlyIsPassedOnAndErrorWithHangingOnlyIsEmptyWithoutAnyCall() {
        DiagnosisService service = service();

        service.findProcesses(withStates(processRequest("dev/node1"),
            Set.of(ProcessState.WAITING), true, Optional.empty()));
        List<NodeResult<Page<ProcessInstance>>> none = service.findProcesses(withStates(
            processRequest("qa"), Set.of(ProcessState.ERROR), true, Optional.empty()));

        ProcessQuery query = processes(DEV).queries().get(0);
        assertThat(query.hangingOnly()).isTrue();
        assertThat(query.states()).containsExactly(ProcessState.WAITING);
        assertThat(none).extracting(NodeResult::node).containsExactly(QA1, QA2);
        assertThat(none).allSatisfy(result -> {
            Page<ProcessInstance> page = result.payload().orElseThrow();
            assertThat(page.items()).isEmpty();
            assertThat(page.total()).hasValue(0);
            assertThat(page.nextOffset()).isEmpty();
        });
        assertThat(processes(QA1).queries()).isEmpty();
        assertThat(processes(QA2).queries()).isEmpty();
    }

    @Test
    void workflowTagStatesAndPagingArePassedOn() {
        service().findProcesses(new ProcessRequest("dev/node1",
            Set.of(ProcessState.ERROR, ProcessState.QUEUED), false, Optional.empty(),
            Optional.of("Order Import"), Optional.of("v2"), Optional.empty(), Optional.empty(),
            20, 10));

        ProcessQuery query = processes(DEV).queries().get(0);
        assertThat(query.states()).containsExactlyInAnyOrder(ProcessState.ERROR,
            ProcessState.QUEUED);
        assertThat(query.workflow()).contains("Order Import");
        assertThat(query.tag()).contains("v2");
        assertThat(query.offset()).isEqualTo(20);
        assertThat(query.limit()).isEqualTo(10);
    }

    // --- paging and bounds -------------------------------------------------------------------

    @Test
    void pagesAreBoundedByTheResultLimiterSharedAcrossTheServers() {
        limiter = new ResultLimiter(100, 10_000);
        sizes = item -> 3_000;
        for (NodeId server : List.of(QA1, QA2)) {
            processes(server).answer = query -> page(List.of(
                instance(server, "1", ProcessState.ERROR, NOW.minusSeconds(10)),
                instance(server, "2", ProcessState.ERROR, NOW.minusSeconds(20)),
                instance(server, "3", ProcessState.ERROR, NOW.minusSeconds(30)),
                instance(server, "4", ProcessState.ERROR, NOW.minusSeconds(40))),
                query.offset(), query.limit(), 900, false);
        }

        List<NodeResult<Page<ProcessInstance>>> stage = service().findProcesses(
            withPaging(processRequest("qa"), 10, 4));
        Page<ProcessInstance> single = single(service().findProcesses(
            withPaging(processRequest("qa/node1"), 10, 4)));

        assertThat(stage).allSatisfy(result -> {
            Page<ProcessInstance> page = result.payload().orElseThrow();
            assertThat(page.items()).as("5,000 chars per server").hasSize(1);
            assertThat(page.truncated()).isTrue();
            assertThat(page.nextOffset()).hasValue(11);
            assertThat(page.total()).hasValue(900);
        });
        assertThat(single.items()).as("10,000 chars").hasSize(3);
        assertThat(single.nextOffset()).hasValue(13);
    }

    @Test
    void aPageWithCutTextStaysTruncated() {
        processes(DEV).answer = query -> page(List.of(
            instance(DEV, "1", ProcessState.ERROR, NOW)), 0, 50, 1, true);

        Page<ProcessInstance> page = single(service().findProcesses(
            processRequest("dev/node1")));

        assertThat(page.truncated()).isTrue();
        assertThat(page.nextOffset()).isEmpty();
    }

    @Test
    void offsetPlusLimitAboveTenThousandIsInvalidInputBeforeAnyCall() {
        ToolError processes = errorOf(() -> service().findProcesses(withPaging(
            processRequest("dev"), 9_990, 11)));
        ToolError logs = errorOf(() -> service().queryLogs(new LogRequest("dev",
            LogType.SYSTEM_LOG, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Set.of(), Optional.empty(), 9_951, 50)));

        assertThat(processes.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(logs.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(processes(DEV).queries()).isEmpty();
        assertThat(logs(DEV).queries()).isEmpty();
    }

    // --- fan-out -------------------------------------------------------------------------------

    @Test
    void aStageIsQueriedPerServerAndAFailedServerOnlyGetsAnError() {
        processes(QA1).answer = query -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE, "down", "cause",
                "next").withNode(QA1));
        };
        processes(QA2).answer = query -> page(List.of(
            instance(QA2, "9", ProcessState.ERROR, NOW)), 0, 50, 1, false);

        List<NodeResult<Page<ProcessInstance>>> results = service().findProcesses(
            processRequest("qa"));

        assertThat(results).extracting(NodeResult::node).containsExactly(QA1, QA2);
        assertThat(results.get(0).error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE));
        assertThat(results.get(1).payload().orElseThrow().items())
            .extracting(ProcessInstance::node).containsOnly(QA2);
    }

    @Test
    void aHangingServerIsATimeoutWhileTheOthersAreReported() {
        CountDownLatch never = new CountDownLatch(1);
        processes(QA1).answer = query -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("interrupted");
        };

        long start = System.nanoTime();
        List<NodeResult<Page<ProcessInstance>>> results = service().findProcesses(
            processRequest("qa"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.multipliedBy(2).plusSeconds(2));
        assertThat(results.get(0).error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThat(results.get(1).isSuccess()).isTrue();
    }

    @Test
    void anUnknownTargetIsEnvironmentUnknownWithoutAnyCall() {
        ToolError error = errorOf(() -> service().findProcesses(processRequest("staging")));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(processes).isEmpty();
    }

    @Test
    void findProcessesUsesOnlyTheRestPortWithoutCliOrVersionDetection() {
        // the fake factory fails on forServer/monitoring: no CLI, no detection (REST only)
        processes(DEV).answer = query -> page(List.of(), 0, 50, 0, false);

        Page<ProcessInstance> page = single(service().findProcesses(
            processRequest("dev/node1")));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).hasValue(0);
    }

    // --- query_logs ----------------------------------------------------------------------------

    @Test
    void aLogQueryIsMappedValidatedOnEveryServerAndBounded() {
        for (NodeId server : List.of(QA1, QA2)) {
            logs(server).answer = query -> page(List.of(entry(server, 1_000),
                entry(server, 3_000), entry(server, 2_000)), query.offset(), query.limit(), 3,
                false);
        }

        List<NodeResult<Page<LogEntry>>> results = service().queryLogs(new LogRequest("qa",
            LogType.SYSTEM_LOG, Optional.of("PT1H"), Optional.empty(), Optional.of("W"),
            Optional.of("110219899"), Set.of(Severity.ERROR), Optional.of("SAX%"), 0, 5));

        assertThat(results).extracting(NodeResult::node).containsExactly(QA1, QA2);
        for (NodeId server : List.of(QA1, QA2)) {
            assertThat(logs(server).validated()).hasSize(1);
            LogQuery query = logs(server).queries().get(0);
            assertThat(query.logType()).isEqualTo(LogType.SYSTEM_LOG);
            assertThat(query.since()).contains(NOW.minus(Duration.ofHours(1)));
            assertThat(query.workflow()).contains("W");
            assertThat(query.processId()).contains("110219899");
            assertThat(query.severities()).containsExactly(Severity.ERROR);
            assertThat(query.text()).contains("SAX%");
            assertThat(query.limit()).isEqualTo(5);
        }
        assertThat(results.get(0).payload().orElseThrow().items())
            .extracting(entry -> entry.timestamp().orElseThrow().toEpochMilli())
            .as("newest first").containsExactly(3_000L, 2_000L, 1_000L);
    }

    @Test
    void anUnsupportedLogFilterFailsTheWholeCallBeforeAnyQuery() {
        logs(QA2).validator = query -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The filter workflow is not supported for auditLog", "cause",
                "Supported filters for auditLog: …"));
        };

        ToolError error = errorOf(() -> service().queryLogs(new LogRequest("qa",
            LogType.AUDIT_LOG, Optional.empty(), Optional.empty(), Optional.of("W"),
            Optional.empty(), Set.of(), Optional.empty(), 0, 5)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("auditLog");
        assertThat(logs(QA1).queries()).isEmpty();
        assertThat(logs(QA2).queries()).isEmpty();
    }

    @Test
    void anInvalidProcessIdIsInvalidInput() {
        ToolError error = errorOf(() -> service().queryLogs(new LogRequest("dev",
            LogType.SYSTEM_LOG, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of("1; DROP"), Set.of(), Optional.empty(), 0, 5)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(logs(DEV).queries()).isEmpty();
    }

    @Test
    void aFailedLogServerOnlyGetsAnError() {
        logs(QA1).answer = query -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED, "401", "cause",
                "next").withNode(QA1));
        };
        logs(QA2).answer = query -> page(List.of(entry(QA2, 5)), 0, 5, 1, false);

        List<NodeResult<Page<LogEntry>>> results = service().queryLogs(logRequest("qa",
            LogType.SYSTEM_LOG));

        assertThat(results.get(0).error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED));
        assertThat(results.get(1).payload().orElseThrow().items()).hasSize(1);
    }

    // --- fakes -----------------------------------------------------------------------------

    private static final class FakeProcesses implements ProcessQueryPort {

        volatile Function<ProcessQuery, Page<ProcessInstance>> answer = query -> page(List.of(),
            query.offset(), query.limit(), 0, false);
        volatile Consumer<ProcessQuery> validator = query -> { };
        private final List<ProcessQuery> queries = new ArrayList<>();
        private final List<ProcessQuery> validated = new ArrayList<>();

        synchronized List<ProcessQuery> queries() {
            return List.copyOf(queries);
        }

        synchronized List<ProcessQuery> validated() {
            return List.copyOf(validated);
        }

        @Override
        public void validate(ProcessQuery query) {
            synchronized (this) {
                validated.add(query);
            }
            validator.accept(query);
        }

        @Override
        public Page<ProcessInstance> find(ProcessQuery query) {
            synchronized (this) {
                queries.add(query);
            }
            return answer.apply(query);
        }

        @Override
        public ProcessRows findByProcessId(String processId, Instant now,
            Duration hangingThreshold) {
            throw new AssertionError("not used by find_processes");
        }
    }

    private static final class FakeLogs implements LogPort {

        volatile Consumer<LogQuery> validator = query -> { };
        volatile Function<LogQuery, Page<LogEntry>> answer = query -> page(List.of(),
            query.offset(), query.limit(), 0, false);
        private final List<LogQuery> validated = new ArrayList<>();
        private final List<LogQuery> queries = new ArrayList<>();

        synchronized List<LogQuery> queries() {
            return List.copyOf(queries);
        }

        synchronized List<LogQuery> validated() {
            return List.copyOf(validated);
        }

        @Override
        public void validate(LogQuery query) {
            synchronized (this) {
                validated.add(query);
            }
            validator.accept(query);
        }

        @Override
        public Page<LogEntry> query(LogQuery query) {
            synchronized (this) {
                queries.add(query);
            }
            validator.accept(query);
            return answer.apply(query);
        }
    }
}
