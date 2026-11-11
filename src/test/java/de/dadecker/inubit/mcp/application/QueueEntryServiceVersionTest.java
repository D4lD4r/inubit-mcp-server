package de.dadecker.inubit.mcp.application;

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
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * US3 end to end (feature 020, FR-006, US3-1…5, T031): {@code read_queue_entry} through the real
 * {@link VersionGate} (detection scripted per node) and a real
 * {@link de.dadecker.inubit.mcp.adapter.cli.queue.CliQueueEntryAdapter} per node on one profile
 * ({@link QueueEntryVersionProfile}: a client installation and JDK per version). The helper is a
 * {@link FakeProcessLauncher} per node that records the launched JDK, class path and request.
 * Every case runs against {@link CapabilityMatrix#production()}, which supports 9.0 since its
 * recording (tasks.md T038). No server is contacted.
 */
@Timeout(60)
class QueueEntryServiceVersionTest {

    private static final NodeId N81 = NodeId.parse("test/n81");
    private static final NodeId N90 = NodeId.parse("test/n90");
    private static final NodeId N61 = NodeId.parse("test/n61");
    private static final NodeId N91 = NodeId.parse("test/n91");
    private static final NodeId UNDETECTED = NodeId.parse("test/auto");
    private static final String PID = "1234";
    private static final String FILE = "Module-0001(4711)@1234@0a1b2c3d";
    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    /** The shipped matrix. */
    static final CapabilityMatrix SHIPPED = CapabilityMatrix.production();

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(NOW);
    private final Map<NodeId, VersionSetting> settings = Map.of(N81, VersionSetting.AUTO,
        N90, VersionSetting.AUTO, N61, VersionSetting.V6_1, N91, VersionSetting.AUTO,
        UNDETECTED, VersionSetting.AUTO);
    private final NodeVersions versions = new NodeVersions(clock, settings::get,
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(N81, "8.1.17").answer(N90, "9.0.10").answer(N91, "9.1.0");
    private final SecretScrubber scrubber = new SecretScrubber();
    private final Map<NodeId, FakeProcessLauncher> launchers = new HashMap<>();
    private final List<NodeId> lookups = new ArrayList<>();
    private final List<ToolError> errors = new ArrayList<>();
    private QueueEntryVersionProfile profile;
    private Map<NodeId, TestNodeConfig> nodes;

    @BeforeEach
    void setUp() throws IOException {
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        profile = new QueueEntryVersionProfile(root);
        nodes = new HashMap<>();
        settings.forEach((node, line) -> nodes.put(node, profile.node(node.value(), line)));
        settings.keySet().forEach(node -> launchers.put(node,
            QueueEntryVersionProfile.helperReading()));
    }

    @AfterEach
    void tearDown() {
        profile.close();
        errors.forEach(error -> TestSecrets.assertNoLeak(error.toString()));
    }

    /** US3-1. */
    @Test
    void anEightOneNodeIsReadWithSoapThe81ClientItsJdkAndTheServletAddress() {
        QueueEntryResult result = service(SHIPPED).read(N81.value(), PID, Optional.empty(),
            OptionalInt.empty());

        assertThat(result.node()).isEqualTo(N81);
        assertThat(detection.requests()).containsExactly(N81);
        FakeProcessLauncher launcher = launchers.get(N81);
        assertThat(launcher.launchCount()).isOne();
        assertLaunchedOn(launcher.last().spec(), profile.client81(), profile.jdk17(),
            profile.client90());
        assertThat(request(launcher.last().stdinBytes()))
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", "http://n81.example.test:8000/ibis/servlet/IBISSoapServlet")
            .containsEntry("objectType", "queue_manager@@@Workflow-0001@@@error")
            .containsEntry("fileName", FILE);
        profile.assertTempRootEmpty();
    }

    /** US3-2. */
    @Test
    void aNineZeroNodeIsReadWithRestThe90ClientItsJdkAndTheBaseUrl() {
        QueueEntryResult result = service(SHIPPED).read(N90.value(), PID, Optional.empty(),
            OptionalInt.empty());

        assertThat(result.node()).isEqualTo(N90);
        FakeProcessLauncher launcher = launchers.get(N90);
        assertThat(launcher.launchCount()).isOne();
        assertLaunchedOn(launcher.last().spec(), profile.client90(), profile.jdk21(),
            profile.client81());
        assertThat(request(launcher.last().stdinBytes()))
            .containsEntry("variant", "REST_90")
            .containsEntry("url", "http://n90.example.test:8000");
        profile.assertTempRootEmpty();
    }

    /** One profile, both versions: each node gets its own variant, client and JDK. */
    @Test
    void oneProfileReadsEachNodeWithTheClientOfItsVersion() {
        QueueEntryService service = service(SHIPPED);

        service.read(N90.value(), PID, Optional.empty(), OptionalInt.empty());
        service.read(N81.value(), PID, Optional.empty(), OptionalInt.empty());

        assertThat(request(launchers.get(N81).last().stdinBytes()))
            .containsEntry("variant", "SOAP_81");
        assertThat(launchers.get(N81).last().spec().command().get(0))
            .isEqualTo(java(profile.jdk17()));
        assertThat(request(launchers.get(N90).last().stdinBytes()))
            .containsEntry("variant", "REST_90");
        assertThat(launchers.get(N90).last().spec().command().get(0))
            .isEqualTo(java(profile.jdk21()));
    }

    /** US3-3: a 6.1 node (pinned, no detection). */
    @Test
    void aSixOneNodeIsUnsupportedNamingTheVersionAndTheSupportedLinesWithoutLaunch() {
        ToolError error = errorOf(() -> service(SHIPPED).read(N61.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("read_queue_entry: INUBIT 6.1 (versionLine V6_1)"
            + " on test/n61 does not support reading Queue Manager entries; supported: 8.1, 9.0;"
            + " use the Queue Manager in the Workbench; nothing was sent");
        assertThat(detection.requests()).isEmpty();
        assertNothingSent();
    }

    /** US3-3: a 9.1 node (detected). */
    @Test
    void aNineOneNodeIsUnsupportedNamingTheVersionAndTheSupportedLinesWithoutLaunch() {
        ToolError error = errorOf(() -> service(SHIPPED).read(N91.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("read_queue_entry: INUBIT 9.1.0 on test/n91 does"
            + " not support reading Queue Manager entries yet; supported: 8.1, 9.0; nothing was"
            + " sent");
        assertThat(detection.requests()).containsExactly(N91);
        assertNothingSent();
    }

    /** US3-4: a fresh detection failure is the call's error, with its own code. */
    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNREACHABLE", "TIMEOUT", "TLS_ERROR",
        "AUTH_FAILED", "UNEXPECTED_RESPONSE"})
    void anUndetectableAutoNodeFailsWithTheDetectionsCodeWithoutLaunch(ErrorCode code) {
        detection.fail(UNDETECTED, code);

        ToolError error = errorOf(() -> service(SHIPPED).read(UNDETECTED.value(), PID,
            Optional.empty(), OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(code);
        assertThat(error.node()).contains(UNDETECTED);
        assertThat(detection.requests()).containsExactly(UNDETECTED);
        assertNothingSent();
    }

    /** US3-4: within the back-off the recorded code is replayed without a second detection. */
    @Test
    void withinTheBackOffTheRecordedDetectionCodeIsReplayedWithoutLaunch() {
        detection.fail(UNDETECTED, ErrorCode.UNREACHABLE);
        QueueEntryService service = service(SHIPPED);
        errorOf(() -> service.read(UNDETECTED.value(), PID, Optional.empty(),
            OptionalInt.empty()));
        clock.advance(Duration.ofSeconds(30));

        ToolError replayed = errorOf(() -> service.read(UNDETECTED.value(), PID,
            Optional.empty(), OptionalInt.empty()));

        assertThat(replayed.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(replayed.message()).startsWith("The INUBIT version of test/auto could not be"
            + " detected (UNREACHABLE at ").endsWith("); read_queue_entry needs it");
        assertThat(detection.requests()).containsExactly(UNDETECTED);
        assertNothingSent();
    }

    /** US3-5: the profile has no client installation for 9.0. */
    @Test
    void withoutA90ClientANineZeroNodeIsUnavailableNamingTheEntryWithoutLaunch() {
        nodes.put(N90, profile.withoutClients(N90.value(), VersionSetting.AUTO)
            .cliClient("8.1", profile.client81(), Optional.of(profile.jdk17())));

        ToolError error = errorOf(() -> service(SHIPPED).read(N90.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("test/n90 (INUBIT 9.0.10)");
        assertThat(error.likelyCause()).contains("defaults.cliClients.\"9.0\"");
        assertThat(error.nextStep()).contains("defaults.cliClients.\"9.0\"");
        assertThat(launchers.get(N90).launchCount()).isZero();
        profile.assertTempRootEmpty();
    }

    /** US3-5: {@code defaults.cliClients."9.0"} names an 8.1 installation ({@code api missing}). */
    @Test
    void anEightOneClientChosenForANineZeroNodeIsUnavailable() {
        nodes.put(N90, profile.withoutClients(N90.value(), VersionSetting.AUTO)
            .cliClient("9.0", profile.client81(), Optional.of(profile.jdk17())));
        launchers.put(N90, QueueEntryVersionProfile.helperLacking(
            QueueEntryVersionProfile.REST_CLIENT_FACTORY));

        ToolError error = errorOf(() -> service(SHIPPED).read(N90.value(), PID, Optional.empty(),
            OptionalInt.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(N90);
        assertThat(error.message()).contains(profile.client81() + " (chosen for INUBIT 9.0)",
            QueueEntryVersionProfile.REST_CLIENT_FACTORY);
        assertThat(error.nextStep()).contains("defaults.cliClients.\"9.0\"");
        assertThat(request(launchers.get(N90).last().stdinBytes()))
            .containsEntry("variant", "REST_90");
        assertThat(launchers.get(N90).launchCount()).isOne();
        profile.assertTempRootEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------

    private QueueEntryService service(CapabilityMatrix matrix) {
        return new QueueEntryService(new TargetResolver(List.copyOf(settings.keySet())),
            node -> true, FakeVersionDetection.gateWithoutClients(versions, detection, matrix),
            this::rows, node -> profile.adapter(nodes.get(node).build(), launchers.get(node)),
            scrubber::scrub);
    }

    /** One ERROR row of {@link #PID} for every node; the lookup is recorded. */
    private ProcessQueryPort rows(NodeId node) {
        return new ProcessQueryPort() {
            @Override
            public void validate(ProcessQuery query) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public Page<ProcessInstance> find(ProcessQuery query) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public ProcessRows findByProcessId(String processId, Instant now,
                Duration hangingThreshold) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public QueueEntries queueEntries(String processId) {
                lookups.add(node);
                return new QueueEntries(List.of(new QueueEntryRow(processId, Optional.empty(),
                    ProcessState.ERROR, "Error", NOW.minusSeconds(60), "Workflow-0001",
                    Optional.of("Module-0001"), Optional.empty(), FILE, 772, new TreeMap<>())),
                    1);
            }
        };
    }

    private ToolError errorOf(Runnable call) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(thrown).as("the call fails").isNotNull();
        errors.add(thrown.error());
        return thrown.error();
    }

    /** No lookup and no helper launch on any node. */
    private void assertNothingSent() {
        assertThat(lookups).as("queueLog lookups").isEmpty();
        launchers.forEach((node, launcher) ->
            assertThat(launcher.launchCount()).as("launches on " + node).isZero());
        profile.assertTempRootEmpty();
    }

    private static void assertLaunchedOn(LaunchSpec spec, Path home, Path jdk, Path otherHome) {
        assertThat(spec.command().get(0)).isEqualTo(java(jdk));
        assertThat(classPath(spec)).contains(clientClassPath(home))
            .doesNotContain(otherHome.toString());
        assertThat(spec.workingDirectory()).isEqualTo(home);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", jdk.toString());
        QueueEntryVersionProfile.assertLaunchedWithoutSecrets(spec);
    }
}
