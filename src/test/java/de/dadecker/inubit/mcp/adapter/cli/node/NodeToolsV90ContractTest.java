package de.dadecker.inubit.mcp.adapter.cli.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.log.ServerLogVersionProfile;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.nodetools.ClusterNodeService;
import de.dadecker.inubit.mcp.application.nodetools.ClusterNodesRequest;
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
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * T062 (feature 024, FR-005, FR-010, FR-011, research D-7, D-27): the INUBIT 9.0 contract of
 * {@code list_cluster_nodes}. Each {@code node-tools/v9_0/cluster-nodes} case runs the real
 * {@link ClusterNodeService} over the real {@link CliClusterNodeAdapter} and
 * {@link NodeHelperRunner} for a node detected as 9.0.10 with both {@code defaults.cliClients}
 * entries ({@link ServerLogVersionProfile}); a {@link FakeProcessLauncher} reads the helper request
 * from stdin and replays the case's {@code .frames}/{@code .exit} with the request's nonce. Every
 * run is the helper's {@code REST_90} variant on the 9.0 client and its JDK at the node's base URL.
 * No process, no network. Test matrix declaration: {@link #MATRIX}, so the contract does not
 * depend on the production declaration.
 *
 * <p>{@code ok} is the recording of spike T060 (repeated on 2026-11-14): the answering node
 * {@code ACTIVE} with its version and uptime, every other member {@code OFFLINE} without version
 * and uptime. {@code no_answer}, {@code details} and {@code garbage} are the reconstructed 8.1
 * cases of the same names in the {@code REST_90} form, {@code api_missing} the 8.1 case unchanged
 * (the API check fails before the client starts). Every started case carries the 9.0 REST
 * client's vendor line on stdout outside the frames, as recorded; it is ignored and reaches no
 * result, error or log line. Every result, error and log line is checked for leaks; {@code details}
 * checks end to end that the credential-like value of the fixture is masked.
 */
@Timeout(60)
class NodeToolsV90ContractTest {

    /** The cases; exactly the {@code v9_0/cluster-nodes/*.frames} on disk. */
    static final List<String> CASES = List.of("ok", "no_answer", "details", "garbage",
        "api_missing");
    /** The cases of a started run, whose stdout carries the REST client's vendor line. */
    private static final List<String> STARTED = List.of("ok", "no_answer", "details", "garbage");

    /** 9.0 supports the cluster's node list; every other version and capability does not. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(9, 0, 9, 0),
            Map.of(Capability.CLUSTER_NODES_READ, SupportState.SUPPORTED))), Map.of());
    private static final Path DIRECTORY =
        Path.of("src/test/resources/fixtures/node-tools/v9_0/cluster-nodes");
    private static final String FIXTURES = "node-tools/v9_0/cluster-nodes/";
    private static final String FRAMES = ".frames";
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String FRAME_PREFIX = "#QE1:" + FIXTURE_NONCE + " ";
    /** A part of the REST client's vendor line in every case (research "Spike findings" T060). */
    private static final String VENDOR_LINE = "MacOSDnsServerAddressStreamProvider";
    private static final NodeId NODE = NodeId.parse("test/n90");
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(20);
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    /** The credential-like value in the {@code JvmArguments} pair of the {@code details} case. */
    private static final String DETAILS_SECRET = "example-secret";
    /** The server's environment, with credentials the helper must never get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);

    @TempDir(factory = GitFreeTempDir.class)
    Path tempDir;

    private Path tempRoot;
    private ServerLogVersionProfile profile;
    private final CliResources resources = new CliResources("t062", PrivateFiles.forHost());
    private final SecretScrubber scrubber = new SecretScrubber();
    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW),
        node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection =
        new FakeVersionDetection(versions).answer(NODE, "9.0.10");
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private FakeProcessLauncher launcher;

    @BeforeEach
    void setUp() throws IOException {
        Path root = tempDir.toRealPath();
        tempRoot = Files.createDirectories(root.resolve("tmp"));
        profile = new ServerLogVersionProfile(root);
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
        profile.close();
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            TestSecrets.assertNoLeak(message);
            assertThat(message).doesNotContain(DETAILS_SECRET, VENDOR_LINE);
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

    @Test
    void everyStartedCaseCarriesTheRestClientsVendorLineOutsideTheFrames() {
        for (String fixtureCase : STARTED) {
            assertThat(fixture(fixtureCase + FRAMES).lines()
                .filter(line -> !line.startsWith(FRAME_PREFIX)).toList()).as(fixtureCase)
                .singleElement().asString().contains(VENDOR_LINE);
        }
    }

    // --- the list (US1-1, US1-2, US1-5, US5-1) ------------------------------------------------

    @Test
    void okListsTheRecordedMembersTheAnsweringNodeActiveAndTheOthersOfflineWithoutUptime()
        throws IOException {
        List<String> frames = frames("ok");
        String answering = token(frames, "node ok ");
        List<String[]> members = frames.stream().filter(frame -> frame.startsWith("member "))
            .map(frame -> frame.split(" ")).toList();
        launcher = replaying("ok");

        ClusterNodesResult result = service().list(request());

        assertThat(result.node()).isEqualTo(NODE);
        ClusterNodes cluster = result.cluster();
        assertThat(cluster.answeringNode()).isEqualTo(answering);
        assertThat(cluster.nodes()).extracting(ClusterNode::name).containsExactlyElementsOf(
            members.stream().map(member -> decode(member[1])).toList());
        assertThat(cluster.total()).isEqualTo(Integer.parseInt(token(frames, "members ok ")))
            .isEqualTo(members.size()).isEqualTo(21);
        assertThat(cluster.truncated()).isFalse();
        assertThat(cluster.details()).isEmpty();
        assertThat(cluster.nodes()).filteredOn(node -> node.name().equals(answering))
            .singleElement().satisfies(node -> {
                assertThat(node.status()).isEqualTo(NodeStatus.ACTIVE);
                assertThat(node.version()).contains("9.0.10");
                assertThat(node.uptime()).isPresent();
            });
        assertThat(cluster.nodes()).filteredOn(node -> !node.name().equals(answering))
            .hasSize(20).allSatisfy(node -> {
                assertThat(node.status()).isEqualTo(NodeStatus.OFFLINE);
                assertThat(node.rawStatus()).isEmpty();
                assertThat(node.version()).isEmpty();
                assertThat(node.uptime()).as("an OFFLINE member's uptime 0 is absent").isEmpty();
            });
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING);
        assertThat(result.hints()).isEmpty();
        assertThat(field("op")).isEqualTo("clusterNodes");
        assertThat(field("details")).isEmpty();
        assertRanRest90OnThe90Client();
        assertNoLeak(result);
        assertTempRootEmpty();
    }

    @Test
    void noAnswerKeepsAMemberWithoutAStatusUnknown() throws IOException {
        launcher = replaying("no_answer");

        ClusterNodesResult result = service().list(request());

        assertThat(result.cluster().answeringNode()).isEqualTo("node1");
        assertThat(result.cluster().nodes()).extracting(ClusterNode::name)
            .containsExactly("node1", "node2");
        assertThat(result.cluster().nodes().get(0).status()).isEqualTo(NodeStatus.ACTIVE);
        assertThat(result.cluster().nodes().get(0).version()).contains("9.0.10");
        assertThat(result.cluster().nodes().get(1)).isEqualTo(new ClusterNode("node2",
            NodeStatus.UNKNOWN, Optional.empty(), Optional.empty(), Optional.of("zone-a"),
            Optional.empty(), Optional.empty()));
        assertThat(result.cluster().total()).isEqualTo(2);
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING);
        assertRanRest90OnThe90Client();
        assertNoLeak(result);
        assertTempRootEmpty();
    }

    // --- the details (US1-3, US1-6) -----------------------------------------------------------

    @Test
    void detailsAreThePairsInOrderWithTheCredentialMaskedInTheResultAndTheDetailsWarning()
        throws IOException {
        assertThat(fixture("details.frames")).as("the case carries a credential-like value")
            .contains("trustStorePassword%3D" + DETAILS_SECRET);
        launcher = replaying("details");

        ClusterNodesResult result = service().list(request("node1"));

        assertThat(field("op")).isEqualTo("clusterNodes");
        assertThat(field("details")).isEqualTo("node1");
        assertThat(result.cluster().nodes()).hasSize(2);
        ClusterNodes.Available details =
            (ClusterNodes.Available) result.cluster().details().orElseThrow();
        assertThat(details.node()).isEqualTo("node1");
        assertThat(details.truncated()).isFalse();
        assertThat(details.pairs()).extracting(NodeDetail::key).containsExactly("NodeName",
            "ZoneName", "ClusterBindAddress", "InstallationDirectory", "JavaVersion",
            "Memory.HeapMax", "Memory.HeapUsed", "JvmArguments", "Description");
        // the 020 rule for a secret-named key=value: the value becomes its placeholder
        assertThat(details.pairs().get(7).value()).isEqualTo("-Xmx4g"
            + " -Djavax.net.ssl.trustStorePassword="
            + "${secret:data.-Djavax.net.ssl.trustStorePassword}");
        assertThat(result.warnings()).containsExactly(ClusterNodesResult.WARNING,
            ClusterNodesResult.DETAILS_WARNING);
        assertThat(result.hints()).isEmpty();
        assertRanRest90OnThe90Client();
        assertNoLeak(result);
        assertTempRootEmpty();
    }

    // --- failing case -------------------------------------------------------------------------

    @Test
    void anUnknownFrameIsAnUnexpectedResponseAndLeavesNothing() throws IOException {
        launcher = replaying("garbage");

        ToolErrorException thrown =
            catchThrowableOfType(ToolErrorException.class, () -> service().list(request()));

        assertThat(thrown).as("the call fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(NODE);
        TestSecrets.assertNoLeak(error.toString());
        assertThat(error.toString()).doesNotContain(VENDOR_LINE);
        assertRanRest90OnThe90Client();
        assertTempRootEmpty();
    }

    @Test
    void aMissingVendorMethodIsCliUnavailableNamingThe90ClientAndLeavesNothing()
        throws IOException {
        launcher = replaying("api_missing");

        ToolErrorException thrown =
            catchThrowableOfType(ToolErrorException.class, () -> service().list(request()));

        assertThat(thrown).as("the call fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(NODE);
        assertThat(error.message()).contains("The INUBIT client installation " + profile.client90()
            + " (chosen for INUBIT 9.0) lacks com.inubit.ibis.server.cluster.ClusterClient"
            + ".getNodes()");
        assertThat(error.nextStep())
            .isEqualTo("Check that defaults.cliClients.\"9.0\" points to an INUBIT 9.0 client");
        TestSecrets.assertNoLeak(error.toString());
        assertRanRest90OnThe90Client();
        assertTempRootEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * The real service over the real adapter for {@link #NODE}: {@link #MATRIX}, the opt-in set,
     * the detection answering 9.0.10, the helper getting the configured credentials and its client
     * chosen for the detected version, as in production.
     */
    private ClusterNodeService service() {
        EffectiveNodeConfig server = profile.node(NODE.value(), VersionSetting.AUTO).build();
        NodeCredentials credentials = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>("jdoe", "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        CredentialGuard guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources,
            versions::effectiveVersion);
        NodeHelperRunner helper = new NodeHelperRunner(server, () -> credentials, guard, runner,
            TestCliRunners.helperHost(PARENT, resources, tempRoot),
            new ProcessRun(launcher, resources), new CliConcurrencyLimiter(MATRIX));
        ClusterNodePort port = new CliClusterNodeAdapter(helper, scrubber, server.secretLabels());
        return new ClusterNodeService(new TargetResolver(List.of(NODE)), node -> true,
            FakeVersionDetection.gateWithoutClients(versions, detection, MATRIX),
            new Gateways(port), node -> CLI_TIMEOUT, System::nanoTime);
    }

    /**
     * One helper run: {@code REST_90} at the node's base URL, on the 9.0 client with its JDK and
     * without the 8.1 client or its JDK.
     */
    private void assertRanRest90OnThe90Client() {
        assertThat(launcher.launchCount()).as("helper runs").isOne();
        assertThat(field("variant")).isEqualTo("REST_90");
        assertThat(field("url")).isEqualTo("http://n90.example.test:8000");
        LaunchSpec spec = launcher.last().spec();
        assertThat(spec.workingDirectory()).isEqualTo(profile.client90());
        assertThat(spec.environment()).containsEntry("JAVA_HOME", profile.jdk21().toString());
        assertThat(String.join(" ", spec.command()) + spec.environment())
            .contains(profile.client90().toString())
            .doesNotContain(profile.client81().toString(), profile.jdk17().toString());
    }

    private static ClusterNodesRequest request() {
        return new ClusterNodesRequest(NODE.value(), Optional.empty());
    }

    private static ClusterNodesRequest request(String nodeName) {
        return new ClusterNodesRequest(NODE.value(), Optional.of(nodeName));
    }

    private static void assertNoLeak(ClusterNodesResult result) {
        TestSecrets.assertNoLeak(result.toString());
        assertThat(result.toString()).doesNotContain(DETAILS_SECRET, VENDOR_LINE);
    }

    private void assertTempRootEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("nothing left below the temporary directory").isEmpty();
        }
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

    /** Replays {@code <case>.frames}/{@code .exit} with the request's nonce. */
    private static FakeProcessLauncher replaying(String fixtureCase) {
        String frames = fixture(fixtureCase + FRAMES);
        int exit = Integer.parseInt(fixture(fixtureCase + ".exit").strip());
        return FakeProcessLauncher.of("", "", 0).responding(request -> new Answer(
            frames.replace(FIXTURE_NONCE, field(request, "nonce")), exit));
    }

    /** The decoded value of {@code key} in the last helper request. */
    private String field(String key) {
        return field(launcher.last().stdinBytes(), key);
    }

    /** The decoded value of {@code key} in a helper request. */
    private static String field(byte[] request, String key) {
        return decode(new String(request, StandardCharsets.US_ASCII).lines()
            .filter(line -> line.startsWith(key + "=")).findFirst().orElseThrow()
            .substring(key.length() + 1));
    }

    /** Only the cluster-node port of {@link #NODE}; every other port is an assertion error. */
    private record Gateways(ClusterNodePort clusterNodePort) implements GatewayFactory {

        @Override
        public ClusterNodePort clusterNodes(NodeId server) {
            assertThat(server).isEqualTo(NODE);
            return clusterNodePort;
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
