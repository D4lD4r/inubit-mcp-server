package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ClientCheck;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ClientCheckLookup;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import de.dadecker.inubit.mcp.infra.Secret;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 017 (T043, FR-018, research D-24): {@link CliRunner#run} with a {@link WritePermit}
 * selects StartCLI and probes the pin without the write lock, then, inside
 * {@link WriteLock#handOff}, selects again with the locked version, judges the launch with it and
 * starts the process; the lock is left right after the start, before the output is awaited.
 * Reads ({@code run} without permit) never take the lock.
 */
@Timeout(30)
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX launch; the lock is taken before any"
    + " platform-specific step")
class CliRunnerWriteLockTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-017-lock";
    private static final String BASE_URL = "https://inubit-acme.example.test:8443";
    private static final Path JDK_17 = Path.of("/opt/jdk-17");
    private static final Path JDK_21 = Path.of("/opt/jdk-21");
    private static final InubitVersion V8_1_17 = version("8.1.17");
    private static final InubitVersion V9_0_6 = version("9.0.6");
    private static final InubitVersion V9_0_7 = version("9.0.7");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path root;

    private Path client81;
    private Path client90;
    /** The version the runner reads before the lock. */
    private final AtomicReference<Optional<InubitVersion>> unlocked =
        new AtomicReference<>(Optional.empty());
    private final Function<NodeId, Optional<InubitVersion>> versions = id -> unlocked.get();
    private final Map<ClientKey, ClientCheck> recorded = new ConcurrentHashMap<>();
    private final ClientCheckLookup checks = (node, key) -> {
        Map<ClientKey, ClientCheck> snapshot = Map.copyOf(recorded);
        if (snapshot.containsKey(key)) {
            return new ClientCheckLookup.Recorded.ForClient(snapshot.get(key));
        }
        return snapshot.isEmpty() ? ClientCheckLookup.Recorded.Absent.NOTHING
            : ClientCheckLookup.Recorded.Absent.OTHER_CLIENTS_ONLY;
    };

    /**
     * A scripted lock: refuses with {@link #refusal} or runs the action with {@link #locked};
     * records the permits and whether a launch happened inside the action.
     */
    private final class ScriptedLock implements WriteLock {
        final List<WritePermit> permits = new CopyOnWriteArrayList<>();
        final AtomicBoolean inside = new AtomicBoolean();
        Optional<InubitVersion> locked = Optional.empty();
        ToolErrorException refusal;

        @Override
        public <T> T handOff(WritePermit permit, Function<Optional<InubitVersion>, T> action) {
            permits.add(permit);
            if (refusal != null) {
                throw refusal;
            }
            inside.set(true);
            try {
                return action.apply(locked);
            } finally {
                inside.set(false);
            }
        }
    }

    private final ScriptedLock lock = new ScriptedLock();

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

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node()
            .baseUrl(BASE_URL)
            .versionLine(VersionSetting.AUTO)
            .cliClient("8.1", client81, Optional.of(JDK_17))
            .cliClient("9.0", client90, Optional.of(JDK_21))
            .build();
    }

    private ClientKey key90() {
        return new ClientKey(client90.toString(), JDK_21.toString());
    }

    private CliRunner runner(FakeProcessLauncher launcher, WriteLock writeLock) {
        return runner(launcher, versions, writeLock);
    }

    private CliRunner runner(FakeProcessLauncher launcher,
        Function<NodeId, Optional<InubitVersion>> source, WriteLock writeLock) {
        return TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()), source, checks, writeLock);
    }

    private static CredentialGuard guard(EffectiveNodeConfig server) {
        return new CredentialGuard(server.secretLabels(),
            new MutableClock(Instant.parse("2026-11-06T08:00:00Z")));
    }

    private static WritePermit permit(NodeId node, InubitVersion admitted) {
        return new WritePermit(node, Capability.PROCESS_CONTROL, admitted);
    }

    private static CliResult write(CliRunner runner, EffectiveNodeConfig server,
        WritePermit permit) {
        return runner.run(server, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), TIMEOUT, guard(server), permit);
    }

    private static void assertNoLeak(ToolError error) {
        String all = error.message() + " " + error.likelyCause() + " " + error.nextStep();
        assertThat(all).doesNotContain(USER, PASSWORD, BASE_URL, "inubit-acme");
    }

    @Test
    void theProcessIsStartedInsideTheHandOffWithThePermit() {
        unlocked.set(Optional.of(V9_0_7));
        lock.locked = Optional.of(V9_0_7);
        AtomicBoolean startedInside = new AtomicBoolean();
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        launcher.onLaunch(spec -> startedInside.set(lock.inside.get()));

        CliResult result = write(runner(launcher, lock), server(), permit(NODE, V9_0_7));

        assertThat(result.exitCode()).isZero();
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(startedInside).as("launched while holding the lock").isTrue();
        assertThat(lock.permits).containsExactly(permit(NODE, V9_0_7));
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(client90);
    }

    @Test
    void aRefusedHandOffLaunchesNothing() {
        unlocked.set(Optional.of(V8_1_17));
        lock.refusal = new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "INUBIT on dev/node1 changed from 8.1.17 to 9.0.7 during this call; the write was"
                + " admitted for INUBIT 8.1; nothing was sent",
            "A parallel call found that dev/node1 runs another INUBIT version",
            "Call the tool again; it is judged on the version INUBIT runs now").withNode(NODE));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = runner(launcher, lock);

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> write(runner, server(), permit(NODE, V8_1_17)));

        assertThat(e).isSameAs(lock.refusal);
        assertThat(launcher.launchCount()).isZero();
        assertThat(runner.resources().closed()).isFalse();
        // the refused launch released its reservation: the next one starts
        lock.refusal = null;
        lock.locked = Optional.of(V8_1_17);
        write(runner, server(), permit(NODE, V8_1_17));
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void anotherClientUnderTheLockIsCliUnavailableAndLaunchesNothing() {
        // read before the lock: 8.1 (8.1 client); under the lock the node is known as 9.0 again
        unlocked.set(Optional.of(V8_1_17));
        lock.locked = Optional.of(V9_0_7);
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> write(runner(launcher, lock), server(), permit(NODE, V9_0_7))).error();

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The StartCLI client for dev/node1 changed to the"
            + " client for INUBIT 9.0 during this call; the new client has not been checked yet");
        assertThat(error.likelyCause()).isEqualTo("INUBIT on dev/node1 was found to run a"
            + " different version while the call was waiting");
        assertThat(error.nextStep()).isEqualTo("Call the tool again");
        assertThat(error.node()).contains(NODE);
        assertNoLeak(error);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void theLaunchGuardJudgesTheLockedVersion() {
        // one version-less client: the same client before and under the lock, but under the
        // lock the node is known as 9.0, which its checked range excludes
        unlocked.set(Optional.of(V8_1_17));
        lock.locked = Optional.of(V9_0_7);
        EffectiveNodeConfig server = TestNodeConfig.node().baseUrl(BASE_URL)
            .versionLine(VersionSetting.AUTO).cliHome(client81).cliJavaHome(JDK_17)
            .cliUrl(BASE_URL).build();
        recorded.put(new ClientKey(client81.toString(), JDK_17.toString()),
            new ClientCheck.Checked(client("8.1.17", "4.0.1", "8.1.17")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> write(runner(launcher, lock), server, permit(NODE, V9_0_7))).error();

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("StartCLI client 8.1.17 of dev/node1 supports"
            + " INUBIT 4.0.1 to 8.1.17, which excludes INUBIT 9.0.7; StartCLI refused; nothing"
            + " was sent");
        assertNoLeak(error);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void anotherAddressUnderTheLockIsCliUnavailableAndLaunchesNothing() {
        // one version-less client without cli.url: its default address differs on 9.0
        unlocked.set(Optional.of(V8_1_17));
        lock.locked = Optional.of(V9_0_7);
        EffectiveNodeConfig server = TestNodeConfig.node().baseUrl(BASE_URL)
            .versionLine(VersionSetting.AUTO).cliHome(client81).cliJavaHome(JDK_17).build();
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> write(runner(launcher, lock), server, permit(NODE, V9_0_7))).error();

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The StartCLI client for dev/node1 changed to the"
            + " client for INUBIT 9.0 during this call; the new client has not been checked yet");
        assertNoLeak(error);
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void aPatchChangeWithinTheClientsRangeLaunches() {
        unlocked.set(Optional.of(V9_0_6));
        lock.locked = Optional.of(V9_0_7);
        recorded.put(key90(), new ClientCheck.Checked(client("9.0.7", "9.0.0", "9.0.99")));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        write(runner(launcher, lock), server(), permit(NODE, V9_0_6));

        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void aPermitOfAnotherNodeIsAProgrammingErrorAndLaunchesNothing() {
        unlocked.set(Optional.of(V8_1_17));
        lock.locked = Optional.of(V8_1_17);
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        assertThatThrownBy(() -> write(runner(launcher, lock), server(),
            permit(NodeId.parse("dev/node2"), V8_1_17)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(launcher.launchCount()).isZero();
        assertThat(lock.permits).isEmpty();
    }

    @Test
    void aReadTakesNoLock() {
        unlocked.set(Optional.of(V8_1_17));
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        EffectiveNodeConfig server = server();

        runner(launcher, lock).run(server, USER, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), TIMEOUT, guard(server));

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(lock.permits).isEmpty();
    }

    @Test
    void aVersionChangeWhileTheProcessRunsIsNotBlocked() throws Exception {
        NodeVersions nodeVersions = new NodeVersions(
            new MutableClock(Instant.parse("2026-11-06T08:00:00Z")),
            node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
        nodeVersions.detected(NODE, V8_1_17);
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).hanging();
        CliRunner runner = runner(launcher, nodeVersions::effectiveVersion, nodeVersions);
        EffectiveNodeConfig server = server();

        CompletableFuture<CliResult> running = CompletableFuture.supplyAsync(
            () -> write(runner, server, permit(NODE, V8_1_17)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (launcher.launchCount() == 0) {
            assertThat(System.nanoTime()).as("started in time").isLessThan(deadline);
            assertThat(running).isNotDone();
            Thread.onSpinWait();
        }
        // the process runs and its output is awaited: the lock must be free
        CompletableFuture<Void> observation = CompletableFuture.runAsync(
            () -> nodeVersions.observed(NODE, V9_0_7));
        observation.get(10, TimeUnit.SECONDS);

        assertThat(running).isNotDone();
        assertThat(nodeVersions.effectiveVersion(NODE)).contains(V9_0_7);
        launcher.last().destroy();
        running.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aFailedStartLeavesTheLock() throws Exception {
        NodeVersions nodeVersions = new NodeVersions(
            new MutableClock(Instant.parse("2026-11-06T08:00:00Z")),
            node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
        nodeVersions.detected(NODE, V8_1_17);
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0)
            .failingToLaunch(new IOException("no such file"));

        ToolError error = catchThrowableOfType(ToolErrorException.class, () -> write(
            runner(launcher, nodeVersions::effectiveVersion, nodeVersions), server(),
            permit(NODE, V8_1_17))).error();

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("StartCLI could not be started (IOException)");
        CompletableFuture.runAsync(() -> nodeVersions.observed(NODE, V9_0_7))
            .get(10, TimeUnit.SECONDS);
        assertThat(nodeVersions.effectiveVersion(NODE)).contains(V9_0_7);
    }

    @Test
    void theRealLockRefusesAfterAChangeAndLaunchesNothing() {
        NodeVersions nodeVersions = new NodeVersions(
            new MutableClock(Instant.parse("2026-11-06T08:00:00Z")),
            node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
        nodeVersions.detected(NODE, V9_0_7);
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);

        ToolError error = catchThrowableOfType(ToolErrorException.class, () -> write(
            runner(launcher, nodeVersions::effectiveVersion, nodeVersions), server(),
            permit(NODE, V8_1_17))).error();

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("INUBIT on dev/node1 changed from 8.1.17 to 9.0.7"
            + " during this call; the write was admitted for INUBIT 8.1; nothing was sent");
        assertThat(launcher.launchCount()).isZero();
    }
}
