package de.dadecker.inubit.mcp.adapter;

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
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.ActionSpec;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.ServerOperationService;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageEntry;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ClientCheckLookup;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerOperationPort;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * T066 (feature 022, US4, research D-22, L-2, L-3): {@code operate_server} against the recordings
 * of an 8.1.17 server (T054), through {@link V81ServerOperationAdapter} with a
 * {@link CliOperationRunner} and the node's REST client as wired in production.
 *
 * <ul>
 *   <li>StartCLI: {@code fixtures/v8_1/cli/admin-operate-*}, replayed in the order of the live run
 *       ({@code maintenance -s}, {@code maintenance on -eq false}, {@code maintenance off},
 *       {@code trace on|off}, {@code trace -l debug|info}, {@code triggerMidnightTask},
 *       {@code processdelete error});
 *   <li>REST: {@code POST /ready/on} ({@code admin-operate-ready-on}, 200) and
 *       {@code POST /ready/off} (503 {@code No Service.}; the JSON envelope of
 *       {@code admin-operate-ready-off-reconstructed} is RECONSTRUCTED, its message recorded);
 *   <li>the coverage rows of every action: {@code reachable} on 8.1, {@code planned} on 9.0 until
 *       the US4 9.0 follow-up; the gate over the production matrix and list admits every action on
 *       8.1 and refuses it on 9.0 as "not yet verified".
 * </ul>
 */
