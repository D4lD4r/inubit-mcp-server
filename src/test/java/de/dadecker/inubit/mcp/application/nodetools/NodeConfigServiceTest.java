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
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigResult;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.NodeConfigPort;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T035 (FR-030 … FR-032, SC-003, research D-10, D-21, D-22;
 * contracts/mcp-tools-delta.md "get_node_config" and "Common rules"): {@link NodeConfigService} —
 * the names against the fixed list, the one server, the opt-in, the version gate with detection,
 * the call deadline ({@code cliTimeout}) and the server's login credentials, the port, the result
 * with its warnings, and the one INFO line. Fakes of the {@link NodeConfigPort} (recording the
 * names sent and the deadline) and the {@link GatewayFactory} (recording the
 * {@code prepareCredentials} bound); the real
 * {@link de.dadecker.inubit.mcp.application.VersionGate} over a scripted detection.
 *
 * <p>The port is the adapter's contract: values arrive masked and cut by the adapter (T034); the
 * fake returns exactly what the adapter would, and the service passes it through unchanged.
 */
class NodeConfigServiceTest {

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
    private static final String ALLOWED = "OperationMode, ClusterName, ZoneName, NodeName,"
        + " LocalMaintenanceMode, ActivateClusterManager, ClusterDebug,"
        + " ContainerShutdownOnNodeFailure, RegisterMBeans, RegisterInternalMBeans,"
        + " RuntimeDataCacheXMXPercentage, socketTimeoutInMillisecs, indexEnabled, iterationCount,"
        + " DataSourceLocation, LogsDBDataSourceLocation, RepositoryDataSourceLocation,"
        + " ClusterBindAddress, noOfRetries, retryInterval";

    private static final NodeConfigValue.Present OPERATION_MODE = new NodeConfigValue.Present(
        NodeConfigName.OPERATION_MODE, "CLUSTER", false, "SelectProperty", "Operation mode",
        "Single or cluster operation", "Cluster", true, false);
    private static final NodeConfigValue.Present ZONE_NAME = new NodeConfigValue.Present(
        NodeConfigName.ZONE_NAME, "zone-a", false, "StringProperty", "Zone name",
        "Name of the zone", "Cluster", true, false);

