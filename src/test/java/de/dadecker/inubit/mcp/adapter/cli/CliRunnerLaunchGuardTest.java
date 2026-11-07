package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ClientCheck;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientCheckLookup;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 017 (T017, FR-017, research D-4): the launch guard of {@link CliRunner#run} and
 * {@link CliRunner#runSession} launches only a client whose recorded check admits the version it
 * was chosen for; it never probes, and the {@code -v}/{@code -h} probes and
 * {@link CliRunner#checkAvailable} are not guarded.
 */
@Timeout(30)
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX launch; the guard runs before any"
    + " platform-specific step")
class CliRunnerLaunchGuardTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-017-guard";
    private static final String BASE_URL = "https://inubit-acme.example.test:8443";
    private static final Path JDK_17 = Path.of("/opt/jdk-17");
    private static final Path JDK_21 = Path.of("/opt/jdk-21");
    private static final InubitVersion V8_1_17 = version("8.1.17");
    private static final InubitVersion V9_0_7 = version("9.0.7");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path root;

    private Path client81;
    private Path client90;
    private final AtomicReference<Optional<InubitVersion>> current =
        new AtomicReference<>(Optional.empty());
    private final Function<NodeId, Optional<InubitVersion>> versions = id -> current.get();
    private final Map<ClientKey, ClientCheck> recorded = new ConcurrentHashMap<>();
    private final ClientCheckLookup checks = (node, key) -> recorded(node, key);

    /** The lookup over {@link #recorded}, answered from one copy of it. */
    private ClientCheckLookup.Recorded recorded(NodeId node, ClientKey key) {
        Map<ClientKey, ClientCheck> snapshot = node.equals(NODE) ? Map.copyOf(recorded) : Map.of();
        if (snapshot.containsKey(key)) {
            return new ClientCheckLookup.Recorded.ForClient(snapshot.get(key));
        }
        return snapshot.isEmpty() ? ClientCheckLookup.Recorded.Absent.NOTHING
            : ClientCheckLookup.Recorded.Absent.OTHER_CLIENTS_ONLY;
    }

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static ClientVersion client(String version, String from, String to) {
        return new ClientVersion(version(version), InubitVersion.parse(from),
            InubitVersion.parse(to));
    }

    @BeforeEach
    void install() throws IOException {
        client81 = Files.createDirectories(root.resolve("client-8.1"));
        client90 = Files.createDirectories(root.resolve("client-9.0"));
        FakeStartCli.install(client81);
        FakeStartCli.install(client90);
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node()
            .baseUrl(BASE_URL)
            .versionLine(VersionSetting.AUTO)
            .cliClient("8.1", client81, Optional.of(JDK_17))
            .cliClient("9.0", client90, Optional.of(JDK_21));
    }

    private ClientKey key81() {
        return new ClientKey(client81.toString(), JDK_17.toString());
    }

    private ClientKey key90() {
        return new ClientKey(client90.toString(), JDK_21.toString());
    }

    private CliRunner runner(FakeProcessLauncher launcher) {
        return TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()), versions, checks);
    }

    private static CredentialGuard guard(EffectiveNodeConfig server) {
        return new CredentialGuard(server.secretLabels(),
            new MutableClock(Instant.parse("2026-11-06T08:00:00Z")));
    }

    private static CliResult run(CliRunner runner, EffectiveNodeConfig server) {
        return runner.run(server, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), TIMEOUT, guard(server));
    }

    private static CliResult session(CliRunner runner, EffectiveNodeConfig server) {
        return runner.runSession(server, USER, Secret.of(PASSWORD),
            new CliClientOptions(true, true, true, Optional.empty()), List.of(), TIMEOUT,
            CliRunner.MAX_OUTPUT_BYTES, guard(server));
    }

    private static void assertNoLeak(ToolError error) {
        String all = error.message() + " " + error.likelyCause() + " " + error.nextStep();
        assertThat(all).doesNotContain(USER, PASSWORD, BASE_URL, "inubit-acme");
    }

    // --- no check for the node: 0.8.0 ------------------------------------------------------

    @Test
    void withoutAnyCheckForTheNodeRunAndSessionLaunchAsIn080() {
        current.set(Optional.of(V9_0_7));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = runner(launcher);
        EffectiveNodeConfig server = node().build();

        run(runner, server);
        session(runner, server);

        assertThat(launcher.launchCount()).isEqualTo(2);
    }

    @Test
    void aSixOneSessionStartingBeforeAnyCheckIsLaunched() throws IOException {
        Path client61 = Files.createDirectories(root.resolve("client-6.1"));
        FakeStartCli.install(client61);
        EffectiveNodeConfig server = TestNodeConfig.node().baseUrl(BASE_URL)
            .versionLine(VersionSetting.V6_1).cliHome(client61).cliJavaHome(JDK_17).build();
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        session(runner(launcher), server);

        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void aCheckOfTheChosenClientRecordedWhileTheGuardLooksIsNoChange() {
        current.set(Optional.of(V9_0_7));
        AtomicInteger lookups = new AtomicInteger();
        // the gate's probe of the 9.0 client records its result right after the guard's look
        ClientCheckLookup racing = (node, key) -> {
            lookups.incrementAndGet();
            ClientCheckLookup.Recorded seen = recorded(node, key);
            recorded.put(key90(), new ClientCheck.Checked(client("9.0.7", "9.0.0", "9.0.99")));
            return seen;
        };
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        run(TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()), versions, racing),
            node().build());

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(lookups).as("one snapshot per launch").hasValue(1);
    }

    // --- a check for the chosen key --------------------------------------------------------

    @Test
    void aCompatibleResultLaunches() {
        current.set(Optional.of(V9_0_7));
        recorded.put(key90(), new ClientCheck.Checked(client("9.0.7", "9.0.0", "9.0.99")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        run(runner(launcher), node().build());

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(client90);
    }

    @Test
    void aResultThatCannotBeEvaluatedOrARecordedFailureLaunches() {
        current.set(Optional.of(V9_0_7));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = runner(launcher);
        EffectiveNodeConfig server = node().build();

        recorded.put(key90(), new ClientCheck.Checked(new ClientVersion(V9_0_7,
            Optional.empty(), Optional.empty())));
        run(runner, server);
        recorded.put(key90(), new ClientCheck.Failed(ErrorCode.TIMEOUT));
        session(runner, server);

        assertThat(launcher.launchCount()).isEqualTo(2);
    }

    @Test
    void aResultExcludingTheSelectedVersionIsRefusedBeforeTheLaunch() {
        current.set(Optional.of(V9_0_7));
        recorded.put(key90(), new ClientCheck.Checked(client("8.1.17", "4.0.1", "8.1.17")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = runner(launcher);
        EffectiveNodeConfig server = node().build();

        for (Runnable call : List.<Runnable>of(() -> run(runner, server),
            () -> session(runner, server))) {
            ToolError error = catchThrowableOfType(call::run, ToolErrorException.class).error();

            assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(error.message()).isEqualTo("StartCLI client 8.1.17 of dev/node1 supports"
                + " INUBIT 4.0.1 to 8.1.17, which excludes INUBIT 9.0.7; StartCLI refused;"
                + " nothing was sent");
            assertThat(error.likelyCause()).isEqualTo("The local StartCLI client does not"
                + " support the INUBIT version of dev/node1");
            assertThat(error.nextStep()).isEqualTo("Install a StartCLI client on the host of"
                + " this server that supports INUBIT 9.0, or add defaults.cliClients.\"9.0\"");
            assertThat(error.node()).contains(NODE);
            assertNoLeak(error);
        }
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void anExcludingResultOnAnEightOneNodeWithoutCliClientsNamesNoEntry() {
        current.set(Optional.of(V8_1_17));
        EffectiveNodeConfig server = TestNodeConfig.node().baseUrl(BASE_URL)
            .versionLine(VersionSetting.AUTO).cliHome(client81).cliJavaHome(JDK_17).build();
        recorded.put(key81(), new ClientCheck.Checked(client("8.0.9", "4.0.1", "8.0.9")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        ToolError error = catchThrowableOfType(() -> run(runner(launcher), server),
            ToolErrorException.class).error();

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.nextStep()).isEqualTo("Install a StartCLI client on the host of this"
            + " server that supports INUBIT 8.1");
        assertThat(launcher.launchCount()).isZero();
    }

    // --- the race: the client changed between admission and launch -------------------------

    @Test
    void aClientChangedBetweenAdmissionAndRunIsNotLaunched() {
        // admitted on 8.1: the gate checked the 8.1 client
        current.set(Optional.of(V8_1_17));
        recorded.put(key81(), new ClientCheck.Checked(client("8.1.17", "8.1.0", "8.1.99")));
        // a parallel get_health observed 9.0 before the launch
        current.set(Optional.of(V9_0_7));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = runner(launcher);
        EffectiveNodeConfig server = node().build();

        for (Runnable call : List.<Runnable>of(() -> run(runner, server),
            () -> session(runner, server))) {
            ToolError error = catchThrowableOfType(call::run, ToolErrorException.class).error();

            assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
            assertThat(error.message()).isEqualTo("The StartCLI client for dev/node1 changed to"
                + " the client for INUBIT 9.0 during this call; the new client has not been"
                + " checked yet");
            assertThat(error.likelyCause()).isEqualTo("INUBIT on dev/node1 was found to run a"
                + " different version while the call was waiting");
            assertThat(error.nextStep()).isEqualTo("Call the tool again");
            assertThat(error.node()).contains(NODE);
            assertNoLeak(error);
        }
        assertThat(launcher.launchCount()).as("nothing launched").isZero();
    }

    @Test
    void aSelectionMadeEarlierIsGuardedWithItsOwnKey() {
        current.set(Optional.of(V9_0_7));
        recorded.put(key81(), new ClientCheck.Checked(client("8.1.17", "8.1.0", "8.1.99")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = runner(launcher);
        EffectiveNodeConfig server = node().build();
        CliRunner.Selection eightOne = runner.select(server, Optional.of(V8_1_17));
        CliRunner.Selection nine = runner.select(server, Optional.of(V9_0_7));

        assertThatCode(() -> runner.checkLaunch(eightOne)).doesNotThrowAnyException();
        assertThat(catchThrowableOfType(() -> runner.checkLaunch(nine),
            ToolErrorException.class).error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);

        runner.runSession(eightOne, USER, Secret.of(PASSWORD),
            new CliClientOptions(true, true, true, Optional.empty()), List.of(), TIMEOUT,
            CliRunner.MAX_OUTPUT_BYTES, guard(server));
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(client81);
    }

    @Test
    void aChangeToAnUnknownVersionNamesNoVersion() {
        recorded.put(key81(), new ClientCheck.Checked(client("8.1.17", "8.1.0", "8.1.99")));
        EffectiveNodeConfig server = node().defaultCliHome(client90).defaultCliJavaHome(JDK_21)
            .build();
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        ToolError error = catchThrowableOfType(() -> run(runner(launcher), server),
            ToolErrorException.class).error();

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The StartCLI client for dev/node1 changed during"
            + " this call; the new client has not been checked yet");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void theGuardNeverProbes() {
        current.set(Optional.of(V9_0_7));
        recorded.put(key81(), new ClientCheck.Checked(client("8.1.17", "8.1.0", "8.1.99")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        catchThrowableOfType(() -> run(runner(launcher), node().build()),
            ToolErrorException.class);

        assertThat(launcher.processes()).as("no -v run, no -h run").isEmpty();
        assertThat(recorded).containsOnlyKeys(key81());
    }

    // --- not guarded: the probes and checkAvailable -----------------------------------------

    @Test
    void theVersionProbeOfAnotherKeyStillExecutes() {
        current.set(Optional.of(V9_0_7));
        recorded.put(key81(), new ClientCheck.Checked(client("8.1.17", "8.1.0", "8.1.99")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("CLI 9.0.7\n", "", 0);
        EffectiveNodeConfig server = node().build();
        CliVersionProbe probe = new CliVersionProbe(runner(launcher), id -> server,
            new SecretScrubber());

        assertThat(probe.probe(NODE, Optional.of(V9_0_7), TIMEOUT).key()).isEqualTo(key90());

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().command()).contains("-v");
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(client90);
    }

    @Test
    void theHelpProbeOfAnotherKeyStillExecutes() throws IOException {
        Path client61 = Files.createDirectories(root.resolve("client-6.1"));
        FakeStartCli.install(client61);
        EffectiveNodeConfig server = TestNodeConfig.node().baseUrl(BASE_URL)
            .versionLine(VersionSetting.V6_1).cliHome(client61).cliJavaHome(JDK_17).build();
        recorded.put(key81(), new ClientCheck.Checked(client("8.1.17", "8.1.0", "8.1.99")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("usage: startcli\n -u <user>\n",
            "", 0);
        CliClientProbe probe = new CliClientProbe(runner(launcher), id -> server,
            CapabilityMatrix.production(),
            new MutableClock(Instant.parse("2026-11-06T08:00:00Z")));

        probe.optionsFor(NODE, TIMEOUT);

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().command()).contains("-h");
    }

    @Test
    void checkAvailableIsNotGuarded() {
        current.set(Optional.of(V9_0_7));
        recorded.put(key81(), new ClientCheck.Checked(client("8.1.17", "8.1.0", "8.1.99")));
        recorded.put(key90(), new ClientCheck.Checked(client("8.1.17", "4.0.1", "8.1.17")));
        CliRunner runner = runner(FakeProcessLauncher.of("", "", 0));
        EffectiveNodeConfig server = node().build();

        assertThatCode(() -> runner.checkAvailable(server)).doesNotThrowAnyException();
        recorded.remove(key90());
        assertThatCode(() -> runner.checkAvailable(server)).doesNotThrowAnyException();
    }
}
