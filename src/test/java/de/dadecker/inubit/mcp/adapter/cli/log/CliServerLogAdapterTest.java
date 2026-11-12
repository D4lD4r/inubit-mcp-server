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
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T026 (feature 021, FR-013, research D-15, D-19): the server-log port takes a StartCLI slot of
 * the node and runs the trace download, both within the call deadline the service passes; when
 * that deadline is exceeded the call ends in {@code TIMEOUT} naming {@code cliExportTimeout},
 * the process is stopped, the slot released and the private directory deleted.
 */
@Timeout(30)
class CliServerLogAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    /** The CliResources profile; the private directories are {@code inubit-mcp-log-<it>-…}. */
    private static final String PROFILE = "t026";
    private static final String PASSWORD = "S3cr3t-log-pw";
    private static final Pattern DOWNLOAD = Pattern.compile("--download '([^']+)'");
    private static final InubitVersion V8_1_17 =
        new InubitVersion(8, 1, OptionalInt.of(17), "8.1.17");
    private static final Function<NodeId, Optional<InubitVersion>> VERSIONS =
        id -> Optional.of(V8_1_17);
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final String LARGE_LOGS = "large logs need a larger cliExportTimeout";

    @TempDir
    Path root;

    private Path client81;
    private final CliConcurrencyLimiter limiter =
        new CliConcurrencyLimiter(CapabilityMatrix.production());
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-12T08:00:00Z")));
    private CliResources resources;

    @BeforeEach
    void installClient() throws IOException {
        client81 = Files.createDirectories(root.resolve("client-8.1"));
        FakeStartCli.install(client81);
        resources = new CliResources(PROFILE, PrivateFiles.forHost());
    }

    @AfterEach
    void closeResources() {
        resources.close();
    }

    private EffectiveNodeConfig node() {
        return node(Duration.ofSeconds(1));
    }

    private EffectiveNodeConfig node(Duration cliExportTimeout) {
        return TestNodeConfig.node().versionLine(VersionSetting.V8_1)
            .cliClient("8.1", client81, Optional.of(Path.of("/opt/jdk-17")))
            .cliExportTimeout(cliExportTimeout).build();
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private CliServerLogAdapter adapter(EffectiveNodeConfig server,
        FakeProcessLauncher launcher) {
        CliRunner runner = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            resources, VERSIONS);
        CliTraceRunner traces = new CliTraceRunner(server, credentials(), guard, runner,
            new CliOutputClassifier(new SecretScrubber(), server.secretLabels(),
                runner.windows()));
        return new CliServerLogAdapter(server, traces, limiter);
    }

    private static String fixture(String name) {
        return new String(FakeProcessLauncher.resource("server-log/v8_1/trace/" + name),
            StandardCharsets.UTF_8);
    }

    /** StartCLI replaying the {@code ok} trace case: writes {@code ok.log} to the download path. */
    private static FakeProcessLauncher replayingOk() {
        return FakeProcessLauncher.of(fixture("ok.stdout"), fixture("ok.stderr"),
            Integer.parseInt(fixture("ok.exit").strip())).onLaunch(spec -> {
                try {
                    Files.write(downloadFile(spec),
                        FakeProcessLauncher.resource("server-log/v8_1/trace/ok.log"));
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

    /** This process's private log directories below {@code java.io.tmpdir}. */
    private static List<Path> leftovers() throws IOException {
        String prefix = "inubit-mcp-log-" + PROFILE + "-" + ProcessHandle.current().pid() + "-";
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix))
                .toList();
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

    /** Both slots of {@code server} are free: two can be taken at once. */
    private void assertBothSlotsFree(EffectiveNodeConfig server) {
        try (CliConcurrencyLimiter.Permit first =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliExportTimeout");
             CliConcurrencyLimiter.Permit second =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliExportTimeout")) {
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
        }
    }

    @Test
    void aTraceDownloadTakesASlotAndReleasesItWhenDone() throws IOException {
        EffectiveNodeConfig server = node();
        AtomicReference<ErrorCode> slotDuringRun = new AtomicReference<>();
        FakeProcessLauncher launcher = replayingOk();
        launcher.onLaunch(launcher.onLaunchAction().andThen(spec -> slotDuringRun.set(
            errorOf(() -> limiter.acquireForHelper(server, Duration.ZERO, "cliExportTimeout")
                .close()).code())));

        try (CliConcurrencyLimiter.Permit other =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliExportTimeout");
             DownloadedLog log = adapter(server, launcher).download(ServerLogKind.TRACE,
                 V8_1_17, System.nanoTime() + BUDGET.toNanos())) {
            assertThat(slotDuringRun).as("the download held the second slot")
                .hasValue(ErrorCode.TIMEOUT);
            assertThat(Files.readAllBytes(log.file()))
                .isEqualTo(FakeProcessLauncher.resource("server-log/v8_1/trace/ok.log"));
            assertThat(leftovers()).hasSize(1);
        }
        assertBothSlotsFree(server);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aHangingDownloadEndsInTimeoutWithinTheDeadlineAndLeavesNothing() throws IOException {
        EffectiveNodeConfig server = node();
        FakeProcessLauncher launcher = replayingOk().hanging();

        long start = System.nanoTime();
        ToolError error = errorOf(() -> adapter(server, launcher).download(ServerLogKind.TRACE,
            V8_1_17, start + server.cliExportTimeout().toNanos()));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).isEqualTo(
            "The trace log download from dev/node1 did not finish within cliExportTimeout (1 s)");
        assertThat(error.nextStep()).contains("raise cliExportTimeout").endsWith(LARGE_LOGS)
            .doesNotContain("raise cliTimeout");
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().destroyed()).as("process stopped").isTrue();
        assertThat(launcher.last().treeAlive()).isFalse();
        assertBothSlotsFree(server);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void bothSlotsHeldByOthersEndTheSlotWaitInTimeoutNamingCliExportTimeout()
        throws IOException {
        EffectiveNodeConfig server = node(Duration.ofMillis(300));
        FakeProcessLauncher launcher = replayingOk();

        ToolError error;
        long start = System.nanoTime();
        try (CliConcurrencyLimiter.Permit first =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliTimeout");
             CliConcurrencyLimiter.Permit second =
                 limiter.acquireForHelper(server, Duration.ZERO, "cliTimeout")) {
            error = errorOf(() -> adapter(server, launcher).download(ServerLogKind.TRACE,
                V8_1_17, start + server.cliExportTimeout().toNanos()));
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start))
            .isGreaterThanOrEqualTo(Duration.ofMillis(300)).isLessThan(Duration.ofSeconds(10));
        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).isEqualTo("The trace log download from dev/node1 did not"
            + " finish within cliExportTimeout (300 ms)");
        assertThat(error.likelyCause()).contains("other StartCLI runs on the node");
        assertThat(error.nextStep()).contains("raise cliExportTimeout").endsWith(LARGE_LOGS)
            .doesNotContain("raise cliTimeout");
        assertThat(launcher.launchCount()).isZero();
        assertBothSlotsFree(server);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aDeadlineAlreadyPassedEndsInTimeoutWithoutLaunch() throws IOException {
        EffectiveNodeConfig server = node();
        FakeProcessLauncher launcher = replayingOk();

        ToolError error = errorOf(() -> adapter(server, launcher).download(ServerLogKind.TRACE,
            V8_1_17, System.nanoTime() - 1));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).isEqualTo(
            "The trace log download from dev/node1 did not finish within cliExportTimeout (1 s)");
        assertThat(error.nextStep()).contains("raise cliExportTimeout").endsWith(LARGE_LOGS);
        assertThat(launcher.launchCount()).isZero();
        assertBothSlotsFree(server);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aCancelledDownloadKeepsItsMessageAndReleasesTheSlot() throws Exception {
        EffectiveNodeConfig server = node();
        FakeProcessLauncher launcher = replayingOk().hanging();
        CliServerLogAdapter adapter = adapter(server, launcher);
        AtomicReference<ToolError> error = new AtomicReference<>();

        Thread call = Thread.ofVirtual().start(() -> error.set(errorOf(() -> adapter.download(
            ServerLogKind.TRACE, V8_1_17, System.nanoTime() + BUDGET.toNanos()))));
        while (launcher.launchCount() == 0) {
            Thread.sleep(10);
        }
        call.interrupt();
        call.join(Duration.ofSeconds(10));

        assertThat(error.get().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.get().message()).as("not the deadline text").contains("cancelled")
            .doesNotContain("did not finish within");
        assertThat(error.get().nextStep()).endsWith(LARGE_LOGS);
        assertBothSlotsFree(server);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void theWebServiceLogIsNotWiredYet() throws IOException {
        EffectiveNodeConfig server = node();
        FakeProcessLauncher launcher = replayingOk();

        ToolError error = errorOf(() -> adapter(server, launcher).download(
            ServerLogKind.WEBSERVICE, V8_1_17, System.nanoTime() + BUDGET.toNanos()));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).isEqualTo("web service log channel not wired");
        assertThat(launcher.launchCount()).isZero();
        assertBothSlotsFree(server);
        assertThat(leftovers()).isEmpty();
    }
}
