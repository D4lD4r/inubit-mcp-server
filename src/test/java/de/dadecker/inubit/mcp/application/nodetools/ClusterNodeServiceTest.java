package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
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
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T021 (FR-010, FR-011, research D-7, D-21, D-22; contracts/mcp-tools-delta.md
 * "list_cluster_nodes" and "Common rules"): {@link ClusterNodeService} — the input checks, the one
 * server, the opt-in, the version gate with detection, the call deadline ({@code cliTimeout}) and
 * the server's login credentials, the port, the result with its warnings and hints, and the one
 * INFO line. Fakes of the {@link ClusterNodePort} (recording calls and the deadline) and the
 * {@link GatewayFactory} (recording the {@code prepareCredentials} bound); the real
 * {@link de.dadecker.inubit.mcp.application.VersionGate} over a scripted detection.
 *
 * <p>The port is the adapter's contract: detail values arrive masked and cut by the adapter
 * ({@link NodeTextMasking}), and an unknown {@code nodeName} arrives as its {@code INVALID_INPUT};
 * the fake returns exactly what the adapter would, and the service passes it through unchanged.
 */
class ClusterNodeServiceTest {

    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final NodeId NEW = NodeId.parse("test/node91");
    private static final NodeId AUTO = NodeId.parse("test/auto");
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(30);
    private static final long START = 7_000_000_000L;
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final String WARNING =
        "Read through an unsupported, undocumented INUBIT interface; diagnosis only";
    private static final String DETAILS_WARNING =
        "The node details contain internal addresses and file paths of the INUBIT installation";

    private static final ClusterNode NODE1 = new ClusterNode("node1", NodeStatus.ACTIVE,
        Optional.empty(), Optional.of("8.1.17"), Optional.of("zone-a"), Optional.of("a1b2c3"),
        Optional.of(Duration.ofSeconds(273_912)));
    private static final ClusterNode NODE2 = new ClusterNode("node2", NodeStatus.UNKNOWN,
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /** {@code cluster_nodes_read} supported on 8.1 (the test matrix); every other one not. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Map.of(Capability.CLUSTER_NODES_READ, SupportState.SUPPORTED))),
        Map.of());

    private final Map<NodeId, VersionSetting> settings = Map.of(TEST, VersionSetting.AUTO,
        PROD, VersionSetting.AUTO, NEW, VersionSetting.AUTO, AUTO, VersionSetting.AUTO);
    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW), settings::get,
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(TEST, "8.1.17").answer(PROD, "8.1.17").answer(NEW, "9.1.0");
    /** The credentials of every configured node, as the adapter's scrubber holds them. */
    private final SecretScrubber scrubber = new SecretScrubber();
    private final FakePort port = new FakePort();
    private final FakeGateways gateways = new FakeGateways();
    /** The seam's time; each reading advances it by {@code tick}. */
    private final AtomicLong nanoTime = new AtomicLong(START);
    private long tick;
    private final List<ToolError> errors = new ArrayList<>();
    private final List<ClusterNodesResult> results = new ArrayList<>();

    private final Logger logger = (Logger) LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
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
    }

