package de.dadecker.inubit.mcp.adapter.cli.log;

import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.java;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ContentKind;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ExcerptLine;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogExcerpt;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T048 (feature 021, FR-017, research D-22): the INUBIT 9.0 contract of
 * {@code download_server_log} in the fallback state. Every {@code server-log/v9_0} case is
 * reconstructed from the 8.1 case of the same name; while a log's {@code v9_0/<log>/ok.origin} is
 * not recorded, {@link CapabilityMatrix#production()} refuses that log on a 9.0 node as "not yet
 * verified" before any launch (T051 trace and T052 web service replace that test with the
 * recorded {@code ok}).
 *
 * <p>With the test declaration {@link #MATRIX} the real {@link ServerLogService} and a real
 * {@link CliServerLogAdapter} ({@link ServerLogVersionProfile}: the 9.0 client installation with
 * its JDK 21) run the 9.0 paths over the reconstructed cases: StartCLI replays a
 * {@code v9_0/trace} case and writes its {@code .log} to the {@code --download} path; the INUBIT
 * client helper, requested in the {@code REST_90} variant at the base URL, replays a
 * {@code v9_0/webservice} case and writes its {@code .log} to {@code logOut}. After every failure
 * no file is in the target folder (SC-004), the private directory is deleted and the call is
 * audited {@code FAILED} with its code. No process, no network.
 */
@Timeout(60)
class ServerLogV90ContractTest {

    private static final NodeId N90 = NodeId.parse("test/n90");
    private static final String VERSION = "v9_0";
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/server-log/v9_0");
    private static final String TRACE = "server-log/v9_0/trace/";
    private static final String WEB_SERVICE = "server-log/v9_0/webservice/";
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000048");

    /** The test declaration: both server logs supported on 9.0, every other version unsupported. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(9, 0, 9, 0), Map.of(
            Capability.TRACE_LOG_READ, SupportState.SUPPORTED,
            Capability.WEBSERVICE_LOG_READ, SupportState.SUPPORTED))),
        Map.of());

    @TempDir(factory = GitFreeTempDir.class)
    Path tempDir;

    private Path root;
    private Path folder;
    private ServerLogVersionProfile profile;
    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW),
        node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(N90, "9.0.10");
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        folder = Files.createDirectories(root.resolve("logs"));
        profile = new ServerLogVersionProfile(root);
    }

    @AfterEach
    void tearDown() {
        profile.close();
    }

    // --- FR-017 fallback: the shipped declaration ------------------------------------------------

    @ParameterizedTest
    @CsvSource({"trace, downloading the trace log",
        "webservice, downloading the web service log"})
    void whileTheOkCaseIsNotRecordedTheProductionMatrixRefusesNineZeroWithoutLaunch(String log,
        String label) {
        assertThat(firstLine(FIXTURES.resolve(log + "/ok.origin"))).startsWith("reconstructed ");
        FakeProcessLauncher launcher = log.equals("trace")
            ? ServerLogVersionProfile.startCli(VERSION, "ok")
            : ServerLogVersionProfile.helper(VERSION, "ok");

        ToolError error = errorOf(service(launcher, CapabilityMatrix.production()), log);

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("download_server_log: INUBIT 9.0.10 on test/n90: "
            + label + " is not yet verified for INUBIT 9.0; nothing was sent");
        assertThat(detection.requests()).containsExactly(N90);
        assertThat(launcher.launchCount()).isZero();
        assertThat(folder).as("no file in the target folder").isEmptyDirectory();
        assertThat(ServerLogVersionProfile.leftovers()).isEmpty();
    }

    // --- trace: the 9.0 StartCLI client ------------------------------------------------------------

    @Test
    void traceOkRunsThe90ClientWritesTheLogAndMasksTheExcerpt() throws IOException {
        byte[] okLog = FakeProcessLauncher.resource(TRACE + "ok.log");
        FakeProcessLauncher launcher = ServerLogVersionProfile.startCli(VERSION, "ok");

        ServerLogResult result = service(launcher, MATRIX).download(call("trace"));

        assertThat(result.node()).isEqualTo(N90);
        assertThat(result.log()).isEqualTo(ServerLogKind.TRACE);
        assertThat(result.inubitNode()).isEmpty();
        assertThat(result.file()).isEqualTo(folder.resolve("test-n90-trace-20261112-100000.log"));
        assertThat(result.sizeBytes()).isEqualTo(okLog.length);
        assertThat(Files.readAllBytes(result.file())).as("the file keeps the content")
            .isEqualTo(okLog);
        assertThat(result.downloadedAt()).isEqualTo(NOW);
        assertThat(result.hints()).isEmpty();
        assertThat(result.warnings()).contains("The log file contains clear-text content");

        // the whole file is within the last 200 lines; only the two secret lines are masked
        List<String> expected = new ArrayList<>(
            new String(okLog, StandardCharsets.UTF_8).lines().toList());
        assertThat(expected.set(7, "Authorization: ${secret:authorization}"))
            .as("Basic acme:trace-secret-0001")
            .contains("YWNtZTp0cmFjZS1zZWNyZXQtMDAwMQ==");
        assertThat(expected.set(15, "  <password>${secret:data.password}</password>"))
            .contains("trace-secret-0002");
        assertExcerpt(result.excerpt().orElseThrow(), expected);

        assertThat(launcher.launchCount()).isOne();
        LaunchSpec spec = launcher.last().spec();
        assertThat(spec.workingDirectory()).isEqualTo(profile.client90());
        assertThat(spec.environment()).containsEntry("JAVA_HOME", profile.jdk21().toString());
        assertThat(ServerLogVersionProfile.leftovers()).as("private directory deleted").isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void traceEmptyWritesAZeroByteFileWithAnEmptyExcerpt() throws IOException {
        ServerLogResult result = service(ServerLogVersionProfile.startCli(VERSION, "empty"),
            MATRIX).download(call("trace"));

        assertThat(result.sizeBytes()).isZero();
        assertThat(result.file()).isRegularFile().isEmptyFile();
        assertThat(result.excerpt().orElseThrow().kind()).isEqualTo(ContentKind.EMPTY);
        assertThat(result.hints()).containsExactly("The log is empty");
        assertThat(ServerLogVersionProfile.leftovers()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @ParameterizedTest
    @CsvSource({
        "missing, NOT_FOUND",
        "login_failure, AUTH_FAILED",
        "forbidden, FORBIDDEN"})
    void everyFailedTraceCaseHasItsCodeAndLeavesNothing(String fixtureCase, ErrorCode expected)
        throws IOException {
        FakeProcessLauncher launcher = ServerLogVersionProfile.startCli(VERSION, fixtureCase);

        ToolError error = errorOf(service(launcher, MATRIX), "trace");

        assertFailedLeavingNothing(error, expected);
        assertThat(launcher.launchCount()).isOne();
    }

    @Test
    void traceMissingNamesTheLogAndTheNode() throws IOException {
        ToolError error = errorOf(service(ServerLogVersionProfile.startCli(VERSION, "missing"),
            MATRIX), "trace");

        assertThat(error.message()).isEqualTo("trace.log does not exist on test/n90");
        assertFailedLeavingNothing(error, ErrorCode.NOT_FOUND);
    }

    // --- web service: the REST_90 helper -------------------------------------------------------

    @Test
    void webServiceOkRunsRestOnThe90ClientNamesTheInubitNodeAndMasksTheExcerpt()
        throws IOException {
        byte[] okLog = FakeProcessLauncher.resource(WEB_SERVICE + "ok.log");
        FakeProcessLauncher launcher = ServerLogVersionProfile.helper(VERSION, "ok");

        ServerLogResult result = service(launcher, MATRIX).download(call("webservice"));

        assertThat(result.node()).isEqualTo(N90);
        assertThat(result.log()).isEqualTo(ServerLogKind.WEBSERVICE);
        assertThat(result.inubitNode()).as("the name in the node ok frame").contains("node1");
        assertThat(result.file())
            .isEqualTo(folder.resolve("test-n90-webservice-20261112-100000.log"));
        assertThat(result.sizeBytes()).isEqualTo(okLog.length);
        assertThat(Files.readAllBytes(result.file())).as("the file keeps the content")
            .isEqualTo(okLog);
        assertThat(result.hints()).isEmpty();
        assertThat(result.warnings()).contains(
            "Read through an unsupported, undocumented INUBIT interface; diagnosis only",
            "The log file contains clear-text content");

        // as in the 8.1 case: the UsernameTokens, the Authorization probe and the Set-Cookie
        // values are masked, every line keeps its number
        List<String> expected = new ArrayList<>(
            new String(okLog, StandardCharsets.UTF_8).lines().toList());
        assertThat(expected.set(15, "      <wsse:UsernameToken>${secret:data.UsernameToken}"))
            .isEqualTo("      <wsse:UsernameToken>");
        assertThat(expected.set(16, "")).contains("<wsse:Username>partner-0001");
        assertThat(expected.set(17, "</wsse:UsernameToken>"))
            .isEqualTo("      </wsse:UsernameToken>");
        assertThat(expected.set(36, "Authorization: ${secret:authorization}"))
            .as("Basic acme:webservice-secret-0001")
            .contains("YWNtZTp3ZWJzZXJ2aWNlLXNlY3JldC0wMDAx");
        assertThat(expected.set(64, "Set-Cookie: ${secret:data.Set-Cookie}"))
            .contains("JSESSIONID=4C2F0A1B9D7E3F5A6B8C0D2E4F6A8B0C");
        assertThat(expected.set(96, "      <wsse:UsernameToken>${secret:data.UsernameToken}"))
            .isEqualTo("      <wsse:UsernameToken>");
        assertThat(expected.set(97, "")).contains("<wsse:Username>partner-0002");
        assertThat(expected.set(98, "")).contains("<wsse:Password", "webservice-secret-0002");
        assertThat(expected.set(99, "</wsse:UsernameToken>"))
            .isEqualTo("      </wsse:UsernameToken>");
        assertThat(expected.set(110, "Set-Cookie: ${secret:data.Set-Cookie}"))
            .contains("JSESSIONID=9E8D7C6B5A4F3E2D1C0B9A8F7E6D5C4B");
        assertExcerpt(result.excerpt().orElseThrow(), expected);

        assertThat(launcher.launchCount()).isOne();
        LaunchSpec spec = launcher.last().spec();
        assertThat(spec.command().get(0)).isEqualTo(java(profile.jdk21()));
        assertThat(spec.workingDirectory()).isEqualTo(profile.client90());
        assertThat(request(launcher.last().stdinBytes()))
            .containsEntry("variant", "REST_90")
            .containsEntry("url", "http://n90.example.test:8000");
        assertThat(ServerLogVersionProfile.leftovers()).as("private directory deleted").isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void webServiceEmptyHintsThatWebServiceLoggingMayBeDisabled() throws IOException {
        ServerLogResult result = service(ServerLogVersionProfile.helper(VERSION, "empty"),
            MATRIX).download(call("webservice"));

        assertThat(result.sizeBytes()).isZero();
        assertThat(result.file()).isRegularFile().isEmptyFile();
        assertThat(result.excerpt().orElseThrow().kind()).isEqualTo(ContentKind.EMPTY);
        assertThat(result.hints())
            .containsExactly("The log is empty; web service logging may be disabled");
        assertThat(ServerLogVersionProfile.leftovers()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @ParameterizedTest
    @CsvSource({
        "missing, NOT_FOUND",
        "forbidden, FORBIDDEN",
        "not_cluster_mode, UNEXPECTED_RESPONSE",
        "garbage_frames, UNEXPECTED_RESPONSE",
        "api_missing, CLI_UNAVAILABLE"})
    void everyFailedWebServiceCaseHasItsCodeAndLeavesNothing(String fixtureCase,
        ErrorCode expected) throws IOException {
        FakeProcessLauncher launcher = ServerLogVersionProfile.helper(VERSION, fixtureCase);

        ToolError error = errorOf(service(launcher, MATRIX), "webservice");

        assertFailedLeavingNothing(error, expected);
        assertThat(launcher.launchCount()).isOne();
    }

    @Test
    void webServiceMissingSaysTheServerHasNoWebServiceLog() throws IOException {
        ToolError error = errorOf(service(ServerLogVersionProfile.helper(VERSION, "missing"),
            MATRIX), "webservice");

        assertThat(error.message()).isEqualTo(
            "test/n90 has no web service log (webservice.log on INUBIT node node1)");
        assertFailedLeavingNothing(error, ErrorCode.NOT_FOUND);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Every line in order with its number from 1. */
    private static void assertExcerpt(LogExcerpt excerpt, List<String> expected) {
        assertThat(excerpt.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(excerpt.lines()).extracting(ExcerptLine::number)
            .containsExactlyElementsOf(LongStream.rangeClosed(1, expected.size()).boxed()
                .toList());
        assertThat(excerpt.lines()).extracting(ExcerptLine::text)
            .containsExactlyElementsOf(expected);
    }

    /** SC-004: the code, no file in the folder, no private directory, audit {@code FAILED}. */
    private void assertFailedLeavingNothing(ToolError error, ErrorCode expected) {
        assertThat(error.code()).isEqualTo(expected);
        assertThat(error.node()).contains(N90);
        assertThat(folder).as("no file in the target folder").isEmptyDirectory();
        assertThat(ServerLogVersionProfile.leftovers()).as("private directory deleted").isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.get(1).reason()).hasValueSatisfying(reason -> assertThat(reason)
            .startsWith(expected + ": "));
    }

    /**
     * The service over {@link #N90} (detected 9.0.10) with {@code matrix}, the group opted in to
     * the web service log, StartCLI and the helper being {@code launcher}.
     */
    private ServerLogService service(FakeProcessLauncher launcher, CapabilityMatrix matrix) {
        ServerLogPort port = profile.port(profile.node(N90.value(), VersionSetting.AUTO)
            .cliExportTimeout(EXPORT_TIMEOUT).build(), launcher, versions::effectiveVersion);
        AuditPort auditPort = audit::add;
        return new ServerLogService(new TargetResolver(List.of(N90)), node -> true,
            node -> Optional.of(QueueEntryVersionProfile.USER),
            new TargetFolderRules(directory("home"), directory("workspace"),
                root.resolve("audit"), List.of(directory("config")), Map.of(), letter -> false,
                false),
            FakeVersionDetection.gateWithoutClients(versions, detection, matrix),
            new Gateways(port), node -> EXPORT_TIMEOUT,
            FakeConfiguredSecrets.held(TestSecrets.CONFIGURED_PASSWORD), Duration.ofSeconds(30),
            new ExcerptPermit(), new LogFileWriter(),
            new ServerLogAudit.Dependencies(auditPort, new MutableClock(NOW), "default", () -> ID),
            new MutableClock(NOW), System::nanoTime);
    }

    private Path directory(String name) {
        try {
            return Files.createDirectories(root.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ServerLogRequest call(String log) {
        return new ServerLogRequest(N90.value(), log, folder.toString(), OptionalInt.empty(),
            Optional.empty(), OptionalInt.empty(), Optional.empty());
    }

    private ToolError errorOf(ServerLogService service, String log) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> service.download(call(log)));
        assertThat(thrown).as("the call fails").isNotNull();
        TestSecrets.assertNoLeak(thrown.error().toString());
        return thrown.error();
    }

    private static String firstLine(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<AuditOutcome> outcomes() {
        return audit.stream().map(AuditRecord::outcome).toList();
    }

    /** Only the server-log port of {@link #N90}; every other port is an assertion error. */
    private record Gateways(ServerLogPort port) implements GatewayFactory {

        @Override
        public ServerLogPort serverLogs(NodeId server) {
            assertThat(server).isEqualTo(N90);
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