    /** {@code node_config_read} supported on 8.1 (the test matrix); every other one not. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Map.of(Capability.NODE_CONFIG_READ, SupportState.SUPPORTED))),
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
    private final List<NodeConfigResult> results = new ArrayList<>();

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

    // --- US3-1: a name from the list -----------------------------------------------------------

    @Test
    void aListedNameReturnsItsValueWithAllAttributesAndNamesTheAnsweringNode() {
        port.answer = new NodeConfigValues("node1", List.of(OPERATION_MODE));

        NodeConfigResult result = read(request(TEST.value(), "OperationMode"));

        assertThat(result.node()).isEqualTo(TEST);
        assertThat(result.answeringNode()).isEqualTo("node1");
        assertThat(result.values()).containsExactly(OPERATION_MODE);
        NodeConfigValue.Present value = (NodeConfigValue.Present) result.values().get(0);
        assertThat(value.value()).isEqualTo("CLUSTER");
        assertThat(value.type()).isEqualTo("SelectProperty");
        assertThat(value.displayName()).isEqualTo("Operation mode");
        assertThat(value.description()).isEqualTo("Single or cluster operation");
        assertThat(value.category()).isEqualTo("Cluster");
        assertThat(value.editable()).isTrue();
        assertThat(value.deprecated()).isFalse();
        assertThat(result.warnings()).containsExactly(WARNING);
        assertThat(port.calls).containsExactly(new Call(List.of(NodeConfigName.OPERATION_MODE),
            V8_1_17, START + CLI_TIMEOUT.toNanos()));
    }

    @Test
    void namesAreSentAndReturnedInRequestOrder() {
        port.answer = new NodeConfigValues("node1", List.of(ZONE_NAME, OPERATION_MODE));

        NodeConfigResult result = read(request(TEST.value(), "ZoneName", "OperationMode"));

        assertThat(port.calls).extracting(Call::names).containsExactly(
            List.of(NodeConfigName.ZONE_NAME, NodeConfigName.OPERATION_MODE));
        assertThat(result.values()).containsExactly(ZONE_NAME, OPERATION_MODE);
    }

    // --- US3-3: no name ------------------------------------------------------------------------

    @Test
    void withoutNamesAllTwentyAreSentInListOrder() {
        List<NodeConfigValue> all = List.of(NodeConfigName.values()).stream()
            .<NodeConfigValue>map(NodeConfigValue.Absent::new).toList();
        port.answer = new NodeConfigValues("node1", all);

        NodeConfigResult result = read(new NodeConfigRequest(TEST.value(), List.of()));

        assertThat(port.calls).extracting(Call::names)
            .containsExactly(List.of(NodeConfigName.values()));
        assertThat(port.calls.get(0).names()).hasSize(20);
        assertThat(result.values()).isEqualTo(all);
        assertThat(infoLines()).containsExactly("get_node_config on test/node1: 20 name(s),"
            + " outcome OK");
    }

    // --- US3-4: a name the node does not know --------------------------------------------------

    @Test
    void aNameTheNodeDoesNotHaveIsReturnedAsNotPresent() {
        NodeConfigValue.Absent absent = new NodeConfigValue.Absent(NodeConfigName.NO_OF_RETRIES);
        port.answer = new NodeConfigValues("node1", List.of(OPERATION_MODE, absent));

        NodeConfigResult result = read(request(TEST.value(), "OperationMode", "noOfRetries"));

        assertThat(result.values()).containsExactly(OPERATION_MODE, absent);
    }

    // --- one failed name -----------------------------------------------------------------------

    @Test
    void aSingleFailedNameKeepsTheOthersAndTheCallSucceeds() {
        NodeConfigValue.Failed failed = new NodeConfigValue.Failed(NodeConfigName.CLUSTER_NAME,
            ErrorCode.UNEXPECTED_RESPONSE, "INUBIT could not read ClusterName");
        port.answer = new NodeConfigValues("node1", List.of(OPERATION_MODE, failed, ZONE_NAME));

        NodeConfigResult result = read(request(TEST.value(), "OperationMode", "ClusterName",
            "ZoneName"));

        assertThat(result.values()).containsExactly(OPERATION_MODE, failed, ZONE_NAME);
        assertThat(infoLines()).containsExactly("get_node_config on test/node1: 3 name(s),"
            + " outcome OK");
    }

    // --- masking: the adapter's work, passed through -------------------------------------------

    /**
     * A credential in a value: the adapter masks and cuts it once ({@link NodeTextMasking} with
     * the scrubber of every node's credentials); the service passes the masked value on unchanged,
     * without a second mask or cut.
     */
    @Test
    void aMaskedAndCutValueIsPassedThroughUnchanged() {
        String raw = "jdbc/repo;password=" + TestSecrets.CONFIGURED_PASSWORD + " <password>"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</password> " + "x".repeat(2_500);
        NodeTextMasking.Bounded masked = new NodeTextMasking(scrubber::scrub)
            .maskAndCut(raw, NodeConfigValue.MAX_VALUE_CHARS);
        NodeConfigValue.Present value = new NodeConfigValue.Present(
            NodeConfigName.REPOSITORY_DATA_SOURCE_LOCATION, masked.text(), masked.cut(),
            "StringProperty", "Repository data source", "JNDI name", "Database", true, false);
        port.answer = new NodeConfigValues("node1", List.of(value));

        NodeConfigResult result = read(request(TEST.value(), "RepositoryDataSourceLocation"));

        NodeConfigValue.Present shown = (NodeConfigValue.Present) result.values().get(0);
        assertThat(shown).isEqualTo(value);
        assertThat(shown.value())
            .endsWith(NodeTextMasking.CUT_MARKER)
            .hasSize(NodeConfigValue.MAX_VALUE_CHARS)
            .doesNotContain(TestSecrets.CONFIGURED_PASSWORD)
            .doesNotContain(TestSecrets.CREDENTIAL_PAYLOAD_VALUE);
        assertThat(shown.value().indexOf(NodeTextMasking.CUT_MARKER))
            .isEqualTo(shown.value().lastIndexOf(NodeTextMasking.CUT_MARKER));
        assertThat(shown.cut()).isTrue();
    }

