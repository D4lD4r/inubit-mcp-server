package de.dadecker.inubit.mcp.adapter.cli.log;

import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.classPath;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.clientClassPath;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.java;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile;
import de.dadecker.inubit.mcp.application.ExcerptPermit;
import de.dadecker.inubit.mcp.application.LogFileWriter;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.ServerLogAudit;
import de.dadecker.inubit.mcp.application.ServerLogRequest;
import de.dadecker.inubit.mcp.application.ServerLogService;
import de.dadecker.inubit.mcp.application.TargetFolderRules;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ServerLogResult;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * US3 end to end (feature 021, FR-002, US3-1…US3-4, research D-6, T047):
 * {@code download_server_log} through the real {@link ServerLogService}, the real
 * {@link de.dadecker.inubit.mcp.application.VersionGate} (detection scripted per node) and a real
 * {@link CliServerLogAdapter} per node on one profile ({@link ServerLogVersionProfile}: a client
 * installation and JDK per version). StartCLI and the INUBIT client helper are a
 * {@link FakeProcessLauncher} per node that records the launched JDK, client and request.
 *
 * <p>The version picks the {@code defaults.cliClients} entry and its JDK for both logs, and for
 * the web service log the helper variant and the address. 6.1, 9.1 and an undetectable version
 * are refused before any launch, as is a 9.0 node without a 9.0 client; an 8.1 client chosen for
 * a 9.0 node fails when the helper misses its 9.0 API. Test matrix declaration {@link #MATRIX}
 * admits both logs on 8.1 and 9.0; {@link CapabilityMatrix#production()} admits the 9.0 trace log
 * since its recording (T051) and refuses the 9.0 web service log until its recording (T052
 * updates that test). No server is contacted.
 */
@Timeout(60)
class ServerLogVersionSelectionTest {

    private static final NodeId N81 = NodeId.parse("test/n81");
    private static final NodeId N90 = NodeId.parse("test/n90");
    private static final NodeId N61 = NodeId.parse("test/n61");
    private static final NodeId N91 = NodeId.parse("test/n91");
    private static final NodeId UNDETECTED = NodeId.parse("test/auto");
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000047");
    private static final String SERVLET = "/ibis/servlet/IBISSoapServlet";

    private static final Map<Capability, SupportState> BOTH_LOGS = Map.of(
        Capability.TRACE_LOG_READ, SupportState.SUPPORTED,
        Capability.WEBSERVICE_LOG_READ, SupportState.SUPPORTED);
    /** Both server logs supported on 8.1 and 9.0; every other version unsupported. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), BOTH_LOGS),
        new CapabilityMatrix.Entry(new VersionRange(9, 0, 9, 0), BOTH_LOGS)),
        Map.of());

    @TempDir(factory = GitFreeTempDir.class)
    Path tempDir;

    private Path root;
    private Path folder;
    private ServerLogVersionProfile profile;
    private final Map<NodeId, VersionSetting> settings = new HashMap<>(Map.of(
        N81, VersionSetting.AUTO, N90, VersionSetting.AUTO, N61, VersionSetting.V6_1,
        N91, VersionSetting.AUTO, UNDETECTED, VersionSetting.AUTO));
    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW), settings::get,
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(N81, "8.1.17").answer(N90, "9.0.10").answer(N91, "9.1.0");
    private final Map<NodeId, TestNodeConfig> nodes = new HashMap<>();
    private final Map<NodeId, FakeProcessLauncher> launchers = new HashMap<>();
    private final List<ToolError> errors = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        folder = Files.createDirectories(root.resolve("logs"));
        profile = new ServerLogVersionProfile(root);
        settings.forEach((node, line) -> nodes.put(node, profile.node(node.value(), line)));
    }

    @AfterEach
    void tearDown() {
        profile.close();
        errors.forEach(error -> TestSecrets.assertNoLeak(error.toString()));
    }

    static Stream<VersionSetting> autoAndPinned81() {
        return Stream.of(VersionSetting.AUTO, VersionSetting.V8_1);
    }

    static Stream<VersionSetting> autoAndPinned90() {
        return Stream.of(VersionSetting.AUTO, VersionSetting.V9_0);
    }

    // --- US3-1, US3-2: the client, JDK, variant and address of the version --------------------

    /** US3-1: the {@code defaults.cliClients."8.1"} StartCLI client and its JDK. */
    @ParameterizedTest
    @MethodSource("autoAndPinned81")
    void eightOneTraceRunsThe81StartCliClientWithItsJdk(VersionSetting line) {
        pin(N81, line);
        FakeProcessLauncher launcher = launch(N81, ServerLogVersionProfile.startCli());

        ServerLogResult result = service(MATRIX).download(call(N81, "trace"));

        assertThat(result.node()).isEqualTo(N81);
        assertThat(result.log()).isEqualTo(ServerLogKind.TRACE);
        assertThat(launcher.launchCount()).isOne();
        assertStartCliLaunchedOn(launcher.last().spec(), profile.client81(), profile.jdk17(),
            profile.client90(), profile.jdk21());
        assertCleanedUp();
    }

    /**
     * US3-2: the {@code defaults.cliClients."9.0"} StartCLI client and its JDK, with the shipped
     * matrix since the 9.0 trace recording (T051).
     */
    @ParameterizedTest
    @MethodSource("autoAndPinned90")
    void nineZeroTraceRunsThe90StartCliClientWithItsJdk(VersionSetting line) {
        pin(N90, line);
        FakeProcessLauncher launcher = launch(N90, ServerLogVersionProfile.startCli());

        ServerLogResult result = service(CapabilityMatrix.production())
            .download(call(N90, "trace"));

        assertThat(result.node()).isEqualTo(N90);
        assertThat(launcher.launchCount()).isOne();
        assertStartCliLaunchedOn(launcher.last().spec(), profile.client90(), profile.jdk21(),
            profile.client81(), profile.jdk17());
        assertCleanedUp();
    }

    /** US3-1: {@code SOAP_81} on the 8.1 client and JDK, at the servlet address. */
    @ParameterizedTest
    @MethodSource("autoAndPinned81")
    void eightOneWebServiceUsesSoapWithThe81ClientItsJdkAndTheServletAddress(
        VersionSetting line) {
        pin(N81, line);
        FakeProcessLauncher launcher = launch(N81, ServerLogVersionProfile.helper());

        ServerLogResult result = service(MATRIX).download(call(N81, "webservice"));

        assertThat(result.node()).isEqualTo(N81);
        assertThat(result.log()).isEqualTo(ServerLogKind.WEBSERVICE);
        assertThat(launcher.launchCount()).isOne();
        assertHelperLaunchedOn(launcher.last().spec(), profile.client81(), profile.jdk17(),
            profile.client90(), profile.jdk21());
        assertThat(request(launcher.last().stdinBytes()))
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", "http://n81.example.test:8000" + SERVLET);
        assertCleanedUp();
    }

    /** US3-2: {@code REST_90} on the 9.0 client and JDK, at the base URL. */
    @ParameterizedTest
    @MethodSource("autoAndPinned90")
    void nineZeroWebServiceUsesRestWithThe90ClientItsJdkAndTheBaseUrl(VersionSetting line) {
        pin(N90, line);
        FakeProcessLauncher launcher = launch(N90, ServerLogVersionProfile.helper());

        ServerLogResult result = service(MATRIX).download(call(N90, "webservice"));

        assertThat(result.node()).isEqualTo(N90);
        assertThat(launcher.launchCount()).isOne();
        assertHelperLaunchedOn(launcher.last().spec(), profile.client90(), profile.jdk21(),
            profile.client81(), profile.jdk17());
        assertThat(request(launcher.last().stdinBytes()))
            .containsEntry("variant", "REST_90")
            .containsEntry("url", "http://n90.example.test:8000");
        assertCleanedUp();
    }

    // --- US3-3: other versions -----------------------------------------------------------------

    static Stream<Arguments> otherVersions() {
        List<Arguments> cases = new ArrayList<>();
        for (String log : List.of("trace", "webservice")) {
            String label = "downloading the " + (log.equals("trace") ? "trace" : "web service")
                + " log";
            cases.add(Arguments.of(log, N61, "download_server_log: INUBIT 6.1 (versionLine V6_1)"
                + " on test/n61 does not support " + label + "; supported: 8.1, 9.0; download"
                + " the log in the Workbench (Administration, Server Log); nothing was sent"));
            cases.add(Arguments.of(log, N91, "download_server_log: INUBIT 9.1.0 on test/n91"
                + " does not support " + label + " yet; supported: 8.1, 9.0; nothing was sent"));
        }
        return cases.stream();
    }

    /** US3-3 with the shipped matrix: a pinned 6.1 node and a detected 9.1 node. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("otherVersions")
    void anotherVersionIsUnsupportedNamingItAndTheSupportedLinesWithoutLaunch(String log,
        NodeId node, String message) {
        ToolError error = errorOf(() -> service(CapabilityMatrix.production())
            .download(call(node, log)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(node);
        assertThat(error.message()).isEqualTo(message);
        assertNothingLaunched();
    }

    static Stream<Arguments> detectionFailures() {
        return Stream.of("trace", "webservice").flatMap(log -> Stream.of(ErrorCode.UNREACHABLE,
            ErrorCode.TLS_ERROR, ErrorCode.AUTH_FAILED, ErrorCode.TIMEOUT)
            .map(code -> Arguments.of(log, code)));
    }

    /** US3-3: the detection's own error, nothing launched. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("detectionFailures")
    void anUndetectableAutoNodeFailsWithTheDetectionsCodeWithoutLaunch(String log,
        ErrorCode code) {
        detection.fail(UNDETECTED, code);

        ToolError error = errorOf(() -> service(MATRIX).download(call(UNDETECTED, log)));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isEqualTo("detection failed");
        assertThat(detection.requests()).containsExactly(UNDETECTED);
        assertNothingLaunched();
    }

    // --- the client installation of 9.0 --------------------------------------------------------

    /** No client installation for 9.0, although 8.1 has one. */
    @ParameterizedTest
    @ValueSource(strings = {"trace", "webservice"})
    void withoutA90ClientANineZeroNodeIsUnavailableNamingTheEntryWithoutLaunch(String log) {
        nodes.put(N90, profile.withoutClients(N90.value(), VersionSetting.AUTO)
            .cliClient("8.1", profile.client81(), Optional.of(profile.jdk17())));

        ToolError error = errorOf(() -> service(MATRIX).download(call(N90, log)));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(N90);
        assertThat(error.message()).contains("test/n90 (INUBIT 9.0.10)");
        assertThat(error.likelyCause()).contains("defaults.cliClients.\"9.0\"");
        assertThat(error.nextStep()).contains("defaults.cliClients.\"9.0\"");
        assertNothingLaunched();
    }

    /** {@code defaults.cliClients."9.0"} names the 8.1 installation: the helper misses its API. */
    @Test
    void anEightOneClientChosenForANineZeroNodeIsUnavailable() {
        nodes.put(N90, profile.withoutClients(N90.value(), VersionSetting.AUTO)
            .cliClient("9.0", profile.client81(), Optional.of(profile.jdk17())));
        FakeProcessLauncher launcher = launch(N90,
            ServerLogVersionProfile.helperLackingClusterClient());

        ToolError error = errorOf(() -> service(MATRIX).download(call(N90, "webservice")));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(N90);
        assertThat(error.message()).startsWith("The INUBIT client installation "
            + profile.client81() + " (chosen for INUBIT 9.0) lacks ").contains("ClusterClient");
        assertThat(error.nextStep()).isEqualTo("Check that defaults.cliClients.\"9.0\" points to"
            + " an INUBIT 9.0 client");
        assertThat(launcher.launchCount()).isOne();
        assertThat(request(launcher.last().stdinBytes())).containsEntry("variant", "REST_90");
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(profile.client81());
        assertCleanedUp();
    }

    // --- the shipped declaration -----------------------------------------------------------------

    /** FR-017: until its recording (T052) the 9.0 web service log is refused. */
    @Test
    void theProductionMatrixRefusesTheNineZeroWebServiceLogAsNotYetVerifiedWithoutLaunch() {
        ToolError error = errorOf(() -> service(CapabilityMatrix.production())
            .download(call(N90, "webservice")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("download_server_log: INUBIT 9.0.10 on test/n90:"
            + " downloading the web service log is not yet verified for INUBIT 9.0; nothing was"
            + " sent");
        assertThat(detection.requests()).containsExactly(N90);
        assertNothingLaunched();
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Sets the {@code versionLine} of {@code node}. */
    private void pin(NodeId node, VersionSetting line) {
        settings.put(node, line);
        nodes.put(node, profile.node(node.value(), line));
    }

    private FakeProcessLauncher launch(NodeId node, FakeProcessLauncher launcher) {
        launchers.put(node, launcher);
        return launcher;
    }

    /**
     * The service over every node, the group opted in to the web service log; a node the test
     * gave no launcher gets StartCLI, so that any launch on it is counted.
     */
    private ServerLogService service(CapabilityMatrix matrix) {
        settings.keySet().forEach(node -> launchers.computeIfAbsent(node,
            unused -> ServerLogVersionProfile.startCli()));
        Function<NodeId, ServerLogPort> ports = node -> profile.port(
            nodes.get(node).cliExportTimeout(EXPORT_TIMEOUT).build(), launchers.get(node),
            versions::effectiveVersion);
        AuditPort audit = record -> { };
        return new ServerLogService(new TargetResolver(List.copyOf(settings.keySet())),
            node -> true, node -> Optional.of(QueueEntryVersionProfile.USER),
            new TargetFolderRules(directory("home"), directory("workspace"),
                root.resolve("audit"), List.of(directory("config")), Map.of(), letter -> false,
                false),
            FakeVersionDetection.gateWithoutClients(versions, detection, matrix),
            new Gateways(ports), node -> EXPORT_TIMEOUT,
            FakeConfiguredSecrets.held(TestSecrets.CONFIGURED_PASSWORD), Duration.ofSeconds(30),
            new ExcerptPermit(), new LogFileWriter(),
            new ServerLogAudit.Dependencies(audit, new MutableClock(NOW), "default", () -> ID),
            new MutableClock(NOW), System::nanoTime);
    }

    private Path directory(String name) {
        try {
            return Files.createDirectories(root.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ServerLogRequest call(NodeId node, String log) {
        return new ServerLogRequest(node.value(), log, folder.toString(), OptionalInt.empty(),
            Optional.empty(), OptionalInt.empty(), Optional.empty());
    }

    private ToolError errorOf(Runnable call) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(thrown).as("the call fails").isNotNull();
        errors.add(thrown.error());
        return thrown.error();
    }

    /** No launch on any node, no file in the folder, no private directory left. */
    private void assertNothingLaunched() {
        launchers.forEach((node, launcher) ->
            assertThat(launcher.launchCount()).as("launches on " + node).isZero());
        assertThat(folder).as("no file in the target folder").isEmptyDirectory();
        assertCleanedUp();
    }

    private static void assertCleanedUp() {
        assertThat(ServerLogVersionProfile.leftovers()).as("private directory deleted").isEmpty();
    }

    /** StartCLI of {@code home} with {@code jdk}; neither the other client nor its JDK. */
    private static void assertStartCliLaunchedOn(LaunchSpec spec, Path home, Path jdk,
        Path otherHome, Path otherJdk) {
        assertThat(spec.workingDirectory()).isEqualTo(home);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", jdk.toString());
        assertThat(String.join(" ", spec.command()) + spec.environment())
            .contains(home.toString()).doesNotContain(otherHome.toString(), otherJdk.toString());
        QueueEntryVersionProfile.assertLaunchedWithoutSecrets(spec);
    }

    /** The helper on {@code home}'s class path with {@code jdk}'s launcher. */
    private static void assertHelperLaunchedOn(LaunchSpec spec, Path home, Path jdk,
        Path otherHome, Path otherJdk) {
        assertThat(spec.command().get(0)).isEqualTo(java(jdk));
        assertThat(classPath(spec)).contains(clientClassPath(home))
            .doesNotContain(otherHome.toString());
        assertThat(spec.workingDirectory()).isEqualTo(home);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", jdk.toString());
        assertThat(String.join(" ", spec.command()) + spec.environment())
            .doesNotContain(otherJdk.toString());
        QueueEntryVersionProfile.assertLaunchedWithoutSecrets(spec);
    }

    /** Only the server-log ports; every other port is an assertion error. */
    private record Gateways(Function<NodeId, ServerLogPort> ports) implements GatewayFactory {

        @Override
        public ServerLogPort serverLogs(NodeId server) {
            return ports.apply(server);
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
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            throw new AssertionError("not expected");
        }
    }
}
