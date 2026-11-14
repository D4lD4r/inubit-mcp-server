package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliSettingsRunner;
import de.dadecker.inubit.mcp.application.ServerSettingsService.NodeSettings;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.PrivilegedWorkflows;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.ServerSettings;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T021 (feature 022, US1, research D-2 "Gate per call", D-6, FR-002, FR-009, FR-013): the reads of
 * {@code get_server_settings}. The capability matrix supports {@code SERVER_SETTINGS} on 8.1 and
 * 9.0 and the coverage list is a fixture whose US1 rows are reachable, so the parts reach the
 * ports, independent of the production list (reachable on 8.1 and 9.0 since T025, T026).
 */
@Timeout(30)
class ServerSettingsServiceTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final NodeId DEV2 = NodeId.parse("dev/node2");
    /** INUBIT 9.0, whose {@code date} row the fixture excludes. */
    private static final NodeId TEST1 = NodeId.parse("test/node1");
    /** INUBIT 8.1 without a StartCLI client. */
    private static final NodeId QA1 = NodeId.parse("qa/node1");

    private static final String DATE_REASON = "shows the time of the StartCLI client host";

    /** The US1 rows of interface-coverage.tsv, reachable; {@code date} excluded on 9.0. */
    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\tthreads showNumberOfWorkflowThreads\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        CLI\tprivilegedWorkflows showThreadsPercentage\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        CLI\tprivilegedWorkflows showWorkflowList\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        CLI\tdbconnectiontolerance show\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        CLI\tdate\tREAD\tA\tUS1\t%1$s\texcluded:%2$s\t-
        REST\tGET /configurations\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        REST\tGET /license\tREAD\tA\tUS1\t%1$s\t%1$s\t-
        """.formatted("reachable:get_server_settings/default", DATE_REASON);

    private static final Set<String> CLI_PARTS = Set.of(UnavailablePart.THREADS,
        UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE,
        UnavailablePart.SERVER_TIME);

    private static final LocalDate EXPIRY = LocalDate.of(2027, 3, 31);
    private static final PrivilegedWorkflows PRIVILEGED =
        new PrivilegedWorkflows(20, List.of("Fictitious-Workflow-0001"), false);
    private static final DbConnectionTolerance TOLERANCE = new DbConnectionTolerance(25, 1, 180);
    private static final String SERVER_TIME = "Thu Nov 12 14:03:27 CET 2026";

    @TempDir
    Path cliHome;

    private final NodeVersions versions = new NodeVersions(Clock.systemUTC(),
        Map.of(DEV1, VersionSetting.V8_1, DEV2, VersionSetting.V8_1, TEST1, VersionSetting.V9_0,
            QA1, VersionSetting.V8_1)::get,
        node -> Duration.ofMinutes(10));
    private final Set<NodeId> withClient = new HashSet<>(Set.of(DEV1, DEV2, TEST1));

    /** What {@code /metrics} answers; licensed by default. */
    private MonitoringPort.Metrics metrics = new MonitoringPort.Metrics.Available(
        LoadFigures.of(512, 1536, 2048, 3, 8, 16, 40, 5000));
    private ToolErrorException licenseFailure;
    private ToolErrorException cliFailure;
    /** The StartCLI reads, if a test runs them through the real session runner. */
    private Function<Set<String>, CliSettingsSnapshot> cliReads;
    private final List<Set<String>> cliCalls = new CopyOnWriteArrayList<>();
    private final List<ConfigurationCall> configurationCalls = new CopyOnWriteArrayList<>();
    private final List<NodeId> licenseCalls = new CopyOnWriteArrayList<>();

    private record ConfigurationCall(NodeId node, Optional<String> section, int maxValues,
        int maxChars) {
    }

    private final GatewayFactory gateways = new GatewayFactory() {
        @Override
        public MonitoringPort monitoring(NodeId server) {
            return new FakeMonitoring();
        }

        @Override
        public ServerSettingsPort serverSettings(NodeId server) {
            return new FakeSettings(server);
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("no gateway expected for " + server);
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("every node is pinned; no detection for " + server);
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError();
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError();
        }

        @Override
        public InventoryPort inventory(NodeId server) {
            throw new AssertionError();
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError();
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            return Optional.empty();
        }
    };

    private final VersionGate gate = new VersionGate(versions, gateways, settingsSupported(),
        (node, version, timeout) -> {
            throw new AssertionError("no client probe expected for " + node);
        }, TestClientSelection.of(withClient::contains), node -> Duration.ZERO,
        Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(LIST)));

    private final ResultLimiter limiter = new ResultLimiter(100, 50_000);

    private final ServerSettingsService service = new ServerSettingsService(gateways,
        new TargetResolver(List.of(DEV1, DEV2, TEST1, QA1)), new FanOut(), limiter,
        node -> Duration.ofSeconds(5), node -> Duration.ofSeconds(5), gate, SecretTiming.NONE);

    {
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, client("8.1")));
        versions.clientProbed(DEV2, new ProbedClient(ClientKey.NONE, client("8.1")));
        versions.clientProbed(TEST1, new ProbedClient(ClientKey.NONE, client("9.0")));
    }

    // --- AS1, AS6, AS7 ---------------------------------------------------------------------

    @Test
    void theDefaultPartsAreAllButTheConfigurationEachWithItsSource() {
        ServerSettings settings = one(service.read("dev/node1", Set.of(), Optional.empty()));

        assertThat(settings.node()).isEqualTo(DEV1);
        assertThat(settings.license()).contains(new LicenseFacts(Optional.of(EXPIRY),
            Optional.of(8L), Optional.of("ACME")));
        assertThat(settings.workflowThreads()).contains(16L);
        assertThat(settings.privilegedWorkflows()).contains(PRIVILEGED);
        assertThat(settings.dbConnectionTolerance()).contains(TOLERANCE);
        assertThat(settings.serverTime()).contains(SERVER_TIME);
        assertThat(settings.configuration()).isEmpty();
        assertThat(settings.unavailable()).isEmpty();
        assertThat(configurationCalls).isEmpty();
        assertThat(cliCalls).containsExactly(CLI_PARTS);
        assertThat(settings.sources()).isEqualTo(Map.ofEntries(
            Map.entry("license", Channel.REST),
            Map.entry("licensedThreads", Channel.REST),
            Map.entry("workflowThreads", Channel.CLI),
            Map.entry("privilegedWorkflows", Channel.CLI),
            Map.entry("dbConnectionTolerance", Channel.CLI),
            Map.entry("serverTime", Channel.CLI),
            Map.entry("blockingQueueSize", Channel.REST),
            Map.entry("version", Channel.REST),
            Map.entry("memoryUsedPercent", Channel.REST),
            Map.entry("maintenanceMode", Channel.REST)));
    }

    /** AS7, FR-002: the facts get_health reads come from its MonitoringPort calls. */
    @Test
    void theGetHealthFactsComeFromTheMonitoringCalls() {
        ServerSettings settings = one(service.read("dev/node1", Set.of(), Optional.empty()));

        assertThat(settings.version()).contains("8.1.12");
        assertThat(settings.memoryUsedPercent()).contains(25.0);
        assertThat(settings.blockingQueueSize()).contains(5000L);
        assertThat(settings.maintenanceMode()).contains(false);
        assertThat(cliCalls).allSatisfy(parts -> assertThat(CLI_PARTS).containsAll(parts));
    }

    /** data-model ServerSettings: the licensed threads of /metrics, not of GET /license. */
    @Test
    void theLicensedThreadsComeFromMetricsWhileItIsLicensed() {
        ServerSettings settings = one(service.read("dev/node1",
            Set.of(UnavailablePart.LICENSE), Optional.empty()));

        // GET /license says 99 (FakeSettings), /metrics 8
        assertThat(settings.license().orElseThrow().licensedThreads()).contains(8L);
    }

    @Test
    void theLicensedThreadsComeFromTheLicenseWhenMetricsIsNotLicensed() {
        metrics = new MonitoringPort.Metrics.NotLicensed("HTTP 403: no license for metrics");

        ServerSettings settings = one(service.read("dev/node1",
            Set.of(UnavailablePart.LICENSE), Optional.empty()));

        assertThat(settings.license().orElseThrow().licensedThreads()).contains(99L);
        assertThat(settings.sources()).containsEntry("licensedThreads", Channel.REST);
        assertThat(settings.blockingQueueSize()).isEmpty();
        assertThat(settings.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.LOAD);
            assertThat(part.reason()).startsWith(UnavailablePart.METRICS_NOT_LICENSED + ": ");
        });
    }

    /** No -sv, -pctmem, getBlockingQueueSize or -maintenanceState is sent (AS7, research D-6). */
    @Test
    void theStartCliSessionSendsOnlyTheSettingsCommands() throws IOException {
        FakeStartCli.install(cliHome);
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            "Password: \n" + "CLI|jdoe@inubit-dev-1.example.test> \n".repeat(6), "", 0);
        CliSettingsRunner runner = new CliSettingsRunner(
            TestNodeConfig.node().baseUrl("https://inubit-dev-1.example.test:8443")
                .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17")).build(),
            new NodeCredentials(DEV1,
                Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
                Optional.of(new SourcedValue<>(Secret.of("S3cr3t-settings-pw"),
                    "INUBIT_DEV_PASSWORD")),
                Optional.empty(), Optional.empty()),
            new CredentialGuard(storeLabels("INUBIT", DEV1),
                new MutableClock(Instant.parse("2026-11-12T08:00:00Z"))),
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV1), false));
        cliReads = runner::read;

        one(service.read("dev/node1", Set.of(), Optional.of("smtp")));

        assertThat(launcher.processes()).singleElement().satisfies(process -> {
            String stdin = new String(process.stdinBytes(), StandardCharsets.UTF_8);
            List<String> argv = process.spec().startCliArguments();
            assertThat(stdin).contains("threads showNumberOfWorkflowThreads\n",
                "privilegedWorkflows showWorkflowList\n", "dbconnectiontolerance show\n",
                "date\n");
            for (String forbidden : List.of("-sv", "-pctmem", "getBlockingQueueSize",
                "-maintenanceState", "license")) {
                assertThat(stdin).doesNotContain(forbidden);
                assertThat(argv).noneMatch(argument -> argument.contains(forbidden));
            }
        });
    }

    // --- AS3: the configuration ---------------------------------------------------------------

    @Test
    void theConfigurationWithoutASectionListsTheSectionNames() {
        ServerSettings settings = one(service.read("dev/node1",
            Set.of(UnavailablePart.CONFIGURATION), Optional.empty()));

        assertThat(settings.configuration()).contains(ConfigurationView.sections(
            List.of("smtp", "jms")));
        assertThat(settings.sources()).containsEntry("configuration", Channel.REST);
        assertThat(configurationCalls).containsExactly(
            new ConfigurationCall(DEV1, Optional.empty(), 100, 50_000));
        assertThat(cliCalls).isEmpty();
        assertThat(licenseCalls).isEmpty();
    }

    @Test
    void aSectionImpliesTheConfigurationNextToTheDefaultParts() {
        ServerSettings settings = one(service.read("dev/node1", Set.of(), Optional.of("smtp")));

        assertThat(settings.configuration().orElseThrow().section()).contains("smtp");
        assertThat(configurationCalls).containsExactly(
            new ConfigurationCall(DEV1, Optional.of("smtp"), 100, 50_000));
        assertThat(settings.license()).isPresent();
        assertThat(cliCalls).containsExactly(CLI_PARTS);
    }

    @Test
    void aStageSharesTheResultLimitsAmongItsServers() {
        service.read("dev", Set.of(UnavailablePart.CONFIGURATION), Optional.of("smtp"));

        assertThat(configurationCalls).extracting(ConfigurationCall::maxValues,
            ConfigurationCall::maxChars).containsOnly(tuple(100, 25_000));
    }

    // --- AS4, AS5 --------------------------------------------------------------------------

    /** AS4: a missing role for the license leaves the other parts and the metrics threads. */
    @Test
    void aMissingRoleForTheLicenseLeavesTheOtherParts() {
        licenseFailure = new ToolErrorException(ToolError.of(ErrorCode.FORBIDDEN,
            "GET /license needs the role System Administrator", "The login user lacks the role",
            "Give the login user the role System Administrator").withNode(DEV1));

        ServerSettings settings = one(service.read("dev/node1", Set.of(), Optional.empty()));

        assertThat(settings.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.LICENSE);
            assertThat(part.reason()).isEqualTo(
                "FORBIDDEN: GET /license needs the role System Administrator");
        });
        assertThat(settings.license()).contains(new LicenseFacts(Optional.empty(),
            Optional.of(8L), Optional.empty()));
        assertThat(settings.sources()).doesNotContainKey("license")
            .containsEntry("licensedThreads", Channel.REST);
        assertThat(settings.workflowThreads()).contains(16L);
        assertThat(settings.serverTime()).contains(SERVER_TIME);
    }

    /** AS5: a stage gives one entry per server, in config order. */
    @Test
    void aStageGivesOneEntryPerServer() {
        List<NodeResult<NodeSettings>> results =
            service.read("dev", Set.of(), Optional.empty());

        assertThat(results).extracting(NodeResult::node).containsExactly(DEV1, DEV2);
        assertThat(results).allSatisfy(result -> assertThat(result.payload().orElseThrow()
            .settings().node()).isEqualTo(result.node()));
    }

    // --- FR-013: parts that cannot be read ----------------------------------------------------

    @Test
    void anExcludedPartIsUnavailableAndTheOthersAreReturned() {
        ServerSettings settings = one(service.read("test/node1", Set.of(), Optional.empty()));

        assertThat(settings.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.SERVER_TIME);
            assertThat(part.reason()).startsWith("UNSUPPORTED_VERSION: ")
                .contains("CLI date is excluded for INUBIT 9.0: " + DATE_REASON);
        });
        assertThat(settings.serverTime()).isEmpty();
        assertThat(settings.workflowThreads()).contains(16L);
        assertThat(settings.license()).isPresent();
        assertThat(cliCalls).containsExactly(Set.of(UnavailablePart.THREADS,
            UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE));
    }

    /**
     * T025 (owner decision 2026-11-13): with the production matrix and coverage list, an 8.1 node
     * reports the root-only parts unavailable through the gate, and only the other StartCLI parts
     * reach the session.
     */
    @Test
    void onProduction81TheRootOnlyPartsAreUnavailableAndTheOthersAreRead() {
        VersionGate production = new VersionGate(versions, gateways,
            CapabilityMatrix.production(), (node, version, timeout) -> {
                throw new AssertionError("no client probe expected for " + node);
            }, TestClientSelection.of(withClient::contains), node -> Duration.ZERO,
            Terminology.DEFAULT, false, InterfaceCoverage.production());
        ServerSettingsService onProduction = new ServerSettingsService(gateways,
            new TargetResolver(List.of(DEV1)), new FanOut(), limiter,
            node -> Duration.ofSeconds(5), node -> Duration.ofSeconds(5), production,
            SecretTiming.NONE);

        ServerSettings settings = one(onProduction.read("dev/node1", Set.of(), Optional.empty()));

        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactlyInAnyOrder(UnavailablePart.THREADS,
                UnavailablePart.PRIVILEGED_WORKFLOWS);
        assertThat(settings.unavailable()).allSatisfy(part -> assertThat(part.reason())
            .startsWith("UNSUPPORTED_VERSION: ")
            .contains("is excluded for INUBIT 8.1: needs the INUBIT root user"));
        assertThat(cliCalls).containsExactly(Set.of(UnavailablePart.DB_CONNECTION_TOLERANCE,
            UnavailablePart.SERVER_TIME));
        assertThat(settings.license()).isPresent();
        assertThat(settings.dbConnectionTolerance()).isPresent();
        assertThat(settings.serverTime()).isPresent();
    }

    @Test
    void aNodeWithoutAStartCliClientGetsTheRestPartsAndTheCliPartsUnavailable() {
        ServerSettings settings = one(service.read("qa/node1", Set.of(), Optional.of("smtp")));

        assertThat(settings.license()).isPresent();
        assertThat(settings.configuration()).isPresent();
        assertThat(settings.version()).contains("8.1.12");
        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactlyInAnyOrderElementsOf(CLI_PARTS);
        assertThat(settings.unavailable()).allSatisfy(part ->
            assertThat(part.reason()).startsWith("CLI_UNAVAILABLE: "));
        assertThat(cliCalls).isEmpty();
    }

    /** T010 review: an incompatible StartCLI client refuses the StartCLI parts only. */
    @Test
    void anIncompatibleStartCliClientMakesOnlyTheCliPartsUnavailable() {
        versions.clientProbed(TEST1, new ProbedClient(ClientKey.NONE, client("8.1")));

        ServerSettings settings = one(service.read("test/node1", Set.of(), Optional.empty()));

        assertThat(settings.license()).isPresent();
        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactlyInAnyOrderElementsOf(CLI_PARTS);
        assertThat(settings.unavailable())
            .filteredOn(part -> !part.part().equals(UnavailablePart.SERVER_TIME))
            .allSatisfy(part -> assertThat(part.reason())
                .startsWith("UNSUPPORTED_VERSION: ").contains("StartCLI client 8.1.12"));
        assertThat(cliCalls).isEmpty();
    }

    /** T019: a failure of the whole session marks the asked StartCLI parts only. */
    @Test
    void aFailedStartCliSessionMakesItsPartsUnavailableAndKeepsTheRestParts() {
        cliFailure = new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
            "StartCLI login of dev/node1 failed", "Wrong credentials", "Check the credentials")
            .withNode(DEV1));

        ServerSettings settings = one(service.read("dev/node1", Set.of(), Optional.empty()));

        assertThat(settings.license()).isPresent();
        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactlyInAnyOrderElementsOf(CLI_PARTS);
        assertThat(settings.unavailable()).allSatisfy(part -> assertThat(part.reason())
            .isEqualTo("AUTH_FAILED: StartCLI login of dev/node1 failed"));
        assertThat(settings.sources()).doesNotContainKeys("workflowThreads", "serverTime");
    }

    @Test
    void aPartTheSessionCouldNotReadIsUnavailable() {
        cliReads = parts -> new CliSettingsSnapshot(Optional.of(16L), Optional.of(PRIVILEGED),
            Optional.of(TOLERANCE), Optional.empty(),
            List.of(new UnavailablePart(UnavailablePart.SERVER_TIME, "UNEXPECTED_RESPONSE: x")));

        ServerSettings settings = one(service.read("dev/node1", Set.of(), Optional.empty()));

        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.SERVER_TIME);
        assertThat(settings.sources()).doesNotContainKey("serverTime")
            .containsEntry("workflowThreads", Channel.CLI);
    }

    @Test
    void aCallWithoutAnAdmittedPartFailsForThatNode() {
        List<NodeResult<NodeSettings>> results = service.read("qa/node1",
            Set.of(UnavailablePart.THREADS), Optional.empty());

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.payload()).isEmpty();
            assertThat(result.error().orElseThrow().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        });
        assertThat(cliCalls).isEmpty();
        assertThat(licenseCalls).isEmpty();
    }

    @Test
    void anUnknownPartIsInvalidInput() {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service.read("dev/node1", Set.of("version"), Optional.empty()));

        assertThat(e).isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // --- helpers ------------------------------------------------------------------------------

    private static ServerSettings one(List<NodeResult<NodeSettings>> results) {
        assertThat(results).hasSize(1);
        NodeResult<NodeSettings> result = results.get(0);
        assertThat(result.error()).as("error").isEmpty();
        return result.payload().orElseThrow().settings();
    }

    /** A client for INUBIT {@code line} (e.g. "8.1"), version {@code line}.12. */
    private static ClientVersion client(String line) {
        return new ClientVersion(InubitVersion.parse(line + ".12").orElseThrow(),
            InubitVersion.parse(line + ".0"), InubitVersion.parse(line + ".99"));
    }

    private static CapabilityMatrix settingsSupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    private final class FakeMonitoring implements MonitoringPort {

        @Override
        public Healthcheck healthcheck() {
            return new Healthcheck(200, HealthStatus.OK, Optional.of(false), Optional.empty(),
                Optional.empty());
        }

        @Override
        public Readiness ready() {
            throw new AssertionError("get_server_settings does not read the readiness");
        }

        @Override
        public SystemInfo systemInfo() {
            return new SystemInfo(Optional.of("8.1.12"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public Metrics metrics() {
            return metrics;
        }

        @Override
        public boolean credentialsConfirmed() {
            return true;
        }
    }

    private final class FakeSettings implements ServerSettingsPort {

        private final NodeId node;

        FakeSettings(NodeId node) {
            this.node = node;
        }

        @Override
        public LicenseFacts license() {
            licenseCalls.add(node);
            if (licenseFailure != null) {
                throw licenseFailure;
            }
            return new LicenseFacts(Optional.of(EXPIRY), Optional.of(99L), Optional.of("ACME"));
        }

        @Override
        public ConfigurationView configuration(Optional<String> section, int maxValues,
            int maxChars) {
            configurationCalls.add(new ConfigurationCall(node, section, maxValues, maxChars));
            return section.map(name -> ConfigurationView.section(name,
                    List.of(ConfigurationView.Leaf.shown("/host", "mail.example.test")), false))
                .orElseGet(() -> ConfigurationView.sections(List.of("smtp", "jms")));
        }

        @Override
        public CliSettingsSnapshot cliSettings(Set<String> parts) {
            cliCalls.add(Set.copyOf(parts));
            if (cliFailure != null) {
                throw cliFailure;
            }
            if (cliReads != null) {
                return cliReads.apply(parts);
            }
            return new CliSettingsSnapshot(
                parts.contains(UnavailablePart.THREADS) ? Optional.of(16L) : Optional.empty(),
                parts.contains(UnavailablePart.PRIVILEGED_WORKFLOWS) ? Optional.of(PRIVILEGED)
                    : Optional.empty(),
                parts.contains(UnavailablePart.DB_CONNECTION_TOLERANCE) ? Optional.of(TOLERANCE)
                    : Optional.empty(),
                parts.contains(UnavailablePart.SERVER_TIME) ? Optional.of(SERVER_TIME)
                    : Optional.empty(),
                List.of());
        }
    }
}
