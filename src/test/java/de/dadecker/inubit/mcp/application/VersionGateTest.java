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
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            V9_X, VersionSetting.V9_X)::get,
        node -> TTL);
    private final ScriptedDetection detection = new ScriptedDetection();
    private final ScriptedProbe probe = new ScriptedProbe();
    /** The nodes with a StartCLI installation; only they are probed (none by default). */
    private final Set<NodeId> withClient = new HashSet<>();

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
        return new VersionGate(versions, detection, matrix, probe, withClient::contains,
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
    void aReadOn90IsAdmittedWithTheUnverifiedWarning() {
        detection.answer("9.0.7");

        Admission admission = gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);

        assertThat(admission.warnings()).containsExactly(
            "INUBIT 9.0.7 on dev/node1 is not verified for diagnosis; results may be incomplete");
    }

    @Test
    void aWriteOn90IsRefusedAfterOnlyTheDetectionRequest() {
        detection.answer("9.0.7");

        ToolError error = refusal(() -> gate().admit(AUTO, Capability.DEVELOPMENT_WRITE,
            Kind.WRITE, CLI_REST));

        assertThat(error.message()).isEqualTo("INUBIT 9.0.7 on dev/node1 does not support"
            + " development writes yet; nothing was sent");
        assertThat(error.node()).contains(AUTO);
        assertThat(error.nextStep()).startsWith("Use another node").doesNotContain("versionLine");
        assertThat(detection.calls).isEqualTo(1);
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(
            event.getFormattedMessage()).contains("dev/node1", "DEVELOPMENT_WRITE", "REFUSED",
            "UNSUPPORTED_VERSION", "INUBIT 9.0.7"));
    }

    @Test
    void withTheToolNameTheRefusalNamesTheToolAndAnAdmissionIsUnchanged() {
        // T009 (contracts/mcp-tools-delta.md): the message of a refusal names the tool
        detection.answer("9.0.7");

        ToolError error = refusal(() -> gate().admit("import_artifacts", AUTO,
            Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST));
        Admission read = gate().admit("find_processes", AUTO, Capability.DIAGNOSIS, Kind.READ,
            REST);

        assertThat(error.message()).isEqualTo("import_artifacts: INUBIT 9.0.7 on dev/node1 does"
            + " not support development writes yet; nothing was sent");
        assertThat(error.node()).contains(AUTO);
        assertThat(error.nextStep()).startsWith("Use another node");
        assertThat(read.warnings()).containsExactly(
            "INUBIT 9.0.7 on dev/node1 is not verified for diagnosis; results may be incomplete");
    }

    @Test
    void anUnknownVersionAfterAFailedDetectionRefusesWritesAndWarnsReads() {
        detection.fail(detectionError(ErrorCode.AUTH_FAILED));

        ToolError error = refusal(() -> gate().admit(AUTO, Capability.PROCESS_CONTROL,
            Kind.WRITE, CLI_REST));
        Admission read = gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);

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
        gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);
        clock.advance(TTL.plusSeconds(1));

        gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);
        assertThat(detection.calls).as("reads use the cached version").isEqualTo(1);

        gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        assertThat(detection.calls).isEqualTo(2);
    }

    @Test
    void aStaleVersionIsDetectedAgainBeforeAWrite() {
        detection.answer("8.1.17");
        gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        versions.stale(AUTO);

        gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);
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
        Admission read = gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);

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
        detection.answer("9.0.7");
        versions.clientProbed(AUTO, client("8.1.17", "4.0.1", "8.1.17"));

        Admission rest = gate().admit(AUTO, Capability.INVENTORY, Kind.READ, REST);
        ToolError cli = refusal(() -> gate().admit(AUTO, Capability.INVENTORY, Kind.READ, CLI));

        assertThat(rest.warnings()).containsExactly(
            "INUBIT 9.0.7 on dev/node1 is not verified for inventory; results may be incomplete");
        assertThat(cli.message()).contains("8.1.17", "4.0.1 to 8.1.17", "INUBIT 9.0.7",
            "dev/node1", "inventory");
        assertThat(cli.nextStep()).isEqualTo(
            "Install a StartCLI client on the host of this server that supports INUBIT 9.0");
        assertThat(detection.calls).isEqualTo(1);
    }

    @Test
    void aClientWithAnotherPatchGivesAMismatchWarning() {
        detection.answer("8.1.18");
        versions.clientProbed(AUTO, client("8.1.17", "4.0.1", "8.1.17"));

        Admission cli = gate().admit(AUTO, Capability.DEVELOPMENT_WRITE, Kind.WRITE, CLI_REST);
        Admission rest = gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);

        assertThat(cli.warnings()).containsExactly("StartCLI client 8.1.17 on dev/node1"
            + " differs from INUBIT 8.1.18 (mismatch);"
            + " StartCLI-backed tools still run, but their results may be unreliable");
        assertThat(rest.warnings()).isEmpty();
    }

    @Test
    void aClientRangeThatCannotBeEvaluatedGivesAWarningNamingTheCause() {
        detection.answer("8.1.17");
        versions.clientProbeFailed(AUTO, ErrorCode.TIMEOUT);

        Admission admission = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        assertThat(admission.warnings()).containsExactly("The StartCLI client probe of dev/node1"
            + " failed (TIMEOUT at 2026-10-18T08:00:00Z); the INUBIT range of the client cannot"
            + " be checked, StartCLI results may be unreliable");
    }

    @Test
    void aReadDeclaredUnsupportedByATestMatrixIsRefused() {
        Map<Capability, SupportState> states = all(SupportState.SUPPORTED);
        states.put(Capability.DIAGNOSIS, SupportState.UNSUPPORTED);
        CapabilityMatrix matrix = new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), states)),
            all(SupportState.UNSUPPORTED));
        detection.answer("8.1.17");

        ToolError error = refusal(() -> gate(matrix).admit(AUTO, Capability.DIAGNOSIS, Kind.READ,
            REST));

        assertThat(error.message()).isEqualTo(
            "INUBIT 8.1.17 on dev/node1 does not support diagnosis yet; nothing was sent");
        assertThat(appender.list).hasSize(1);
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
        assertThat(gate().admit(PINNED, Capability.DIAGNOSIS, Kind.READ, REST).warnings())
            .isEmpty();

        assertThat(detection.calls).isZero();
    }

    @Test
    void aV9xLineRefusesWritesAndWarnsReadsWithoutDetection() {
        ToolError error = refusal(() -> gate().admit(V9_X, Capability.DEPLOYMENT, Kind.WRITE,
            CLI_REST));
        Admission read = gate().admit(V9_X, Capability.DIAGNOSIS, Kind.READ, REST);

        assertThat(error.message()).isEqualTo("INUBIT 9.x (versionLine V9_X) on prod/node3 does"
            + " not support deployment yet; nothing was sent");
        assertThat(read.warnings()).containsExactly("INUBIT 9.x (versionLine V9_X) on prod/node3"
            + " is not verified for diagnosis; results may be incomplete");
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

        Admission rest = gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);
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
        assertThat(versions.state(AUTO).client()).hasValueSatisfying(known ->
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
        assertThat(error.nextStep()).isEqualTo(
            "Install a StartCLI client on the host of this server that supports INUBIT 9.0");
        assertThat(probe.calls).hasSize(1);
        assertThat(detection.calls).isEqualTo(1);
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
        assertThat(versions.state(AUTO).clientFailure()).isEmpty();
    }

    @Test
    void aNodeWithoutStartCliInstallationIsNeverProbed() {
        Admission admission = gate().admit(PINNED, Capability.DEVELOPMENT_WRITE, Kind.WRITE,
            CLI_REST);

        assertThat(admission.warnings()).isEmpty();
        assertThat(probe.calls).isEmpty();
        assertThat(versions.state(PINNED).clientFailure()).isEmpty();
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
        assertThat(versions.state(PINNED).clientFailure()).as("no back-off").isEmpty();
    }

    // --- T023: evaluate, the non-throwing admission of deploy_release (research D-11) ---------

    @Test
    void evaluateReturnsTheRefusalAdmitWouldThrowWithTheVersionAndTheCause() {
        detection.answer("9.0.7");

        Evaluation evaluation = gate().evaluate(AUTO, Capability.DEPLOYMENT, Kind.WRITE,
            CLI_REST);

        assertThat(evaluation.version()).contains(version("9.0.7"));
        assertThat(evaluation.versionName()).isEqualTo("INUBIT 9.0.7");
        assertThat(evaluation.warnings()).isEmpty();
        assertThat(evaluation.refusal()).get().satisfies(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).isEqualTo(refusal(() -> gate().admit(AUTO,
                Capability.DEPLOYMENT, Kind.WRITE, CLI_REST)).message());
        });
        assertThat(evaluation.cause()).contains(
            "INUBIT 9.0.7 on dev/node1 does not support deployment yet");
        assertThat(detection.calls).isEqualTo(1);
        assertThat(appender.list).hasSize(2).allSatisfy(event -> assertThat(
            event.getFormattedMessage()).contains("REFUSED", "UNSUPPORTED_VERSION"));
    }

    @Test
    void evaluateAdmitsWithTheWarningsOfTheClientCheck() {
        versions.clientProbed(PINNED, client("8.1.16", "4.0.1", "8.1.16"));

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
        Admission unknownRead = gate().admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);
        versions.clientProbeFailed(AUTO, ErrorCode.CLI_UNAVAILABLE);
        Admission unknownCli = gate().admit(AUTO, Capability.INVENTORY, Kind.READ, CLI);
        clock.advance(Duration.ofMinutes(2));
        detection.answer("9.0.7");
        versions.clientProbed(AUTO, client("8.1.17", "4.0.1", "8.1.17"));
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
        Admission unknownRead = gate.admit(AUTO, Capability.DIAGNOSIS, Kind.READ, REST);
        versions.clientProbeFailed(AUTO, ErrorCode.CLI_UNAVAILABLE);
        Admission unknownCli = gate.admit(AUTO, Capability.INVENTORY, Kind.READ, CLI);
        clock.advance(Duration.ofMinutes(2));
        detection.answer("9.0.7");
        versions.clientProbed(AUTO, client("8.1.17", "4.0.1", "8.1.17"));
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

    /**
     * A client probe that answers with the last scripted outcome and records the timeout of each
     * call; each call is one StartCLI run.
     */
    private final class ScriptedProbe implements ClientVersionPort {

        private Supplier<ClientVersion> outcome = () -> {
            throw new AssertionError("no client probe scripted");
        };
        private final List<Duration> calls = new ArrayList<>();

        void answer(ClientVersion client) {
            outcome = () -> client;
        }

        void fail(ErrorCode code) {
            outcome = () -> {
                throw new ToolErrorException(ToolError.of(code, "probe failed", "c", "s"));
            };
        }

        @Override
        public ClientVersion probe(NodeId node, Duration timeout) {
            calls.add(timeout);
            return outcome.get();
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
