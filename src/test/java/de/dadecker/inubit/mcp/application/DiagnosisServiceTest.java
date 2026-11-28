package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.DiagnosisService.LogRequest;
import de.dadecker.inubit.mcp.application.DiagnosisService.NodePage;
import de.dadecker.inubit.mcp.application.DiagnosisService.ProcessRequest;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ContactedException;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueryRules;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import de.dadecker.inubit.mcp.testing.TestMatrices;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T069: the use cases behind {@code find_processes} and {@code query_logs} with fake ports and a
 * fixed clock (FR-009 – FR-013, research R-8, R-9). Feature 009 (T012, FR-006, FR-009): the
 * version gate admits the tool's capability per node before its REST query; its warnings go into
 * that node's result, a refusal becomes that node's error. Feature 012 (FR-004): that capability
 * is {@code PROCESS_DIAGNOSIS} for {@code find_processes} and {@code LOG_DIAGNOSIS} for
 * {@code query_logs}.
 */
@Timeout(30)
class DiagnosisServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final NodeId QA2 = NodeId.parse("qa/node2");
    private static final List<NodeId> SERVERS = List.of(DEV, QA1, QA2);
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final ClientVersion CLIENT_610 = new ClientVersion(
        InubitVersion.parse("6.1.0").orElseThrow(),
        Optional.of(InubitVersion.parse("4.0.1").orElseThrow()),
        Optional.of(InubitVersion.parse("6.1.0").orElseThrow()));

    /** T045b: the {@code CLI ps -u} row as in interface-coverage.tsv (8.1 covered, 9.0 planned). */
    private static final InterfaceCoverage COVERAGE = InterfaceCoverage.load(new StringReader("""
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\tps -u\tREAD\tD\tUS3\tcovered:find_processes\tplanned\t-
        """));

    private final MutableClock clock = new MutableClock(NOW);
    private final Map<NodeId, FakeProcesses> processes = new ConcurrentHashMap<>();
    private final Map<NodeId, FakeLogs> logs = new ConcurrentHashMap<>();
    private final Map<NodeId, Duration> thresholds = new HashMap<>(Map.of(
        DEV, Duration.ofMinutes(90), QA1, Duration.ofMinutes(60), QA2, Duration.ofMinutes(60)));
    private ResultLimiter limiter = ResultLimiter.withDefaults();
    private Function<Object, Integer> sizes = item -> 100;
    /** Feature 009: the {@code versionLine} of each server ({@code V8_1} if absent). */
    private final Map<NodeId, VersionSetting> versionLines = new ConcurrentHashMap<>();
    private final NodeVersions versions = new NodeVersions(clock,
        node -> versionLines.getOrDefault(node, VersionSetting.V8_1),
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions);
    private CapabilityMatrix matrix = CapabilityMatrix.production();
    /** Feature 010: the per-node fetch allowance (no AWS node unless a test sets one). */
    private SecretTiming secretTiming = SecretTiming.NONE;
    /**
     * Feature 010: the nodes whose secrets are not held yet; acquiring one of their ports runs
     * {@link #fetch} first, as {@code AdapterGatewayFactory} waits for the first fetch there.
     */
    private final Set<NodeId> pendingSecrets = ConcurrentHashMap.newKeySet();
    /** Feature 010: the first fetch of a pending node (default: succeeds at once). */
    private volatile Consumer<NodeId> fetch = pendingSecrets::remove;
    /** Feature 012: the {@code cliTimeout} of every server. */
    private Duration cliTimeout = Duration.ofSeconds(3);
    /** Feature 009: the nodes with a StartCLI installation, whose client the gate probes. */
    private final Set<NodeId> withClient = ConcurrentHashMap.newKeySet();
    /** Every client probe of the gate (a local StartCLI launch), one entry per probe. */
    private final List<NodeId> probes = new CopyOnWriteArrayList<>();

    private void acquire(NodeId server) {
        if (pendingSecrets.contains(server)) {
            fetch.accept(server);
        }
    }

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
            public InubitVersion detect(NodeId server) {
                throw new AssertionError("the version gate detects through its own factory");
            }

            @Override
            public MonitoringPort monitoring(NodeId server) {
                throw new AssertionError("US2 does not use the monitoring");
            }

            @Override
            public boolean credentialsHeld(NodeId server) {
                return !pendingSecrets.contains(server);
            }

            @Override
            public ProcessQueryPort processes(NodeId server) {
                acquire(server);
                return DiagnosisServiceTest.this.processes(server);
            }

            @Override
            public LogPort logs(NodeId server) {
                acquire(server);
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

            @Override
            public QueryRules queryRules(NodeId server) {
                // feature 011 (review T013-T014 r1): credential-free, never acquires the port
                return new QueryRules() {
                    @Override
                    public void validate(LogQuery query) {
                        DiagnosisServiceTest.this.logs(server).validate(query);
                    }

                    @Override
                    public void validate(ProcessQuery query) {
                        DiagnosisServiceTest.this.processes(server).validate(query);
                    }
                };
            }
        };
        return new DiagnosisService(gateways, new TargetResolver(SERVERS), new FanOut(),
            limiter, server -> TIMEOUT, server -> cliTimeout, thresholds::get, clock,
            item -> sizes.apply(item), new VersionGate(versions, detection, matrix,
                (node, version, timeout) -> {
                    probes.add(node);
                    return new ProbedClient(ClientKey.NONE, CLIENT_610);
                }, TestClientSelection.of(withClient::contains), node -> cliTimeout,
                Terminology.DEFAULT, false, COVERAGE),
            secretTiming);
    }

    private static ProcessRequest processRequest(String target) {
        return new ProcessRequest(target, Set.of(), false, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), 0, 50, Optional.empty());
    }

    private static ProcessRequest withStates(ProcessRequest r, Set<ProcessState> states,
        boolean hangingOnly, Optional<Integer> threshold) {
        return new ProcessRequest(r.target(), states, hangingOnly, threshold, r.workflow(),
            r.tag(), r.since(), r.until(), r.offset(), r.limit(), r.userColumn());
    }

    private static ProcessRequest withTimes(ProcessRequest r, Optional<String> since,
        Optional<String> until) {
        return new ProcessRequest(r.target(), r.states(), r.hangingOnly(),
            r.hangingThresholdMinutes(), r.workflow(), r.tag(), since, until, r.offset(),
            r.limit(), r.userColumn());
    }

    private static ProcessRequest withPaging(ProcessRequest r, int offset, int limit) {
        return new ProcessRequest(r.target(), r.states(), r.hangingOnly(),
            r.hangingThresholdMinutes(), r.workflow(), r.tag(), r.since(), r.until(), offset,
            limit, r.userColumn());
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
            Optional.empty(), Optional.empty(), Optional.of("OWNERS"), Optional.of("normal"),
            Optional.empty());
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

    private static <T> Page<T> single(List<NodeResult<NodePage<T>>> results) {
        assertThat(results).hasSize(1);
        assertThat(results.get(0).error()).isEmpty();
        return results.get(0).payload().orElseThrow().page();
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
            Optional.empty(), Optional.empty(), 0, 5, Optional.empty())));

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
        List<NodeResult<NodePage<ProcessInstance>>> none = service.findProcesses(withStates(
            processRequest("qa"), Set.of(ProcessState.ERROR), true, Optional.empty()));

        ProcessQuery query = processes(DEV).queries().get(0);
        assertThat(query.hangingOnly()).isTrue();
        assertThat(query.states()).containsExactly(ProcessState.WAITING);
        assertThat(none).extracting(NodeResult::node).containsExactly(QA1, QA2);
        assertThat(none).allSatisfy(result -> {
            Page<ProcessInstance> page = result.payload().orElseThrow().page();
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
            20, 10, Optional.empty()));

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

        List<NodeResult<NodePage<ProcessInstance>>> stage = service().findProcesses(
            withPaging(processRequest("qa"), 10, 4));
        Page<ProcessInstance> single = single(service().findProcesses(
            withPaging(processRequest("qa/node1"), 10, 4)));

        assertThat(stage).allSatisfy(result -> {
            Page<ProcessInstance> page = result.payload().orElseThrow().page();
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

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            processRequest("qa"));

        assertThat(results).extracting(NodeResult::node).containsExactly(QA1, QA2);
        assertThat(results.get(0).error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE));
        assertThat(results.get(1).payload().orElseThrow().page().items())
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
        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            processRequest("qa"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(TIMEOUT.multipliedBy(3).plusSeconds(2));
        assertThat(results.get(0).error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThat(results.get(1).isSuccess()).isTrue();
    }

    @Test
    void theFanOutDeadlineIncludesTheGuardWaitTheDetectionAndTheQuery() {
        // feature 009 (T012 review r1): the version gate's detection adds one timeout
        assertThat(service().deadline(DEV, Capability.PROCESS_DIAGNOSIS))
            .isEqualTo(TIMEOUT.multipliedBy(3).plus(DiagnosisService.FAN_OUT_GRACE));
    }

    @Test
    void theFanOutDeadlineOfANodeWhoseCredentialsAreNotHeldAddsTheFetchAllowance() {
        // feature 010 (T013, research R-8), feature 011 (FR-008): while the node's credentials
        // are not held, for every source; never for a node without a store wait
        secretTiming = new SecretTiming(Map.of(QA1, Duration.ofSeconds(10)), node -> false);
        DiagnosisService service = service();
        Duration base = TIMEOUT.multipliedBy(3).plus(DiagnosisService.FAN_OUT_GRACE);

        assertThat(service.deadline(QA1, Capability.PROCESS_DIAGNOSIS))
            .isEqualTo(base.plusSeconds(10).plusSeconds(1));
        assertThat(service.deadline(DEV, Capability.PROCESS_DIAGNOSIS)).isEqualTo(base);
    }

    @Test
    void aHeldNodeWhoseCallOutlastsItsDeadlineKeepsThePlainFanOutTimeout() {
        // feature 011 (T015, research R-8): the node's credentials are held, so its branch gets
        // no allowance and its TIMEOUT keeps the text of the fan-out (no access dialog hint); the
        // other node is unaffected, and the next call works. The real re-read inside a fan-out
        // (quickstart Q23) is RotationInsideAFanOutTest
        secretTiming = new SecretTiming(Map.of(QA1, Duration.ofSeconds(60)), node -> true,
            Map.of(QA1, "macOS Keychain"));
        CountDownLatch reread = new CountDownLatch(1);
        processes(QA1).answer = query -> {
            try {
                if (!reread.await(3, TimeUnit.SECONDS)) {
                    Thread.sleep(Duration.ofMillis(10));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return page(List.of(), query.offset(), query.limit(), 0, false);
        };
        DiagnosisService service = service();

        List<NodeResult<NodePage<ProcessInstance>>> first = service.findProcesses(
            processRequest("qa"));
        reread.countDown();
        List<NodeResult<NodePage<ProcessInstance>>> second = service.findProcesses(
            processRequest("qa"));

        assertThat(first.get(0).error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.likelyCause()).doesNotContain("access dialog");
            assertThat(error.nextStep()).doesNotContain("access dialog");
        });
        assertThat(first.get(1).isSuccess()).isTrue();
        assertThat(second).allSatisfy(result -> assertThat(result.isSuccess()).isTrue());
    }

    @Test
    void theFetchAllowanceEndsOnceTheCredentialsAreHeld() {
        // feature 011 (T015, FR-008): deadlines are as before feature 010 once a node's
        // credentials are held, for every source
        Set<NodeId> held = ConcurrentHashMap.newKeySet();
        secretTiming = new SecretTiming(Map.of(QA1, Duration.ofSeconds(60)), held::contains);
        DiagnosisService service = service();
        Duration base = TIMEOUT.multipliedBy(3).plus(DiagnosisService.FAN_OUT_GRACE);

        assertThat(service.deadline(QA1, Capability.PROCESS_DIAGNOSIS))
            .isEqualTo(base.plusSeconds(61));
        held.add(QA1);
        assertThat(service.deadline(QA1, Capability.PROCESS_DIAGNOSIS)).isEqualTo(base);
    }

    @Test
    void aSlowFirstFetchDelaysNoOtherNodeAndGivesThatNodesErrorNotAFanOutTimeout() {
        // feature 010 (T013, research R-8): acquiring the port of qa/node1 waits for its first
        // fetch. The fetch ends only after qa/node2 was queried (no node waits for another), and
        // 3 s later still, longer than the 1.9 s deadline without the allowance; then it fails
        secretTiming = new SecretTiming(Map.of(QA1, Duration.ofSeconds(10)), node -> false);
        pendingSecrets.add(QA1);
        CountDownLatch otherQueried = new CountDownLatch(1);
        Set<Thread> fetchThreads = ConcurrentHashMap.newKeySet();
        fetch = node -> {
            fetchThreads.add(Thread.currentThread());
            boolean released;
            try {
                released = otherQueried.await(10, TimeUnit.SECONDS);
                Thread.sleep(Duration.ofSeconds(3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
            if (!released) {
                throw new IllegalStateException("qa/node2 was not queried while qa/node1 fetched");
            }
            throw new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
                "AWS Parameter Store did not answer", "cause", "next").withNode(node));
        };
        processes(QA2).answer = query -> {
            otherQueried.countDown();
            return page(List.of(), query.offset(), query.limit(), 0, false);
        };
        Thread caller = Thread.currentThread();

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            processRequest("qa"));

        assertThat(fetchThreads).as("fetched in the node's own branch only").hasSize(1)
            .doesNotContain(caller);
        assertThat(results.get(0).error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.UNREACHABLE));
        assertThat(results.get(1).isSuccess()).isTrue();
    }

    @Test
    void anInvalidQueryOfANodeWithHeldSecretsFailsTheCallBeforeAnyFetchOrQuery() {
        // feature 010 (T013): nodes whose secrets are held are still validated up front
        pendingSecrets.add(QA1);
        AtomicInteger fetches = new AtomicInteger();
        fetch = node -> fetches.incrementAndGet();
        logs(QA2).validator = query -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, "bad filter",
                "cause", "next"));
        };

        ToolError error = errorOf(() -> service().queryLogs(logRequest("qa",
            LogType.SYSTEM_LOG)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(fetches).hasValue(0);
        assertThat(logs(QA1).queries()).isEmpty();
        assertThat(logs(QA2).queries()).isEmpty();
    }

    @Test
    void aNodeWithoutHeldSecretsIsValidatedUpFrontWithoutReadingThem() {
        // feature 011 (review T013-T014 r1): the input check needs no credentials, so a bad
        // filter is one tool error before any read (no access dialog for a refused request)
        pendingSecrets.add(QA1);
        AtomicInteger fetches = new AtomicInteger();
        fetch = node -> fetches.incrementAndGet();
        logs(QA1).validator = query -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, "bad filter",
                "cause", "next"));
        };

        ToolError error = errorOf(() -> service().queryLogs(logRequest("qa",
            LogType.SYSTEM_LOG)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(fetches).hasValue(0);
        assertThat(logs(QA1).queries()).isEmpty();
        assertThat(logs(QA2).queries()).isEmpty();
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
        assertThat(detection.requests()).as("pinned V8_1: no detection").isEmpty();
    }

    // --- feature 009: version gate (T012) ------------------------------------------------------

    @Test
    void onInubit91BothToolsRunWithTheUnverifiedWarningAfterOneDetection() {
        versionLines.put(DEV, VersionSetting.AUTO);
        detection.answer(DEV, "9.1.0");
        processes(DEV).answer = query -> page(List.of(
            instance(DEV, "1", ProcessState.ERROR, NOW)), 0, 50, 1, false);
        logs(DEV).answer = query -> page(List.of(entry(DEV, 5)), 0, 50, 1, false);
        DiagnosisService service = service();

        NodePage<ProcessInstance> processPage = service.findProcesses(
            processRequest("dev/node1")).get(0).payload().orElseThrow();
        NodePage<LogEntry> logPage = service.queryLogs(logRequest("dev/node1",
            LogType.SYSTEM_LOG)).get(0).payload().orElseThrow();

        assertThat(processPage.warnings()).containsExactly("INUBIT 9.1.0 on dev/node1 is not"
            + " verified for process diagnosis; results may be incomplete");
        assertThat(processPage.page().items()).hasSize(1);
        assertThat(logPage.warnings()).containsExactly("INUBIT 9.1.0 on dev/node1 is not"
            + " verified for log diagnosis; results may be incomplete");
        assertThat(logPage.page().items()).hasSize(1);
        assertThat(detection.requests()).as("the second call uses the cached version")
            .containsExactly(DEV);
    }

    @Test
    void onInubit81ThereAreNoWarnings() {
        versionLines.put(DEV, VersionSetting.AUTO);
        detection.answer(DEV, "8.1.17");
        processes(DEV).answer = query -> page(List.of(), 0, 50, 0, false);
        logs(DEV).answer = query -> page(List.of(), 0, 50, 0, false);
        DiagnosisService service = service();

        NodePage<ProcessInstance> processPage = service.findProcesses(
            processRequest("dev/node1")).get(0).payload().orElseThrow();
        NodePage<LogEntry> logPage = service.queryLogs(logRequest("dev/node1",
            LogType.SYSTEM_LOG)).get(0).payload().orElseThrow();

        assertThat(processPage.warnings()).isEmpty();
        assertThat(logPage.warnings()).isEmpty();
        assertThat(detection.requests()).containsExactly(DEV);
    }

    @Test
    void aNodeRefusedByATestMatrixGetsItsErrorAndTheOtherNodesTheirResults() {
        // FR-006: each node of a group is judged by its own version
        matrix = diagnosisOnlyOn81();
        versionLines.put(QA1, VersionSetting.AUTO);
        versionLines.put(QA2, VersionSetting.AUTO);
        detection.answer(QA1, "8.1.17").answer(QA2, "9.0.7");
        processes(QA1).answer = query -> page(List.of(
            instance(QA1, "1", ProcessState.ERROR, NOW)), 0, 50, 1, false);
        logs(QA1).answer = query -> page(List.of(entry(QA1, 5)), 0, 50, 1, false);
        DiagnosisService service = service();

        List<NodeResult<NodePage<ProcessInstance>>> processResults = service.findProcesses(
            processRequest("qa"));
        List<NodeResult<NodePage<LogEntry>>> logResults = service.queryLogs(logRequest("qa",
            LogType.SYSTEM_LOG));

        assertThat(processResults.get(0).payload().orElseThrow().page().items()).hasSize(1);
        assertThat(logResults.get(0).payload().orElseThrow().page().items()).hasSize(1);
        assertThat(processResults.get(1).payload()).isEmpty();
        assertThat(processResults.get(1).error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.node()).contains(QA2);
            assertThat(error.message()).isEqualTo("find_processes: INUBIT 9.0.7 on qa/node2"
                + " does not support process diagnosis yet; nothing was sent");
        });
        assertThat(logResults.get(1).error()).hasValueSatisfying(error -> assertThat(
            error.message()).isEqualTo("query_logs: INUBIT 9.0.7 on qa/node2 does not support"
                + " log diagnosis yet; nothing was sent"));
        assertThat(processes(QA2).queries()).isEmpty();
        assertThat(logs(QA2).queries()).isEmpty();
    }

    @Test
    void eachToolIsAdmittedForItsOwnDiagnosisCapability() {
        // feature 012 (FR-004): find_processes needs PROCESS_DIAGNOSIS, query_logs LOG_DIAGNOSIS
        matrix = new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
            new VersionRange(8, 1, 8, 1),
            Map.of(Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED))), Map.of());
        versionLines.put(DEV, VersionSetting.AUTO);
        detection.answer(DEV, "8.1.17");
        processes(DEV).answer = query -> page(List.of(), 0, 50, 0, false);
        DiagnosisService service = service();

        NodeResult<NodePage<ProcessInstance>> processResult = service.findProcesses(
            processRequest("dev/node1")).get(0);
        NodeResult<NodePage<LogEntry>> logResult = service.queryLogs(logRequest("dev/node1",
            LogType.SYSTEM_LOG)).get(0);

        assertThat(processResult.payload()).hasValueSatisfying(
            page -> assertThat(page.warnings()).isEmpty());
        assertThat(logResult.error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).isEqualTo("query_logs: INUBIT 8.1.17 on dev/node1"
                + " does not support log diagnosis yet; nothing was sent");
        });
        assertThat(logs(DEV).queries()).isEmpty();
    }

    /** A test-only matrix: both diagnosis capabilities on 8.1 only, unsupported elsewhere. */
    private static CapabilityMatrix diagnosisOnlyOn81() {
        Map<Capability, SupportState> supported = new EnumMap<>(Capability.class);
        supported.put(Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED);
        supported.put(Capability.LOG_DIAGNOSIS, SupportState.SUPPORTED);
        return new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
            new VersionRange(8, 1, 8, 1), supported)), Map.of());
    }

    // --- feature 012, T033: find_processes on a 6.1 node (FR-007, FR-010, research D-10) ------

    /** The warning of a 6.1 entry, from the gate admission's version notes (FR-010, FR-016). */
    private static final String LOG_NOTE = "logs of INUBIT 6.1 cannot be read with query_logs;"
        + " read them in the Workbench";

    /** 6.1 CLI-only with process diagnosis supported over StartCLI (as after T040); 8.1 all. */
    private static CapabilityMatrix with61() {
        Map<Capability, SupportState> all = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            all.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
                Map.of(Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED),
                Map.of(Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI)), true),
            new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), all)), Map.of());
    }

    /** The adapter's refusal of {@code tag} on a 6.1 node (V61ProcessQueryAdapter, T032). */
    private static ToolErrorException tagRefused(NodeId node) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "find_processes: INUBIT 6.1 does not support the input tag; nothing was sent",
            "The process listing of StartCLI 6.1 has no tag column",
            "Omit tag for this node, or filter by workflow").withNode(node));
    }

    private static ProcessRequest withTag(ProcessRequest r, String tag) {
        return new ProcessRequest(r.target(), r.states(), r.hangingOnly(),
            r.hangingThresholdMinutes(), r.workflow(), Optional.of(tag), r.since(), r.until(),
            r.offset(), r.limit(), r.userColumn());
    }

    @Test
    void theFanOutDeadlineOfA61NodeFollowsItsStartCliChannel() {
        // options probe + limiter wait + TLS handshake + session + grace (+ AWS allowance)
        matrix = with61();
        versionLines.put(QA2, VersionSetting.V6_1);
        secretTiming = new SecretTiming(Map.of(QA2, Duration.ofSeconds(10)), id -> false);
        DiagnosisService service = service();
        Duration rest = TIMEOUT.multipliedBy(3).plus(DiagnosisService.FAN_OUT_GRACE);
        Duration aws = Duration.ofSeconds(10).plusSeconds(1);

        assertThat(service.deadline(QA2, Capability.PROCESS_DIAGNOSIS)).isEqualTo(
            cliTimeout.multipliedBy(3).plus(TIMEOUT).plus(DiagnosisService.FAN_OUT_GRACE)
                .plus(aws));
        assertThat(service.deadline(QA2, Capability.LOG_DIAGNOSIS))
            .as("logs are not read over StartCLI").isEqualTo(rest.plus(aws));
        assertThat(service.deadline(QA1, Capability.PROCESS_DIAGNOSIS)).isEqualTo(rest);
        assertThat(detection.requests()).isEmpty();
        assertThat(probes).isEmpty();
    }

    @Test
    void a61SessionLongerThanThreeTimeoutsButWithinCliTimeoutIsAnsweredWithTheLogNote() {
        // the 8.1 deadline (3 x 300 ms + 1 s) would cut the 2.5 s session off; cliTimeout is 3 s
        matrix = with61();
        versionLines.put(QA2, VersionSetting.V6_1);
        withClient.add(QA2);
        processes(QA1).answer = query -> page(List.of(
            instance(QA1, "1", ProcessState.ERROR, NOW)), 0, 50, 1, false);
        processes(QA2).answer = query -> {
            try {
                Thread.sleep(Duration.ofMillis(2_500));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cut off");
            }
            return page(List.of(instance(QA2, "2", ProcessState.ERROR, NOW)), 0, 50, 1, false);
        };

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            processRequest("qa"));

        assertThat(results.get(1).error()).isEmpty();
        NodePage<ProcessInstance> legacy = results.get(1).payload().orElseThrow();
        assertThat(legacy.page().items()).extracting(ProcessInstance::processId)
            .containsExactly("2");
        assertThat(legacy.warnings()).containsExactly(LOG_NOTE);
        assertThat(results.get(0).payload().orElseThrow().warnings())
            .as("no note on 8.1").isEmpty();
        assertThat(probes).as("the gate checks the StartCLI client of the 6.1 node")
            .containsExactly(QA2);
    }

    @Test
    void anEmptyQueryOn61StillCarriesTheLogNote() {
        matrix = with61();
        versionLines.put(QA2, VersionSetting.V6_1);

        NodePage<ProcessInstance> page = service().findProcesses(withStates(
            processRequest("qa/node2"), Set.of(ProcessState.ERROR), true, Optional.empty()))
            .get(0).payload().orElseThrow();

        assertThat(page.page().items()).isEmpty();
        assertThat(page.warnings()).containsExactly(LOG_NOTE);
        assertThat(processes(QA2).queries()).isEmpty();
    }

    @Test
    void aHeld61NodeRefusingTagGetsItsRefusalAndThe81NodeItsResultWithoutAdmission() {
        // research D-10: the refusal of the up-front validation is kept as the node's entry; the
        // gate is not asked, so neither the client probe nor a session is launched
        matrix = with61();
        versionLines.put(QA2, VersionSetting.V6_1);
        withClient.add(QA2);
        processes(QA1).answer = query -> page(List.of(
            instance(QA1, "1", ProcessState.ERROR, NOW)), 0, 50, 1, false);
        processes(QA2).validator = query -> {
            throw tagRefused(QA2);
        };

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            withTag(processRequest("qa"), "release-1"));

        assertThat(results).extracting(NodeResult::node).containsExactly(QA1, QA2);
        assertThat(results.get(0).payload().orElseThrow().page().items()).hasSize(1);
        assertThat(processes(QA1).queries()).singleElement()
            .satisfies(query -> assertThat(query.tag()).contains("release-1"));
        assertThat(results.get(1).payload()).isEmpty();
        assertThat(results.get(1).error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.node()).contains(QA2);
            assertThat(error.message()).isEqualTo(
                "find_processes: INUBIT 6.1 does not support the input tag; nothing was sent");
        });
        assertThat(processes(QA2).validated()).as("validated once, up front").hasSize(1);
        assertThat(processes(QA2).queries()).isEmpty();
        assertThat(probes).isEmpty();
    }

    @Test
    void aPending61NodeRefusingTagGetsItsRefusalFromItsOwnBranchWithoutAdmission() {
        matrix = with61();
        versionLines.put(QA2, VersionSetting.V6_1);
        withClient.add(QA2);
        pendingSecrets.add(QA2);
        processes(QA2).validator = query -> {
            throw tagRefused(QA2);
        };

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            withTag(processRequest("qa"), "release-1"));

        assertThat(results.get(0).isSuccess()).isTrue();
        assertThat(results.get(1).error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
        assertThat(processes(QA2).queries()).isEmpty();
        assertThat(probes).isEmpty();
    }

    // --- feature 025, T032: find_processes on a pinned 7.4 node (FR-028, FR-030) ---------------

    @Test
    void anUnverified74NodeCarriesTheWarningAndTheLogNoteAndThe81NodeNeither() {
        matrix = TestMatrices.unverified74();
        versionLines.put(QA2, VersionSetting.V7_4);
        processes(QA1).answer = query -> page(List.of(
            instance(QA1, "1", ProcessState.ERROR, NOW)), 0, 50, 1, false);
        processes(QA2).answer = query -> page(List.of(
            instance(QA2, "2", ProcessState.ERROR, NOW)), 0, 50, 1, false);

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            withStates(processRequest("qa"), Set.of(ProcessState.ERROR), false,
                Optional.empty()));

        assertThat(results).extracting(NodeResult::node).containsExactly(QA1, QA2);
        assertThat(results.get(0).payload().orElseThrow().warnings())
            .as("no warning on 8.1").isEmpty();
        NodePage<ProcessInstance> legacy = results.get(1).payload().orElseThrow();
        assertThat(legacy.page().items()).extracting(ProcessInstance::processId)
            .containsExactly("2");
        assertThat(legacy.warnings()).containsExactlyInAnyOrder(
            "INUBIT 7.4 on qa/node2 is not verified for process diagnosis; results may be"
                + " incomplete",
            "logs of INUBIT 7.4 cannot be read with query_logs; read them in the Workbench");
    }

    @Test
    void anEmptyQueryOnAnUnverified74NodeStillCarriesTheWarningAndTheLogNote() {
        matrix = TestMatrices.unverified74();
        versionLines.put(QA2, VersionSetting.V7_4);
        processes(QA2).answer = query -> page(List.of(), 0, 50, 0, false);

        NodePage<ProcessInstance> page = service().findProcesses(processRequest("qa/node2"))
            .get(0).payload().orElseThrow();

        assertThat(page.page().items()).isEmpty();
        assertThat(page.warnings()).containsExactlyInAnyOrder(
            "INUBIT 7.4 on qa/node2 is not verified for process diagnosis; results may be"
                + " incomplete",
            "logs of INUBIT 7.4 cannot be read with query_logs; read them in the Workbench");
    }

    // --- feature 025, T045: the warning on errors after contact only (FR-030 (b)) -------------

    /** Pins qa/node2 to {@code line} ("7.4" or "6.1") with the T038 test matrix of that line. */
    private void pinUnverified(String line) {
        boolean v74 = line.equals("7.4");
        matrix = v74 ? TestMatrices.unverified74() : TestMatrices.unverified61();
        versionLines.put(QA2, v74 ? VersionSetting.V7_4 : VersionSetting.V6_1);
    }

    /** The admission's warning and version note of qa/node2 on an unverified {@code line}. */
    private static String[] admissionWarnings(String line) {
        return new String[] {
            "INUBIT " + line + " on qa/node2 is not verified for process diagnosis; results may be"
                + " incomplete",
            "logs of INUBIT " + line + " cannot be read with query_logs; read them in the"
                + " Workbench"};
    }

    private static ToolError errorOnQa2(ErrorCode code) {
        return ToolError.of(code, code + " on qa/node2", "cause", "next step").withNode(QA2);
    }

    private NodeResult<NodePage<ProcessInstance>> findOnQa2(ProcessRequest request) {
        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(request);
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @ParameterizedTest
    @CsvSource({
        // a rejected login, an unreachable server, an unrecognized output, the session's TIMEOUT
        // after launch, a rejected TLS handshake: what the adapters mark as contacted (T041)
        "7.4, AUTH_FAILED", "7.4, UNREACHABLE", "7.4, UNEXPECTED_RESPONSE", "7.4, TIMEOUT",
        "7.4, TLS_ERROR",
        "6.1, AUTH_FAILED", "6.1, UNREACHABLE", "6.1, UNEXPECTED_RESPONSE", "6.1, TIMEOUT",
        "6.1, TLS_ERROR"})
    void anErrorAfterContactCarriesTheAdmissionsWarnings(String line, ErrorCode code) {
        pinUnverified(line);
        processes(QA2).answer = query -> {
            throw new ContactedException(errorOnQa2(code));
        };

        NodeResult<NodePage<ProcessInstance>> entry = findOnQa2(processRequest("qa/node2"));

        assertThat(entry.payload()).isEmpty();
        assertThat(entry.error()).contains(errorOnQa2(code));
        assertThat(entry.warnings()).containsExactly(admissionWarnings(line));
    }

    @ParameterizedTest
    @CsvSource({
        // no client or StartCLI could not be started, missing credentials or the credential
        // guard's cooldown, a refused TLS option, no free StartCLI slot, the launch guard: what
        // the adapters refuse before contact (T041)
        "7.4, CLI_UNAVAILABLE", "7.4, AUTH_FAILED", "7.4, TLS_ERROR", "7.4, TIMEOUT",
        "7.4, UNSUPPORTED_VERSION",
        "6.1, CLI_UNAVAILABLE", "6.1, AUTH_FAILED", "6.1, TLS_ERROR", "6.1, TIMEOUT",
        "6.1, UNSUPPORTED_VERSION"})
    void aRefusalBeforeContactCarriesNoWarning(String line, ErrorCode code) {
        pinUnverified(line);
        processes(QA2).answer = query -> {
            throw new ToolErrorException(errorOnQa2(code));
        };

        NodeResult<NodePage<ProcessInstance>> entry = findOnQa2(processRequest("qa/node2"));

        assertThat(entry.error()).contains(errorOnQa2(code));
        assertThat(entry.warnings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7.4", "6.1"})
    void theFanOutDeadlineOfAnAdmittedNodeStillWaitingForASlotCarriesTheWarnings(String line) {
        pinUnverified(line);
        cliTimeout = Duration.ofMillis(100); // deadline: 3 x 100 ms + 300 ms + 1 s
        processes(QA2).answer = query -> {
            try {
                Thread.sleep(Duration.ofSeconds(10)); // the session waits for a free slot
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("cut off");
        };

        NodeResult<NodePage<ProcessInstance>> entry = findOnQa2(processRequest("qa/node2"));

        assertThat(entry.error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message()).startsWith("No response from qa/node2 within");
        });
        assertThat(entry.warnings()).containsExactly(admissionWarnings(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"7.4", "6.1"})
    void aTagRefusalCarriesNoWarning(String line) {
        pinUnverified(line);
        processes(QA2).validator = query -> {
            throw tagRefused(QA2);
        };

        NodeResult<NodePage<ProcessInstance>> entry = findOnQa2(
            withTag(processRequest("qa/node2"), "release-1"));

        assertThat(entry.error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
        assertThat(entry.warnings()).isEmpty();
        assertThat(processes(QA2).queries()).isEmpty();
    }

    // --- userColumn (T045b, research D-21) -----------------------------------------------------

    @Test
    void theUserColumnGoesWithTheQueryOfAnAdmittedServerAndItsValuesAreShown() {
        processes(DEV).answer = query -> page(List.of(withUserColumnValue(
            instance(DEV, "1", ProcessState.ERROR, NOW), "order 4711")), 0, 50, 1, false);

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            withUserColumn(processRequest("dev/node1"), 2));

        assertThat(single(results).items()).singleElement().satisfies(item ->
            assertThat(item.userColumnValue()).contains("order 4711"));
        assertThat(results.get(0).payload().orElseThrow().warnings()).isEmpty();
        assertThat(processes(DEV).queries()).singleElement().satisfies(query ->
            assertThat(query.userColumn()).contains(2));
    }

    @Test
    void inAStageWithA90ServerThe81ServerReadsTheColumnAndThe90ServerIsListedWithAWarning() {
        versionLines.put(QA2, VersionSetting.V9_0);
        processes(QA1).answer = query -> page(List.of(
            instance(QA1, "1", ProcessState.ERROR, NOW)), 0, 50, 1, false);
        processes(QA2).answer = query -> page(List.of(
            instance(QA2, "2", ProcessState.ERROR, NOW)), 0, 50, 1, false);

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            withUserColumn(processRequest("qa"), 2));

        assertThat(results).extracting(NodeResult::node).containsExactly(QA1, QA2);
        assertThat(results.get(0).payload().orElseThrow().warnings()).isEmpty();
        assertThat(processes(QA1).queries()).singleElement().satisfies(query ->
            assertThat(query.userColumn()).contains(2));
        NodePage<ProcessInstance> other = results.get(1).payload().orElseThrow();
        assertThat(other.page().items()).extracting(ProcessInstance::processId)
            .containsExactly("2");
        assertThat(processes(QA2).queries()).singleElement().satisfies(query ->
            assertThat(query.userColumn()).isEmpty());
        assertThat(other.warnings()).singleElement().asString()
            .startsWith("userColumn unavailable (UNSUPPORTED_VERSION): find_processes: INUBIT 9.0")
            .contains("CLI ps -u is not yet verified for INUBIT 9.0");
    }

    @Test
    void inAStageWithA61ServerThe61ServerIsListedWithAWarningAndWithoutTheColumn() {
        matrix = with61();
        versionLines.put(QA2, VersionSetting.V6_1);
        withClient.add(QA2);
        processes(QA2).answer = query -> page(List.of(
            instance(QA2, "2", ProcessState.ERROR, NOW)), 0, 50, 1, false);

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            withUserColumn(processRequest("qa"), 2));

        assertThat(processes(QA1).queries()).singleElement().satisfies(query ->
            assertThat(query.userColumn()).contains(2));
        NodePage<ProcessInstance> other = results.get(1).payload().orElseThrow();
        assertThat(other.page().items()).hasSize(1);
        assertThat(processes(QA2).queries()).singleElement().satisfies(query ->
            assertThat(query.userColumn()).isEmpty());
        assertThat(other.warnings()).anySatisfy(warning -> assertThat(warning)
            .startsWith("userColumn unavailable (UNSUPPORTED_VERSION): find_processes: INUBIT 6.1")
            .contains("CLI ps -u is not yet verified for INUBIT 6.1"));
    }

    @Test
    void aSingle90Or61ServerAskedForTheColumnIsRefusedWithoutAQuery() {
        matrix = with61();
        versionLines.put(QA1, VersionSetting.V9_0);
        versionLines.put(QA2, VersionSetting.V6_1);
        withClient.add(QA2);

        for (NodeId server : List.of(QA1, QA2)) {
            List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
                withUserColumn(processRequest(server.value()), 1));

            assertThat(results).singleElement().satisfies(result -> {
                assertThat(result.payload()).isEmpty();
                assertThat(result.error()).hasValueSatisfying(error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                    assertThat(error.node()).contains(server);
                    assertThat(error.message()).contains("CLI ps -u is not yet verified");
                });
            });
            assertThat(processes(server).queries()).isEmpty();
        }
        assertThat(probes).as("refused before the 6.1 client is probed").isEmpty();
    }

    @Test
    void withoutTheColumnA90ServerIsQueriedAsBefore() {
        versionLines.put(QA1, VersionSetting.V9_0);
        processes(QA1).answer = query -> page(List.of(
            instance(QA1, "1", ProcessState.ERROR, NOW)), 0, 50, 1, false);

        List<NodeResult<NodePage<ProcessInstance>>> results = service().findProcesses(
            processRequest("qa/node1"));

        assertThat(single(results).items()).singleElement().satisfies(item ->
            assertThat(item.userColumnValue()).isEmpty());
        assertThat(results.get(0).payload().orElseThrow().warnings()).isEmpty();
        assertThat(processes(QA1).queries()).singleElement().satisfies(query ->
            assertThat(query.userColumn()).isEmpty());
    }

    private static ProcessRequest withUserColumn(ProcessRequest r, int column) {
        return new ProcessRequest(r.target(), r.states(), r.hangingOnly(),
            r.hangingThresholdMinutes(), r.workflow(), r.tag(), r.since(), r.until(),
            r.offset(), r.limit(), Optional.of(column));
    }

    private static ProcessInstance withUserColumnValue(ProcessInstance i, String value) {
        return new ProcessInstance(i.node(), i.processId(), i.globalProcessId(), i.state(),
            i.rawState(), i.since(), i.timeInState(), i.hanging(), i.workflow(), i.module(),
            i.moduleType(), i.tag(), i.inubitNode(), i.owner(), i.priority(), Optional.of(value));
    }

    // --- query_logs ----------------------------------------------------------------------------

    @Test
    void aLogQueryIsMappedValidatedOnEveryServerAndBounded() {
        for (NodeId server : List.of(QA1, QA2)) {
            logs(server).answer = query -> page(List.of(entry(server, 1_000),
                entry(server, 3_000), entry(server, 2_000)), query.offset(), query.limit(), 3,
                false);
        }

        List<NodeResult<NodePage<LogEntry>>> results = service().queryLogs(new LogRequest("qa",
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
        assertThat(results.get(0).payload().orElseThrow().page().items())
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

        List<NodeResult<NodePage<LogEntry>>> results = service().queryLogs(logRequest("qa",
            LogType.SYSTEM_LOG));

        assertThat(results.get(0).error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED));
        assertThat(results.get(1).payload().orElseThrow().page().items()).hasSize(1);
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
