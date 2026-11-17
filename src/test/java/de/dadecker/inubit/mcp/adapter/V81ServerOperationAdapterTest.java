package de.dadecker.inubit.mcp.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliOperationRunner;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ClientCheckLookup;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerOperationPort;
import de.dadecker.inubit.mcp.domain.port.ServerOperationPort.ErrorProcesses;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.RecordingWriteLock;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestPermits;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 022 T058 (US4, research D-14, D-22): the 8.1 server operation adapter. The StartCLI
 * forms run on the {@link CliOperationRunner} (scripted by {@link ScriptedProcessLauncher}; the
 * answers are the RECONSTRUCTED forms of T057), the readiness switch on the node's REST client
 * (WireMock), both handed off under the node's write lock; the set of processes in ERROR comes
 * from the {@link ProcessQueryPort}, page by page up to 10,000 rows.
 */
@Timeout(60)
class V81ServerOperationAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final WritePermit PERMIT = TestPermits.of(DEV, Capability.SERVER_OPERATION);
    private static final String PREAMBLE = "JAVA_HOME is set\nPassword: \n";
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");
    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;

    private final SecretScrubber scrubber = new SecretScrubber();
    private final MutableClock clock = new MutableClock(NOW);
    /** The node's write lock: hands off without a check and records the permits. */
    private final RecordingWriteLock lock = new RecordingWriteLock(new WriteLock() {
        @Override
        public <T> T handOff(WritePermit permit, Function<Optional<InubitVersion>, T> action) {
            return action.apply(Optional.of(permit.admitted()));
        }
    });
    private EffectiveNodeConfig config;
    private NodeCredentials credentials;
    private CredentialGuard guard;
    private InubitHttpClient client;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        wireMock.resetAll();
        FakeStartCli.install(cliHome);
        config = TestNodeConfig.node().baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build();
        credentials = TestSecrets.credentialStore(List.of(config), Map.of(
            "INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", TestSecrets.CONFIGURED_PASSWORD),
            scrubber).get(DEV).current(Duration.ofSeconds(5));
        guard = new CredentialGuard(storeLabels("INUBIT", DEV), clock);
        client = InubitHttpClient.create(config, credentials, MaintenanceProbe.NONE, scrubber,
            guard);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private ServerOperationPort adapter(ScriptedProcessLauncher cli, ProcessQueryPort processes) {
        CliOperationRunner operations = new CliOperationRunner(config, credentials, guard,
            TestCliRunners.forHost(cli, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost()),
                id -> Optional.of(TestPermits.V8_1_17), ClientCheckLookup.NONE, lock),
            new CliOutputClassifier(scrubber, storeLabels("INUBIT", DEV), false));
        return new V81ServerOperationAdapter(config, operations, client, lock, processes);
    }

    private ServerOperationPort adapter(ScriptedProcessLauncher cli) {
        return adapter(cli, new ErrorRows(0));
    }

    private static void step(ScriptedProcessLauncher cli, String command, String answer) {
        cli.expect(Pattern.compile(Pattern.quote(command))).replying(PREAMBLE + answer + "\n",
            "", 0);
    }

    // --- StartCLI ----------------------------------------------------------------------------

    @Test
    void theStateReadAndTheStartCliChangesRunTheirFormsWithTheRunnersAnswers() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        step(cli, "maintenance -s", "Maintenance mode: off");
        step(cli, "maintenance on -eq true", "Maintenance mode: on");
        step(cli, "maintenance off", "Maintenance mode: off");
        step(cli, "trace on", "Trace: on");
        step(cli, "trace -l info", "Trace level: info");
        step(cli, "debug off", "Debug mode: off");
        step(cli, "triggerMidnightTask", "Midnight task triggered.");
        step(cli, "processdelete error", "Deleted processes: 3");
        ServerOperationPort port = adapter(cli);

        assertThat(port.maintenanceState()).isFalse();
        assertThat(port.switchMaintenance(true, Optional.of(true), PERMIT)).isTrue();
        assertThat(port.switchMaintenance(false, Optional.empty(), PERMIT)).isFalse();
        assertThat(port.switchTrace(true, PERMIT)).isTrue();
        assertThat(port.setTraceLevel(TraceLevel.INFO, PERMIT)).isEqualTo(TraceLevel.INFO);
        assertThat(port.switchDebug(false, PERMIT)).isFalse();
        assertThat(port.triggerMidnightTask(PERMIT)).isEqualTo("Midnight task triggered.");
        assertThat(port.deleteErrorProcesses(PERMIT)).isEqualTo(3L);

        cli.verifyComplete();
        assertThat(cli.execCommands()).containsExactly("maintenance -s",
            "maintenance on -eq true", "maintenance off", "trace on", "trace -l info",
            "debug off", "triggerMidnightTask", "processdelete error");
        assertThat(lock.permits()).as("every change, not the state read, is handed off")
            .hasSize(7).allSatisfy(permit -> assertThat(permit).isSameAs(PERMIT));
        assertThat(wireMock.getAllServeEvents()).as("no REST request").isEmpty();
    }

    @Test
    void theTraceLevelAndTheDebugStateAreUnknownWithoutContact() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        ServerOperationPort port = adapter(cli);

        assertThat(port.traceLevel()).isEmpty();
        assertThat(port.debugState()).isEmpty();

        assertThat(cli.launches()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // --- REST readiness switch -----------------------------------------------------------------

    @Test
    void theReadinessIsSwitchedOverRestWithCredentialsInsideTheHandOff() {
        wireMock.stubFor(post(urlEqualTo("/ibis/rest/ready/on"))
            .willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(post(urlEqualTo("/ibis/rest/ready/off"))
            .willReturn(aResponse().withStatus(200)));
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        ServerOperationPort port = adapter(cli);

        port.switchReadiness(false, PERMIT);
        port.switchReadiness(true, PERMIT);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/ibis/rest/ready/off"))
            .withHeader("Authorization", matching("Basic .+")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ibis/rest/ready/on"))
            .withHeader("Authorization", matching("Basic .+")));
        assertThat(lock.permits()).hasSize(2).allSatisfy(permit ->
            assertThat(permit).isSameAs(PERMIT));
        assertThat(cli.launches()).as("no StartCLI run").isEmpty();
    }

    @Test
    void aReadinessSwitchWithThePermitOfAnotherNodeSendsNothing() {
        ServerOperationPort port = adapter(new ScriptedProcessLauncher());
        WritePermit other = TestPermits.of(NodeId.parse("dev/node2"),
            Capability.SERVER_OPERATION);

        assertThatThrownBy(() -> port.switchReadiness(false, other))
            .isInstanceOf(IllegalArgumentException.class);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- the set of processes in ERROR (D-14) --------------------------------------------------

    @Test
    void theErrorProcessesAreReadPageByPageWithDistinctSortedIdsAndWorkflows() {
        ErrorRows rows = new ErrorRows(2_345);
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();

        ErrorProcesses errors = adapter(cli, rows).errorProcesses(NOW);

        assertThat(errors.count()).isEqualTo(2_345);
        assertThat(errors.countOnly()).isFalse();
        assertThat(errors.ids()).hasSize(2_344).as("the sub-workflow row adds no id")
            .isSorted().startsWith("1000000", "1000001").endsWith("1002343");
        assertThat(errors.workflows()).containsExactly("Workflow-0000", "Workflow-0001",
            "Workflow-0002");
        assertThat(rows.queries).extracting(ProcessQuery::offset).containsExactly(0, 1000, 2000);
        assertThat(rows.queries).allSatisfy(query -> {
            assertThat(query.states()).containsExactly(ProcessState.ERROR);
            assertThat(query.rawStates()).contains(List.of("Error"));
            assertThat(query.hangingOnly()).isFalse();
            assertThat(query.workflow()).isEmpty();
            assertThat(query.now()).isEqualTo(NOW);
            assertThat((long) query.offset() + query.limit())
                .isLessThanOrEqualTo(ServerOperationPort.MAX_ERROR_PROCESS_IDS);
        });
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void exactlyTenThousandRowsAreStillListed() {
        ErrorRows rows = new ErrorRows(10_000);

        ErrorProcesses errors = adapter(new ScriptedProcessLauncher(), rows).errorProcesses(NOW);

        assertThat(errors.count()).isEqualTo(10_000);
        assertThat(errors.countOnly()).isFalse();
        assertThat(errors.ids()).hasSize(9_999);
        assertThat(rows.queries).hasSize(10);
    }

    @Test
    void moreThanTenThousandRowsGiveTheCountOnlyAfterTheFirstPage() {
        ErrorRows rows = new ErrorRows(10_001);

        ErrorProcesses errors = adapter(new ScriptedProcessLauncher(), rows).errorProcesses(NOW);

        assertThat(errors).isEqualTo(ErrorProcesses.countOnly(10_001));
        assertThat(errors.countOnly()).isTrue();
        assertThat(errors.ids()).isEmpty();
        assertThat(errors.workflows()).isEmpty();
        assertThat(rows.queries).hasSize(1);
    }

    @Test
    void noProcessInErrorIsAnEmptySet() {
        ErrorRows rows = new ErrorRows(0);

        ErrorProcesses errors = adapter(new ScriptedProcessLauncher(), rows).errorProcesses(NOW);

        assertThat(errors).isEqualTo(new ErrorProcesses(0, List.of(), List.of()));
        assertThat(rows.queries).hasSize(1);
    }

    /**
     * {@code total} rows in state ERROR, in the paging of the 8.1 queue log adapter: row
     * {@code i} has the process id {@code 1000000 + i} and workflow {@code Workflow-000<i % 3>},
     * except the last row of a set of more than one, which is a sub-workflow row of the process
     * before it.
     */
    private static final class ErrorRows implements ProcessQueryPort {

        final List<ProcessQuery> queries = new ArrayList<>();
        private final int total;

        ErrorRows(int total) {
            this.total = total;
        }

        @Override
        public void validate(ProcessQuery query) {
        }

        @Override
        public Page<ProcessInstance> find(ProcessQuery query) {
            queries.add(query);
            List<ProcessInstance> items = new ArrayList<>();
            int end = (int) Math.min((long) query.offset() + query.limit(), total);
            for (int i = query.offset(); i < end && total <= MAX_LISTED; i++) {
                int process = i == total - 1 && total > 1 ? i - 1 : i;
                items.add(row(String.valueOf(1_000_000 + process),
                    "Workflow-000" + (process % 3)));
            }
            int next = query.offset() + items.size();
            return new Page<>(items, query.offset(), query.limit(), OptionalLong.of(total),
                false, false, next < total ? OptionalInt.of(next) : OptionalInt.empty());
        }

        @Override
        public ProcessRows findByProcessId(String processId, Instant now,
            Duration hangingThreshold) {
            throw new AssertionError("not expected");
        }

        /** Beyond this the fake answers the total without rows (only the count is read). */
        private static final int MAX_LISTED = ServerOperationPort.MAX_ERROR_PROCESS_IDS;

        private static ProcessInstance row(String processId, String workflow) {
            return new ProcessInstance(DEV, processId, Optional.of(processId), ProcessState.ERROR,
                "Error", NOW.minusSeconds(60), Duration.ofSeconds(60), false,
                Optional.of(workflow), Optional.of("Module-0001"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        }
    }
}
