package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.VersionGate.Admission;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Capability.Kind;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
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
import java.util.EnumMap;
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
 * through the {@link NodeVersions} API (the probe is wired in T019).
 */
class VersionGateTest {

    private static final NodeId AUTO = NodeId.parse("dev/node1");
    private static final NodeId PINNED = NodeId.parse("prod/node1");
    private static final NodeId V9_X = NodeId.parse("prod/node3");
    private static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

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
        return new VersionGate(versions, detection, CapabilityMatrix.production());
    }

    private VersionGate gate(CapabilityMatrix matrix) {
        return new VersionGate(versions, detection, matrix);
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
        assertThat(error.nextStep()).startsWith("Use a node").doesNotContain("versionLine");
        assertThat(detection.calls).isEqualTo(1);
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(
            event.getFormattedMessage()).contains("dev/node1", "DEVELOPMENT_WRITE", "REFUSED",
            "UNSUPPORTED_VERSION", "INUBIT 9.0.7"));
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

        assertThat(cli.warnings()).containsExactly("StartCLI client 8.1.17 and INUBIT 8.1.18 on"
            + " dev/node1 differ; StartCLI results may be unreliable");
        assertThat(rest.warnings()).isEmpty();
    }

    @Test
    void aClientRangeThatCannotBeEvaluatedGivesAWarningNamingTheCause() {
        detection.answer("8.1.17");
        versions.clientProbeFailed(AUTO, ErrorCode.TIMEOUT);

        Admission admission = gate().admit(AUTO, Capability.WORKSPACE_EXPORT, Kind.READ, CLI);

        assertThat(admission.warnings()).containsExactly("The StartCLI client of dev/node1 could"
            + " not be checked (TIMEOUT at 2026-10-18T08:00:00Z); StartCLI results may be"
            + " unreliable");
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