    /** Leak check of every call: no credential in an error, a log line or a result. */
    @AfterEach
    void noLeak() {
        List<String> texts = new ArrayList<>();
        errors.forEach(error -> texts.add(error.toString()));
        results.forEach(result -> texts.add(result.toString()));
        appender.list.forEach(event -> texts.add(event.getFormattedMessage()));
        texts.forEach(text -> assertThat(text)
            .doesNotContain(TestSecrets.CONFIGURED_PASSWORD)
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE));
    }

    // --- US1-1, US1-2, US1-5: the list -------------------------------------------------------

    @Test
    void listsEveryNodeTheClusterKnowsAndNamesTheAnsweringNode() {
        port.answer = new ClusterNodes("node1", List.of(NODE1, NODE2), 2, false, Optional.empty());

        ClusterNodesResult result = listed(request(TEST.value()));

        assertThat(result.node()).isEqualTo(TEST);
        assertThat(result.cluster()).isEqualTo(port.answer);
        assertThat(result.cluster().answeringNode()).isEqualTo("node1");
        assertThat(result.hints()).isEmpty();
        assertThat(result.warnings()).containsExactly(WARNING);
        assertThat(port.calls).containsExactly(new Call(Optional.empty(), V8_1_17,
            START + CLI_TIMEOUT.toNanos()));
    }

    @Test
    void aNodeThatDoesNotAnswerIsKeptAsUnknownWithoutItsValues() {
        port.answer = new ClusterNodes("node1", List.of(NODE1, NODE2), 2, false, Optional.empty());

        ClusterNodesResult result = listed(request(TEST.value()));

        assertThat(result.cluster().nodes()).hasSize(2);
        ClusterNode unknown = result.cluster().nodes().get(1);
        assertThat(unknown.name()).isEqualTo("node2");
        assertThat(unknown.status()).isEqualTo(NodeStatus.UNKNOWN);
        assertThat(unknown.version()).isEmpty();
        assertThat(unknown.zone()).isEmpty();
        assertThat(unknown.nodeId()).isEmpty();
        assertThat(unknown.uptime()).isEmpty();
    }

    @Test
    void aSingleNodeServerListsItsOneNode() {
        port.answer = new ClusterNodes("node1", List.of(NODE1), 1, false, Optional.empty());

        ClusterNodesResult result = listed(request(TEST.value()));

        assertThat(result.cluster().nodes()).containsExactly(NODE1);
        assertThat(result.cluster().total()).isEqualTo(1);
        assertThat(result.cluster().truncated()).isFalse();
    }

    @Test
    void aListCutAt200KeepsTheTotalAndIsMarkedTruncated() {
        List<ClusterNode> nodes = IntStream.rangeClosed(1, ClusterNodes.MAX_NODES)
            .mapToObj(i -> new ClusterNode("node" + i, NodeStatus.ACTIVE, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
            .toList();
        port.answer = new ClusterNodes("node1", nodes, 230, true, Optional.empty());

        ClusterNodesResult result = listed(request(TEST.value()));

        assertThat(result.cluster().nodes()).hasSize(200);
        assertThat(result.cluster().total()).isEqualTo(230);
        assertThat(result.cluster().truncated()).isTrue();
    }

    // --- US1-3 and the details edge cases ----------------------------------------------------

    @Test
    void withNodeNameTheDetailsAreReturnedWithTheDetailsWarning() {
        ClusterNodes.Available details = new ClusterNodes.Available("node2", List.of(
            new NodeDetail("JDKVersion", "17.0.9", false),
            new NodeDetail("InternalNodeAddress", "10.0.0.11:7800", false)), false);
        port.answer = new ClusterNodes("node1", List.of(NODE1, NODE2), 2, false,
            Optional.of(details));

        ClusterNodesResult result = listed(request(TEST.value(), "node2"));

        assertThat(port.calls).extracting(Call::details).containsExactly(Optional.of("node2"));
        assertThat(result.cluster().details()).contains(details);
        assertThat(result.warnings()).containsExactly(WARNING, DETAILS_WARNING);
        assertThat(result.hints()).isEmpty();
    }

    @Test
    void detailsTheHelperCouldNotReadKeepTheListWithoutTheDetailsWarning() {
        ClusterNodes.Unavailable details = new ClusterNodes.Unavailable("node2",
            "INUBIT could not read the node's details");
        port.answer = new ClusterNodes("node1", List.of(NODE1, NODE2), 2, false,
            Optional.of(details));

        ClusterNodesResult result = listed(request(TEST.value(), "node2"));

        assertThat(result.cluster().nodes()).containsExactly(NODE1, NODE2);
        assertThat(result.cluster().details()).contains(details);
        assertThat(result.warnings()).containsExactly(WARNING);
    }

    @Test
    void detailsStoppedByTheCapOrBudgetAddTheHint() {
        port.answer = new ClusterNodes("node1", List.of(NODE1), 1, false, Optional.of(
            new ClusterNodes.Available("node1", List.of(new NodeDetail("JDKVersion", "17.0.9",
                false)), true)));

        ClusterNodesResult result = listed(request(TEST.value(), "node1"));

        assertThat(result.hints()).containsExactly("Only part of the node details was returned");
    }

    /**
     * A credential in a detail value: the adapter masks and cuts it once ({@link NodeTextMasking}
     * with the scrubber of every node's credentials); the service passes the masked value on
     * unchanged, without a second cut.
     */
    @Test
    void aCredentialInADetailValueArrivesMaskedAndCutOnlyOnce() {
        String raw = "-Dpeer.pass=" + TestSecrets.CONFIGURED_PASSWORD + " <password>"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</password> " + "x".repeat(2_500);
        NodeTextMasking.Bounded masked = new NodeTextMasking(scrubber::scrub)
            .maskAndCut(raw, NodeDetail.MAX_VALUE_CHARS);
        NodeDetail detail = new NodeDetail("JavaArguments", masked.text(), masked.cut());
        port.answer = new ClusterNodes("node1", List.of(NODE1), 1, false, Optional.of(
            new ClusterNodes.Available("node1", List.of(detail), false)));

        ClusterNodesResult result = listed(request(TEST.value(), "node1"));

        NodeDetail shown = ((ClusterNodes.Available) result.cluster().details().orElseThrow())
            .pairs().get(0);
        assertThat(shown).isEqualTo(detail);
        assertThat(shown.value())
            .startsWith("-Dpeer.pass=*** <password>${secret:data.password}</password> ")
            .endsWith(NodeTextMasking.CUT_MARKER)
            .hasSize(NodeDetail.MAX_VALUE_CHARS)
            .doesNotContain(TestSecrets.CONFIGURED_PASSWORD)
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE);
        assertThat(shown.value().indexOf(NodeTextMasking.CUT_MARKER))
            .isEqualTo(shown.value().lastIndexOf(NodeTextMasking.CUT_MARKER));
        assertThat(shown.cut()).isTrue();
    }

    // --- US1-6: a nodeName the cluster does not know -----------------------------------------

    @Test
    void aNodeNameNotInTheClusterIsTheAdaptersInvalidInputNamingTheKnownNodes() {
        ToolError unknown = ToolError.of(ErrorCode.INVALID_INPUT, "Node nodeX is not in the"
                + " cluster of test/node1; known nodes: node1, node2",
            "The INUBIT server knows no cluster member of that name", "Use a name from the list")
            .withNode(TEST);
        port.failure = new ToolErrorException(unknown);

        ToolError error = errorOf(() -> listed(request(TEST.value(), "nodeX")));

        assertThat(error).isEqualTo(unknown);
        assertThat(port.calls).extracting(Call::details).containsExactly(Optional.of("nodeX"));
        assertThat(infoLines()).containsExactly("list_cluster_nodes on test/node1: - node(s),"
            + " details nodeX, outcome INVALID_INPUT");
    }

    // --- step 1: input checks ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "node 1", "node/1", "node1;rm", "ä-node"})
    void anInvalidNodeNameIsRefusedWithoutEchoAndNothingIsSent(String nodeName) {
        ToolError error = errorOf(() -> listed(request(TEST.value(), nodeName)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Invalid nodeName: expected 1–128 characters of"
            + " A-Z, a-z, 0-9, '_', '.', ':' and '-'");
        assertNothingSent();
        assertThat(infoLines()).containsExactly("list_cluster_nodes on -: - node(s), details -,"
            + " outcome INVALID_INPUT");
    }

    @Test
    void aNodeNameOf129CharactersIsRefused() {
        ToolError error = errorOf(() -> listed(request(TEST.value(), "n".repeat(129))));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertNothingSent();
    }

    // --- step 2: one server ------------------------------------------------------------------

    @Test
    void aGroupIdIsRefusedWithTheResolversTextAndNothingIsSent() {
        ToolError error = errorOf(() -> listed(request("test")));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("this action needs the id of exactly one");
        assertNothingSent();
    }

    @Test
    void anUnknownServerIsTargetUnknownAndNothingIsSent() {
        ToolError error = errorOf(() -> listed(request("test/node7")));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertNothingSent();
    }

    // --- step 3: the opt-in (US1-4) ----------------------------------------------------------

    @Test
    void withoutTheOptInItIsNotConfiguredAndNothingIsSent() {
        ToolError error = errorOf(() -> listed(request(PROD.value(), "node1")));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.message()).isEqualTo("list_cluster_nodes is not enabled for group prod:"
            + " unsupported.nodeStatusRead is not set");
        assertThat(error.nextStep()).isEqualTo("Set unsupported.nodeStatusRead: true on group"
            + " prod and restart the MCP client (unsupported INUBIT functionality, see"
            + " docs/setup.md \"Unsupported functions\")");
        assertThat(error.node()).contains(PROD);
        assertNothingSent();
        assertThat(infoLines()).containsExactly("list_cluster_nodes on prod/node1: - node(s),"
            + " details node1, outcome NOT_CONFIGURED");
    }

    // --- step 4: the version gate ------------------------------------------------------------

    @Test
    void anUnsupportedVersionIsRefusedByTheGateAndNothingElseIsSent() {
        ToolError error = errorOf(() -> listed(request(NEW.value())));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).startsWith("list_cluster_nodes: ");
        assertThat(detection.requests()).containsExactly(NEW);
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNREACHABLE", "TLS_ERROR", "AUTH_FAILED",
        "TIMEOUT", "MAINTENANCE_MODE", "SECRET_UNRESOLVED"})
    void anUndetectableVersionFailsWithTheDetectionsCodeAndNothingElseIsSent(ErrorCode code) {
        detection.fail(AUTO, code);

        ToolError error = errorOf(() -> listed(request(AUTO.value())));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isEqualTo("detection failed");
        assertThat(detection.requests()).containsExactly(AUTO);
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
        assertThat(infoLines()).containsExactly("list_cluster_nodes on test/auto: - node(s),"
            + " details -, outcome " + code);
    }

    // --- step 5: the call deadline and the credentials ---------------------------------------

    @Test
    void credentialsArePreparedWithinTheDeadlineBeforeThePortIsTaken() {
        attempt(request(TEST.value()));

        assertThat(gateways.calls).containsExactly("prepareCredentials " + TEST,
            "clusterNodes " + TEST);
        assertThat(gateways.bounds).containsExactly(CLI_TIMEOUT);
    }

    @Test
    void theCredentialBoundIsWhatIsLeftOfTheDeadlineAndNeverNegative() {
        tick = CLI_TIMEOUT.toNanos() + 1;

        attempt(request(TEST.value()));

        assertThat(gateways.bounds).containsExactly(Duration.ZERO);
    }

    @Test
    void credentialsNotReadInTimeAreTimeoutNamingCliTimeoutAndThePortIsNotCalled() {
        gateways.state = CredentialState.PENDING;

        ToolError error = errorOf(() -> listed(request(TEST.value())));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).isEqualTo("list_cluster_nodes on test/node1 did not finish"
            + " within cliTimeout (30 s): the login credentials of test/node1 were not read in"
            + " time");
        assertThat(error.nextStep()).contains("cliTimeout of test/node1");
        assertThat(error.node()).contains(TEST);
        assertThat(gateways.calls).containsExactly("prepareCredentials " + TEST);
        assertThat(port.calls).isEmpty();
    }

    @Test
    void aFailedCredentialFetchPassesThroughAndThePortIsNotCalled() {
        ToolError fetch = ToolError.of(ErrorCode.AUTH_FAILED, "The credentials of test/node1"
            + " could not be read", "cause", "next").withNode(TEST);
        gateways.failure = new ToolErrorException(fetch);

        ToolError error = errorOf(() -> listed(request(TEST.value())));

        assertThat(error).isEqualTo(fetch);
        assertThat(port.calls).isEmpty();
    }

    // --- step 6: the port's failures ---------------------------------------------------------

    @Test
    void aWholeCallFailureOfThePortPassesThrough() {
        ToolError timeout = ToolError.of(ErrorCode.TIMEOUT, "list_cluster_nodes on test/node1"
            + " did not finish within cliTimeout (30 s)", "cause", "Raise cliTimeout of"
            + " test/node1").withNode(TEST);
        port.failure = new ToolErrorException(timeout);

        ToolError error = errorOf(() -> listed(request(TEST.value())));

        assertThat(error).isEqualTo(timeout);
        assertThat(infoLines()).containsExactly("list_cluster_nodes on test/node1: - node(s),"
            + " details -, outcome TIMEOUT");
    }

    @Test
    void aPortErrorWithoutANodeNamesTheServer() {
        port.failure = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No INUBIT client", "cause", "next"));

        ToolError error = errorOf(() -> listed(request(TEST.value())));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("No INUBIT client");
        assertThat(error.node()).contains(TEST);
    }

    // --- step 7: the INFO line (D-22) --------------------------------------------------------

    @Test
    void aCallLogsOneInfoLineWithoutAnyValue() {
        port.answer = new ClusterNodes("node1", List.of(NODE1, NODE2), 2, false, Optional.of(
            new ClusterNodes.Available("node2", List.of(
                new NodeDetail("InternalNodeAddress", "10.0.0.11:7800", false),
                new NodeDetail("JavaHome", "/opt/inubit/jdk-17", false)), false)));

        listed(request(TEST.value(), "node2"));

        assertThat(infoLines()).containsExactly("list_cluster_nodes on test/node1: 2 node(s),"
            + " details node2, outcome OK");
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
            .doesNotContain("10.0.0.11").doesNotContain("/opt/inubit"));
        assertThat(appender.list).filteredOn(event -> event.getLoggerName()
                .equals(ClusterNodeService.class.getName()))
            .allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("8.1.17").doesNotContain("zone-a").doesNotContain("a1b2c3"));
    }

    // --- helpers -----------------------------------------------------------------------------

    private ClusterNodeService service() {
        return new ClusterNodeService(new TargetResolver(List.of(TEST, PROD, NEW, AUTO)),
            node -> !node.group().value().equals("prod"),
            FakeVersionDetection.gateWithoutClients(versions, detection, MATRIX), gateways,
            node -> CLI_TIMEOUT, () -> nanoTime.getAndAdd(tick));
    }

    private static ClusterNodesRequest request(String target) {
        return new ClusterNodesRequest(target, Optional.empty());
    }

    private static ClusterNodesRequest request(String target, String nodeName) {
        return new ClusterNodesRequest(target, Optional.of(nodeName));
    }

    /** Calls the service and keeps the result for the leak check. */
    private ClusterNodesResult listed(ClusterNodesRequest request) {
        ClusterNodesResult result = service().list(request);
        results.add(result);
        return result;
    }

    /** Runs a call whatever its outcome; a tool error is kept for the leak check. */
    private void attempt(ClusterNodesRequest request) {
        Throwable thrown = catchThrowable(() -> listed(request));
        if (thrown instanceof ToolErrorException e) {
            errors.add(e.error());
        }
    }

    private ToolError errorOf(Runnable call) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(thrown).isNotNull();
        errors.add(thrown.error());
        return thrown.error();
    }

    private List<String> infoLines() {
        return appender.list.stream()
            .filter(event -> event.getLoggerName().equals(ClusterNodeService.class.getName()))
            .filter(event -> event.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private void assertNothingSent() {
        assertThat(detection.requests()).isEmpty();
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
    }

    private record Call(Optional<String> details, InubitVersion version, long deadlineNanos) {
    }

    /** Records each call; answers {@code answer} or throws {@code failure}. */
    private static final class FakePort implements ClusterNodePort {

        final List<Call> calls = new ArrayList<>();
        ClusterNodes answer = new ClusterNodes("node1", List.of(NODE1), 1, false,
            Optional.empty());
        ToolErrorException failure;

        @Override
        public ClusterNodes nodes(Optional<String> details, InubitVersion version,
            long deadlineNanos) {
            calls.add(new Call(details, version, deadlineNanos));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    /**
     * Only {@code prepareCredentials} and {@code clusterNodes}, recorded in call order; every
     * other port is an assertion error.
     */
    private final class FakeGateways implements GatewayFactory {

        final List<String> calls = new ArrayList<>();
        final List<Duration> bounds = new ArrayList<>();
        CredentialState state = CredentialState.READY;
        ToolErrorException failure;

        @Override
        public CredentialState prepareCredentials(NodeId server, Duration bound) {
            calls.add("prepareCredentials " + server);
            bounds.add(bound);
            if (failure != null) {
                throw failure;
            }
            return state;
        }

        @Override
        public ClusterNodePort clusterNodes(NodeId server) {
            calls.add("clusterNodes " + server);
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
