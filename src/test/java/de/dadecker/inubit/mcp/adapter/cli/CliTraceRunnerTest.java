package de.dadecker.inubit.mcp.adapter.cli;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.config.CliPaths;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T025 (feature 021, research D-1, D-7, D-14): {@code trace --download} through StartCLI into a
 * private directory, replaying the 8.1 trace fixtures of T024 with {@link FakeProcessLauncher},
 * whose launch writes {@code <case>.log} to the {@code --download} path as StartCLI does.
 */
@Timeout(30)
class CliTraceRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "S3cr3t-trace-pw";
    private static final Pattern DOWNLOAD = Pattern.compile("--download '([^']+)'");
    private static final InubitVersion V8_1_17 =
        new InubitVersion(8, 1, OptionalInt.of(17), "8.1.17");
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final String UNCONFIRMED = "download_server_log got an unexpected answer from"
        + " dev/node1; StartCLI did not confirm the trace download";

    @TempDir
    Path root;
    @TempDir
    Path tempRoot;

    private Path client81;
    private Path client90;
    private final Function<NodeId, Optional<InubitVersion>> versions =
        id -> Optional.of(V8_1_17);
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-12T08:00:00Z")));
    /** The permissions of the private directory when StartCLI ran (POSIX). */
    private final List<Set<PosixFilePermission>> launchPermissions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void installClients() throws IOException {
        client81 = Files.createDirectories(root.resolve("client-8.1"));
        client90 = Files.createDirectories(root.resolve("client-9.0"));
        FakeStartCli.install(client81);
        FakeStartCli.install(client90);
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node().versionLine(VersionSetting.AUTO)
            .cliClient("8.1", client81, Optional.of(Path.of("/opt/jdk-17")))
            .cliClient("9.0", client90, Optional.of(Path.of("/opt/jdk-21")));
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private CliRunner runner(FakeProcessLauncher launcher) {
        return runner(launcher, new CliResources("acme", PrivateFiles.forHost()));
    }

    private CliRunner runner(FakeProcessLauncher launcher, CliResources resources) {
        return TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()), resources,
            versions);
    }

    private CliTraceRunner traces(EffectiveNodeConfig config, NodeCredentials credentials,
        CliRunner runner) {
        return new CliTraceRunner(config, credentials, guard, runner,
            new CliOutputClassifier(new SecretScrubber(), config.secretLabels(), runner.windows()),
            tempRoot);
    }

    private CliTraceRunner traces(FakeProcessLauncher launcher) {
        return traces(node().build(), credentials(), runner(launcher));
    }

    private static String fixture(String name) {
        return new String(FakeProcessLauncher.resource("server-log/v8_1/trace/" + name),
            StandardCharsets.UTF_8);
    }

    /** StartCLI that prints {@code stdout} and writes {@code log}, if any, to the download path. */
    private FakeProcessLauncher answering(String stdout, String stderr, int exitCode,
        Optional<byte[]> log) {
        return FakeProcessLauncher.of(stdout, stderr, exitCode).onLaunch(spec -> {
            Path file = downloadFile(spec);
            try {
                if (POSIX) {
                    launchPermissions.add(Files.getPosixFilePermissions(file.getParent()));
                }
                if (log.isPresent()) {
                    Files.write(file, log.get());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Replays {@code server-log/v8_1/trace/<fixtureCase>.*}. */
    private FakeProcessLauncher replaying(String fixtureCase) {
        return answering(fixture(fixtureCase + ".stdout"), fixture(fixtureCase + ".stderr"),
            Integer.parseInt(fixture(fixtureCase + ".exit").strip()),
            Optional.of(FakeProcessLauncher.resource("server-log/v8_1/trace/" + fixtureCase
                + ".log")));
    }

    private static String execCommand(LaunchSpec spec) {
        List<String> command = spec.startCliArguments();
        return command.get(command.indexOf("--execCommand") + 1);
    }

    private static Path downloadFile(LaunchSpec spec) {
        Matcher matcher = DOWNLOAD.matcher(execCommand(spec));
        assertThat(matcher.find()).as(execCommand(spec)).isTrue();
        return Path.of(matcher.group(1));
    }

    private List<Path> leftovers() throws IOException {
        try (Stream<Path> files = Files.list(tempRoot)) {
            return files.toList();
        }
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().toString()).doesNotContain(PASSWORD);
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    /** The call deadline {@code budget} from now ({@link System#nanoTime()}). */
    private static long deadline(Duration budget) {
        return System.nanoTime() + budget.toNanos();
    }

    /** Another login attempt holds the unconfirmed guard for {@code duration}, then ends. */
    private Thread otherLoginFor(Duration duration) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        Thread other = Thread.ofVirtual().start(() -> {
            try (CredentialGuard.Permit permit = guard.acquire()) {
                held.countDown();
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(held.await(5, TimeUnit.SECONDS)).as("the other attempt holds the guard")
            .isTrue();
        return other;
    }

    // --- success -------------------------------------------------------------------------------

    @Test
    void theTraceLogIsDownloadedWithTheAllowListedCommandIntoAPrivateDirectory()
        throws IOException {
        FakeProcessLauncher launcher = replaying("ok");

        try (DownloadedLog log = traces(launcher).download(deadline(BUDGET))) {
            Path file = downloadFile(launcher.last().spec());
            assertThat(execCommand(launcher.last().spec()))
                .isEqualTo("trace --download '" + Platform.commandPath(file) + "'");
            // the requested path, never the one in StartCLI's message
            assertThat(log.file()).isEqualTo(file);
            assertThat(file.getFileName()).hasToString("trace.log");
            assertThat(file.getParent().getParent())
                .isEqualTo(CliPaths.forCommand(tempRoot, Platform.windows()));
            assertThat(file.getParent().getFileName().toString())
                .startsWith("inubit-mcp-log-acme-" + ProcessHandle.current().pid() + "-");
            byte[] content = FakeProcessLauncher.resource("server-log/v8_1/trace/ok.log");
            assertThat(Files.readAllBytes(log.file())).isEqualTo(content);
            assertThat(log.size()).isEqualTo(content.length);
            assertThat(log.inubitNode()).isEmpty();
            assertThat(log.presence()).isEqualTo(LogPresence.PRESENT);
            if (POSIX) {
                assertThat(launchPermissions)
                    .containsExactly(PosixFilePermissions.fromString("rwx------"));
            }
            assertThat(leftovers()).hasSize(1);
        }
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void thePasswordGoesOnlyToStdin() {
        FakeProcessLauncher launcher = replaying("ok");

        traces(launcher).download(deadline(BUDGET)).close();

        LaunchSpec spec = launcher.last().spec();
        assertThat(spec.command()).doesNotContain(PASSWORD)
            .noneMatch(argument -> argument.contains(PASSWORD));
        assertThat(spec.environment().values()).noneMatch(value -> value.contains(PASSWORD));
        assertThat(spec.startCliArguments()).containsExactly("-u", "jdoe", "--execCommand",
            "trace --download '" + Platform.commandPath(downloadFile(spec)) + "'",
            "https://inubit.example.test:8443" + EffectiveNodeConfig.DEFAULT_CLI_PATH);
        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
    }

    @Test
    void theClientChosenForEightOneRunsTheDownload() {
        FakeProcessLauncher launcher = replaying("ok");

        traces(launcher).download(deadline(BUDGET)).close();

        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(client81);
    }

    @Test
    void anEmptyTraceLogIsAPresentLogOfZeroBytes() throws IOException {
        try (DownloadedLog log = traces(replaying("empty")).download(deadline(BUDGET))) {
            assertThat(log.size()).isZero();
            assertThat(log.presence()).isEqualTo(LogPresence.PRESENT);
        }
        assertThat(leftovers()).isEmpty();
    }

    // --- failures ------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "missing, NOT_FOUND",
        "login_failure, AUTH_FAILED",
        "forbidden, FORBIDDEN"})
    void everyFailedTraceCaseHasItsCodeAndLeavesNothing(String fixtureCase, ErrorCode expected)
        throws IOException {
        ToolError error = errorOf(() -> traces(replaying(fixtureCase)).download(deadline(BUDGET)));

        assertThat(error.code()).isEqualTo(expected);
        assertThat(error.node()).contains(DEV);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aMissingTraceLogNamesTheNode() {
        ToolError error = errorOf(() -> traces(replaying("missing")).download(deadline(BUDGET)));

        assertThat(error.message()).isEqualTo("trace.log does not exist on dev/node1");
        assertThat(error.nextStep())
            .isEqualTo("Check the trace settings of the server in the Workbench");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "1-OK: Trace data (0 MB) stored to: /tmp/inubit-mcp-log-0001/trace.log\n",
        "Trace data (0 MB) stored to: /tmp/a/trace.log\nTrace data (0 MB) stored to: /tmp/b/x\n",
        "Trace data stored to: /tmp/inubit-mcp-log-0001/trace.log\n"})
    void withoutExactlyOneConfirmationLineTheDownloadIsUnexpected(String confirmation)
        throws IOException {
        String stdout = "JAVA_HOME is set\nPassword: \n" + confirmation;
        FakeProcessLauncher launcher = answering(stdout, "", 0,
            Optional.of(FakeProcessLauncher.resource("server-log/v8_1/trace/ok.log")));

        ToolError error = errorOf(() -> traces(launcher).download(deadline(BUDGET)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNCONFIRMED);
        assertThat(error.nextStep())
            .isEqualTo("Download the log in the Workbench; report the INUBIT version");
        assertThat(error.node()).contains(DEV);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aConfirmationWithoutAFileIsUnexpected() throws IOException {
        FakeProcessLauncher launcher = answering(fixture("ok.stdout"), fixture("ok.stderr"), 0,
            Optional.empty());

        ToolError error = errorOf(() -> traces(launcher).download(deadline(BUDGET)));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNCONFIRMED);
        assertThat(error.likelyCause()).contains("no file");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void theRemainingBudgetBoundsTheRunAndNamesCliExportTimeout() throws IOException {
        FakeProcessLauncher launcher = replaying("ok").hanging();

        long start = System.nanoTime();
        ToolError error = errorOf(
            () -> traces(launcher).download(deadline(Duration.ofMillis(300))));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliExportTimeout")
            .doesNotContain("raise cliTimeout");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(launcher.last().destroyed()).isTrue();
        assertThat(leftovers()).isEmpty();
    }

    /** FR-013, D-15: the wait for another login attempt is inside the call deadline. */
    @Test
    void aDeadlinePassedWhileWaitingForAnotherLoginLaunchesNothing() throws Exception {
        FakeProcessLauncher launcher = replaying("ok");
        Thread other = otherLoginFor(Duration.ofSeconds(3));

        long start = System.nanoTime();
        ToolError error = errorOf(
            () -> traces(launcher).download(deadline(Duration.ofMillis(300))));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        other.join();

        assertThat(took).as("not until the other attempt ends").isLessThan(Duration.ofMillis(2000));
        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(DEV);
        assertThat(error.nextStep()).contains("raise cliExportTimeout")
            .doesNotContain("raise cliTimeout");
        assertThat(launcher.launchCount()).isZero();
        assertThat(guard.confirmed()).as("no login result").isFalse();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aDeadlinePassedBeforeTheLoginLaunchesNothing() throws IOException {
        FakeProcessLauncher launcher = replaying("ok");

        ToolError error = errorOf(() -> traces(launcher).download(System.nanoTime() - 1));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).isEqualTo("cliExportTimeout on dev/node1 passed before the"
            + " login; StartCLI was not started");
        assertThat(error.nextStep()).contains("raise cliExportTimeout");
        assertThat(launcher.launchCount()).isZero();
        assertThat(guard.confirmed()).as("no login result").isFalse();
        assertThat(leftovers()).isEmpty();
    }

    /** FR-013, D-15: StartCLI gets what is left of the deadline after the wait for the guard. */
    @Test
    void startCliGetsWhatIsLeftOfTheDeadlineAfterTheWaitForAnotherLogin() throws Exception {
        FakeProcessLauncher launcher = replaying("ok").hanging();
        Thread other = otherLoginFor(Duration.ofMillis(1500));

        long start = System.nanoTime();
        ToolError error = errorOf(
            () -> traces(launcher).download(deadline(Duration.ofSeconds(2))));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        other.join();

        assertThat(took).as("one deadline of 2 s, not 1.5 s of waiting plus 2 s for StartCLI")
            .isGreaterThanOrEqualTo(Duration.ofMillis(1900)).isLessThan(Duration.ofMillis(3400));
        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliExportTimeout");
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().destroyed()).isTrue();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void anInterruptedDownloadDeletesTheDirectory() throws Exception {
        FakeProcessLauncher launcher = replaying("ok").hanging();
        CliTraceRunner traces = traces(launcher);
        AtomicReference<ToolError> error = new AtomicReference<>();

        Thread call = Thread.ofVirtual().start(() -> error.set(errorOf(
            () -> traces.download(deadline(BUDGET)))));
        while (launcher.launchCount() == 0) {
            Thread.sleep(10);
        }
        call.interrupt();
        call.join(Duration.ofSeconds(10));

        assertThat(error.get().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aShutdownStopsARunningDownloadAndDeletesItsDirectory() throws Exception {
        FakeProcessLauncher launcher = replaying("ok").hanging();
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        CliTraceRunner traces = traces(node().build(), credentials(), runner(launcher, resources));
        AtomicReference<ToolError> error = new AtomicReference<>();

        Thread call = Thread.ofVirtual().start(() -> error.set(errorOf(
            () -> traces.download(deadline(BUDGET)))));
        while (launcher.launchCount() == 0 || leftovers().isEmpty()) {
            Thread.sleep(10);
        }
        resources.close();
        call.join(Duration.ofSeconds(10));

        assertThat(error.get()).isNotNull();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void withoutAClientForEightOneTheCliIsUnavailableNamingItsEntry() throws IOException {
        FakeProcessLauncher launcher = replaying("ok");
        EffectiveNodeConfig onlyNine = TestNodeConfig.node().versionLine(VersionSetting.AUTO)
            .cliClient("9.0", client90, Optional.of(Path.of("/opt/jdk-21"))).build();

        ToolError error = errorOf(() -> traces(onlyNine, credentials(), runner(launcher))
            .download(deadline(BUDGET)));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.toString()).contains("defaults.cliClients.\"8.1\"");
        assertThat(launcher.launchCount()).isZero();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aTemporaryDirectoryStartCliCannotTakeMakesTheCliUnavailable() throws IOException {
        Path odd = Files.createDirectories(tempRoot.resolve("tmp~dir"));
        FakeProcessLauncher launcher = replaying("ok");
        CliRunner runner = runner(launcher);
        CliTraceRunner traces = new CliTraceRunner(node().build(), credentials(), guard, runner,
            new CliOutputClassifier(new SecretScrubber()), odd);

        ToolError error = errorOf(() -> traces.download(deadline(BUDGET)));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.nextStep()).contains("java.io.tmpdir");
        assertThat(error.node()).contains(DEV);
        assertThat(launcher.launchCount()).isZero();
        try (Stream<Path> files = Files.list(odd)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    // --- edge case "credentials not available, TLS failure" (spec) ------------------------------
    // Maintenance mode is not a StartCLI-level case: it comes from the detection before the
    // launch and is covered there (T027).

    @Test
    void credentialsThatAreNotAvailableAreTheNodesCredentialErrorWithoutLaunch()
        throws IOException {
        FakeProcessLauncher launcher = replaying("ok");
        NodeCredentials none = new NodeCredentials(DEV, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolError error = errorOf(() -> traces(node().build(), none, runner(launcher))
            .download(deadline(BUDGET)));

        // as every StartCLI export of the node (CliExportRunner.checkPreconditions)
        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.nextStep()).contains("INUBIT_DEV_NODE1_PASSWORD");
        assertThat(launcher.launchCount()).isZero();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aTlsFailureOfStartCliIsATlsError() throws IOException {
        FakeProcessLauncher launcher = answering("JAVA_HOME is set\nPassword: \n"
            + "ERROR 10:00:00,000 [main      ] CLI                       ERROR\n"
            + "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailed@@@Login to the"
            + " server failed.@End@javax.net.ssl.SSLHandshakeException: PKIX path building"
            + " failed\nCONNECTION ERROR\n", "", 1, Optional.of(new byte[0]));

        ToolError error = errorOf(() -> traces(launcher).download(deadline(BUDGET)));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aRefusedPinProbeIsATlsErrorWithoutLaunch() throws IOException {
        FakeProcessLauncher launcher = replaying("ok");
        StartCliPinProbe refusing = server -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.TLS_ERROR,
                "The certificate at the StartCLI address of " + server.id() + " does not match",
                "Another server answers", "Check the pin").withNode(server.id()));
        };
        CliRunner runner = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()), refusing, versions);

        ToolError error = errorOf(() -> traces(marked(node().build()), credentials(), runner)
            .download(deadline(BUDGET)));

        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(launcher.launchCount()).isZero();
        assertThat(leftovers()).isEmpty();
    }

    /** {@code server} as a server of a stage marked with {@code tls.pinPerServer}. */
    private static EffectiveNodeConfig marked(EffectiveNodeConfig server) {
        EffectiveNodeConfig.Tls tls = server.tls();
        return new EffectiveNodeConfig(server.id(), server.production(), server.baseUrl(),
            server.allowInsecureHttp(), server.versionLine(), server.write(),
            new EffectiveNodeConfig.Tls(tls.trustStore(), tls.disableHostnameVerification(),
                tls.pinnedCertificateSha256(), true),
            server.cli(), server.inventory(), server.timeout(), server.cliTimeout(),
            server.cliExportTimeout(), server.hangingThreshold(), server.confirmationTtl(),
            server.credentialPrefix(), server.development(), server.secretPlan(),
            server.archivesProductionOptIn(), server.queueEntryRead(), server.serverLogRead(),
            server.cliSources());
    }
}