    // --- US3-2, SC-003: names outside the list -------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Database", "ClusterPassword", "initialRootUserPassword",
        "IdentityProvider", "", "operationmode", "OPERATIONMODE", "Operationmode"})
    void aNameOutsideTheListIsRefusedListingTheAllowedNamesAndNothingIsSent(String name) {
        ToolError error = errorOf(() -> read(request(TEST.value(), name)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Configuration value names[0] is not readable;"
            + " allowed: " + ALLOWED);
        assertNotEchoed(error, name);
        assertNothingSent();
        assertThat(infoLines()).containsExactly("get_node_config on -: - name(s), outcome"
            + " INVALID_INPUT");
    }

    @Test
    void aTextOf10000CharactersIsRefusedWithoutEchoAndNothingIsSent() {
        String text = "Database" + "y".repeat(9_992);

        ToolError error = errorOf(() -> read(request(TEST.value(), text)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertNotEchoed(error, text);
        assertThat(error.toString()).doesNotContain("yyyy");
        assertNothingSent();
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
            .doesNotContain("yyyy"));
    }

    @Test
    void anUnlistedNameAfterListedOnesIsRefusedAndNothingIsSent() {
        ToolError error = errorOf(() -> read(request(TEST.value(), "OperationMode", "ZoneName",
            "ClusterPassword")));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).startsWith("Configuration value names[2] is not readable;");
        assertNotEchoed(error, "ClusterPassword");
        assertNothingSent();
    }

    @Test
    void aNameGivenTwiceIsRefusedAndNothingIsSent() {
        ToolError error = errorOf(() -> read(request(TEST.value(), "OperationMode", "ZoneName",
            "OperationMode")));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("Configuration value OperationMode is named more"
            + " than once in names");
        assertNothingSent();
        assertThat(infoLines()).containsExactly("get_node_config on -: - name(s), outcome"
            + " INVALID_INPUT");
    }

    // --- step 2: one server --------------------------------------------------------------------

    @Test
    void aGroupIdIsRefusedWithTheResolversTextAndNothingIsSent() {
        ToolError error = errorOf(() -> read(request("test", "OperationMode")));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("this action needs the id of exactly one");
        assertNothingSent();
    }

    @Test
    void anUnknownServerIsTargetUnknownAndNothingIsSent() {
        ToolError error = errorOf(() -> read(request("test/node7", "OperationMode")));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertNothingSent();
    }

    // --- step 3: the opt-in (US3-5) ------------------------------------------------------------

    @Test
    void withoutTheOptInItIsNotConfiguredAndNothingIsSent() {
        ToolError error = errorOf(() -> read(request(PROD.value(), "OperationMode")));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.message()).isEqualTo("get_node_config is not enabled for group prod:"
            + " unsupported.nodeConfigRead is not set");
        assertThat(error.nextStep()).isEqualTo("Set unsupported.nodeConfigRead: true on group"
            + " prod and restart the MCP client (unsupported INUBIT functionality, see"
            + " docs/setup.md \"Unsupported functions\")");
        assertThat(error.node()).contains(PROD);
        assertNothingSent();
        assertThat(infoLines()).containsExactly("get_node_config on prod/node1: 1 name(s),"
            + " outcome NOT_CONFIGURED");
    }

    // --- step 4: the version gate --------------------------------------------------------------

    @Test
    void anUnsupportedVersionIsRefusedByTheGateAndNothingElseIsSent() {
        ToolError error = errorOf(() -> read(request(NEW.value(), "OperationMode")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).startsWith("get_node_config: ");
        assertThat(detection.requests()).containsExactly(NEW);
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNREACHABLE", "TLS_ERROR", "AUTH_FAILED",
        "TIMEOUT", "MAINTENANCE_MODE", "SECRET_UNRESOLVED"})
    void anUndetectableVersionFailsWithTheDetectionsCodeAndNothingElseIsSent(ErrorCode code) {
        detection.fail(AUTO, code);

        ToolError error = errorOf(() -> read(request(AUTO.value(), "OperationMode")));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isEqualTo("detection failed");
        assertThat(detection.requests()).containsExactly(AUTO);
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
        assertThat(infoLines()).containsExactly("get_node_config on test/auto: 1 name(s),"
            + " outcome " + code);
    }

    // --- step 5: the call deadline and the credentials -----------------------------------------

    @Test
    void credentialsArePreparedWithinTheDeadlineBeforeThePortIsTaken() {
        attempt(request(TEST.value(), "OperationMode"));

        assertThat(gateways.calls).containsExactly("prepareCredentials " + TEST,
            "nodeConfig " + TEST);
        assertThat(gateways.bounds).containsExactly(CLI_TIMEOUT);
    }

    @Test
    void theCredentialBoundIsWhatIsLeftOfTheDeadlineAndNeverNegative() {
        tick = CLI_TIMEOUT.toNanos() + 1;

        attempt(request(TEST.value(), "OperationMode"));

        assertThat(gateways.bounds).containsExactly(Duration.ZERO);
    }

    @Test
    void credentialsNotReadInTimeAreTimeoutNamingCliTimeoutAndThePortIsNotCalled() {
        gateways.state = CredentialState.PENDING;

        ToolError error = errorOf(() -> read(request(TEST.value(), "OperationMode")));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).isEqualTo("get_node_config on test/node1 did not finish"
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

        ToolError error = errorOf(() -> read(request(TEST.value(), "OperationMode")));

        assertThat(error).isEqualTo(fetch);
        assertThat(port.calls).isEmpty();
    }

    // --- step 6: the port's failures -----------------------------------------------------------

    @Test
    void aWholeCallFailureOfThePortPassesThrough() {
        ToolError timeout = ToolError.of(ErrorCode.TIMEOUT, "get_node_config on test/node1"
            + " did not finish within cliTimeout (30 s)", "cause", "Raise cliTimeout of"
            + " test/node1").withNode(TEST);
        port.failure = new ToolErrorException(timeout);

        ToolError error = errorOf(() -> read(request(TEST.value(), "OperationMode")));

        assertThat(error).isEqualTo(timeout);
        assertThat(infoLines()).containsExactly("get_node_config on test/node1: 1 name(s),"
            + " outcome TIMEOUT");
    }

    @Test
    void aPortErrorWithoutANodeNamesTheServer() {
        port.failure = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No INUBIT client", "cause", "next"));

        ToolError error = errorOf(() -> read(request(TEST.value(), "OperationMode")));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("No INUBIT client");
        assertThat(error.node()).contains(TEST);
    }

    // --- step 7: the INFO line (D-22) ----------------------------------------------------------

    @Test
    void aCallLogsOneInfoLineWithoutAnyValue() {
        NodeConfigValue.Present location = new NodeConfigValue.Present(
            NodeConfigName.DATA_SOURCE_LOCATION, "java:comp/env/jdbc/ibis", false,
            "StringProperty", "Data source", "JNDI name of the data source", "Database", true,
            false);
        port.answer = new NodeConfigValues("node1", List.of(OPERATION_MODE, location));

        read(request(TEST.value(), "OperationMode", "DataSourceLocation"));

        assertThat(infoLines()).containsExactly("get_node_config on test/node1: 2 name(s),"
            + " outcome OK");
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
            .doesNotContain("CLUSTER").doesNotContain("jdbc/ibis")
            .doesNotContain("Operation mode").doesNotContain("JNDI name"));
    }

    // --- helpers -------------------------------------------------------------------------------

    private NodeConfigService service() {
        return new NodeConfigService(new TargetResolver(List.of(TEST, PROD, NEW, AUTO)),
            node -> !node.group().value().equals("prod"),
            FakeVersionDetection.gateWithoutClients(versions, detection, MATRIX), gateways,
            node -> CLI_TIMEOUT, () -> nanoTime.getAndAdd(tick));
    }

    private static NodeConfigRequest request(String target, String... names) {
        return new NodeConfigRequest(target, List.of(names));
    }

    /** Calls the service and keeps the result for the leak check. */
    private NodeConfigResult read(NodeConfigRequest request) {
        NodeConfigResult result = service().read(request);
        results.add(result);
        return result;
    }

    /** Runs a call whatever its outcome; a tool error is kept for the leak check. */
    private void attempt(NodeConfigRequest request) {
        Throwable thrown = catchThrowable(() -> read(request));
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

    /** The refused text appears in no field of the error and in no log line. */
    private void assertNotEchoed(ToolError error, String refused) {
        if (refused.isEmpty()) {
            return;
        }
        assertThat(List.of(error.message(), error.likelyCause(), error.nextStep()))
            .allSatisfy(text -> assertThat(text).doesNotContain(refused));
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
            .doesNotContain(refused));
    }

    private List<String> infoLines() {
        return appender.list.stream()
            .filter(event -> event.getLoggerName().equals(NodeConfigService.class.getName()))
            .filter(event -> event.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    /** SC-003: no detection, no credentials, no port, so no name reached INUBIT. */
    private void assertNothingSent() {
        assertThat(detection.requests()).isEmpty();
        assertThat(gateways.calls).isEmpty();
        assertThat(port.calls).isEmpty();
    }

    private record Call(List<NodeConfigName> names, InubitVersion version, long deadlineNanos) {
    }

    /** Records each call; answers {@code answer} or throws {@code failure}. */
    private static final class FakePort implements NodeConfigPort {

        final List<Call> calls = new ArrayList<>();
        NodeConfigValues answer = new NodeConfigValues("node1", List.of(OPERATION_MODE));
        ToolErrorException failure;

        @Override
        public NodeConfigValues values(List<NodeConfigName> names, InubitVersion version,
            long deadlineNanos) {
            calls.add(new Call(List.copyOf(names), version, deadlineNanos));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    /**
     * Only {@code prepareCredentials} and {@code nodeConfig}, recorded in call order; every other
     * port is an assertion error.
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
        public NodeConfigPort nodeConfig(NodeId server) {
            calls.add("nodeConfig " + server);
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
