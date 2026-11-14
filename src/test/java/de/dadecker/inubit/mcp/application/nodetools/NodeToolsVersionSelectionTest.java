package de.dadecker.inubit.mcp.application.nodetools;

import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.classPath;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.clientClassPath;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.java;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.log.ServerLogVersionProfile;
import de.dadecker.inubit.mcp.adapter.cli.node.CliClusterNodeAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliIoLogAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliLogLevelAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.CliNodeConfigAdapter;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeHelperRunner;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
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
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.domain.port.ClusterNodePort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.IoLogPort;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.NodeConfigPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T059 (feature 024, US5-1 … US5-3, FR-005, research D-4): the five node tools through their real
 * services, the real {@link VersionGate} (detection scripted per node), the real
 * {@link WriteGuard} for the log-level tools and the real adapters, {@link NodeHelperRunner} and
 * {@link CliRunner} per node on one profile ({@link ServerLogVersionProfile}: a client
 * installation and JDK per version). The INUBIT client helper and StartCLI are a fake launcher
 * each per node that records the launched JDK, client and request; no process is started and no
 * server is contacted.
 *
 * <p>On 8.1 every tool runs the helper with {@code SOAP_81} on the
 * {@code defaults.cliClients."8.1"} installation and its JDK, on 9.0 with {@code REST_90} on the
 * 9.0 installation; the log-level tools run StartCLI of the same installation. 6.1, 9.1, a version
 * no matrix entry declares and an undetectable version are refused before any launch (a read with
 * the detection's code, a log-level change by the write guard's gate as an unknown version, except
 * a {@code TLS_ERROR}). {@link #MATRIX} admits the four node
 * capabilities on 8.1 and 9.0; {@link CapabilityMatrix#production()} refuses every (version,
 * capability) it has not flipped to {@code SUPPORTED} as "not yet verified".
 */
@Timeout(60)
class NodeToolsVersionSelectionTest {

    private static final NodeId N81 = NodeId.parse("test/n81");
    private static final NodeId N90 = NodeId.parse("test/n90");
    private static final NodeId N61 = NodeId.parse("test/n61");
    private static final NodeId N91 = NodeId.parse("test/n91");
    /** Runs 8.0, a version no matrix entry declares. */
    private static final NodeId N80 = NodeId.parse("test/n80");
    private static final NodeId UNDETECTED = NodeId.parse("test/auto");
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(20);
    private static final String SERVLET = "/ibis/servlet/IBISSoapServlet";
    private static final String FIXTURES = "node-tools/v8_1/";
    /** The nonce in the recorded frames; the fake helper answers with the request's nonce. */
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String LOGGER = "com.inubit.ibis.acme";
    private static final LeaseOwner SELF = new LeaseOwner(4711, Optional.empty());
    /** The server's environment, with credentials no launch may get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);

    /** The four node capabilities supported on 8.1 and 9.0; every other version unsupported. */
    private static final CapabilityMatrix MATRIX = matrix();

    /** A tool, its capability, the helper operation it runs and the StartCLI commands it runs. */
    enum Tool {
        LIST_CLUSTER_NODES("list_cluster_nodes", Capability.CLUSTER_NODES_READ, "clusterNodes",
            List.of(), "reading the cluster's node list", "use the Workbench's Cluster Manager"),
        READ_IO_LOG("read_io_log", Capability.IO_LOG_READ, "ioLog", List.of(),
            "reading module input/output logging",
            "use the Workbench's input/output logging view"),
        GET_NODE_CONFIG("get_node_config", Capability.NODE_CONFIG_READ, "nodeConfig", List.of(),
            "reading node configuration values", "use the Workbench's Cluster Manager"),
        SET_TEMPORARY_LOG_LEVEL("set_temporary_log_level", Capability.LOG_LEVEL_WRITE, "ownNode",
            List.of("trace --nodelevel DEBUG --package '" + LOGGER + "'"),
            "changing temporary log levels", "use StartCLI trace -nl/-r by hand"),
        RESET_TEMPORARY_LOG_LEVEL("reset_temporary_log_level", Capability.LOG_LEVEL_WRITE,
            "ownNode", List.of("trace --reset"), "changing temporary log levels",
            "use StartCLI trace -nl/-r by hand");

        final String tool;
        final Capability capability;
        final String operation;
        final List<String> startCli;
        final String label;
        final String workbenchStep;

        Tool(String tool, Capability capability, String operation, List<String> startCli,
            String label, String workbenchStep) {
            this.tool = tool;
            this.capability = capability;
            this.operation = operation;
            this.startCli = startCli;
            this.label = label;
            this.workbenchStep = workbenchStep;
        }

        boolean write() {
            return capability.kind() == Capability.Kind.WRITE;
        }
    }

    @TempDir(factory = GitFreeTempDir.class)
    Path tempDir;

    private Path root;
    private Path tempRoot;
    private ServerLogVersionProfile profile;
    private CliResources resources;
    private final SecretScrubber scrubber = new SecretScrubber();
    private final Map<NodeId, VersionSetting> settings = new HashMap<>(Map.of(
        N81, VersionSetting.AUTO, N90, VersionSetting.AUTO, N61, VersionSetting.V6_1,
        N91, VersionSetting.AUTO, N80, VersionSetting.AUTO, UNDETECTED, VersionSetting.AUTO));
    private final MutableClock clock = new MutableClock(NOW);
    private final NodeVersions versions = new NodeVersions(clock, settings::get,
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(N81, "8.1.17").answer(N90, "9.0.10").answer(N91, "9.1.0").answer(N80, "8.0.5");
    private final Map<NodeId, FakeProcessLauncher> helpers = new HashMap<>();
    private final Map<NodeId, FakeProcessLauncher> startClis = new HashMap<>();
    private final List<ToolError> errors = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        tempRoot = Files.createDirectories(root.resolve("tmp"));
        profile = new ServerLogVersionProfile(root);
        resources = new CliResources("t059", PrivateFiles.forHost());
        for (NodeId node : settings.keySet()) {
            helpers.put(node, helper());
            startClis.put(node, startCli());
        }
    }

    @AfterEach
    void tearDown() {
        resources.close();
        profile.close();
        errors.forEach(error -> TestSecrets.assertNoLeak(error.toString()));
    }

    static Stream<Arguments> eightOneCalls() {
        return calls(VersionSetting.V8_1);
    }

    static Stream<Arguments> nineZeroCalls() {
        return calls(VersionSetting.V9_0);
    }

    /** Every tool on a detected and on a pinned node of {@code line}. */
    private static Stream<Arguments> calls(VersionSetting line) {
        return Stream.of(Tool.values()).flatMap(tool -> Stream.of(VersionSetting.AUTO, line)
            .map(setting -> Arguments.of(tool, setting)));
    }

    // --- US5-1: the client installation, JDK, helper variant and address of the version ---------

    /** US5-1: {@code SOAP_81} on the 8.1 client and JDK at the servlet address; 8.1 StartCLI. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("eightOneCalls")
    void eightOneRunsThe81ClientWithSoap81(Tool tool, VersionSetting line) throws IOException {
        pin(N81, line);

        String answering = call(tool, N81, MATRIX);

        assertThat(answering).isEqualTo("node1");
        assertRanOnItsLine(tool, N81);
    }

    /** US5-1: {@code REST_90} on the 9.0 client and JDK at the base URL; 9.0 StartCLI. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nineZeroCalls")
    void nineZeroRunsThe90ClientWithRest90(Tool tool, VersionSetting line) throws IOException {
        pin(N90, line);

        String answering = call(tool, N90, MATRIX);

        assertThat(answering).isEqualTo("node1");
        assertRanOnItsLine(tool, N90);
    }

    // --- US5-2: other versions and an undetectable version ------------------------------------

    static Stream<Arguments> otherVersions() {
        List<Arguments> cases = new ArrayList<>();
        for (Tool tool : Tool.values()) {
            cases.add(Arguments.of(tool, N61, tool.tool + ": INUBIT 6.1 (versionLine V6_1) on"
                + " test/n61 does not support " + tool.label + "; supported: 8.1, 9.0; "
                + tool.workbenchStep + "; nothing was sent"));
            cases.add(Arguments.of(tool, N91, tool.tool + ": INUBIT 9.1.0 on test/n91 does not"
                + " support " + tool.label + " yet; supported: 8.1, 9.0; nothing was sent"));
            cases.add(Arguments.of(tool, N80, tool.tool + ": INUBIT 8.0.5 on test/n80 does not"
                + " support " + tool.label + " yet; supported: 8.1, 9.0; nothing was sent"));
        }
        return cases.stream();
    }

    /**
     * US5-2 with the shipped matrix: a pinned 6.1 node, a detected 9.1 node and a detected
     * version no entry declares are refused naming the supported lines.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("otherVersions")
    void anotherVersionIsUnsupportedNamingTheSupportedLinesWithNothingSent(Tool tool,
        NodeId node, String message) throws IOException {
        ToolError error = errorOf(() -> call(tool, node, CapabilityMatrix.production()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(node);
        assertThat(error.message()).isEqualTo(message);
        assertNothingSent();
    }

    static Stream<Arguments> readDetectionFailures() {
        return Stream.of(Tool.values()).filter(tool -> !tool.write())
            .flatMap(tool -> Stream.of(ErrorCode.UNREACHABLE, ErrorCode.TLS_ERROR,
                ErrorCode.AUTH_FAILED, ErrorCode.TIMEOUT).map(code -> Arguments.of(tool, code)));
    }

    /** US5-2, a read: the detection's own error ({@code admitWithDetection}), nothing sent. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("readDetectionFailures")
    void anUndetectableVersionFailsAReadWithTheDetectionsCodeWithNothingSent(Tool tool,
        ErrorCode code) throws IOException {
        detection.fail(UNDETECTED, code);

        ToolError error = errorOf(() -> call(tool, UNDETECTED, MATRIX));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isEqualTo("detection failed");
        assertThat(detection.requests()).containsExactly(UNDETECTED);
        assertNothingSent();
    }

    static Stream<Arguments> writeDetectionFailures() {
        return Stream.of(Tool.values()).filter(Tool::write).flatMap(tool -> Stream.of(
            Arguments.of(tool, ErrorCode.UNREACHABLE, ErrorCode.UNSUPPORTED_VERSION,
                unknownVersionRefusal(tool, ErrorCode.UNREACHABLE)),
            Arguments.of(tool, ErrorCode.AUTH_FAILED, ErrorCode.UNSUPPORTED_VERSION,
                unknownVersionRefusal(tool, ErrorCode.AUTH_FAILED)),
            Arguments.of(tool, ErrorCode.TIMEOUT, ErrorCode.UNSUPPORTED_VERSION,
                unknownVersionRefusal(tool, ErrorCode.TIMEOUT)),
            Arguments.of(tool, ErrorCode.TLS_ERROR, ErrorCode.TLS_ERROR, "detection failed")));
    }

    /** The gate's refusal of a log-level change on {@link #UNDETECTED} after a failed detection. */
    private static String unknownVersionRefusal(Tool tool, ErrorCode detected) {
        return tool.tool + ": Changing temporary log levels refused: the INUBIT version of"
            + " test/auto is unknown (" + detected + " at " + NOW + "); nothing was sent";
    }

    /**
     * US5-2, a log-level change: the write guard judges an undetectable version as unknown and
     * refuses it with the gate's text naming the failed detection (research D-4, contracts
     * "set_temporary_log_level" errors: gate texts); a {@code TLS_ERROR} of the detection passes
     * through. Nothing sent.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("writeDetectionFailures")
    void anUndetectableVersionRefusesALogLevelChangeWithNothingSent(Tool tool,
        ErrorCode detected, ErrorCode expected, String message) throws IOException {
        detection.fail(UNDETECTED, detected);

        ToolError error = errorOf(() -> call(tool, UNDETECTED, MATRIX));

        assertThat(error.code()).isEqualTo(expected);
        assertThat(error.node()).contains(UNDETECTED);
        assertThat(error.message()).isEqualTo(message);
        assertThat(detection.requests()).containsExactly(UNDETECTED);
        assertNothingSent();
    }

    // --- US5-3: the shipped matrix ------------------------------------------------------------

    static Stream<Arguments> productionPairs() {
        return Stream.of(Tool.values()).flatMap(tool -> Stream.of(
            Arguments.of(tool, N81, "8.1", "8.1.17"), Arguments.of(tool, N90, "9.0", "9.0.10")));
    }

    /**
     * US5-3 with {@link CapabilityMatrix#production()}: every (version, tool) whose capability is
     * not {@code SUPPORTED} there is refused "not yet verified" with nothing sent; one that a
     * recording has flipped runs on the client of its line.
     */
    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("productionPairs")
    void theShippedMatrixRefusesEveryUnflippedVersionAsNotYetVerified(Tool tool, NodeId node,
        String line, String version) throws IOException {
        CapabilityMatrix production = CapabilityMatrix.production();
        if (production.state(InubitVersion.parse(version), tool.capability)
            == SupportState.SUPPORTED) {
            assertThat(call(tool, node, production)).isEqualTo("node1");
            assertRanOnItsLine(tool, node);
            return;
        }

        ToolError error = errorOf(() -> call(tool, node, production));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(node);
        assertThat(error.message()).isEqualTo(tool.tool + ": INUBIT " + version + " on " + node
            + ": " + tool.label + " is not yet verified for INUBIT " + line
            + "; nothing was sent");
        assertNothingSent();
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Sets the {@code versionLine} of {@code node}. */
    private void pin(NodeId node, VersionSetting line) {
        settings.put(node, line);
    }

    /**
     * Calls {@code tool} on {@code node} through its real service over {@code matrix}, with every
     * opt-in set and write access with confirmation {@code CLIENT}.
     *
     * @return the INUBIT node that answered
     */
    private String call(Tool tool, NodeId node, CapabilityMatrix matrix) {
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions, detection, matrix);
        TargetResolver targets = new TargetResolver(List.copyOf(settings.keySet()));
        Gateways gateways = new Gateways(this);
        return switch (tool) {
            case LIST_CLUSTER_NODES -> new ClusterNodeService(targets, server -> true, gate,
                gateways, server -> CLI_TIMEOUT, System::nanoTime)
                .list(new ClusterNodesRequest(node.value(), Optional.empty()))
                .cluster().answeringNode();
            case READ_IO_LOG -> new IoLogService(targets, server -> true, gate, gateways,
                server -> CLI_TIMEOUT, server -> CLI_TIMEOUT, scrubber::scrub, System::nanoTime)
                .read(new IoLogRequest(node.value(), "Orders-Inbound", "4711", Optional.empty(),
                    Optional.empty(), OptionalInt.empty()))
                .answeringNode();
            case GET_NODE_CONFIG -> new NodeConfigService(targets, server -> true, gate,
                gateways, server -> CLI_TIMEOUT, System::nanoTime)
                .read(new NodeConfigRequest(node.value(), List.of()))
                .answeringNode();
            case SET_TEMPORARY_LOG_LEVEL -> executed(levels(targets, gate, gateways)
                .set(new SetLevelRequest(node.value(), List.of(LOGGER), "DEBUG",
                    OptionalInt.empty(), Optional.empty(), Optional.empty())));
            case RESET_TEMPORARY_LOG_LEVEL -> executed(levels(targets, gate, gateways)
                .reset(new ResetLevelRequest(Optional.of(node.value()), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty())));
        };
    }

    /** The real log-level service: the lease store and node locks below the temporary directory. */
    private LogLevelService levels(TargetResolver targets, VersionGate gate, Gateways gateways) {
        WriteGuard guard = new WriteGuard(targets, server -> new WritePolicy(server, false, true,
            false, WritePolicy.Confirmation.CLIENT, Duration.ofMinutes(5),
            Optional.of(QueueEntryVersionProfile.USER)), gateways, gate);
        Path directory = root.resolve("log-levels");
        ProcessLiveness liveness = new ProcessLiveness() {
            @Override
            public LeaseOwner self() {
                return SELF;
            }

            @Override
            public boolean alive(LeaseOwner owner) {
                return owner.equals(SELF);
            }
        };
        return new LogLevelService(guard, server -> true, gateways,
            new WriteChallengeRegistry(clock), new LeaseStore(directory, PrivateFiles.forHost(),
                clock), new NodeLogLevelLocks(directory, PrivateFiles.forHost()), liveness,
            new LogLevelAudit(record -> { }, clock, "acme", UUID::randomUUID),
            server -> CLI_TIMEOUT, clock, System::nanoTime);
    }

    private static String executed(LogLevelResult result) {
        assertThat(result).isInstanceOf(LogLevelResult.Outcome.class);
        LogLevelResult.Outcome outcome = (LogLevelResult.Outcome) result;
        assertThat(outcome.outcome()).isEqualTo(LogLevelResult.Status.EXECUTED);
        return outcome.inubitNode();
    }

    /**
     * The node's four ports as {@code V81PortSet} builds them: one {@link NodeHelperRunner} over
     * the node's helper launcher, StartCLI chosen by {@link CliRunner} for the node's effective
     * version, as in production.
     */
    private NodePorts ports(NodeId node) {
        EffectiveNodeConfig server = profile.node(node.value(), settings.get(node)).build();
        NodeCredentials credentials = new NodeCredentials(node,
            Optional.of(new SourcedValue<>(QueueEntryVersionProfile.USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        CredentialGuard guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(startClis.get(node), PARENT, resources,
            versions::effectiveVersion);
        NodeHelperRunner helper = new NodeHelperRunner(server, () -> credentials, guard, runner,
            TestCliRunners.helperHost(PARENT, resources, tempRoot),
            new ProcessRun(helpers.get(node), resources),
            new CliConcurrencyLimiter(CapabilityMatrix.production()));
        return new NodePorts(
            new CliClusterNodeAdapter(helper, scrubber, server.secretLabels()),
            new CliIoLogAdapter(helper, scrubber, server.secretLabels()),
            new CliNodeConfigAdapter(helper, scrubber, server.secretLabels()),
            new CliLogLevelAdapter(helper, server, credentials, guard, runner,
                new CliOutputClassifier(scrubber, server.secretLabels(), runner.windows()),
                scrubber));
    }

    private ToolError errorOf(Runnable call) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(thrown).as("the call fails").isNotNull();
        errors.add(thrown.error());
        return thrown.error();
    }

    /**
     * {@link #assertRanOn} for {@link #N81} ({@code SOAP_81} on the 8.1 client and JDK 17 at the
     * servlet address) or {@link #N90} ({@code REST_90} on the 9.0 client and JDK 21 at the base
     * URL).
     */
    private void assertRanOnItsLine(Tool tool, NodeId node) throws IOException {
        if (node.equals(N81)) {
            assertRanOn(tool, N81, profile.client81(), profile.jdk17(), profile.client90(),
                profile.jdk21(), "SOAP_81", "http://n81.example.test:8000" + SERVLET);
        } else {
            assertRanOn(tool, N90, profile.client90(), profile.jdk21(), profile.client81(),
                profile.jdk17(), "REST_90", "http://n90.example.test:8000");
        }
    }

    /**
     * One helper run of {@code tool}'s operation with {@code variant} at {@code url}, and the
     * tool's StartCLI commands, all on {@code home} with {@code jdk} and neither the other client
     * nor its JDK; nothing on any other node; no private directory left.
     */
    private void assertRanOn(Tool tool, NodeId node, Path home, Path jdk, Path otherHome,
        Path otherJdk, String variant, String url) throws IOException {
        FakeProcessLauncher helper = helpers.get(node);
        assertThat(helper.launchCount()).as("helper runs").isOne();
        LaunchSpec spec = helper.last().spec();
        assertThat(spec.command().get(0)).isEqualTo(java(jdk));
        assertThat(classPath(spec)).contains(clientClassPath(home))
            .doesNotContain(otherHome.toString());
        assertThat(spec.workingDirectory()).isEqualTo(home);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", jdk.toString());
        assertThat(String.join(" ", spec.command()) + spec.environment())
            .doesNotContain(otherJdk.toString());
        QueueEntryVersionProfile.assertLaunchedWithoutSecrets(spec);
        assertThat(request(helper.last().stdinBytes()))
            .containsEntry("op", tool.operation)
            .containsEntry("variant", variant)
            .containsEntry("url", url);

        FakeProcessLauncher startCli = startClis.get(node);
        assertThat(startCli.processes()).as("StartCLI runs")
            .map(process -> ScriptedProcessLauncher.execCommand(process.spec()))
            .containsExactlyElementsOf(tool.startCli);
        assertThat(startCli.processes()).allSatisfy(process -> {
            LaunchSpec run = process.spec();
            assertThat(run.workingDirectory()).isEqualTo(home);
            assertThat(run.environment()).containsEntry("JAVA_HOME", jdk.toString());
            assertThat(String.join(" ", run.command()) + run.environment())
                .contains(home.toString())
                .doesNotContain(otherHome.toString(), otherJdk.toString());
            QueueEntryVersionProfile.assertLaunchedWithoutSecrets(run);
        });

        settings.keySet().stream().filter(other -> !other.equals(node)).forEach(other -> {
            assertThat(helpers.get(other).launchCount()).as("helper runs on " + other).isZero();
            assertThat(startClis.get(other).launchCount()).as("StartCLI runs on " + other)
                .isZero();
        });
        assertTempRootEmpty();
    }

    /** No helper run and no StartCLI run on any node, no private directory left. */
    private void assertNothingSent() throws IOException {
        settings.keySet().forEach(node -> {
            assertThat(helpers.get(node).launchCount()).as("helper runs on " + node).isZero();
            assertThat(startClis.get(node).launchCount()).as("StartCLI runs on " + node)
                .isZero();
        });
        assertTempRootEmpty();
    }

    private void assertTempRootEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("private directory deleted").isEmpty();
        }
    }

    /**
     * The helper replaying the 8.1 fixture of the requested operation in the requested
     * {@code variant}: {@code cluster-nodes/single}, {@code io-log/listing} (its listing written
     * to {@code listOut}), {@code node-config/all}; the own-node lookup answers {@code node1}.
     */
    private static FakeProcessLauncher helper() {
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            Map<String, String> fields = request(stdin);
            String frames = switch (fields.get("op")) {
                case "clusterNodes" -> text("cluster-nodes/single.frames");
                case "ioLog" -> {
                    write(Path.of(fields.get("listOut")), text("io-log/listing.list"));
                    yield text("io-log/listing.frames");
                }
                case "nodeConfig" -> text("node-config/all.frames");
                case "ownNode" -> Stream.of("start SOAP_81", "login ok", "node ok node1", "end")
                    .map(frame -> "#QE1:" + FIXTURE_NONCE + " " + frame + "\n")
                    .reduce("", String::concat);
                default -> throw new AssertionError("unexpected operation " + fields.get("op"));
            };
            return new Answer(frames.replace(FIXTURE_NONCE, fields.get("nonce"))
                .replace(" start SOAP_81", " start " + fields.get("variant")), 0);
        });
    }

    /** StartCLI confirming a log-level change ({@code log-level/set_ok}). */
    private static FakeProcessLauncher startCli() {
        return FakeProcessLauncher.of(text("log-level/set_ok.stdout"),
            text("log-level/set_ok.stderr"),
            Integer.parseInt(text("log-level/set_ok.exit").strip()));
    }

    private static String text(String fixture) {
        return new String(FakeProcessLauncher.resource(FIXTURES + fixture),
            StandardCharsets.UTF_8);
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CapabilityMatrix matrix() {
        Map<Capability, SupportState> nodeTools = new EnumMap<>(Capability.class);
        for (Tool tool : Tool.values()) {
            nodeTools.put(tool.capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), nodeTools),
            new CapabilityMatrix.Entry(new VersionRange(9, 0, 9, 0), nodeTools)),
            Map.of());
    }

    /** The four node-tool ports of one node. */
    private record NodePorts(ClusterNodePort clusterNodes, IoLogPort ioLog,
        NodeConfigPort nodeConfig, LogLevelPort logLevel) {
    }

    /**
     * The node-tool ports of every node, built per call; StartCLI's availability is checked by the
     * log-level adapter itself. Every other port is an assertion error.
     */
    private record Gateways(NodeToolsVersionSelectionTest test) implements GatewayFactory {

        @Override
        public ClusterNodePort clusterNodes(NodeId server) {
            return test.ports(server).clusterNodes();
        }

        @Override
        public IoLogPort ioLog(NodeId server) {
            return test.ports(server).ioLog();
        }

        @Override
        public NodeConfigPort nodeConfig(NodeId server) {
            return test.ports(server).nodeConfig();
        }

        @Override
        public LogLevelPort logLevel(NodeId server) {
            return test.ports(server).logLevel();
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            return new ProcessControlPort() {
                @Override
                public void checkAvailable() {
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
}
