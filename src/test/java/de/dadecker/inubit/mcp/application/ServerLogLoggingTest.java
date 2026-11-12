package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTraceRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.log.CliServerLogAdapter;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.ExcerptLine;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogResult;
import de.dadecker.inubit.mcp.domain.model.SupportState;
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
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 021 T029 (FR-015, SC-003, research D-18): a {@code download_server_log} call logs
 * exactly one INFO line naming the node, the log, the file name, its size, the scanned window and
 * the outcome; the trace runner adds one DEBUG line naming the client home and the JDK. Neither
 * the log content, an excerpt line, the {@code contains} text, StartCLI's output nor a credential
 * appears in any log line at any level. The service runs over the real {@link CliServerLogAdapter}
 * and {@link CliTraceRunner}, with StartCLI replaying the 8.1 trace fixtures.
 */
@Timeout(30)
class ServerLogLoggingTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final InubitVersion V8_1_17 = InubitVersion.parse("8.1.17").orElseThrow();
    /** The version StartCLI is chosen for. */
    private static final Function<NodeId, Optional<InubitVersion>> STARTCLI_VERSIONS =
        id -> Optional.of(V8_1_17);
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String NAME = "dev-node1-trace-20261112-100000.log";
    private static final Pattern DOWNLOAD = Pattern.compile("--download '([^']+)'");
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Map.of(Capability.TRACE_LOG_READ, SupportState.SUPPORTED))),
        Map.of());
    /** The {@code contains} text, which must never be logged (it may be a guessed secret). */
    private static final String CONTAINS = "Needle-Contains-0001";
    private static final String EXCERPT_TEXT = "Excerpt-Line-0001";
    private static final String STARTCLI_TEXT = "StartCLI-Output-0001";
    /** Secret-looking log content: a configured password and a value under a credential name. */
    private static final String CONTENT = String.join("\n",
        "2026-11-12 09:59:57,001 INFO  [acme.Login] login with " + TestSecrets.CONFIGURED_PASSWORD,
        "2026-11-12 09:59:58,002 INFO  [acme.Order] " + EXCERPT_TEXT + " " + CONTAINS
            + " password=" + TestSecrets.CREDENTIAL_PAYLOAD_VALUE,
        "2026-11-12 09:59:59,003 INFO  [acme.Order] Other-Line-0001", "");

    @TempDir(factory = GitFreeTempDir.class)
    Path tempDir;

    private Path root;
    private Path client81;
    private EffectiveNodeConfig server;
    private CliResources resources;
    private FakeProcessLauncher launcher;
    private CredentialState credentialState = CredentialState.READY;
    private final MutableClock clock = new MutableClock(NOW);
    private final NodeVersions versions = new NodeVersions(clock,
        node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection =
        new FakeVersionDetection(versions).answer(DEV, "8.1.17");
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        client81 = Files.createDirectories(root.resolve("client-8.1"));
        FakeStartCli.install(client81);
        server = TestNodeConfig.node().versionLine(VersionSetting.AUTO)
            .cliClient("8.1", client81, Optional.of(Path.of("/opt/jdk-17"))).build();
        resources = new CliResources("t029", PrivateFiles.forHost());
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
    }

    @Test
    void aFullCallLogsOneInfoLineAndNoContent() {
        launcher = startCli("ok", Optional.of(CONTENT));

        ServerLogResult result = service().download(request(Optional.of(CONTAINS)));

        assertThat(infoLines()).containsExactly("download_server_log on dev/node1: log trace, file "
            + NAME + ", " + CONTENT.getBytes(StandardCharsets.UTF_8).length + " bytes, window 3,"
            + " outcome OK");
        assertThat(debugLines()).contains("dev/node1: trace log download, client " + client81
            + ", JDK /opt/jdk-17");
        String logged = allLines();
        assertThat(logged).doesNotContain(EXCERPT_TEXT, "Other-Line-0001", CONTAINS, "acme.Login",
            STARTCLI_TEXT, "Trace data", "JAVA_HOME is set", "SECURITY WARNING");
        TestSecrets.assertNoLeak(logged);
        // the excerpt was returned, so its absence from the log is not an empty excerpt
        assertThat(result.excerpt().orElseThrow().lines()).extracting(ExcerptLine::text)
            .anySatisfy(text -> assertThat(text).contains(EXCERPT_TEXT));
    }

    @Test
    void aMissingLogLogsNotFoundWithoutStartCliOutput() {
        launcher = startCli("missing", Optional.empty());

        catchThrowableOfType(ToolErrorException.class,
            () -> service().download(request(Optional.of(CONTAINS))));

        assertThat(infoLines()).containsExactly("download_server_log on dev/node1: log trace,"
            + " file -, - bytes, window -, outcome NOT_FOUND");
        String logged = allLines();
        assertThat(logged).doesNotContain(CONTAINS, STARTCLI_TEXT, "File not found",
            "FileWasNotFound", "ibis_root");
        TestSecrets.assertNoLeak(logged);
    }

    @Test
    void credentialsNotReadInTimeLogTimeout() {
        launcher = startCli("ok", Optional.of(CONTENT));
        credentialState = CredentialState.PENDING;

        catchThrowableOfType(ToolErrorException.class,
            () -> service().download(request(Optional.empty())));

        assertThat(infoLines()).containsExactly("download_server_log on dev/node1: log trace,"
            + " file -, - bytes, window -, outcome TIMEOUT");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void anInvalidInputIsLoggedWithoutEchoingIt() {
        launcher = startCli("ok", Optional.of(CONTENT));

        catchThrowableOfType(ToolErrorException.class, () -> service().download(
            new ServerLogRequest("dev/" + TestSecrets.CONFIGURED_PASSWORD,
                TestSecrets.CREDENTIAL_PAYLOAD_VALUE, root.resolve("logs").toString(),
                OptionalInt.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty())));
        catchThrowableOfType(ToolErrorException.class, () -> service().download(
            new ServerLogRequest("dev/" + TestSecrets.CONFIGURED_PASSWORD, "trace",
                root.resolve("logs").toString(), OptionalInt.empty(), Optional.empty(),
                OptionalInt.empty(), Optional.empty())));

        assertThat(infoLines()).containsExactly(
            "download_server_log on -: log -, file -, - bytes, window -, outcome INVALID_INPUT",
            "download_server_log on -: log trace, file -, - bytes, window -,"
                + " outcome TARGET_UNKNOWN");
        TestSecrets.assertNoLeak(allLines());
    }

    // --- helpers ------------------------------------------------------------------------------

    private ServerLogRequest request(Optional<String> contains) {
        return new ServerLogRequest(DEV.value(), "trace", root.resolve("logs").toString(),
            OptionalInt.empty(), contains, OptionalInt.empty(), Optional.empty());
    }

    private ServerLogService service() {
        CliRunner runner = TestCliRunners.forHost(launcher,
            Map.of("PATH", Platform.searchPath()), resources, STARTCLI_VERSIONS);
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(TestSecrets.CONFIGURED_PASSWORD),
                "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        CliTraceRunner traces = new CliTraceRunner(server, credentials,
            new CredentialGuard(storeLabels("INUBIT", DEV), clock), runner,
            new CliOutputClassifier(new SecretScrubber(), server.secretLabels(),
                runner.windows()));
        ServerLogPort port = new CliServerLogAdapter(server, traces,
            new CliConcurrencyLimiter(CapabilityMatrix.production()));
        TargetFolderRules rules = new TargetFolderRules(root.resolve("home"),
            root.resolve("workspace"), root.resolve("audit"), List.of(root.resolve("config")),
            Map.of(), letter -> false, false);
        return new ServerLogService(new TargetResolver(List.of(DEV)), node -> true,
            node -> Optional.of("jdoe"), rules,
            FakeVersionDetection.gateWithoutClients(versions, detection, MATRIX),
            new Gateways(port), node -> server.cliExportTimeout(),
            FakeConfiguredSecrets.held(TestSecrets.CONFIGURED_PASSWORD), Duration.ofSeconds(30),
            new ExcerptPermit(), new LogFileWriter(),
            new ServerLogAudit.Dependencies(record -> { }, clock, "default", UUID::randomUUID),
            clock, System::nanoTime);
    }

    /**
     * StartCLI replaying {@code server-log/v8_1/trace/<fixtureCase>} with a secret-looking line
     * added to its stdout; writes {@code log}, if given, to the download path.
     */
    private static FakeProcessLauncher startCli(String fixtureCase, Optional<String> log) {
        String stdout = STARTCLI_TEXT + " " + TestSecrets.CONFIGURED_PASSWORD + "\n"
            + fixture(fixtureCase + ".stdout");
        return FakeProcessLauncher.of(stdout, fixture(fixtureCase + ".stderr"),
            Integer.parseInt(fixture(fixtureCase + ".exit").strip())).onLaunch(spec -> {
                if (log.isPresent()) {
                    try {
                        Files.writeString(downloadFile(spec), log.get());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
    }

    private static String fixture(String name) {
        return new String(FakeProcessLauncher.resource("server-log/v8_1/trace/" + name),
            StandardCharsets.UTF_8);
    }

    private static Path downloadFile(LaunchSpec spec) {
        List<String> command = spec.startCliArguments();
        Matcher matcher = DOWNLOAD.matcher(command.get(command.indexOf("--execCommand") + 1));
        assertThat(matcher.find()).isTrue();
        return Path.of(matcher.group(1));
    }

    private List<String> infoLines() {
        return lines(Level.INFO);
    }

    private List<String> debugLines() {
        return lines(Level.DEBUG);
    }

    private List<String> lines(Level level) {
        return appender.list.stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private String allLines() {
        return String.join("\n", appender.list.stream()
            .map(event -> event.getFormattedMessage() + " " + (event.getThrowableProxy() == null
                ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
            .toList());
    }

    /** Only {@code prepareCredentials} and {@code serverLogs}; every other port is an error. */
    private final class Gateways implements GatewayFactory {

        private final ServerLogPort port;

        Gateways(ServerLogPort port) {
            this.port = port;
        }

        @Override
        public CredentialState prepareCredentials(NodeId node, Duration bound) {
            return credentialState;
        }

        @Override
        public ServerLogPort serverLogs(NodeId node) {
            return port;
        }

        @Override
        public Gateway forServer(NodeId node) {
            throw new AssertionError("not expected");
        }

        @Override
        public InubitVersion detect(NodeId node) {
            throw new AssertionError("not expected");
        }

        @Override
        public MonitoringPort monitoring(NodeId node) {
            throw new AssertionError("not expected");
        }

        @Override
        public ProcessQueryPort processes(NodeId node) {
            throw new AssertionError("not expected");
        }

        @Override
        public LogPort logs(NodeId node) {
            throw new AssertionError("not expected");
        }

        @Override
        public InventoryPort inventory(NodeId node) {
            throw new AssertionError("not expected");
        }

        @Override
        public ProcessControlPort processControl(NodeId node) {
            throw new AssertionError("not expected");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId node) {
            throw new AssertionError("not expected");
        }
    }
}