@Timeout(60)
class OperateV81ContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId DEV_90 = NodeId.parse("dev/node2");
    private static final WritePermit PERMIT = TestPermits.of(DEV, Capability.SERVER_OPERATION);
    private static final MajorMinor V8_1 = new MajorMinor(8, 1);
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);
    private static final String TOOL = ServerOperationService.TOOL;
    private static WireMockServer wireMock;

    /** The ERROR set is the {@code find_processes} query, under its own contract tests. */
    private static final ProcessQueryPort NO_QUERY = new ProcessQueryPort() {
        @Override
        public void validate(ProcessQuery query) {
            throw new AssertionError("not expected");
        }

        @Override
        public Page<ProcessInstance> find(ProcessQuery query) {
            throw new AssertionError("not expected");
        }

        @Override
        public ProcessRows findByProcessId(String processId, Instant now,
            Duration hangingThreshold) {
            throw new AssertionError("not expected");
        }
    };

    @TempDir
    Path cliHome;

    private final SecretScrubber scrubber = new SecretScrubber();
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
        guard = new CredentialGuard(storeLabels("INUBIT", DEV),
            new MutableClock(Instant.parse("2026-11-18T08:00:00Z")));
        client = InubitHttpClient.create(config, credentials, MaintenanceProbe.NONE, scrubber,
            guard);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private ServerOperationPort adapter(ScriptedProcessLauncher cli) {
        CliOperationRunner operations = new CliOperationRunner(config, credentials, guard,
            TestCliRunners.forHost(cli, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost()),
                id -> Optional.of(TestPermits.V8_1_17), ClientCheckLookup.NONE, lock),
            new CliOutputClassifier(scrubber, storeLabels("INUBIT", DEV), false));
        return new V81ServerOperationAdapter(config, operations, client, lock, NO_QUERY);
    }

    private static void recorded(ScriptedProcessLauncher cli, String command,
        String fixtureCase) {
        cli.expect(Pattern.compile(Pattern.quote(command))).replying(fixtureCase);
    }

    // --- StartCLI ----------------------------------------------------------------------------

    @Test
    void theRecordedStartCliAnswersAreReadThroughTheAdapter() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        recorded(cli, "maintenance -s", "admin-operate-maintenance-state");
        recorded(cli, "maintenance on -eq false", "admin-operate-maintenance-on-eq-false");
        recorded(cli, "maintenance -s", "admin-operate-maintenance-state-on");
        recorded(cli, "maintenance off", "admin-operate-maintenance-off");
        recorded(cli, "trace on", "admin-operate-trace-on");
        recorded(cli, "trace -l debug", "admin-operate-trace-level-debug");
        recorded(cli, "trace -l info", "admin-operate-trace-level-info");
        recorded(cli, "trace off", "admin-operate-trace-off");
        recorded(cli, "triggerMidnightTask", "admin-operate-midnight-task");
        recorded(cli, "processdelete error", "admin-operate-processdelete-error");
        ServerOperationPort port = adapter(cli);

        assertThat(port.maintenanceState()).isFalse();
        assertThat(port.switchMaintenance(true, Optional.of(false), PERMIT))
            .isEqualTo("Updated Successfully");
        assertThat(port.maintenanceState()).isTrue();
        assertThat(port.switchMaintenance(false, Optional.empty(), PERMIT))
            .isEqualTo("Updated Successfully");
        port.switchTrace(true, PERMIT);
        assertThat(port.setTraceLevel(TraceLevel.DEBUG, PERMIT)).isEqualTo(TraceLevel.DEBUG);
        assertThat(port.setTraceLevel(TraceLevel.INFO, PERMIT)).isEqualTo(TraceLevel.INFO);
        port.switchTrace(false, PERMIT);
        port.triggerMidnightTask(PERMIT);
        assertThat(port.deleteErrorProcesses(PERMIT))
            .as("INUBIT's answer names no count")
            .isEqualTo("Process with status [error] has been deleted.");

        cli.verifyComplete();
        assertThat(lock.permits()).as("every change, not the state reads, is handed off")
            .hasSize(8).allSatisfy(permit -> assertThat(permit).isSameAs(PERMIT));
        assertThat(wireMock.getAllServeEvents()).as("no REST request").isEmpty();
    }

    // --- REST readiness switch -----------------------------------------------------------------

    /** 8.1.17 answers {@code on} with 200 and a successful {@code off} with 503 (T054). */
    @Test
    void theRecordedReadinessAnswersAreTheSwitchDone() {
        wireMock.stubFor(post(urlEqualTo("/ibis/rest/ready/off"))
            .willReturn(RestFixtures.response("admin-operate-ready-off-reconstructed", "json")));
        wireMock.stubFor(post(urlEqualTo("/ibis/rest/ready/on"))
            .willReturn(RestFixtures.response("admin-operate-ready-on", "json")));
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        ServerOperationPort port = adapter(cli);

        port.switchReadiness(false, PERMIT);
        port.switchReadiness(true, PERMIT);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/ibis/rest/ready/off")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/ibis/rest/ready/on")));
        assertThat(lock.permits()).hasSize(2);
        assertThat(cli.launches()).as("no StartCLI run").isEmpty();
    }

    // --- coverage and gate -------------------------------------------------------------------

    @Test
    void everyActionsRowsAreReachableByItOn81AndWaitForThe90FollowUp() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        for (ActionSpec spec : ServerOperationService.SPECS) {
            assertThat(spec.declaredEntries()).as(spec.action()).isNotEmpty();
            for (CoverageKey key : spec.declaredEntries()) {
                CoverageEntry row = coverage.entries().stream()
                    .filter(entry -> entry.key().equals(key)).findFirst().orElseThrow();
                assertThat(row.states()).as("%s", key)
                    .containsEntry(V8_1, new CoverageState.Reachable(TOOL, spec.action()))
                    .containsEntry(V9_0, new CoverageState.Planned());
                assertThat(row.story()).contains("US4");
                assertThat(row.waits()).as("%s", key).containsExactly("9.0-followup");
            }
        }
        assertThat(coverage.deliveredStories()).contains("US4");
    }

    /** {@code debug on|off} has no observable effect on 8.1.17 (T054): excluded, never sent. */
    @Test
    void debugIsExcludedOnBothLinesAndNoActionSendsIt() {
        for (MajorMinor line : List.of(V8_1, V9_0)) {
            assertThat(InterfaceCoverage.production().state(Channel.CLI, "debug on|off", line))
                .hasValueSatisfying(state ->
                    assertThat(state).isInstanceOf(CoverageState.Excluded.class));
        }
        assertThat(ServerOperationService.SPECS).flatMap(ActionSpec::declaredEntries)
            .noneMatch(key -> key.entry().startsWith("debug"));
    }

    @Test
    void theProductionGateAdmitsEveryActionOn81AndRefusesItOn90AsNotYetVerified() {
        NodeVersions versions = new NodeVersions(new MutableClock(Instant.parse(
            "2026-11-18T08:00:00Z")), Map.of(DEV, VersionSetting.V8_1, DEV_90,
            VersionSetting.V9_0)::get, node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), CapabilityMatrix.production(),
            InterfaceCoverage.production());

        for (ActionSpec spec : ServerOperationService.SPECS) {
            assertThat(gate.admitCall(TOOL, DEV, spec, spec.callEntries()).warnings())
                .as(spec.action()).isEmpty();
            assertThatThrownBy(() -> gate.admitCall(TOOL, DEV_90, spec, spec.callEntries()))
                .as(spec.action()).isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                    assertThat(e.error().message()).contains("not yet verified");
                });
        }
    }
}
