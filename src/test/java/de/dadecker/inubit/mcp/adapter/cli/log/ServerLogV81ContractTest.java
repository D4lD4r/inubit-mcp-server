package de.dadecker.inubit.mcp.adapter.cli.log;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTraceRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.application.ExcerptPermit;
import de.dadecker.inubit.mcp.application.LogFileWriter;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.ServerLogAudit;
import de.dadecker.inubit.mcp.application.ServerLogRequest;
import de.dadecker.inubit.mcp.application.ServerLogService;
import de.dadecker.inubit.mcp.application.TargetFolderRules;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ContentKind;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ExcerptLine;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogExcerpt;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ServerLogResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
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
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TraceMatrix;
import de.dadecker.inubit.mcp.testing.WebServiceMatrix;
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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T032 (feature 021, FR-017, research D-22): the 8.1 trace contract. {@link ServerLogService} over
 * the real {@link CliServerLogAdapter} and {@link CliTraceRunner}, StartCLI being a
 * {@link FakeProcessLauncher} that replays each {@code server-log/v8_1/trace} case and writes its
 * {@code <case>.log} to the {@code --download} path; {@code ok} is the neutralized 8.1.17
 * recording (T033), the other cases are reconstructed. Test matrix declaration:
 * {@link TraceMatrix#admittingEightOne()}, so the contract does not depend on the production
 * declaration. After every failure no file is in the target folder (SC-004), the private
 * directory is deleted and the call is audited {@code FAILED} with its code.
 *
 * <p>T044 (FR-017, D-22): the 8.1 web service contract over the same service and the real
 * {@link CliWebServiceLogAdapter}, the INUBIT client helper replaying each reconstructed
 * {@code server-log/v8_1/webservice} case ({@link CliWebServiceLogAdapterTest#replaying}), for a
 * group with {@code unsupported.serverLogRead}. Test matrix declaration:
 * {@link WebServiceMatrix#admittingEightOneWithoutTrace()}, as the production matrix refuses the
 * web service log until the 8.1 recording (T046).
 */
@Timeout(30)
class ServerLogV81ContractTest {

    private static final CapabilityMatrix MATRIX = TraceMatrix.admittingEightOne();
    private static final CapabilityMatrix WEB_SERVICE_MATRIX =
        WebServiceMatrix.admittingEightOneWithoutTrace();
    private static final String WEB_SERVICE = "server-log/v8_1/webservice/";
    /** The INUBIT node name the helper reports in a frames file. */
    private static final Pattern NODE_OK = Pattern.compile(" node ok (\\S+)");
    private static final NodeId DEV = NodeId.parse("dev/node1");
    /** The CliResources profile; the private directories are {@code inubit-mcp-log-<it>-…}. */
    private static final String PROFILE = "t032";
    private static final String PASSWORD = "S3cr3t-contract-pw";
    private static final Pattern DOWNLOAD = Pattern.compile("--download '([^']+)'");
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final Function<NodeId, Optional<InubitVersion>> VERSIONS =
        id -> Optional.of(V8_1_17);
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000032");

    @TempDir(factory = GitFreeTempDir.class)
    Path tempDir;

    private Path root;
    private Path folder;
    private CliResources resources;
    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW),
        node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection =
        new FakeVersionDetection(versions).answer(DEV, "8.1.17");
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        Path client = Files.createDirectories(root.resolve("client-8.1"));
        FakeStartCli.install(client);
        // what the INUBIT client helper needs of the client home
        Files.createDirectories(client.resolve("lib").resolve("shared"));
        Files.createDirectories(client.resolve("conf"));
        Files.createFile(client.resolve("lib").resolve("ibis.jar"));
        folder = Files.createDirectories(root.resolve("logs"));
        resources = new CliResources(PROFILE, PrivateFiles.forHost());
    }

    @AfterEach
    void closeResources() {
        resources.close();
    }

    // --- success -------------------------------------------------------------------------------

    @Test
    void okWritesTheLogAndReturnsTheContractShapeWithAMaskedExcerpt() throws IOException {
        byte[] okLog = FakeProcessLauncher.resource("server-log/v8_1/trace/ok.log");
        FakeProcessLauncher launcher = replaying("ok");

        ServerLogResult result = service(launcher, credentials()).download(request());

        assertThat(result.node()).isEqualTo(DEV);
        assertThat(result.log()).isEqualTo(ServerLogKind.TRACE);
        assertThat(result.inubitNode()).isEmpty();
        assertThat(result.file()).isEqualTo(folder.resolve("dev-node1-trace-20261112-100000.log"));
        assertThat(result.file()).isAbsolute();
        assertThat(result.sizeBytes()).isEqualTo(okLog.length);
        assertThat(Files.readAllBytes(result.file())).as("the file keeps the content")
            .isEqualTo(okLog);
        assertThat(result.downloadedAt()).isEqualTo(NOW);
        assertThat(result.hints()).isEmpty();
        assertThat(result.warnings()).contains("The log file contains clear-text content");

        LogExcerpt excerpt = result.excerpt().orElseThrow();
        assertThat(excerpt.kind()).isEqualTo(ContentKind.TEXT);
        // the whole file is within the last 200 lines; only the two secret lines are masked
        List<String> expected = new ArrayList<>(
            new String(okLog, StandardCharsets.UTF_8).lines().toList());
        assertThat(expected.set(7, "Authorization: ${secret:authorization}"))
            .as("Basic acme:trace-secret-0001")
            .contains("YWNtZTp0cmFjZS1zZWNyZXQtMDAwMQ==");
        assertThat(expected.set(15, "  <password>${secret:data.password}</password>"))
            .contains("trace-secret-0002");
        assertThat(excerpt.lines()).extracting(ExcerptLine::number)
            .containsExactlyElementsOf(LongStream.rangeClosed(1, expected.size()).boxed()
                .toList());
        assertThat(excerpt.lines()).extracting(ExcerptLine::text)
            .containsExactlyElementsOf(expected);

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(leftovers()).as("private directory deleted").isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void emptyWritesAZeroByteFileWithAnEmptyExcerpt() throws IOException {
        ServerLogResult result = service(replaying("empty"), credentials()).download(request());

        assertThat(result.sizeBytes()).isZero();
        assertThat(result.file()).isRegularFile().isEmptyFile();
        assertThat(result.excerpt().orElseThrow().kind()).isEqualTo(ContentKind.EMPTY);
        assertThat(result.excerpt().orElseThrow().lines()).isEmpty();
        assertThat(result.hints()).containsExactly("The log is empty");
        assertThat(leftovers()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    // --- failures of the reconstructed cases ---------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "missing, NOT_FOUND",
        "login_failure, AUTH_FAILED",
        "forbidden, FORBIDDEN"})
    void everyFailedCaseHasItsCodeAndLeavesNothing(String fixtureCase, ErrorCode expected)
        throws IOException {
        FakeProcessLauncher launcher = replaying(fixtureCase);

        ToolError error = errorOf(service(launcher, credentials()));

        assertFailedLeavingNothing(error, expected);
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void missingNamesTheLogAndTheNode() throws IOException {
        ToolError error = errorOf(service(replaying("missing"), credentials()));

        assertThat(error.message()).isEqualTo("trace.log does not exist on dev/node1");
        assertThat(error.nextStep())
            .isEqualTo("Check the trace settings of the server in the Workbench");
        assertFailedLeavingNothing(error, ErrorCode.NOT_FOUND);
    }

    // --- edge case "maintenance mode, credentials not available, TLS failure" (spec) -----------

    @Test
    void credentialsThatAreNotAvailableAreTheNodesCredentialErrorWithoutLaunch()
        throws IOException {
        FakeProcessLauncher launcher = replaying("ok");
        NodeCredentials none = new NodeCredentials(DEV, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolError error = errorOf(service(launcher, none));

        assertFailedLeavingNothing(error, ErrorCode.AUTH_FAILED);
        assertThat(error.nextStep()).contains("INUBIT_DEV_NODE1_PASSWORD");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void maintenanceModeComesFromTheDetectionBeforeLaunch() throws IOException {
        detection.fail(DEV, ErrorCode.MAINTENANCE_MODE);
        FakeProcessLauncher launcher = replaying("ok");

        ToolError error = errorOf(service(launcher, credentials()));

        assertFailedLeavingNothing(error, ErrorCode.MAINTENANCE_MODE);
        assertThat(detection.requests()).containsExactly(DEV);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aTlsFailureOfStartCliIsATlsError() throws IOException {
        FakeProcessLauncher launcher = answering("JAVA_HOME is set\nPassword: \n"
            + "ERROR 10:00:00,000 [main      ] CLI                       ERROR\n"
            + "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailed@@@Login to the"
            + " server failed.@End@javax.net.ssl.SSLHandshakeException: PKIX path building"
            + " failed\nCONNECTION ERROR\n", "", 1, new byte[0]);

        ToolError error = errorOf(service(launcher, credentials()));

        assertFailedLeavingNothing(error, ErrorCode.TLS_ERROR);
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    // --- T044: the web service log (FR-017, US2-1, US2-4) --------------------------------------

    @Test
    void webServiceOkWritesTheLogNamesTheInubitNodeAndMasksTheExcerpt() throws IOException {
        byte[] okLog = FakeProcessLauncher.resource(WEB_SERVICE + "ok.log");
        FakeProcessLauncher launcher = CliWebServiceLogAdapterTest.replaying("ok");

        ServerLogResult result = webService(launcher, http(), () -> credentials())
            .download(request("webservice"));

        assertThat(result.node()).isEqualTo(DEV);
        assertThat(result.log()).isEqualTo(ServerLogKind.WEBSERVICE);
        assertThat(result.inubitNode()).as("the name the helper reported")
            .contains(reportedNode("ok.frames"));
        assertThat(result.file())
            .isEqualTo(folder.resolve("dev-node1-webservice-20261112-100000.log"));
        assertThat(result.sizeBytes()).isEqualTo(okLog.length);
        assertThat(Files.readAllBytes(result.file())).as("the file keeps the content")
            .isEqualTo(okLog);
        assertThat(result.downloadedAt()).isEqualTo(NOW);
        assertThat(result.hints()).isEmpty();
        assertThat(result.warnings()).contains(
            "Read through an unsupported, undocumented INUBIT interface; diagnosis only",
            "The log file contains clear-text content");

        LogExcerpt excerpt = result.excerpt().orElseThrow();
        assertThat(excerpt.kind()).isEqualTo(ContentKind.TEXT);
        // the whole file is within the last 200 lines; every line keeps its number, only the
        // UsernameToken (with the wsse:Password), Authorization and Set-Cookie values are masked
        List<String> expected = new ArrayList<>(
            new String(okLog, StandardCharsets.UTF_8).lines().toList());
        assertThat(expected.set(14, "      <wsse:UsernameToken>${secret:data.UsernameToken}"))
            .isEqualTo("      <wsse:UsernameToken>");
        assertThat(expected.set(15, "")).contains("<wsse:Username>partner-0001");
        assertThat(expected.set(16, "")).contains("<wsse:Password", "webservice-secret-0002");
        assertThat(expected.set(17, "</wsse:UsernameToken>"))
            .isEqualTo("      </wsse:UsernameToken>");
        assertThat(expected.set(36, "Authorization: ${secret:authorization}"))
            .as("Basic acme:webservice-secret-0001")
            .contains("YWNtZTp3ZWJzZXJ2aWNlLXNlY3JldC0wMDAx");
        assertThat(expected.set(64, "Set-Cookie: ${secret:data.Set-Cookie}"))
            .contains("JSESSIONID=4C2F0A1B9D7E3F5A6B8C0D2E4F6A8B0C");
        assertThat(excerpt.lines()).extracting(ExcerptLine::number)
            .containsExactlyElementsOf(LongStream.rangeClosed(1, expected.size()).boxed()
                .toList());
        assertThat(excerpt.lines()).extracting(ExcerptLine::text)
            .containsExactlyElementsOf(expected);

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(leftovers()).as("private directory deleted").isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void webServiceEmptyHintsThatWebServiceLoggingMayBeDisabled() throws IOException {
        ServerLogResult result = webService(CliWebServiceLogAdapterTest.replaying("empty"), http(),
            () -> credentials()).download(request("webservice"));

        assertThat(result.sizeBytes()).isZero();
        assertThat(result.file()).isRegularFile().isEmptyFile();
        assertThat(result.excerpt().orElseThrow().kind()).isEqualTo(ContentKind.EMPTY);
        assertThat(result.hints())
            .containsExactly("The log is empty; web service logging may be disabled");
        assertThat(leftovers()).isEmpty();
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
        FakeProcessLauncher launcher = CliWebServiceLogAdapterTest.replaying(fixtureCase);

        ToolError error = webServiceError(webService(launcher, http(), () -> credentials()));

        assertFailedLeavingNothing(error, expected);
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void webServiceMissingSaysTheServerHasNoWebServiceLog() throws IOException {
        // US2-4: the interface tells a missing file apart, so no file and no empty result
        ToolError error = webServiceError(webService(
            CliWebServiceLogAdapterTest.replaying("missing"), http(), () -> credentials()));

        assertThat(error.message()).isEqualTo("dev/node1 has no web service log (webservice.log"
            + " on INUBIT node " + reportedNode("missing.frames") + ")");
        assertThat(error.nextStep())
            .isEqualTo("Enable web service logging in the server configuration, then retry");
        assertFailedLeavingNothing(error, ErrorCode.NOT_FOUND);
    }

    @Test
    void webServiceCredentialsThatCannotBeResolvedLaunchNothing() throws IOException {
        FakeProcessLauncher launcher = CliWebServiceLogAdapterTest.replaying("ok");
        ToolErrorException unresolved = new ToolErrorException(ToolError.of(
            ErrorCode.SECRET_UNRESOLVED, "The password of dev/node1 could not be resolved",
            "The secret store has no entry for it", "Add the entry").withNode(DEV));

        ToolError error = webServiceError(webService(launcher, http(), () -> {
            throw unresolved;
        }));

        assertFailedLeavingNothing(error, ErrorCode.SECRET_UNRESOLVED);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void webServiceTlsFailureLaunchesNothing() throws Exception {
        FakeProcessLauncher launcher = CliWebServiceLogAdapterTest.replaying("ok");
        TestCertificates certificates = TestCertificates.get();

        try (TlsTestServer tls = TlsTestServer.start(certificates.rogueKeyStore())) {
            EffectiveNodeConfig server = webServiceNode()
                .baseUrl("https://127.0.0.1:" + tls.port())
                .trustStore(certificates.trustStore()).build();

            ToolError error = webServiceError(webService(launcher, server, () -> credentials()));

            assertFailedLeavingNothing(error, ErrorCode.TLS_ERROR);
        }
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void webServiceMaintenanceModeComesFromTheDetectionBeforeLaunch() throws IOException {
        detection.fail(DEV, ErrorCode.MAINTENANCE_MODE);
        FakeProcessLauncher launcher = CliWebServiceLogAdapterTest.replaying("ok");

        ToolError error = webServiceError(webService(launcher, http(), () -> credentials()));

        assertFailedLeavingNothing(error, ErrorCode.MAINTENANCE_MODE);
        assertThat(detection.requests()).containsExactly(DEV);
        assertThat(launcher.launchCount()).isZero();
    }

    // --- helpers -------------------------------------------------------------------------------

    /** SC-004: the code, no file in the folder, no private directory, audit {@code FAILED}. */
    private void assertFailedLeavingNothing(ToolError error, ErrorCode expected)
        throws IOException {
        assertThat(error.code()).isEqualTo(expected);
        assertThat(error.node()).contains(DEV);
        assertThat(folder).as("no file in the target folder").isEmptyDirectory();
        assertThat(leftovers()).as("private directory deleted").isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(audit.get(1).reason()).hasValueSatisfying(reason -> assertThat(reason)
            .startsWith(expected + ": "));
    }

    /** The trace service: {@link #MATRIX}, no {@code unsupported.serverLogRead}. */
    private ServerLogService service(FakeProcessLauncher launcher, NodeCredentials credentials) {
        EffectiveNodeConfig server = TestNodeConfig.node().versionLine(VersionSetting.AUTO)
            .cliClient("8.1", root.resolve("client-8.1"), Optional.of(Path.of("/opt/jdk-17")))
            .cliExportTimeout(EXPORT_TIMEOUT).build();
        return service(launcher, server, credentials, () -> credentials, MATRIX, false);
    }

    /**
     * The web service service: {@link #WEB_SERVICE_MATRIX}, {@code unsupported.serverLogRead} set,
     * the helper getting its credentials from {@code credentials}.
     */
    private ServerLogService webService(FakeProcessLauncher launcher, EffectiveNodeConfig server,
        Supplier<NodeCredentials> credentials) {
        return service(launcher, server, credentials(), credentials, WEB_SERVICE_MATRIX, true);
    }

    private ServerLogService service(FakeProcessLauncher launcher, EffectiveNodeConfig server,
        NodeCredentials traceCredentials, Supplier<NodeCredentials> webServiceCredentials,
        CapabilityMatrix matrix, boolean serverLogRead) {
        CliRunner runner = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            resources, VERSIONS);
        CredentialGuard guard =
            new CredentialGuard(storeLabels("INUBIT", DEV), new MutableClock(NOW));
        CliTraceRunner traces = new CliTraceRunner(server, traceCredentials, guard, runner,
            new CliOutputClassifier(new SecretScrubber(), server.secretLabels(),
                runner.windows()));
        CliWebServiceLogAdapter webServiceLogs = new CliWebServiceLogAdapter(server,
            webServiceCredentials, guard, runner,
            TestCliRunners.helperHost(Map.of("PATH", Platform.searchPath()), resources,
                Path.of(System.getProperty("java.io.tmpdir"))),
            new ProcessRun(launcher, resources), new SecretScrubber());
        ServerLogPort port = new CliServerLogAdapter(server, traces, webServiceLogs,
            new CliConcurrencyLimiter(matrix));
        AuditPort auditPort = audit::add;
        return new ServerLogService(new TargetResolver(List.of(DEV)), node -> serverLogRead,
            node -> Optional.of("jdoe"),
            new TargetFolderRules(createDirectory("home"), createDirectory("workspace"),
                root.resolve("audit"), List.of(createDirectory("config")), Map.of(),
                letter -> false, false),
            FakeVersionDetection.gateWithoutClients(versions, detection, matrix),
            new Gateways(port), node -> EXPORT_TIMEOUT, FakeConfiguredSecrets.held(PASSWORD),
            Duration.ofSeconds(30), new ExcerptPermit(), new LogFileWriter(),
            new ServerLogAudit.Dependencies(auditPort, new MutableClock(NOW), "default", () -> ID),
            new MutableClock(NOW), System::nanoTime);
    }

    private Path createDirectory(String name) {
        try {
            return Files.createDirectories(root.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The web service node over plain HTTP, the 8.1 client home with this JVM as its JDK, as the
     * helper needs a JDK with {@code bin/java}.
     */
    private EffectiveNodeConfig http() {
        return webServiceNode().baseUrl("http://inubit.example.test:8000").allowInsecureHttp(true)
            .build();
    }

    private TestNodeConfig webServiceNode() {
        return TestNodeConfig.node().versionLine(VersionSetting.AUTO)
            .cliClient("8.1", root.resolve("client-8.1"),
                Optional.of(Path.of(System.getProperty("java.home"))))
            .cliExportTimeout(EXPORT_TIMEOUT);
    }

    private ServerLogRequest request() {
        return request("trace");
    }

    private ServerLogRequest request(String log) {
        return new ServerLogRequest(DEV.value(), log, folder.toString(), OptionalInt.empty(),
            Optional.empty(), OptionalInt.empty(), Optional.empty());
    }

    private ToolError errorOf(ServerLogService service) {
        return errorOf(service, request());
    }

    private ToolError webServiceError(ServerLogService service) {
        return errorOf(service, request("webservice"));
    }

    private ToolError errorOf(ServerLogService service, ServerLogRequest request) {
        try {
            service.download(request);
        } catch (ToolErrorException e) {
            assertThat(e.error().toString()).doesNotContain(PASSWORD);
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private static String fixture(String name) {
        return new String(FakeProcessLauncher.resource("server-log/v8_1/trace/" + name),
            StandardCharsets.UTF_8);
    }

    /** The INUBIT node name in the {@code node ok} frame of web service fixture {@code name}. */
    private static String reportedNode(String name) {
        Matcher matcher = NODE_OK.matcher(new String(
            FakeProcessLauncher.resource(WEB_SERVICE + name), StandardCharsets.UTF_8));
        assertThat(matcher.find()).as("a node ok frame in %s", name).isTrue();
        return matcher.group(1);
    }

    /** Replays {@code server-log/v8_1/trace/<fixtureCase>.*}. */
    private static FakeProcessLauncher replaying(String fixtureCase) {
        return answering(fixture(fixtureCase + ".stdout"), fixture(fixtureCase + ".stderr"),
            Integer.parseInt(fixture(fixtureCase + ".exit").strip()),
            FakeProcessLauncher.resource("server-log/v8_1/trace/" + fixtureCase + ".log"));
    }

    /** StartCLI that prints {@code stdout} and writes {@code log} to the download path. */
    private static FakeProcessLauncher answering(String stdout, String stderr, int exitCode,
        byte[] log) {
        return FakeProcessLauncher.of(stdout, stderr, exitCode).onLaunch(spec -> {
            try {
                Files.write(downloadFile(spec), log);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static Path downloadFile(LaunchSpec spec) {
        List<String> command = spec.startCliArguments();
        Matcher matcher = DOWNLOAD.matcher(command.get(command.indexOf("--execCommand") + 1));
        assertThat(matcher.find()).isTrue();
        return Path.of(matcher.group(1));
    }

    /** This process's private log directories of {@link #PROFILE} below {@code java.io.tmpdir}. */
    private static List<Path> leftovers() throws IOException {
        String prefix = "inubit-mcp-log-" + PROFILE + "-" + ProcessHandle.current().pid() + "-";
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix))
                .toList();
        }
    }

    private List<AuditOutcome> outcomes() {
        return audit.stream().map(AuditRecord::outcome).toList();
    }

    /** Only the server-log port of {@link #DEV}; every other port is an assertion error. */
    private record Gateways(ServerLogPort port) implements GatewayFactory {

        @Override
        public ServerLogPort serverLogs(NodeId server) {
            assertThat(server).isEqualTo(DEV);
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
