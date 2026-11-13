package de.dadecker.inubit.mcp.adapter.cli.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.nodetools.ClusterNodeService;
import de.dadecker.inubit.mcp.application.nodetools.ClusterNodesRequest;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNode;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodesResult;
import de.dadecker.inubit.mcp.domain.model.node.NodeDetail;
import de.dadecker.inubit.mcp.domain.model.node.NodeStatus;
import de.dadecker.inubit.mcp.domain.port.ClusterNodePort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TraceMatrix;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T024 (feature 024, FR-005, FR-010, FR-011, research D-7, D-27): the INUBIT 8.1 contract of
 * {@code list_cluster_nodes}. Each {@code node-tools/v8_1/cluster-nodes} case runs the real
 * {@link ClusterNodeService} over the real {@link CliClusterNodeAdapter} and
 * {@link NodeHelperRunner}; a {@link FakeProcessLauncher} reads the helper request from stdin and
 * replays the case's {@code .frames}/{@code .exit} with the request's nonce. No process, no
 * network (the TLS edge case talks to a local test server only). Test matrix declaration:
 * {@link TraceMatrix#admittingEightOne()}, so the contract does not depend on the production
 * declaration.
 *
 * <p>{@code ok} is the recording of spike T018 (T025): the answering node {@code ACTIVE} with its
 * version and uptime, every other member {@code OFFLINE} without version and uptime; its names and
 * total are asserted against what its frames say. {@code no_answer} keeps the reconstructed case
 * of a member without a status. Every result,
 * error and log line is checked for leaks; the {@code details} case checks end to end that the
 * credential-like value of the fixture is masked in the service's result.
 */
@Timeout(60)
class NodeToolsV81ContractTest {

    /** The cases; exactly the {@code v8_1/cluster-nodes/*.frames} on disk. */
    static final List<String> CASES = List.of("ok", "no_answer", "single", "details",
        "details_truncated", "details_unknown", "details_error", "unknown_status", "api_missing",
        "garbage");

    private static final CapabilityMatrix MATRIX = TraceMatrix.admittingEightOne();
    private static final Path DIRECTORY =
        Path.of("src/test/resources/fixtures/node-tools/v8_1/cluster-nodes");
    private static final String FIXTURES = "node-tools/v8_1/cluster-nodes/";
    private static final String FRAMES = ".frames";
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String FRAME_PREFIX = "#QE1:" + FIXTURE_NONCE + " ";
    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final String USER = "jdoe";
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(20);
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    /** The credential-like value in the {@code JvmArguments} pair of the {@code details} case. */
    private static final String DETAILS_SECRET = "example-secret";
    /** The server's environment, with credentials the helper must never get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);

    @TempDir
    Path root;

    private Path tempRoot;
    private Path home;
    private Path jdk;
    private final CliResources resources = new CliResources("t024", PrivateFiles.forHost());
    private final SecretScrubber scrubber = new SecretScrubber();
    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW),
        node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection =
        new FakeVersionDetection(versions).answer(NODE, "8.1.17");
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private FakeProcessLauncher launcher;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createDirectories(root.resolve("tmp"));
        home = client();
        jdk = jdk();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
        resources.close();
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            TestSecrets.assertNoLeak(message);
            assertThat(message).doesNotContain(DETAILS_SECRET);
        }
    }

    @Test
    void theCasesAreExactlyTheFixtureCasesOnDisk() throws IOException {
        TreeSet<String> onDisk;
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            onDisk = files.map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith(FRAMES))
                .map(name -> name.substring(0, name.length() - FRAMES.length()))
                .collect(Collectors.toCollection(TreeSet::new));
        }

        assertThat(onDisk).containsExactlyInAnyOrderElementsOf(CASES);
    }

    // --- the list (US1-1, US1-2, US1-5) -------------------------------------------------------

    @Test
    void okListsTheRecordedMembersTheAnsweringNodeActiveAndTheOthersOfflineWithoutUptime()
        throws IOException {
        List<String> frames = frames("ok");
        String answering = token(frames, "node ok ");
        List<String[]> members = frames.stream().filter(frame -> frame.startsWith("member "))
            .map(frame -> frame.split(" ")).toList();
        launcher = replaying("ok");

        ClusterNodesResult result = service(http().build(), credentials()).list(request());

        assertThat(result.node()).isEqualTo(NODE);
        ClusterNodes cluster = result.cluster();
        assertThat(cluster.answeringNode()).isEqualTo(answering);
        assertThat(cluster.nodes()).extracting(ClusterNode::name).containsExactlyElementsOf(
            members.stream().map(member -> decode(member[1])).toList());
        assertThat(cluster.total()).isEqualTo(Integer.parseInt(token(frames, "members ok ")))
            .isEqualTo(members.size()).isEqualTo(12);
        assertThat(cluster.truncated()).isFalse();
        assertThat(cluster.details()).isEmpty();
        assertThat(cluster.nodes()).filteredOn(node -> node.name().equals(answering))
            .singleElement().satisfies(node -> {
                assertThat(node.status()).isEqualTo(NodeStatus.ACTIVE);
                assertThat(node.version()).contains("8.1.17");
                assertThat(node.uptime()).isPresent();
            });
        assertThat(cluster.nodes()).filteredOn(node -> !node.name().equals(answering))
            .hasSize(11).allSatisfy(node -> {
                assertThat(node.status()).isEqualTo(NodeStatus.OFFLINE);
                assertThat(node.rawStatus()).isEmpty();
                assertThat(node.version()).isEmpty();
                assertThat(node.uptime()).as("an OFFLINE member's uptime 0 is absent").isEmpty();
            });
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING);
        assertThat(result.hints()).isEmpty();
        assertThat(requestLines()).contains("op=clusterNodes", "details=");
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertNoLeak(result);
        assertTempRootEmpty();
    }

    @Test
    void noAnswerKeepsAMemberWithoutAStatusUnknown() throws IOException {
        launcher = replaying("no_answer");

        ClusterNodesResult result = service(http().build(), credentials()).list(request());

        assertThat(result.cluster().answeringNode()).isEqualTo("node1");
        assertThat(result.cluster().nodes()).extracting(ClusterNode::name)
            .containsExactly("node1", "node2");
        assertThat(result.cluster().nodes().get(0).status()).isEqualTo(NodeStatus.ACTIVE);
        assertThat(result.cluster().nodes().get(1)).isEqualTo(new ClusterNode("node2",
            NodeStatus.UNKNOWN, Optional.empty(), Optional.empty(), Optional.of("zone-a"),
            Optional.empty(), Optional.empty()));
        assertThat(result.cluster().total()).isEqualTo(2);
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING);
        assertNoLeak(result);
        assertTempRootEmpty();
    }

    @Test
    void singleListsTheOneMember() throws IOException {
        launcher = replaying("single");

        ClusterNodesResult result = service(http().build(), credentials()).list(request());

        assertThat(result.cluster().nodes()).extracting(ClusterNode::name)
            .containsExactly("node1");
        assertThat(result.cluster().total()).isEqualTo(1);
        assertThat(result.cluster().answeringNode()).isEqualTo("node1");
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING);
        assertTempRootEmpty();
    }

    @Test
    void anUnknownStatusConstantIsUnknownWithTheRawStatus() throws IOException {
        launcher = replaying("unknown_status");

        ClusterNodesResult result = service(http().build(), credentials()).list(request());

        assertThat(result.cluster().nodes().get(1)).isEqualTo(new ClusterNode("node2",
            NodeStatus.UNKNOWN, Optional.of("STARTING"), Optional.of("8.1.17"),
            Optional.of("zone-a"), Optional.empty(), Optional.of(Duration.ofMinutes(2))));
        assertTempRootEmpty();
    }

    // --- the details (US1-3, US1-6) -----------------------------------------------------------

    @Test
    void detailsAreThePairsInOrderWithTheCredentialMaskedInTheResultAndTheDetailsWarning()
        throws IOException {
        assertThat(fixture("details.frames")).as("the case carries a credential-like value")
            .contains("trustStorePassword%3D" + DETAILS_SECRET);
        launcher = replaying("details");

        ClusterNodesResult result = service(http().build(), credentials())
            .list(request("node1"));

        assertThat(requestLines()).contains("op=clusterNodes", "details=node1");
        assertThat(result.cluster().nodes()).hasSize(2);
        ClusterNodes.Available details =
            (ClusterNodes.Available) result.cluster().details().orElseThrow();
        assertThat(details.node()).isEqualTo("node1");
        assertThat(details.truncated()).isFalse();
        assertThat(details.pairs()).extracting(NodeDetail::key).containsExactly("NodeName",
            "ZoneName", "ClusterBindAddress", "InstallationDirectory", "JavaVersion",
            "Memory.HeapMax", "Memory.HeapUsed", "JvmArguments", "Description");
        assertThat(details.pairs().get(2))
            .isEqualTo(new NodeDetail("ClusterBindAddress", "10.0.0.11:7800", false));
        assertThat(details.pairs().get(8)).isEqualTo(new NodeDetail("Description", "", false));
        NodeDetail jvm = details.pairs().get(7);
        // the 020 rule for a secret-named key=value: the value becomes its placeholder
        assertThat(jvm.value()).isEqualTo("-Xmx4g -Djavax.net.ssl.trustStorePassword="
            + "${secret:data.-Djavax.net.ssl.trustStorePassword}");
        assertThat(jvm.cut()).isFalse();
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING,
            ClusterNodesResult.DETAILS_WARNING);
        assertThat(result.hints()).isEmpty();
        assertNoLeak(result);
        assertTempRootEmpty();
    }

    @Test
    void truncatedDetailsKeepTheHelpersFlagAndAddTheHint() throws IOException {
        launcher = replaying("details_truncated");

        ClusterNodesResult result = service(http().build(), credentials())
            .list(request("node1"));

        ClusterNodes.Available details =
            (ClusterNodes.Available) result.cluster().details().orElseThrow();
        assertThat(details.truncated()).isTrue();
        assertThat(details.pairs()).hasSize(ClusterNodes.MAX_PAIRS);
        assertThat(details.pairs()).filteredOn(NodeDetail::cut).extracting(NodeDetail::key)
            .containsExactly("ClassPath");
        assertThat(result.hints()).containsExactly(ClusterNodesResult.DETAILS_TRUNCATED_HINT);
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING,
            ClusterNodesResult.DETAILS_WARNING);
        assertTempRootEmpty();
    }

    @Test
    void anUnknownDetailsNodeIsInvalidInputNamingTheKnownMembers() throws IOException {
        launcher = replaying("details_unknown");

        ToolError error = errorOf(service(http().build(), credentials()), request("node3"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo(
            "Node node3 is not in the cluster of test/node1; known nodes: node1, node2");
        assertThat(error.nextStep()).isEqualTo("Use a name from the list");
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertTempRootEmpty();
    }

    @Test
    void detailsInubitCannotDeliverAreUnavailableTheListStaysAndNoDetailsWarning()
        throws IOException {
        launcher = replaying("details_error");

        ClusterNodesResult result = service(http().build(), credentials())
            .list(request("node2"));

        assertThat(result.cluster().nodes()).extracting(ClusterNode::name)
            .containsExactly("node1", "node2");
        assertThat(result.cluster().details()).contains(
            new ClusterNodes.Unavailable("node2", "Node node2 did not answer. Read timed out"));
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING);
        assertThat(result.hints()).isEmpty();
        assertTempRootEmpty();
    }

    // --- failing cases --------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "api_missing, CLI_UNAVAILABLE",
        "garbage, UNEXPECTED_RESPONSE"})
    void eachFailingCaseHasItsCodeAndLeavesNothing(String fixtureCase, ErrorCode expected)
        throws IOException {
        launcher = replaying(fixtureCase);

        ToolError error = errorOf(service(http().build(), credentials()), request());

        assertThat(error.code()).isEqualTo(expected);
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertTempRootEmpty();
    }

    // --- edge case "credentials not available, TLS failure, maintenance mode" (spec) -----------

    @Test
    void maintenanceModeComesFromTheDetectionAndNothingIsLaunched() throws IOException {
        detection.fail(NODE, ErrorCode.MAINTENANCE_MODE);
        launcher = replaying("ok");

        ToolError error = errorOf(service(http().build(), credentials()), request());

        assertThat(error.code()).isEqualTo(ErrorCode.MAINTENANCE_MODE);
        assertThat(detection.requests()).containsExactly(NODE);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void missingCredentialsAreAuthFailedAndNothingIsLaunched() throws IOException {
        launcher = replaying("ok");
        NodeCredentials none = new NodeCredentials(NODE, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolError error = errorOf(service(http().build(), none), request());

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).isEqualTo("No INUBIT credentials are configured for"
            + " test/node1, so the INUBIT client helper cannot log in");
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void anUntrustedCertificateIsATlsErrorAndNothingIsLaunched() throws Exception {
        TestCertificates certificates = TestCertificates.get();
        launcher = replaying("ok");
        ToolError error;
        try (TlsTestServer tls = TlsTestServer.start(certificates.rogueKeyStore())) {
            EffectiveNodeConfig server = TestNodeConfig.node().id(NODE.value())
                .baseUrl("https://127.0.0.1:" + tls.port())
                .trustStore(certificates.trustStore()).cliHome(home).cliJavaHome(jdk).build();

            error = errorOf(service(server, credentials()), request());
        }

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * The real service over the real adapter for {@code server}: {@link #MATRIX}, the opt-in set,
     * the detection answering 8.1.17, the helper getting {@code credentials}.
     */
    private ClusterNodeService service(EffectiveNodeConfig server, NodeCredentials credentials) {
        CredentialGuard guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources);
        NodeHelperRunner helper = new NodeHelperRunner(server, () -> credentials, guard, runner,
            TestCliRunners.helperHost(PARENT, resources, tempRoot),
            new ProcessRun(launcher, resources), new CliConcurrencyLimiter(MATRIX));
        ClusterNodePort port = new CliClusterNodeAdapter(helper, scrubber, server.secretLabels());
        return new ClusterNodeService(new TargetResolver(List.of(NODE)), node -> true,
            FakeVersionDetection.gateWithoutClients(versions, detection, MATRIX),
            new Gateways(port), node -> CLI_TIMEOUT, System::nanoTime);
    }

    private TestNodeConfig http() {
        return TestNodeConfig.node().id(NODE.value()).baseUrl("http://inubit.example.test:8000")
            .allowInsecureHttp(true).cliHome(home).cliJavaHome(jdk);
    }

    private NodeCredentials credentials() {
        return new NodeCredentials(NODE, Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
    }

    private static ClusterNodesRequest request() {
        return new ClusterNodesRequest(NODE.value(), Optional.empty());
    }

    private static ClusterNodesRequest request(String nodeName) {
        return new ClusterNodesRequest(NODE.value(), Optional.of(nodeName));
    }

    /** The error of a call; it carries the node and leaks nothing. */
    private static ToolError errorOf(ClusterNodeService service, ClusterNodesRequest request) {
        ToolErrorException thrown =
            catchThrowableOfType(ToolErrorException.class, () -> service.list(request));
        assertThat(thrown).as("the call fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.node()).contains(NODE);
        TestSecrets.assertNoLeak(error.toString());
        return error;
    }

    private static void assertNoLeak(ClusterNodesResult result) {
        TestSecrets.assertNoLeak(result.toString());
        assertThat(result.toString()).doesNotContain(DETAILS_SECRET);
    }

    private void assertTempRootEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("nothing left below the temporary directory").isEmpty();
        }
    }

    /** The lines of the last helper request, as written to its stdin. */
    private List<String> requestLines() {
        return new String(launcher.last().stdinBytes(), StandardCharsets.US_ASCII).lines()
            .toList();
    }

    /** The operation frames of {@code <case>.frames}: each line after the nonce, still encoded. */
    private static List<String> frames(String fixtureCase) {
        return fixture(fixtureCase + FRAMES).lines()
            .filter(line -> line.startsWith(FRAME_PREFIX))
            .map(line -> line.substring(FRAME_PREFIX.length())).toList();
    }

    /** The decoded rest of the one frame starting with {@code start}. */
    private static String token(List<String> frames, String start) {
        List<String> matching = frames.stream().filter(frame -> frame.startsWith(start))
            .toList();
        assertThat(matching).as("one '%s' frame", start).hasSize(1);
        return decode(matching.get(0).substring(start.length()));
    }

    private static String decode(String token) {
        return URLDecoder.decode(token, StandardCharsets.UTF_8);
    }

    private static String fixture(String name) {
        return new String(FakeProcessLauncher.resource(FIXTURES + name), StandardCharsets.UTF_8);
    }

    /** Replays {@code <case>.frames}/{@code .exit} of the cluster-nodes fixtures with the nonce. */
    private static FakeProcessLauncher replaying(String fixtureCase) {
        String frames = fixture(fixtureCase + FRAMES);
        int exit = Integer.parseInt(fixture(fixtureCase + ".exit").strip());
        return FakeProcessLauncher.of("", "", 0).responding(request -> new Answer(
            frames.replace(FIXTURE_NONCE, nonce(request)), exit));
    }

    /** The {@code nonce} of a helper request (hex, so not encoded). */
    private static String nonce(byte[] request) {
        return new String(request, StandardCharsets.US_ASCII).lines()
            .filter(line -> line.startsWith("nonce=")).findFirst().orElseThrow()
            .substring("nonce=".length());
    }

    /** A client home with {@code lib/ibis.jar}, {@code lib/shared/} and {@code conf/}. */
    private Path client() throws IOException {
        Path client = root.resolve("client");
        Files.createDirectories(client.resolve("lib").resolve("shared"));
        Files.createDirectories(client.resolve("conf"));
        Files.createFile(client.resolve("lib").resolve("ibis.jar"));
        return client;
    }

    /** A JDK directory with an executable {@code bin/java} and {@code bin/java.exe}. */
    private Path jdk() throws IOException {
        Path directory = root.resolve("jdk");
        Path bin = Files.createDirectories(directory.resolve("bin"));
        for (String name : List.of("java", "java.exe")) {
            Path java = Files.createFile(bin.resolve(name));
            if (!Platform.windows()) {
                Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
            }
        }
        return directory;
    }

    /** Only the cluster-node port of {@link #NODE}; every other port is an assertion error. */
    private record Gateways(ClusterNodePort port) implements GatewayFactory {

        @Override
        public ClusterNodePort clusterNodes(NodeId server) {
            assertThat(server).isEqualTo(NODE);
            return port;
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
