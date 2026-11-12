package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.VersionGate.Admission;
import de.dadecker.inubit.mcp.application.VersionGate.Evaluation;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Capability.Kind;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientSelectionPort;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * T008: the version gate (research D-5, FR-005, FR-008 to FR-011, FR-015, FR-016). Detections go
 * through a scripted {@link GatewayFactory#detect} that reports to {@link NodeVersions} like the
 * real factory; every call of it is one {@code /system/info} request. Client results are seeded
 * through the {@link NodeVersions} API; T019: the gate's own client probe (FR-002) runs only for
 * the nodes in {@link #withClient}, through a scripted {@link ClientVersionPort}.
 */
class VersionGateTest {

    private static final NodeId AUTO = NodeId.parse("dev/node1");
    private static final NodeId PINNED = NodeId.parse("prod/node1");
    private static final NodeId V9_X = NodeId.parse("prod/node3");
    /** Feature 012: a node on the 6.1 line and one pinned to a concrete 6.1 version. */
    private static final NodeId V6_1 = NodeId.parse("old/node1");
    private static final NodeId V6_1_PINNED = NodeId.parse("old/node2");
    /** Feature 017: a node on the 9.0 line. */
    private static final NodeId V9_0 = NodeId.parse("prod/node4");
    private static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(45);

    private static final Set<Channel> REST = Set.of(Channel.REST);
    private static final Set<Channel> CLI = Set.of(Channel.CLI);
    private static final Set<Channel> CLI_REST = Set.of(Channel.CLI, Channel.REST);

    /** Secrets and the URL of the node, as a detection error of the adapter might carry them. */
    private static final String BASE_URL = "https://inubit-dev.example.com:8443";
    private static final String USERNAME = "svc-inubit";
    private static final String PASSWORD = "s3cr3t-Pa55";

    private final MutableClock clock = new MutableClock(T0);
    private final NodeVersions versions = new NodeVersions(clock,
        Map.of(AUTO, VersionSetting.AUTO,
            PINNED, VersionSetting.parse("8.1.17").orElseThrow(),
            V9_X, VersionSetting.V9_X,
            V6_1, VersionSetting.V6_1,
            V6_1_PINNED, VersionSetting.parse("6.1.0.89").orElseThrow(),
            V9_0, VersionSetting.V9_0)::get,
        node -> TTL);
    private final ScriptedDetection detection = new ScriptedDetection();
    private final ScriptedProbe probe = new ScriptedProbe();
    /** The nodes with a StartCLI installation; only they are probed (none by default). */
    private final Set<NodeId> withClient = new HashSet<>();
    /** Feature 017: the client choice; by default one client per node with a StartCLI. */
    private ClientSelectionPort selection = TestClientSelection.of(withClient::contains);

    private final Logger logger = (Logger) LoggerFactory.getLogger(VersionGate.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** Refusals are logged at INFO (the production root level); tests run at WARN. */
    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLog() {
        logger.setLevel(null);
        logger.setAdditive(true);
        logger.detachAppender(appender);
        appender.stop();
    }

    private VersionGate gate() {
        return gate(CapabilityMatrix.production());
    }

    private VersionGate gate(CapabilityMatrix matrix) {
        return gate(matrix, Terminology.DEFAULT);
    }

    private VersionGate gate(CapabilityMatrix matrix, Terminology terms) {
        return new VersionGate(versions, detection, matrix, probe, selection,
            node -> CLI_TIMEOUT, terms);
    }

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static ClientVersion client(String version, String from, String to) {
        return new ClientVersion(version(version), Optional.of(version(from)),
            Optional.of(version(to)));
    }

    private static ToolError refusal(Runnable call) {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(e).as("refused").isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        return e.error();
    }

    private static ToolErrorException detectionError(ErrorCode code) {
        return new ToolErrorException(ToolError.of(code,
            "Login at " + BASE_URL + " as " + USERNAME + " failed (" + PASSWORD + ")",
            "Credentials of " + USERNAME + " rejected",
            "Set versionLine: V8_1 for " + AUTO + " in the configuration").withNode(AUTO));
    }

    /** A matrix that supports everything on every version, so only the tested cause refuses. */
    private static Map<Capability, SupportState> all(SupportState state) {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, state);
        }
        return states;
    }

    /**
     * Feature 012 (research D-3): the 6.1 entry of production with process diagnosis and
     * monitoring {@code SUPPORTED} (channel CLI) and inventory {@code UNVERIFIED}; every
     * capability is supported on 8.1 and on unknown versions, so only the 6.1 rules refuse.
     */
    private static CapabilityMatrix matrix61() {
        return new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
                Map.of(Capability.MONITORING, SupportState.SUPPORTED,
                    Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED,
                    Capability.INVENTORY, SupportState.UNVERIFIED),
                Map.of(Capability.MONITORING, CLI, Capability.PROCESS_DIAGNOSIS, CLI), true),
            new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
                all(SupportState.SUPPORTED))),
            all(SupportState.SUPPORTED));
    }

    @Test
    void anAutoNodeOn81IsAdmittedAfterOneDetection() {
        detection.answer("8.1.17");

        Admission admission = gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE,
            CLI_REST);

        assertThat(admission.warnings()).isEmpty();
        assertThat(detection.calls).isEqualTo(1);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void anAdmittedWriteCarriesTheVersionItWasJudgedOn() {
        // feature 017 (T040, FR-018, research D-24): the write permit's admitted version
        detection.answer("8.1.17");

        Admission auto = gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE,
            CLI_REST);
        Admission pinned = gate().admit("restart_process", PINNED, Capability.PROCESS_CONTROL,
            Kind.WRITE, CLI_REST);
        Admission line = gate().admit(V9_0, Capability.E2E_TEST, Kind.WRITE, REST);

        assertThat(auto.version()).contains(version("8.1.17"));
        assertThat(pinned.version()).contains(version("8.1.17"));
        assertThat(line.version()).map(InubitVersion::raw).contains("9.0");
    }

    @Test
    void anAdmittedWriteCarriesTheVersionOfTheRedetectionNotTheOutdatedOne() {
        detection.answer("8.1.17");
        gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        clock.advance(TTL.plusSeconds(1));
        detection.answer("8.1.18");

        Admission admission = gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE,
            CLI_REST);

        assertThat(admission.version()).contains(version("8.1.18"));
    }

    @Test
    void aReadAdmissionCarriesItsVersionAndAnUnknownOneIsEmpty() {
        detection.answer("9.1.0");
        Admission known = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        Admission line = gate().admit(V9_X, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        Admission monitoring = gate().admitMonitoring(PINNED, Optional.of(version("8.1.20")));

        assertThat(known.version()).contains(version("9.1.0"));
        assertThat(line.version()).isEmpty();
        assertThat(monitoring.version()).contains(version("8.1.20"));
        assertThat(new Admission(List.of(), List.of()).version()).isEmpty();
    }

    @Test
    void aReadOn91IsAdmittedWithTheUnverifiedWarning() {
        detection.answer("9.1.0");

        Admission processes = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        Admission logs = gate().admit(AUTO, Capability.LOG_DIAGNOSIS, Kind.READ, REST);

        // feature 012 (FR-004): the former "diagnosis" is named per tool
        assertThat(processes.warnings()).containsExactly("INUBIT 9.1.0 on dev/node1 is not"
            + " verified for process diagnosis; results may be incomplete");
        assertThat(logs.warnings()).containsExactly("INUBIT 9.1.0 on dev/node1 is not verified"
            + " for log diagnosis; results may be incomplete");
    }

    @Test
    void aWriteOn91IsRefusedAfterOnlyTheDetectionRequest() {
        detection.answer("9.1.0");

        ToolError error = refusal(() -> gate().admit(AUTO, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));

        assertThat(error.message()).isEqualTo("INUBIT 9.1.0 on dev/node1 does not support"
            + " development writes yet; nothing was sent");
        assertThat(error.node()).contains(AUTO);
        assertThat(error.nextStep()).startsWith("Use another node").doesNotContain("versionLine");
        assertThat(detection.calls).isEqualTo(1);
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(
            event.getFormattedMessage()).contains("dev/node1", "DEVELOPMENT_WRITE", "REFUSED",
            "UNSUPPORTED_VERSION", "INUBIT 9.1.0"));
    }

    @Test
    void withTheToolNameTheRefusalNamesTheToolAndAnAdmissionIsUnchanged() {
        // T009 (contracts/mcp-tools-delta.md): the message of a refusal names the tool
        detection.answer("9.1.0");

        ToolError error = refusal(() -> gate().admit("import_artifacts", AUTO,
            Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST));
        Admission read = gate().admit("find_processes", AUTO, Capability.PROCESS_DIAGNOSIS,
            Kind.READ, REST);

        assertThat(error.message()).isEqualTo("import_artifacts: INUBIT 9.1.0 on dev/node1 does"
            + " not support development writes yet; nothing was sent");
        assertThat(error.node()).contains(AUTO);
        assertThat(error.nextStep()).startsWith("Use another node");
        assertThat(read.warnings()).containsExactly("INUBIT 9.1.0 on dev/node1 is not verified"
            + " for process diagnosis; results may be incomplete");
    }

    @Test
    void anUnknownVersionAfterAFailedDetectionRefusesWritesAndWarnsReads() {
        detection.fail(detectionError(ErrorCode.AUTH_FAILED));

        ToolError error = refusal(() -> gate().admit(AUTO, Capability.PROCESS_CONTROL,
            Kind.WRITE, CLI_REST));
        Admission read = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);

        assertThat(error.message()).contains(
            "the INUBIT version of dev/node1 is unknown (AUTH_FAILED at 2026-10-18T08:00:00Z)");
        // FR-005: the remedy is to pin the version the node runs, never a concrete guess
        assertThat(error.nextStep()).contains("versionLine", "the version the node actually runs")
            .doesNotContain("V8_1", "8.1");
        assertThat(read.warnings()).containsExactly("the INUBIT version of dev/node1 is unknown"
            + " (AUTH_FAILED at 2026-10-18T08:00:00Z); results are unverified");
        assertThat(detection.calls).as("the read within 60 s does not detect again")
            .isEqualTo(1);
    }

    /** Feature 012 T011 (FR-003): the 6.1 hint follows only a detection answered with 404. */
    @Test
    void theUnknownVersionHintsAtV61OnlyAfterADetectionAnsweredWith404() {
        detection.failEndpointMissing(detectionError(ErrorCode.UNEXPECTED_RESPONSE));

        Admission afterNotFound = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ,
            REST);
        ToolError write = refusal(() -> gate().admit(AUTO, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));

        assertThat(afterNotFound.warnings()).containsExactly("the INUBIT version of dev/node1"
            + " is unknown (UNEXPECTED_RESPONSE at 2026-10-18T08:00:00Z); if dev/node1 runs"
            + " INUBIT 6.1, pin V6_1; results are unverified");
        assertThat(write.message()).contains("the INUBIT version of dev/node1 is unknown"
            + " (UNEXPECTED_RESPONSE at 2026-10-18T08:00:00Z); if dev/node1 runs INUBIT 6.1,"
            + " pin V6_1");
        assertThat(versions.state(AUTO).effectiveVersion()).as("AUTO never selects 6.1")
            .isEmpty();

        clock.advance(Duration.ofSeconds(60));
        detection.fail(detectionError(ErrorCode.UNEXPECTED_RESPONSE));

        Admission afterOtherFailure = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS,
            Kind.READ, REST);

        assertThat(afterOtherFailure.warnings()).containsExactly("the INUBIT version of"
            + " dev/node1 is unknown (UNEXPECTED_RESPONSE at 2026-10-18T08:01:00Z); results are"
            + " unverified");
        assertThat(detection.calls).isEqualTo(2);
    }

    @Test
    void aPinContradictedByGetHealthRefusesWrites() {
        versions.observed(PINNED, version("8.2.0"));

        ToolError error = refusal(() -> gate(new CapabilityMatrix(List.of(),
            all(SupportState.SUPPORTED))).admit(PINNED, Capability.DEVELOPMENT_WRITE, Kind.WRITE,
            CLI_REST));

        assertThat(error.message()).contains("versionLine 8.1.17", "prod/node1", "INUBIT 8.2.0",
            "development writes");
        assertThat(error.nextStep()).contains("versionLine", "restart");
        assertThat(detection.calls).isZero();
    }

    @Test
    void aVersionOlderThanTheBoundIsDetectedAgainBeforeAWriteButNotBeforeARead() {
        detection.answer("8.1.17");
        gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        clock.advance(TTL.plusSeconds(1));

        gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        assertThat(detection.calls).as("reads use the cached version").isEqualTo(1);

        gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        assertThat(detection.calls).isEqualTo(2);
    }

    @Test
    void aStaleVersionIsDetectedAgainBeforeAWrite() {
        detection.answer("8.1.17");
        gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        versions.stale(AUTO);

        gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        assertThat(detection.calls).isEqualTo(1);

        gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        assertThat(detection.calls).isEqualTo(2);
    }

    @Test
    void aFailedReDetectionOfAStaleVersionRefusesWritesAndBacksOff() {
        detection.answer("8.1.17");
        gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        versions.stale(AUTO);
        clock.advance(Duration.ofSeconds(5));
        detection.fail(detectionError(ErrorCode.UNREACHABLE));

        ToolError first = refusal(() -> gate().admit(AUTO, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));
        clock.advance(Duration.ofSeconds(59));
        ToolError second = refusal(() -> gate().admit(AUTO, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));
        Admission read = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);

        assertThat(first.message()).contains(
            "the INUBIT version of dev/node1 is unknown (UNREACHABLE at 2026-10-18T08:00:05Z)");
        assertThat(second.message()).isEqualTo(first.message());
        assertThat(detection.calls).as("the second write is within the 60 s back-off")
            .isEqualTo(2);
        assertThat(read.warnings()).as("reads keep the cached 8.1.17").isEmpty();
        assertThat(appender.list).hasSize(2);
    }

    @Test
    void anIncompatibleClientRefusesOnlyTheCliChannel() {
        detection.answer("9.1.0");
        versions.clientProbed(AUTO, new ProbedClient(ClientKey.NONE,
            client("8.1.17", "4.0.1", "8.1.17")));

        Admission rest = gate().admit(AUTO, Capability.INVENTORY, Kind.READ, REST);
        ToolError cli = refusal(() -> gate().admit(AUTO, Capability.INVENTORY, Kind.READ, CLI));

        assertThat(rest.warnings()).containsExactly(
            "INUBIT 9.1.0 on dev/node1 is not verified for inventory; results may be incomplete");
        assertThat(cli.message()).contains("8.1.17", "4.0.1 to 8.1.17", "INUBIT 9.1.0",
            "dev/node1", "inventory");
        // feature 017 (T011, research D-8): on INUBIT 9 the next step names the version entry
        assertThat(cli.nextStep()).isEqualTo(
            "Install a StartCLI client on the host of this server that supports INUBIT 9.1, or"
                + " add defaults.cliClients.\"9.1\"");
        assertThat(detection.calls).isEqualTo(1);
    }

    @Test
    void aClientWithAnotherPatchGivesAMismatchWarning() {
        detection.answer("8.1.18");
        versions.clientProbed(AUTO, new ProbedClient(ClientKey.NONE,
            client("8.1.17", "4.0.1", "8.1.17")));

        Admission cli = gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        Admission rest = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);

        assertThat(cli.warnings()).containsExactly("StartCLI client 8.1.17 on dev/node1"
            + " differs from INUBIT 8.1.18 (mismatch);"
            + " StartCLI-backed tools still run, but their results may be unreliable");
        assertThat(rest.warnings()).isEmpty();
    }

    @Test
    void aClientRangeThatCannotBeEvaluatedGivesAWarningNamingTheCause() {
        detection.answer("8.1.17");
        versions.clientProbeFailed(AUTO, ClientKey.NONE, ErrorCode.TIMEOUT);

        Admission admission = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        assertThat(admission.warnings()).containsExactly("The StartCLI client probe of dev/node1"
            + " failed (TIMEOUT at 2026-10-18T08:00:00Z); the INUBIT range of the client cannot"
            + " be checked, StartCLI results may be unreliable");
    }

    @Test
    void aReadDeclaredUnsupportedByATestMatrixIsRefused() {
        Map<Capability, SupportState> states = all(SupportState.SUPPORTED);
        states.put(Capability.PROCESS_DIAGNOSIS, SupportState.UNSUPPORTED);
        states.put(Capability.LOG_DIAGNOSIS, SupportState.UNSUPPORTED);
        CapabilityMatrix matrix = new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), states)),
            all(SupportState.UNSUPPORTED));
        detection.answer("8.1.17");

        ToolError processes = refusal(() -> gate(matrix).admit(AUTO,
            Capability.PROCESS_DIAGNOSIS, Kind.READ, REST));
        ToolError logs = refusal(() -> gate(matrix).admit(AUTO, Capability.LOG_DIAGNOSIS,
            Kind.READ, REST));

        assertThat(processes.message()).isEqualTo("INUBIT 8.1.17 on dev/node1 does not support"
            + " process diagnosis yet; nothing was sent");
        assertThat(logs.message()).isEqualTo("INUBIT 8.1.17 on dev/node1 does not support"
            + " log diagnosis yet; nothing was sent");
        assertThat(appender.list).hasSize(2);
    }

    @Test
    void monitoringIsNeverRefusedAndNeverDetects() {
        CapabilityMatrix matrix = new CapabilityMatrix(List.of(), all(SupportState.UNSUPPORTED));

        Admission notYetDetected = gate(matrix).admit(AUTO, Capability.MONITORING, Kind.READ,
            REST);
        versions.observed(AUTO, version("8.1.17"));
        Admission observed = gate(matrix).admit(AUTO, Capability.MONITORING, Kind.READ, REST);

        assertThat(detection.calls).isZero();
        assertThat(notYetDetected.warnings()).containsExactly("the INUBIT version of dev/node1"
            + " is unknown (not yet detected); results are unverified");
        assertThat(observed.warnings()).containsExactly(
            "INUBIT 8.1.17 on dev/node1 is not supported for monitoring; results may be"
                + " incomplete");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void aConcretePinSendsNoDetectionRequest() {
        assertThat(gate().admit(PINNED, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST)
            .warnings()).isEmpty();
        clock.advance(TTL.multipliedBy(3));
        versions.stale(PINNED);
        assertThat(gate().admit(PINNED, Capability.PROCESS_CONTROL, Kind.WRITE, CLI_REST)
            .warnings()).isEmpty();
        assertThat(gate().admit(PINNED, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST).warnings())
            .isEmpty();

        assertThat(detection.calls).isZero();
    }

    @Test
    void aV9xLineRefusesWritesAndWarnsReadsWithoutDetection() {
        ToolError error = refusal(() -> gate().admit(V9_X, Capability.DEPLOYMENT, Kind.WRITE,
            CLI_REST));
        Admission read = gate().admit(V9_X, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);

        assertThat(error.message()).isEqualTo("INUBIT 9.x (versionLine V9_X) on prod/node3 does"
            + " not support deployment yet; nothing was sent");
        assertThat(read.warnings()).containsExactly("INUBIT 9.x (versionLine V9_X) on prod/node3"
            + " is not verified for process diagnosis; results may be incomplete");
        assertThat(detection.calls).isZero();
    }

    @Test
    void aTlsErrorOfTheDetectionIsPassedThroughWithoutBackOff() {
        ToolErrorException tls = new ToolErrorException(ToolError.of(ErrorCode.TLS_ERROR,
            "The trust store of dev/node1 cannot be used", "cause", "next").withNode(AUTO));
        detection.failUnreported(tls);

        ToolErrorException first = catchThrowableOfType(ToolErrorException.class,
            () -> gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST));
        detection.answer("8.1.17");
        Admission second = gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE,
            CLI_REST);

        assertThat(first).isSameAs(tls);
        assertThat(second.warnings()).isEmpty();
        assertThat(detection.calls).isEqualTo(2);
        assertThat(appender.list).as("not a version refusal").isEmpty();
    }

    // --- T019: the client probe outside get_health (FR-002, FR-011) ---------------------------

    @Test
    void theFirstCliAdmissionProbesTheClientOnceAndAPatchMismatchWarns() {
        withClient.add(AUTO);
        detection.answer("8.1.18");
        probe.answer(client("8.1.17", "4.0.1", "8.1.17"));

        Admission rest = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        assertThat(probe.calls).as("no probe for a REST-only call").isEmpty();
        Admission first = gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        Admission second = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        assertThat(rest.warnings()).isEmpty();
        String mismatch = "StartCLI client 8.1.17 on dev/node1"
            + " differs from INUBIT 8.1.18 (mismatch);"
            + " StartCLI-backed tools still run, but their results may be unreliable";
        assertThat(first.warnings()).containsExactly(mismatch);
        assertThat(second.warnings()).containsExactly(mismatch);
        assertThat(probe.calls).as("one probe bounded by cliTimeout, kept afterwards")
            .containsExactly(CLI_TIMEOUT);
        assertThat(versions.state(AUTO).clientFor(ClientKey.NONE)).hasValueSatisfying(known ->
            assertThat(known.value().version().raw()).isEqualTo("8.1.17"));
    }

    @Test
    void aProbedClientWhoseRangeExcludesTheServerRefusesTheCliChannel() {
        withClient.add(AUTO);
        detection.answer("9.0.7");
        probe.answer(client("8.1.17", "4.0.1", "8.1.17"));

        ToolError error = refusal(() -> gate().admit("list_inventory", AUTO,
            Capability.INVENTORY, Kind.READ, CLI));

        assertThat(error.message()).isEqualTo("list_inventory: StartCLI client 8.1.17 of"
            + " dev/node1 supports INUBIT 4.0.1 to 8.1.17, which excludes INUBIT 9.0.7;"
            + " inventory via StartCLI refused; nothing was sent");
        // feature 017 (T011, research D-8): on INUBIT 9 the next step names the version entry
        assertThat(error.nextStep()).isEqualTo(
            "Install a StartCLI client on the host of this server that supports INUBIT 9.0, or"
                + " add defaults.cliClients.\"9.0\"");
        assertThat(probe.calls).hasSize(1);
        assertThat(detection.calls).isEqualTo(1);
    }

    @Test
    void withCliClientsConfiguredTheNextStepNamesTheEntryOfTheVersion() {
        // feature 017 (T011, research D-8)
        detection.answer("8.1.17");
        versions.clientProbed(AUTO, new ProbedClient(ClientKey.NONE,
            client("9.0.7", "9.0.0", "9.0.99")));
        VersionGate gate = new VersionGate(versions, detection, CapabilityMatrix.production(),
            probe, selection, node -> CLI_TIMEOUT, Terminology.DEFAULT, true);

        ToolError error = refusal(() -> gate.admit(AUTO, Capability.INVENTORY, Kind.READ, CLI));

        assertThat(error.nextStep()).isEqualTo("Install a StartCLI client on the host of this"
            + " server that supports INUBIT 8.1, or add defaults.cliClients.\"8.1\"");
        // leak check
        assertThat(error.toString()).doesNotContain(PASSWORD, USERNAME, BASE_URL);
    }

    @Test
    void withoutCliClientsBelowNineTheNextStepIsUnchanged() {
        // feature 017 (T011, FR-009): the 0.8.0 text
        detection.answer("8.1.17");
        versions.clientProbed(AUTO, new ProbedClient(ClientKey.NONE,
            client("9.0.7", "9.0.0", "9.0.99")));

        ToolError error = refusal(() -> gate().admit(AUTO, Capability.INVENTORY, Kind.READ,
            CLI));

        assertThat(error.nextStep()).isEqualTo(
            "Install a StartCLI client on the host of this server that supports INUBIT 8.1");
    }

    @Test
    void aLineWithoutMinorNamesNoEntryEvenWithCliClients() {
        // feature 017 (T011, research D-8): an entry needs major.minor
        withClient.add(V9_X);
        probe.answer(client("8.1.17", "4.0.1", "8.1.17"));
        VersionGate gate = new VersionGate(versions, detection, CapabilityMatrix.production(),
            probe, selection, node -> CLI_TIMEOUT, Terminology.DEFAULT, true);

        ToolError error = refusal(() -> gate.admit(V9_X, Capability.INVENTORY, Kind.READ, CLI));

        assertThat(error.nextStep()).isEqualTo(
            "Install a StartCLI client on the host of this server that supports INUBIT 9.x");
    }

    @Test
    void aLineWithoutMinorNamesNoEntryEvenAfterAnObservation() {
        // feature 017 (research D-3, FR-011): V9_X never chooses an entry, so none is named
        withClient.add(V9_X);
        versions.observed(V9_X, version("9.1.3"));
        probe.answer(client("8.1.17", "4.0.1", "8.1.17"));
        VersionGate gate = new VersionGate(versions, detection, CapabilityMatrix.production(),
            probe, selection, node -> CLI_TIMEOUT, Terminology.DEFAULT, true);

        ToolError error = refusal(() -> gate.admit(V9_X, Capability.INVENTORY, Kind.READ, CLI));

        assertThat(error.nextStep()).isEqualTo(
            "Install a StartCLI client on the host of this server that supports INUBIT 9.1");
    }

    @Test
    void aProbedClientRefusesTheCliChannelOfAV9xLine() {
        withClient.add(V9_X);
        probe.answer(client("8.1.17", "4.0.1", "8.1.17"));

        ToolError error = refusal(() -> gate().admit(V9_X, Capability.INVENTORY, Kind.READ,
            CLI));

        assertThat(error.message()).contains("StartCLI client 8.1.17", "4.0.1 to 8.1.17",
            "INUBIT 9.x (versionLine V9_X)", "prod/node3");
        assertThat(error.nextStep()).endsWith("supports INUBIT 9.x");
        assertThat(probe.calls).hasSize(1);
        assertThat(detection.calls).isZero();
    }

    @Test
    void aTimedOutProbeAdmitsWithAWarningAndIsRetriedOnlyAfter60Seconds() {
        withClient.add(AUTO);
        detection.answer("8.1.17");
        probe.fail(ErrorCode.TIMEOUT);

        Admission timedOut = gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE,
            CLI_REST);
        clock.advance(Duration.ofSeconds(59));
        Admission inBackOff = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        String warning = "The StartCLI client probe of dev/node1 failed (TIMEOUT at"
            + " 2026-10-18T08:00:00Z); the INUBIT range of the client cannot be checked,"
            + " StartCLI results may be unreliable";
        assertThat(timedOut.warnings()).containsExactly(warning);
        assertThat(inBackOff.warnings()).containsExactly(warning);
        assertThat(probe.calls).as("no second probe within 60 s").hasSize(1);

        clock.advance(Duration.ofSeconds(2));
        probe.answer(client("8.1.17", "4.0.1", "8.1.17"));
        Admission retried = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        assertThat(probe.calls).hasSize(2);
        assertThat(retried.warnings()).isEmpty();
        assertThat(versions.state(AUTO).clientFailureFor(ClientKey.NONE)).isEmpty();
    }

    @Test
    void aNodeWithoutStartCliInstallationIsNeverProbed() {
        Admission admission = gate().admit(PINNED, Capability.DEVELOPMENT_WRITE, Kind.WRITE,
            CLI_REST);

        assertThat(admission.warnings()).isEmpty();
        assertThat(probe.calls).isEmpty();
        assertThat(versions.state(PINNED).clientFailureFor(ClientKey.NONE)).isEmpty();
    }

    @Test
    void aCancelledProbeIsPassedThroughAndNotRecorded() {
        withClient.add(PINNED);
        ToolErrorException cancelled = new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
            "The StartCLI call on prod/node1 was cancelled", "cause", "next").withNode(PINNED));
        probe.outcome = () -> {
            Thread.currentThread().interrupt();
            throw cancelled;
        };

        ToolErrorException thrown;
        try {
            thrown = catchThrowableOfType(ToolErrorException.class,
                () -> gate().admit(PINNED, Capability.WORKSPACE_EXPORT, Kind.READ, CLI));
        } finally {
            Thread.interrupted();
        }

        assertThat(thrown).isSameAs(cancelled);
        assertThat(versions.state(PINNED).clientFailureFor(ClientKey.NONE)).as("no back-off")
            .isEmpty();
    }

    // --- feature 017, T020: the client check per client (FR-017, research D-6, D-7, D-19) ----

    private static final String SERVLET_WARNING = "cli.url of prod/node4 uses"
        + " /ibis/servlet/IBISSoapServlet, which INUBIT 9.0.2 and later removed; remove cli.url"
        + " or set it to the base URL";

    /** One client per major.minor, as {@code defaults.cliClients} chooses it. */
    private static ClientKey keyOf(Optional<InubitVersion> version) {
        return new ClientKey(version.map(v -> "/opt/acme/startcli-" + v.major() + "." + v.minor())
            .orElse("/opt/acme/startcli"), "/opt/jdk-21");
    }

    /** Every client exists; a servlet {@code cli.url} on {@link #V9_0} from major 9 on. */
    private void clientPerVersion() {
        probe.keys = VersionGateTest::keyOf;
        selection = new ClientSelectionPort() {
            @Override
            public Optional<ClientKey> current(NodeId node, Optional<InubitVersion> version) {
                return Optional.of(keyOf(version));
            }

            @Override
            public boolean available(NodeId node, Optional<InubitVersion> version) {
                return true;
            }

            @Override
            public boolean anyCandidate(NodeId node) {
                return true;
            }

            @Override
            public Optional<String> warning(NodeId node, Optional<InubitVersion> version) {
                return node.equals(V9_0) && version.map(v -> v.major() >= 9).orElse(false)
                    ? Optional.of(SERVLET_WARNING) : Optional.empty();
            }

            @Override
            public NodeSummary.StartCli startCli(NodeId node, Optional<InubitVersion> version) {
                return NodeSummary.StartCli.none(version.isPresent());
            }
        };
    }

    @Test
    void anAutoNodeThatMovesFrom81To90ProbesThe90ClientAndDoesNotReuseThe81Result() {
        clientPerVersion();
        detection.answer("8.1.17");
        probe.answer(client("8.1.17", "4.0.1", "8.1.17"));
        Admission on81 = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        versions.observed(AUTO, version("9.0.7"));
        probe.answer(client("9.0.7", "9.0.0", "9.0.7"));
        Admission on90 = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        assertThat(on81.warnings()).isEmpty();
        assertThat(on90.warnings()).noneMatch(warning -> warning.contains("StartCLI"));
        assertThat(probe.probedVersions).as("each probe chose its client by the gate's version")
            .containsExactly(Optional.of(version("8.1.17")), Optional.of(version("9.0.7")));
        assertThat(versions.state(AUTO).clientFor(keyOf(Optional.of(version("8.1.17")))))
            .hasValueSatisfying(known ->
                assertThat(known.value().version().raw()).isEqualTo("8.1.17"));
        assertThat(versions.state(AUTO).clientFor(keyOf(Optional.of(version("9.0.7")))))
            .hasValueSatisfying(known ->
                assertThat(known.value().version().raw()).isEqualTo("9.0.7"));

        gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);
        assertThat(probe.calls).as("the 9.0 result is kept").hasSize(2);
    }

    @Test
    void theResultOfAnotherClientIsNotJudged() {
        clientPerVersion();
        detection.answer("9.0.7");
        versions.clientProbed(AUTO, new ProbedClient(keyOf(Optional.of(version("8.1.17"))),
            client("8.1.17", "4.0.1", "8.1.17")));
        probe.answer(client("9.0.7", "9.0.0", "9.0.7"));

        Admission admission = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        assertThat(admission.warnings()).noneMatch(warning -> warning.contains("StartCLI"));
        assertThat(probe.calls).hasSize(1);
    }

    @Test
    void theProbeBackOffHoldsPerClient() {
        clientPerVersion();
        detection.answer("8.1.17");
        probe.fail(ErrorCode.TIMEOUT);
        Admission failed81 = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        clock.advance(Duration.ofSeconds(10));
        versions.observed(AUTO, version("9.0.7"));
        probe.answer(client("9.0.7", "9.0.0", "9.0.7"));
        Admission on90 = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        assertThat(failed81.warnings()).containsExactly("The StartCLI client probe of dev/node1"
            + " failed (TIMEOUT at 2026-10-18T08:00:00Z); the INUBIT range of the client cannot"
            + " be checked, StartCLI results may be unreliable");
        assertThat(probe.calls).as("the 8.1 back-off does not delay the 9.0 client").hasSize(2);
        assertThat(on90.warnings()).noneMatch(warning -> warning.contains("StartCLI"));
        assertThat(versions.state(AUTO).clientFailureFor(keyOf(Optional.of(version("8.1.17")))))
            .as("the 8.1 failure stays for its client").isPresent();
        assertThat(versions.needsClientProbe(AUTO, keyOf(Optional.of(version("8.1.17")))))
            .isFalse();
    }

    @Test
    void anExplicitServletAddressOnANineNodeWarnsOnStartCliCalls() {
        clientPerVersion();
        probe.answer(client("9.0.7", "9.0.0", "9.0.99"));

        Admission cli = gate().admit(V9_0, Capability.WORKSPACE_EXPORT, Kind.READ, CLI_REST);
        Admission rest = gate().admit(V9_0, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);

        assertThat(cli.warnings()).contains(SERVLET_WARNING);
        assertThat(rest.warnings()).doesNotContain(SERVLET_WARNING);
    }

    // --- feature 012, T006: CLI-only versions (FR-005 to FR-007, FR-013, FR-019) -----------

    @Test
    void theProductionMatrixRefusesEveryCapabilityOf61BeforeAnyDetectionOrProbe() {
        withClient.add(V6_1);
        withClient.add(V6_1_PINNED);

        for (NodeId node : List.of(V6_1, V6_1_PINNED)) {
            for (Capability capability : Capability.values()) {
                ToolError error = refusal(() -> gate().admit(node, capability,
                    capability.kind(), CLI_REST));
                assertThat(error.message()).as("%s on %s", capability, node)
                    .contains(node.toString(), "INUBIT 6.1", "does not support "
                        + capability.label());
                assertThat(error.node()).contains(node);
            }
        }

        assertThat(detection.calls).isZero();
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
        // FR-019: every refusal is logged like the refusals of feature 009
        assertThat(appender.list).hasSize(2 * Capability.values().length)
            .allSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("REFUSED", "UNSUPPORTED_VERSION", "INUBIT 6.1"));
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
            .contains("MONITORING", "old/node2", "INUBIT 6.1.0.89"));
    }

    @Test
    void a61NodeRefusesEveryCapabilityThatIsNotSupportedIncludingAnUnverifiedRead() {
        Admission processes = gate(matrix61()).admit(V6_1, Capability.PROCESS_DIAGNOSIS,
            Kind.READ, REST);
        Admission monitoring = gate(matrix61()).admit(V6_1, Capability.MONITORING, Kind.READ,
            REST);
        // no warning path and no 8.1 fallback on a CLI-only version
        ToolError inventory = refusal(() -> gate(matrix61()).admit(V6_1, Capability.INVENTORY,
            Kind.READ, REST));
        ToolError logs = refusal(() -> gate(matrix61()).admit(V6_1, Capability.LOG_DIAGNOSIS,
            Kind.READ, REST));
        ToolError write = refusal(() -> gate(matrix61()).admit(V6_1,
            Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST));

        assertThat(processes.warnings()).isEmpty();
        assertThat(monitoring.warnings()).isEmpty();
        assertThat(inventory.message()).startsWith("INUBIT 6.1 (versionLine V6_1) on old/node1"
            + " does not support inventory; ");
        assertThat(logs.message()).contains("log diagnosis");
        assertThat(write.message()).contains("development writes");
        assertThat(detection.calls).isZero();
        assertThat(appender.list).hasSize(3);
    }

    @Test
    void aClientWhoseRangeExcludes61RefusesProcessDiagnosisThroughTheChannelOfTheEntry() {
        withClient.add(V6_1);
        withClient.add(V6_1_PINNED);
        probe.answer(client("9.0.7", "8.1.0", "9.0.7"));

        // the caller declares REST; the 6.1 entry declares CLI, so the client is checked
        ToolError error = refusal(() -> gate(matrix61()).admit("find_processes", V6_1,
            Capability.PROCESS_DIAGNOSIS, Kind.READ, REST));
        probe.answer(client("6.1.0", "4.0.0", "6.1.0"));
        Admission compatible = gate(matrix61()).admit(V6_1_PINNED,
            Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);

        assertThat(error.message()).isEqualTo("find_processes: StartCLI client 9.0.7 of"
            + " old/node1 supports INUBIT 8.1.0 to 9.0.7, which excludes INUBIT 6.1;"
            + " process diagnosis via StartCLI refused; nothing was sent");
        assertThat(error.nextStep()).isEqualTo(
            "Install a StartCLI client on the host of this server that supports INUBIT 6.1");
        assertThat(compatible.warnings()).isEmpty();
        assertThat(probe.calls).hasSize(2);
        assertThat(versions.state(V6_1_PINNED).clientFor(ClientKey.NONE)).isPresent();
        assertThat(detection.calls).isZero();
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(
            event.getFormattedMessage()).contains("PROCESS_DIAGNOSIS", "old/node1", "REFUSED",
            "UNSUPPORTED_VERSION", "excludes INUBIT 6.1"));
    }

    @Test
    void aContradicted61PinRefusesEveryCapabilityExceptMonitoring() {
        versions.observed(V6_1, version("8.1.17"));
        versions.observed(PINNED, version("8.2.0"));

        for (Capability capability : Capability.values()) {
            if (capability == Capability.MONITORING) {
                continue;
            }
            ToolError error = refusal(() -> gate(matrix61()).admit(V6_1, capability,
                capability.kind(), CLI_REST));
            assertThat(error.message()).as("%s", capability).isEqualTo("versionLine V6_1 of"
                + " old/node1 contradicts INUBIT 8.1.17 observed by get_health at"
                + " 2026-10-18T08:00:00Z; " + capability.label()
                + " refused; check the version with get_health and correct versionLine;"
                + " nothing was sent");
        }
        Admission monitoring = gate(matrix61()).admit(V6_1, Capability.MONITORING, Kind.READ,
            REST);
        // FR-018: a contradicted pin of another version still refuses writes only
        Admission read = gate(matrix61()).admit(PINNED, Capability.PROCESS_DIAGNOSIS,
            Kind.READ, REST);

        assertThat(monitoring.warnings()).isEmpty();
        assertThat(read.warnings()).isEmpty();
        assertThat(detection.calls).isZero();
        assertThat(appender.list).hasSize(Capability.values().length - 1);
    }

    // --- feature 012, T007: monitoring access before contact (FR-001, research D-11) --------

    @Test
    void monitoringAccessIsRestForEveryNodeNotPinnedToACliOnlyVersion() {
        withClient.add(AUTO);
        withClient.add(PINNED);

        assertThat(gate(matrix61()).monitoringAccess(AUTO)).isEqualTo(Channel.REST);
        assertThat(gate(matrix61()).monitoringAccess(PINNED)).isEqualTo(Channel.REST);
        assertThat(gate(matrix61()).monitoringAccess(V9_X)).isEqualTo(Channel.REST);
        assertThat(gate().monitoringAccess(PINNED)).isEqualTo(Channel.REST);

        assertThat(detection.calls).isZero();
        assertThat(probe.calls).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void monitoringAccessOfA61PinIsCliWhenSupportedEvenWhileThePinIsContradicted() {
        withClient.add(V6_1);
        withClient.add(V6_1_PINNED);
        versions.observed(V6_1, version("8.1.17"));

        assertThat(gate(matrix61()).monitoringAccess(V6_1)).as("decided by the pin (FR-002)")
            .isEqualTo(Channel.CLI);
        assertThat(gate(matrix61()).monitoringAccess(V6_1_PINNED)).isEqualTo(Channel.CLI);

        // get_health probes the client itself, in parallel with its own calls
        assertThat(probe.calls).isEmpty();
        assertThat(detection.calls).isZero();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void monitoringAccessOfA61PinIsRefusedWithTheProductionMatrix() {
        withClient.add(V6_1);
        withClient.add(V6_1_PINNED);

        ToolError line = refusal(() -> gate().monitoringAccess(V6_1));
        ToolError pinned = refusal(() -> gate().monitoringAccess(V6_1_PINNED));

        assertThat(line.message()).isEqualTo("INUBIT 6.1 (versionLine V6_1) on old/node1 does"
            + " not support monitoring; use the Workbench, or another node with a different"
            + " INUBIT version; nothing was sent");
        assertThat(line.node()).contains(V6_1);
        assertThat(pinned.message()).contains("INUBIT 6.1.0.89", "old/node2", "monitoring");
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
        assertThat(detection.calls).isZero();
        // FR-019: the refusal is logged
        assertThat(appender.list).hasSize(2)
            .allSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("MONITORING", "REFUSED", "UNSUPPORTED_VERSION", "INUBIT 6.1"));
    }

    // --- feature 012, T033: the channels of a capability before contact (research D-10) ------

    @Test
    void theChannelsOfACapabilityAreDecidedOnThePinWithoutContact() {
        withClient.add(AUTO);
        withClient.add(PINNED);
        withClient.add(V6_1);
        withClient.add(V6_1_PINNED);
        versions.observed(V6_1_PINNED, version("8.1.17"));
        VersionGate gate = gate(matrix61());

        assertThat(gate.channels(V6_1, Capability.PROCESS_DIAGNOSIS)).isEqualTo(CLI);
        assertThat(gate.channels(V6_1_PINNED, Capability.PROCESS_DIAGNOSIS))
            .as("decided by the pin, also while it is contradicted").isEqualTo(CLI);
        assertThat(gate.channels(V6_1, Capability.LOG_DIAGNOSIS))
            .as("not declared for 6.1: the capability's own").isEqualTo(REST);
        assertThat(gate.channels(PINNED, Capability.PROCESS_DIAGNOSIS)).isEqualTo(REST);
        assertThat(gate.channels(AUTO, Capability.PROCESS_DIAGNOSIS)).isEqualTo(REST);
        assertThat(gate.channels(AUTO, Capability.INVENTORY)).isEqualTo(CLI_REST);

        assertThat(detection.calls).isZero();
        assertThat(probe.calls).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    // --- feature 012, T008: refusal texts, next steps and version notes (FR-007) -------------

    @Test
    void a61RefusalNamesTheVersionLineTheCapabilityAndItsNextStep() {
        String other = "use the Workbench, or another node with a different INUBIT version";
        Map<Capability, String> expected = new EnumMap<>(Capability.class);
        expected.put(Capability.MONITORING, "monitoring; " + other);
        expected.put(Capability.PROCESS_DIAGNOSIS, "process diagnosis; " + other);
        expected.put(Capability.LOG_DIAGNOSIS,
            "log diagnosis; read the logs of INUBIT 6.1 in the Workbench");
        expected.put(Capability.INVENTORY,
            "inventory; modules of INUBIT 6.1 cannot be looked up; " + other);
        expected.put(Capability.PARTNER_MANAGEMENT, "partner management; " + other);
        expected.put(Capability.WORKSPACE_EXPORT, "workspace export; use the Workbench export");
        expected.put(Capability.DEVELOPMENT_WRITE, "development writes; use the Workbench");
        expected.put(Capability.DEPLOYMENT, "deployment; use the Workbench");
        expected.put(Capability.E2E_TEST, "end-to-end test; use the Workbench");
        expected.put(Capability.PROCESS_CONTROL, "process control; use the Workbench");
        expected.put(Capability.QUEUE_ENTRY_READ, "reading Queue Manager entries; supported: 8.1,"
            + " 9.0; use the Queue Manager in the Workbench");
        expected.put(Capability.TRACE_LOG_READ, "downloading the trace log; supported: 8.1, 9.0;"
            + " download the log in the Workbench (Administration, Server Log)");
        expected.put(Capability.WEBSERVICE_LOG_READ, "downloading the web service log; supported:"
            + " 8.1, 9.0; download the log in the Workbench (Administration, Server Log)");

        for (Capability capability : Capability.values()) {
            ToolError error = refusal(() -> gate().admit(V6_1, capability, capability.kind(),
                CLI_REST));
            assertThat(error.message()).as("%s", capability).isEqualTo("INUBIT 6.1 (versionLine"
                + " V6_1) on old/node1 does not support " + expected.get(capability)
                + "; nothing was sent");
            String step = expected.get(capability)
                .substring(expected.get(capability).lastIndexOf("; ") + 2);
            assertThat(error.nextStep()).as("%s", capability)
                .isEqualTo(Character.toUpperCase(step.charAt(0)) + step.substring(1));
        }
        // a concrete pin is named by its version; the line exists only for a versionLine
        ToolError pinned = refusal(() -> gate().admit(V6_1_PINNED, Capability.LOG_DIAGNOSIS,
            Kind.READ, REST));
        assertThat(pinned.message()).isEqualTo("INUBIT 6.1.0.89 on old/node2 does not support"
            + " log diagnosis; read the logs of INUBIT 6.1 in the Workbench; nothing was sent");
        assertThat(detection.calls).isZero();
    }

    @Test
    void a61RefusalWithTheToolNameAndAsEvaluationCarriesTheNextStepInItsCause() {
        ToolError logs = refusal(() -> gate().admit("query_logs", V6_1,
            Capability.LOG_DIAGNOSIS, Kind.READ, REST));
        // T010: check_artifacts takes the module lookup text from the cause of the evaluation
        Evaluation inventory = gate().evaluate(V6_1, Capability.INVENTORY, Kind.READ, CLI_REST);

        assertThat(logs.message()).isEqualTo("query_logs: INUBIT 6.1 (versionLine V6_1) on"
            + " old/node1 does not support log diagnosis; read the logs of INUBIT 6.1 in the"
            + " Workbench; nothing was sent");
        assertThat(inventory.cause()).contains("INUBIT 6.1 (versionLine V6_1) on old/node1 does"
            + " not support inventory; modules of INUBIT 6.1 cannot be looked up; use the"
            + " Workbench, or another node with a different INUBIT version");
        assertThat(inventory.refusal()).get().satisfies(error -> assertThat(error.likelyCause())
            .isEqualTo("This server declares inventory unsupported for that version"));
    }

    @Test
    void a61RefusalUsesTheProfilesTerminology() {
        Terminology terms = new Terminology("Umgebung", "Umgebungen", "Knoten", "Knoten");

        ToolError inventory = refusal(() -> gate(CapabilityMatrix.production(), terms)
            .admit(V6_1, Capability.INVENTORY, Kind.READ, CLI_REST));
        ToolError write = refusal(() -> gate(CapabilityMatrix.production(), terms)
            .admit(V6_1, Capability.PROCESS_CONTROL, Kind.WRITE, CLI_REST));

        assertThat(inventory.nextStep())
            .isEqualTo("Use the Workbench, or another Knoten with a different INUBIT version");
        assertThat(write.likelyCause())
            .isEqualTo("The Knoten runs a version for which no verified write access exists");
        assertThat(Stream.of(inventory, write).flatMap(error -> Stream.of(error.message(),
            error.likelyCause(), error.nextStep())))
            .allSatisfy(text -> assertThat(text).doesNotContainPattern("(?i)\\bnodes?\\b"));
    }

    @Test
    void anAdmittedProcessDiagnosisOn61CarriesTheLogNoteAndNoOtherAdmissionDoes() {
        withClient.add(V6_1_PINNED);
        probe.answer(client("6.1.0", "4.0.1", "6.1.0"));
        String note = "logs of INUBIT 6.1 cannot be read with query_logs; read them in the"
            + " Workbench";

        Admission line = gate(matrix61()).admit(V6_1, Capability.PROCESS_DIAGNOSIS, Kind.READ,
            REST);
        Admission pinned = gate(matrix61()).admit("find_processes", V6_1_PINNED,
            Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        Admission monitoring = gate(matrix61()).admit(V6_1, Capability.MONITORING, Kind.READ,
            REST);
        // FR-018: no notes on 8.1 and 9.x
        Admission on81 = gate(matrix61()).admit(PINNED, Capability.PROCESS_DIAGNOSIS, Kind.READ,
            REST);
        Admission on9x = gate().admit(V9_X, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);

        assertThat(line.notes()).containsExactly(note);
        assertThat(line.warnings()).as("a note is no warning").isEmpty();
        assertThat(pinned.notes()).containsExactly(note);
        assertThat(pinned.warnings()).isEmpty();
        assertThat(monitoring.notes()).isEmpty();
        assertThat(on81.notes()).isEmpty();
        assertThat(on9x.notes()).isEmpty();
        assertThat(on9x.warnings()).hasSize(1);
    }

    @Test
    void theLogNoteIsLeftOutWhenTheCliOnlyVersionSupportsLogDiagnosis() {
        CapabilityMatrix withLogs = new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
                Map.of(Capability.PROCESS_DIAGNOSIS, SupportState.SUPPORTED,
                    Capability.LOG_DIAGNOSIS, SupportState.SUPPORTED),
                Map.of(Capability.PROCESS_DIAGNOSIS, CLI, Capability.LOG_DIAGNOSIS, CLI), true)),
            all(SupportState.SUPPORTED));

        assertThat(gate(withLogs).admit(V6_1, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST)
            .notes()).isEmpty();
    }

    // --- T023: evaluate, the non-throwing admission of deploy_release (research D-11) ---------

    @Test
    void evaluateReturnsTheRefusalAdmitWouldThrowWithTheVersionAndTheCause() {
        detection.answer("9.1.0");

        Evaluation evaluation = gate().evaluate(AUTO, Capability.DEPLOYMENT, Kind.WRITE,
            CLI_REST);

        assertThat(evaluation.version()).contains(version("9.1.0"));
        assertThat(evaluation.versionName()).isEqualTo("INUBIT 9.1.0");
        assertThat(evaluation.warnings()).isEmpty();
        assertThat(evaluation.refusal()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).isEqualTo(refusal(() -> gate().admit(AUTO,
                Capability.DEPLOYMENT, Kind.WRITE, CLI_REST)).message());
        });
        assertThat(evaluation.cause()).contains(
            "INUBIT 9.1.0 on dev/node1 does not support deployment yet");
        assertThat(detection.calls).isEqualTo(1);
        assertThat(appender.list).hasSize(2).allSatisfy(event -> assertThat(
            event.getFormattedMessage()).contains("REFUSED", "UNSUPPORTED_VERSION"));
    }

    @Test
    void evaluateAdmitsWithTheWarningsOfTheClientCheck() {
        versions.clientProbed(PINNED, new ProbedClient(ClientKey.NONE,
            client("8.1.16", "4.0.1", "8.1.16")));

        Evaluation evaluation = gate().evaluate(PINNED, Capability.DEPLOYMENT, Kind.WRITE,
            CLI_REST);

        assertThat(evaluation.refusal()).isEmpty();
        assertThat(evaluation.cause()).isEmpty();
        assertThat(evaluation.version()).contains(version("8.1.17"));
        assertThat(evaluation.warnings()).containsExactly("StartCLI client 8.1.16 on prod/node1"
            + " differs from INUBIT 8.1.17 (mismatch);"
            + " StartCLI-backed tools still run, but their results may be unreliable");
    }

    @Test
    void evaluateGivesNoVersionForAV9xLineOrAFailedDetection() {
        detection.fail(detectionError(ErrorCode.AUTH_FAILED));

        Evaluation line = gate().evaluate(V9_X, Capability.DEPLOYMENT, Kind.WRITE, CLI_REST);
        Evaluation unknown = gate().evaluate(AUTO, Capability.DEPLOYMENT, Kind.WRITE, CLI_REST);

        assertThat(line.version()).isEmpty();
        assertThat(line.versionName()).isEqualTo("INUBIT 9.x (versionLine V9_X)");
        assertThat(line.refusal()).isPresent();
        assertThat(unknown.version()).isEmpty();
        assertThat(unknown.versionName()).isEqualTo("INUBIT version unknown");
        assertThat(unknown.cause()).contains("Deployment refused: the INUBIT version of"
            + " dev/node1 is unknown (AUTH_FAILED at 2026-10-18T08:00:00Z)");
    }

    @Test
    void anEvaluationCarriesTheCauseOfItsRefusalItselfNotParsedFromTheMessage() {
        ToolError refusal = ToolError.of(ErrorCode.UNSUPPORTED_VERSION, "any wording at all",
            "likely cause", "next step");

        Evaluation refused = new Evaluation(Optional.empty(), "INUBIT version unknown",
            List.of(), Optional.of(refusal), Optional.of("the cause"));

        assertThat(refused.cause()).contains("the cause");
        assertThatThrownBy(() -> new Evaluation(Optional.empty(), "INUBIT version unknown",
            List.of(), Optional.of(refusal), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Evaluation(Optional.empty(), "INUBIT version unknown",
            List.of(), Optional.empty(), Optional.of("the cause")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** FR-016: refusals and warnings never carry the URL or credentials of a detection error. */
    @Test
    void noRefusalOrWarningContainsTheBaseUrlOrCredentials() {
        detection.fail(detectionError(ErrorCode.AUTH_FAILED));
        ToolError unknown = refusal(() -> gate().admit(AUTO, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));
        Admission unknownRead = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        versions.clientProbeFailed(AUTO, ClientKey.NONE, ErrorCode.CLI_UNAVAILABLE);
        Admission unknownCli = gate().admit(AUTO, Capability.INVENTORY, Kind.READ, CLI);
        clock.advance(Duration.ofMinutes(2));
        detection.answer("9.1.0");
        versions.clientProbed(AUTO, new ProbedClient(ClientKey.NONE,
            client("8.1.17", "4.0.1", "8.1.17")));
        ToolError unsupported = refusal(() -> gate().admit(AUTO, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));
        ToolError incompatible = refusal(() -> gate().admit(AUTO, Capability.INVENTORY,
            Kind.READ, CLI));
        Admission unverified = gate().admit(AUTO, Capability.INVENTORY, Kind.READ, REST);

        List<String> texts = Stream.of(
                Stream.of(unknown, unsupported, incompatible).flatMap(error -> Stream.of(
                    error.message(), error.likelyCause(), error.nextStep())),
                Stream.of(unknownRead, unknownCli, unverified)
                    .flatMap(admission -> admission.warnings().stream()),
                appender.list.stream().map(ILoggingEvent::getFormattedMessage))
            .flatMap(s -> s)
            .toList();
        assertThat(texts).hasSizeGreaterThan(10).allSatisfy(text -> assertThat(text)
            .doesNotContain(BASE_URL, "inubit-dev.example.com", USERNAME, PASSWORD,
                "Set versionLine: V8_1"));
    }

    /**
     * T030 (feature 002, FR-007): the refusals and warnings name the node level with the
     * profile's terminology, never with the default word "node".
     */
    @Test
    void refusalsAndWarningsUseTheProfilesTerminology() {
        Terminology terms = new Terminology("Umgebung", "Umgebungen", "Knoten", "Knoten");
        VersionGate gate = gate(CapabilityMatrix.production(), terms);
        detection.fail(detectionError(ErrorCode.AUTH_FAILED));
        ToolError unknown = refusal(() -> gate.admit(AUTO, Capability.PROCESS_CONTROL,
            Kind.WRITE, CLI_REST));
        Admission unknownRead = gate.admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);
        versions.clientProbeFailed(AUTO, ClientKey.NONE, ErrorCode.CLI_UNAVAILABLE);
        Admission unknownCli = gate.admit(AUTO, Capability.INVENTORY, Kind.READ, CLI);
        clock.advance(Duration.ofMinutes(2));
        detection.answer("9.1.0");
        versions.clientProbed(AUTO, new ProbedClient(ClientKey.NONE,
            client("8.1.17", "4.0.1", "8.1.17")));
        ToolError unsupported = refusal(() -> gate.admit(AUTO, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));
        ToolError incompatible = refusal(() -> gate.admit(AUTO, Capability.INVENTORY,
            Kind.READ, CLI));
        Admission unverified = gate.admit(AUTO, Capability.INVENTORY, Kind.READ, REST);
        versions.observed(PINNED, version("8.2.0"));
        ToolError contradiction = refusal(() -> gate(new CapabilityMatrix(List.of(),
            all(SupportState.SUPPORTED)), terms).admit(PINNED, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));

        assertThat(unsupported.likelyCause())
            .isEqualTo("The Knoten runs a version for which no verified write access exists");
        assertThat(unsupported.nextStep()).isEqualTo("Use another Knoten whose INUBIT version"
            + " supports development writes (list_nodes shows the usable capabilities per"
            + " Knoten), or wait for a version of this server that supports that INUBIT version");
        assertThat(unknown.nextStep()).contains("the version the Knoten actually runs");
        assertThat(incompatible.likelyCause()).isEqualTo(
            "The local StartCLI client does not support the INUBIT version of the Knoten");
        assertThat(contradiction.likelyCause()).contains("the version the Knoten runs");
        List<String> texts = Stream.concat(
                Stream.of(unknown, unsupported, incompatible, contradiction).flatMap(error ->
                    Stream.of(error.message(), error.likelyCause(), error.nextStep())),
                Stream.of(unknownRead, unknownCli, unverified)
                    .flatMap(admission -> admission.warnings().stream()))
            .toList();
        assertThat(texts).hasSize(16).allSatisfy(text -> assertThat(text)
            .doesNotContainPattern("(?i)\\b(groups?|nodes?)\\b"));
    }

    // --- feature 020, T003: reading Queue Manager entries (FR-006, FR-019 fallback, D-6) ----

    @Test
    void queueEntryReadIsAdmittedOn81() {
        Admission admission = gate().admit("read_queue_entry", PINNED,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST);

        assertThat(admission.warnings()).isEmpty();
        assertThat(admission.version()).contains(version("8.1.17"));
        assertThat(appender.list).isEmpty();
    }

    /** T038: 9.0 is admitted since its recorded error entry. */
    @Test
    void queueEntryReadIsAdmittedOn90() {
        detection.answer("9.0.10");

        Admission detected = gate().admit("read_queue_entry", AUTO,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST);
        Admission line = gate().admit("read_queue_entry", V9_0,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST);

        assertThat(detected.warnings()).isEmpty();
        assertThat(detected.version()).contains(version("9.0.10"));
        assertThat(line.warnings()).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    /**
     * The FR-019 fallback mechanism: a refusal uses the matrix note of the entry (the production
     * declaration has no note since T038; a test declaration with the former 9.0 note).
     */
    @Test
    void queueEntryReadIsRefusedWithTheNoteOfItsEntry() {
        detection.answer("9.0.10");
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        states.put(Capability.QUEUE_ENTRY_READ, SupportState.UNSUPPORTED);
        CapabilityMatrix noted = new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), Map.of(
                Capability.QUEUE_ENTRY_READ, SupportState.SUPPORTED)),
            new CapabilityMatrix.Entry(new VersionRange(9, 0, 9, 0), states, Map.of(), false,
                Map.of(Capability.QUEUE_ENTRY_READ, "not yet verified"))), Map.of());

        ToolError detected = refusal(() -> gate(noted).admit("read_queue_entry", AUTO,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST));
        ToolError line = refusal(() -> gate(noted).admit("read_queue_entry", V9_0,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST));

        assertThat(detected.message()).isEqualTo("read_queue_entry: INUBIT 9.0.10 on dev/node1:"
            + " reading Queue Manager entries is not yet verified for INUBIT 9.0; nothing was"
            + " sent");
        assertThat(line.message()).isEqualTo("read_queue_entry: INUBIT 9.0 on prod/node4:"
            + " reading Queue Manager entries is not yet verified for INUBIT 9.0; nothing was"
            + " sent");
        assertThat(detected.nextStep()).isEqualTo("Use another node whose INUBIT version"
            + " supports reading Queue Manager entries (list_nodes shows the usable capabilities"
            + " per node), or wait for a version of this server that supports that INUBIT"
            + " version");
        assertThat(detected.node()).contains(AUTO);
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
        assertThat(appender.list).hasSize(2).allSatisfy(event -> assertThat(
            event.getFormattedMessage()).contains("QUEUE_ENTRY_READ", "REFUSED",
            "UNSUPPORTED_VERSION", "not yet verified"));
        assertNoLeak(detected, line);
    }

    @Test
    void queueEntryReadOn61On91AndOnAMajorOnlyLineIsRefusedNamingTheSupportedLines() {
        detection.answer("9.1.0");

        ToolError v91 = refusal(() -> gate().admit("read_queue_entry", AUTO,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST));
        ToolError v61 = refusal(() -> gate().admit("read_queue_entry", V6_1,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST));
        ToolError v9x = refusal(() -> gate().admit("read_queue_entry", V9_X,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST));

        assertThat(v91.message()).isEqualTo("read_queue_entry: INUBIT 9.1.0 on dev/node1 does"
            + " not support reading Queue Manager entries yet; supported: 8.1, 9.0; nothing was"
            + " sent");
        assertThat(v61.message()).isEqualTo("read_queue_entry: INUBIT 6.1 (versionLine V6_1) on"
            + " old/node1 does not support reading Queue Manager entries; supported: 8.1, 9.0;"
            + " use the Queue Manager in the Workbench; nothing was sent");
        assertThat(v61.nextStep()).isEqualTo("Use the Queue Manager in the Workbench");
        assertThat(v9x.message()).isEqualTo("read_queue_entry: INUBIT 9.x (versionLine V9_X) on"
            + " prod/node3 does not support reading Queue Manager entries yet; supported: 8.1,"
            + " 9.0; nothing was sent");
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
        assertThat(appender.list).hasSize(3);
        assertNoLeak(v91, v61, v9x);
    }

    // --- feature 021, T009: downloading the server logs (FR-002, FR-017, D-6) ----------------

    /**
     * Until its recording a log is refused with the matrix note: the web service log on 8.1 and
     * 9.0, the trace log on 9.0; T033: the recorded 8.1 trace log is admitted without warning.
     */
    @Test
    void aServerLogReadIsNotYetVerifiedUntilItsRecording() {
        Admission trace81 = gate().admit("download_server_log", PINNED,
            Capability.TRACE_LOG_READ, Kind.READ, CLI);
        assertThat(trace81.warnings()).isEmpty();
        assertThat(trace81.version()).contains(version("8.1.17"));

        ToolError webservice81 = refusal(() -> gate().admit("download_server_log", PINNED,
            Capability.WEBSERVICE_LOG_READ, Kind.READ, CLI));
        assertThat(webservice81.message()).isEqualTo("download_server_log: INUBIT 8.1.17 on"
            + " prod/node1: downloading the web service log is not yet verified for INUBIT 8.1;"
            + " nothing was sent");
        assertNoLeak(webservice81);
        for (Capability capability : List.of(Capability.TRACE_LOG_READ,
                Capability.WEBSERVICE_LOG_READ)) {
            ToolError v90 = refusal(() -> gate().admit("download_server_log", V9_0,
                capability, Kind.READ, CLI));

            assertThat(v90.message()).isEqualTo("download_server_log: INUBIT 9.0 on prod/node4: "
                + capability.label() + " is not yet verified for INUBIT 9.0; nothing was sent");
            assertNoLeak(v90);
        }
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
        assertThat(appender.list).hasSize(3);
    }

    @Test
    void aServerLogReadOn61IsRefusedNamingTheVersionTheSupportedLinesAndTheWorkbench() {
        detection.answer("6.1.3");
        for (Capability capability : List.of(Capability.TRACE_LOG_READ,
                Capability.WEBSERVICE_LOG_READ)) {
            ToolError v61 = refusal(() -> gate().admit("download_server_log", AUTO,
                capability, Kind.READ, CLI));
            ToolError v61Line = refusal(() -> gate().admit("download_server_log", V6_1,
                capability, Kind.READ, CLI));

            assertThat(v61.message()).isEqualTo("download_server_log: INUBIT 6.1.3 on dev/node1"
                + " does not support " + capability.label() + "; supported: 8.1, 9.0; download"
                + " the log in the Workbench (Administration, Server Log); nothing was sent");
            assertThat(v61.nextStep())
                .isEqualTo("Download the log in the Workbench (Administration, Server Log)");
            assertThat(v61Line.message()).isEqualTo("download_server_log: INUBIT 6.1 (versionLine"
                + " V6_1) on old/node1 does not support " + capability.label() + "; supported:"
                + " 8.1, 9.0; download the log in the Workbench (Administration, Server Log);"
                + " nothing was sent");
            assertNoLeak(v61, v61Line);
        }
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
    }

    @Test
    void aServerLogReadOn91IsRefusedNamingTheVersionAndTheSupportedLines() {
        detection.answer("9.1.0");
        for (Capability capability : List.of(Capability.TRACE_LOG_READ,
                Capability.WEBSERVICE_LOG_READ)) {
            ToolError v91 = refusal(() -> gate().admit("download_server_log", AUTO,
                capability, Kind.READ, CLI));

            assertThat(v91.message()).isEqualTo("download_server_log: INUBIT 9.1.0 on dev/node1"
                + " does not support " + capability.label() + " yet; supported: 8.1, 9.0;"
                + " nothing was sent");
            assertNoLeak(v91);
        }
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
    }

    // --- feature 020, T004: an undetectable version fails read_queue_entry (US3-4, D-6) -------

    /** A detection error as the factory reports it, without URL or credentials. */
    private static ToolErrorException plainDetectionError(ErrorCode code) {
        return new ToolErrorException(ToolError.of(code, "Detection of dev/node1 failed: " + code,
            "cause of " + code, "next step of " + code).withNode(AUTO));
    }

    private ToolErrorException admitQueueEntryReadFails() {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> gate().admitWithDetection("read_queue_entry", AUTO,
                Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST));
        assertThat(e).as("failed").isNotNull();
        return e;
    }

    @Test
    void aFailedDetectionOfThisCallIsPassedThroughWithItsOwnCodeAndMessage() {
        for (ErrorCode code : List.of(ErrorCode.UNREACHABLE, ErrorCode.TIMEOUT,
            ErrorCode.TLS_ERROR, ErrorCode.AUTH_FAILED, ErrorCode.UNEXPECTED_RESPONSE)) {
            ToolErrorException error = plainDetectionError(code);
            if (code == ErrorCode.TLS_ERROR) {
                detection.failUnreported(error);
            } else {
                detection.fail(error);
            }

            assertThat(admitQueueEntryReadFails()).as(code.name()).isSameAs(error);
            if (code != ErrorCode.TLS_ERROR) {
                assertThat(versions.state(AUTO).detectionFailure()).as("recorded as today")
                    .hasValueSatisfying(failure -> assertThat(failure.value().code())
                        .isEqualTo(code));
            }
            clock.advance(Duration.ofSeconds(60));
        }

        assertThat(detection.calls).isEqualTo(5);
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
        assertThat(appender.list).as("not a version refusal").isEmpty();
    }

    @Test
    void withinTheBackOffTheRecordedCodeIsReplayedWithoutASecondDetection() {
        detection.fail(plainDetectionError(ErrorCode.UNREACHABLE));
        admitQueueEntryReadFails();
        clock.advance(Duration.ofSeconds(59));

        ToolError replayed = admitQueueEntryReadFails().error();

        assertThat(replayed.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(replayed.message()).isEqualTo("The INUBIT version of dev/node1 could not be"
            + " detected (UNREACHABLE at 2026-10-18T08:00:00Z); read_queue_entry needs it");
        assertThat(replayed.nextStep())
            .isEqualTo("Fix the cause and retry in a minute, or set versionLine of dev/node1");
        assertThat(replayed.node()).contains(AUTO);
        assertThat(detection.calls).as("no second detection within the back-off").isEqualTo(1);
        assertThat(probe.calls).as("no StartCLI run").isEmpty();
        assertNoLeak(replayed);

        clock.advance(Duration.ofSeconds(1));
        detection.answer("8.1.17");
        Admission afterBackOff = gate().admitWithDetection("read_queue_entry", AUTO,
            Capability.QUEUE_ENTRY_READ, Kind.READ, REST);

        assertThat(afterBackOff.version()).contains(version("8.1.17"));
        assertThat(detection.calls).isEqualTo(2);
    }

    /** A failure another tool's admission recorded (and judged as unknown) is replayed, too. */
    @Test
    void aFailureRecordedByAnotherAdmissionIsReplayed() {
        detection.fail(detectionError(ErrorCode.AUTH_FAILED));
        Admission other = gate().admit(AUTO, Capability.PROCESS_DIAGNOSIS, Kind.READ, REST);

        ToolError replayed = admitQueueEntryReadFails().error();

        assertThat(other.warnings()).containsExactly("the INUBIT version of dev/node1 is unknown"
            + " (AUTH_FAILED at 2026-10-18T08:00:00Z); results are unverified");
        assertThat(replayed.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(replayed.message()).isEqualTo("The INUBIT version of dev/node1 could not be"
            + " detected (AUTH_FAILED at 2026-10-18T08:00:00Z); read_queue_entry needs it");
        assertThat(Stream.of(replayed.message(), replayed.likelyCause(), replayed.nextStep()))
            .allSatisfy(text -> assertThat(text).doesNotContain(BASE_URL, USERNAME, PASSWORD));
        assertThat(detection.calls).isEqualTo(1);
        assertNoLeak(replayed);
    }

    @Test
    void aKnownVersionIsAdmittedAndAnUnsupportedOneRefusedAsByAdmit() {
        detection.answer("8.1.17");

        Admission detected = gate().admitWithDetection("read_queue_entry", AUTO,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST);
        Admission cached = gate().admitWithDetection("read_queue_entry", AUTO,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST);
        Admission pinned = gate().admitWithDetection("read_queue_entry", PINNED,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST);
        ToolError v61 = refusal(() -> gate().admitWithDetection("read_queue_entry", V6_1,
            Capability.QUEUE_ENTRY_READ, Kind.READ, CLI_REST));

        assertThat(detected.version()).contains(version("8.1.17"));
        assertThat(detected.warnings()).isEmpty();
        assertThat(cached.version()).contains(version("8.1.17"));
        assertThat(pinned.version()).contains(version("8.1.17"));
        assertThat(detection.calls).as("one detection, then the cached version").isEqualTo(1);
        assertThat(v61.message()).startsWith("read_queue_entry: INUBIT 6.1 (versionLine V6_1)"
            + " on old/node1 does not support reading Queue Manager entries");
        assertNoLeak(v61);
    }

    private void assertNoLeak(ToolError... errors) {
        for (ToolError error : errors) {
            TestSecrets.assertNoLeak(error.message());
            TestSecrets.assertNoLeak(error.likelyCause());
            TestSecrets.assertNoLeak(error.nextStep());
        }
        appender.list.forEach(event -> TestSecrets.assertNoLeak(event.getFormattedMessage()));
    }

    /**
     * A client probe that answers with the last scripted outcome and records the timeout of each
     * call; each call is one StartCLI run.
     */
    private final class ScriptedProbe implements ClientVersionPort {

        private Supplier<ClientVersion> outcome = () -> {
            throw new AssertionError("no client probe scripted");
        };
        private final List<Duration> calls = new ArrayList<>();
        /** Feature 017: the version each call chose its client by, and that client's key. */
        private final List<Optional<InubitVersion>> probedVersions = new ArrayList<>();
        private Function<Optional<InubitVersion>, ClientKey> keys = version -> ClientKey.NONE;

        void answer(ClientVersion client) {
            outcome = () -> client;
        }

        void fail(ErrorCode code) {
            outcome = () -> {
                throw new ToolErrorException(ToolError.of(code, "probe failed", "c", "s"));
            };
        }

        @Override
        public ProbedClient probe(NodeId node, Optional<InubitVersion> version,
            Duration timeout) {
            calls.add(timeout);
            probedVersions.add(version);
            return new ProbedClient(keys.apply(version), outcome.get());
        }
    }

    /**
     * A detection that answers with the last scripted outcome, reports to {@link NodeVersions}
     * like {@code AdapterGatewayFactory.detect}, and counts its calls; each call is one
     * {@code /system/info} request, except for an unusable trust store ({@link #failUnreported}).
     */
    private final class ScriptedDetection implements GatewayFactory {

        private Supplier<InubitVersion> outcome = () -> {
            throw new AssertionError("no detection scripted");
        };
        private int calls;

        void answer(String raw) {
            outcome = () -> {
                InubitVersion detected = version(raw);
                versions.detected(AUTO, detected);
                return detected;
            };
        }

        void fail(ToolErrorException error) {
            outcome = () -> {
                versions.detectionFailed(AUTO, error.error().code());
                throw error;
            };
        }

        /** As {@link #fail}, for a detection request answered with HTTP 404 (FR-003). */
        void failEndpointMissing(ToolErrorException error) {
            outcome = () -> {
                versions.detectionFailed(AUTO, error.error().code(), true);
                throw error;
            };
        }

        /** As a trust store that cannot be used: no request is sent and nothing is reported. */
        void failUnreported(ToolErrorException error) {
            outcome = () -> {
                throw error;
            };
        }

        @Override
        public InubitVersion detect(NodeId server) {
            assertThat(server).as("only the AUTO node is ever detected").isEqualTo(AUTO);
            calls++;
            return outcome.get();
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("the gate only detects");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("the gate only detects");
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError("the gate only detects");
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError("the gate only detects");
        }

        @Override
        public InventoryPort inventory(NodeId server) {
            throw new AssertionError("the gate only detects");
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("the gate only detects");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            throw new AssertionError("the gate only detects");
        }
    }
}
