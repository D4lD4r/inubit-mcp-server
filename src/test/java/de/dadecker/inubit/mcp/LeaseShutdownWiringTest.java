package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLiveness;
import de.dadecker.inubit.mcp.adapter.os.NoJob;
import de.dadecker.inubit.mcp.application.nodetools.LeaseScheduler;
import de.dadecker.inubit.mcp.application.nodetools.LeaseStore;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseAttempt;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseReason;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.TraceMatrix;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Feature 024 T053 (FR-043, research D-17): the open leases of this instance are reset when stdin
 * is closed and in the JVM shutdown hook, before StartCLI work is stopped; the nodes in parallel,
 * bounded by the largest {@code cliTimeout} of the profile; a lease not reset by then and a lease
 * whose {@code PENDING} record cannot be written stay open.
 *
 * <p>The cleanup itself is {@code LogLevelService.cleanup} (T051). Each reset first opens its
 * node's lock file ({@code log-levels/nodes/<group>.<node>.lock}); the tests observe the resets
 * there, through a private file system that calls a hook before opening such a file. With the
 * production matrix no version may set log levels yet, so a reset ends as a refused attempt
 * ({@code RESET_FAILED}); nothing is sent to INUBIT.
 */
@Timeout(60)
class LeaseShutdownWiringTest {

    private static final LeaseOwner SELF = new SystemProcessLiveness().self();
    private static final Map<String, String> CREDENTIALS = Map.of(
        "INUBIT_ACME_DEV_USERNAME", "jdoe",
        "INUBIT_ACME_DEV_PASSWORD", TestSecrets.CONFIGURED_PASSWORD);

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    @Test
    void closingStdinResetsTheOwnLeasesBeforeStartCliWorkIsStopped() throws Exception {
        Path file = temp.resolve("config.yaml");
        Files.writeString(file, profile("""
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
            """));
        Lease own = active("dev/node1", SELF);
        seed(own);
        AtomicReference<Wiring> wiring = new AtomicReference<>();
        List<Boolean> stoppedAtReset = new CopyOnWriteArrayList<>();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Launcher launcher = new Launcher(new Launcher.Context(
            Map.of("INUBIT_MCP_UPDATE_CHECK", "off"), home(), false,
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8), new SecretScrubber(),
            Files::exists, () -> {
                throw new AssertionError("no AWS node");
            }, () -> TestSecrets.store(CREDENTIALS), NoJob::new,
            new NodeLockHook(lock -> stoppedAtReset.add(wiring.get().cliWorkStopped())),
            () -> {
                throw new AssertionError("the update check is off");
            }, () -> {
                throw new AssertionError("the update check is off");
            }), created -> {
                wiring.set(created);
                return created.toolHandlers();
            });

        assertThat(launcher.run("--config", file.toString())).as(err.toString()).isZero();

        assertThat(stoppedAtReset).as("one reset, while StartCLI could still run")
            .containsExactly(false);
        assertThat(wiring.get().cliWorkStopped()).isTrue();
        assertThat(onlyLease()).satisfies(lease -> {
            assertThat(lease.leaseId()).isEqualTo(own.leaseId());
            assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
            assertThat(lease.lastAttempt()).isPresent();
        });
        assertThat(shutdownRecords(temp.resolve("audit"), own)).hasSize(1);
    }

    @Test
    void theOwnOpenLeasesOfConfiguredServersAreResetInParallel() throws Exception {
        Lease first = active("dev/node1", SELF);
        Lease second = active("dev/node2", SELF);
        Lease others = active("dev/node1", liveOtherInstance());
        Lease unconfigured = active("gone/node9", SELF);
        seed(first, second, others, unconfigured);
        CountDownLatch both = new CountDownLatch(2);
        List<Boolean> met = new CopyOnWriteArrayList<>();
        List<Path> opened = new CopyOnWriteArrayList<>();

        try (Wiring wiring = wiring(twoNodes("PT10S", "PT10S"), new NodeLockHook(lock -> {
            opened.add(lock.getFileName());
            both.countDown();
            try {
                met.add(both.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }), refusingLauncher(), CapabilityMatrix.production())) {
            wiring.resetLeasesOnShutdown();
        }

        assertThat(met).as("both resets ran at the same time").containsExactly(true, true);
        assertThat(opened).extracting(Path::toString)
            .containsExactlyInAnyOrder("dev.node1.lock", "dev.node2.lock");
        assertThat(leases()).filteredOn(lease -> lease.owner().equals(SELF)
                && lease.server().value().startsWith("dev/"))
            .hasSize(2).allSatisfy(lease -> assertThat(lease.lastAttempt()).isPresent());
        assertThat(leases()).as("another live instance's and an unconfigured server's lease")
            .contains(others, unconfigured);
    }

    @Test
    void theResetIsBoundedByTheLargestCliTimeoutAndAHangingResetLeavesTheLeaseActive()
        throws Exception {
        Lease first = active("dev/node1", SELF);
        Lease second = active("dev/node2", SELF);
        seed(first, second);
        CountDownLatch never = new CountDownLatch(1);

        long took;
        try (Wiring wiring = wiring(twoNodes("PT1S", "PT2S"), new NodeLockHook(lock -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }), refusingLauncher(), CapabilityMatrix.production())) {
            long start = System.nanoTime();
            wiring.resetLeasesOnShutdown();
            took = System.nanoTime() - start;
        } finally {
            never.countDown();
        }

        assertThat(Duration.ofNanos(took)).as("the largest cliTimeout, not the smallest")
            .isGreaterThanOrEqualTo(Duration.ofMillis(1_900))
            .isLessThan(Duration.ofSeconds(5));
        assertThat(leases()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void aPendingRecordThatCannotBeWrittenAtShutdownSendsNothingAndKeepsTheLease()
        throws Exception {
        Path client = temp.resolve("client");
        FakeStartCli.install(client);
        Files.createDirectories(client.resolve("lib").resolve("shared"));
        Files.createDirectories(client.resolve("conf"));
        Files.createFile(client.resolve("lib").resolve("ibis.jar"));
        Path audit = Files.writeString(temp.resolve("audit-is-a-file"), "");
        String yaml = """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
                versionLine: V8_1
                cli:
                  home: %s
                  javaHome: %s
                nodes:
                  - name: node1
                    baseUrl: http://node1.example.test:8000
                    allowInsecureHttp: true
            """.formatted(audit, temp.resolve("workspace"), client,
            System.getProperty("java.home"));
        Lease own = active("dev/node1", SELF);
        seed(own);
        List<List<String>> launched = new CopyOnWriteArrayList<>();
        ProcessLauncher launcher = spec -> {
            launched.add(spec.command());
            return FakeProcessLauncher.replaying("version").launch(spec);
        };

        try (Wiring wiring = wiring(yaml, new NodeLockHook(lock -> { }), launcher,
            TraceMatrix.admittingEightOne())) {
            wiring.resetLeasesOnShutdown();
        }

        assertThat(onlyLease()).satisfies(lease -> {
            assertThat(lease.state()).isEqualTo(LeaseState.ACTIVE);
            assertThat(lease.lastAttempt()).map(LeaseAttempt::reason)
                .contains(Optional.of(LeaseReason.AUDIT_NOT_WRITABLE));
            assertThat(lease.lastAttempt()).map(LeaseAttempt::outcome)
                .contains(AuditOutcome.FAILED);
        });
        assertThat(launched).as("no trace command").noneMatch(command -> String.join(" ", command)
            .contains("trace"));
    }

    @Test
    void theShutdownHookResetsTheLeasesOnceBeforeStoppingStartCliWork() throws Exception {
        Lease own = active("dev/node1", SELF);
        seed(own);
        AtomicReference<Wiring> current = new AtomicReference<>();
        List<Boolean> stoppedAtReset = new CopyOnWriteArrayList<>();

        try (Wiring wiring = wiring(twoNodes("PT10S", "PT10S"),
            new NodeLockHook(lock -> stoppedAtReset.add(current.get().cliWorkStopped())),
            refusingLauncher(), CapabilityMatrix.production())) {
            current.set(wiring);
            wiring.shutdownHook();
            assertThat(wiring.cliWorkStopped()).isTrue();
            // stdin closed afterwards, and the hook once more: nothing further
            wiring.resetLeasesOnShutdown();
            wiring.shutdownHook();
        }

        assertThat(stoppedAtReset).containsExactly(false);
        assertThat(leases()).singleElement().satisfies(lease -> {
            assertThat(lease.state()).isEqualTo(LeaseState.RESET_FAILED);
            assertThat(lease.lastAttempt()).isPresent();
        });
        assertThat(shutdownRecords(temp.resolve("audit"), own)).hasSize(1);
    }

    @Test
    void theBoundIsTheLargestCliTimeoutOfTheNodesWithOwnOpenLeases() throws Exception {
        // review T053 r1 #1: node2's long cliTimeout does not hold the shutdown, it has no lease
        Lease first = active("dev/node1", SELF);
        seed(first);
        CountDownLatch never = new CountDownLatch(1);

        long took;
        try (Wiring wiring = wiring(twoNodes("PT1S", "PT30S"), new NodeLockHook(lock -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }), refusingLauncher(), CapabilityMatrix.production())) {
            long start = System.nanoTime();
            wiring.resetLeasesOnShutdown();
            took = System.nanoTime() - start;
        } finally {
            never.countDown();
        }

        assertThat(Duration.ofNanos(took)).as("node1's cliTimeout, not node2's")
            .isGreaterThanOrEqualTo(Duration.ofMillis(900))
            .isLessThan(Duration.ofSeconds(4));
        assertThat(leases()).containsExactly(first);
    }

    @Test
    void closingTheWiringAfterTheLeasesStartedResetsThemFirst() throws Exception {
        // review T053 r1 #2: serve may leave early (interrupted, failed) and only close the wiring
        Lease own = active("dev/node1", SELF);
        seed(own);
        AtomicReference<Wiring> current = new AtomicReference<>();
        List<Boolean> stoppedAtReset = new CopyOnWriteArrayList<>();

        List<String> schedulerLog = logOf(LeaseScheduler.class, () -> {
            try (Wiring wiring = wiring(twoNodes("PT10S", "PT10S"),
                new NodeLockHook(lock -> stoppedAtReset.add(current.get().cliWorkStopped())),
                refusingLauncher(), CapabilityMatrix.production())) {
                current.set(wiring);
                assertThat(wiring.startLeases().open()).isOne();
            }
        });

        assertThat(stoppedAtReset).as("reset once, before StartCLI work stopped")
            .containsExactly(false);
        assertThat(current.get().cliWorkStopped()).isTrue();
        assertThat(onlyLease().state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(shutdownRecords(temp.resolve("audit"), own)).hasSize(1);
        assertThat(schedulerLog).noneMatch(line -> line.contains("did not stop"));
    }

    @Test
    void aSchedulerCleanupStillRunningAtShutdownSharesTheOneDeadline() throws Exception {
        // review T053 r2 #1: the scheduler stop and the resets end within one cliTimeout in total
        Lease expired = active("dev/node1", SELF,
            Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(1)));
        seed(expired);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);

        long took;
        try (Wiring wiring = wiring(twoNodes("PT2S", "PT30S"), new NodeLockHook(lock -> {
            entered.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }), refusingLauncher(), CapabilityMatrix.production())) {
            wiring.startLeases();
            assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("the startup tick's cleanup hangs on node1's lock").isTrue();
            long start = System.nanoTime();
            wiring.resetLeasesOnShutdown();
            took = System.nanoTime() - start;
        } finally {
            never.countDown();
        }

        assertThat(Duration.ofNanos(took)).as("node1's cliTimeout once, not twice")
            .isGreaterThanOrEqualTo(Duration.ofMillis(1_900))
            .isLessThan(Duration.ofMillis(3_500));
        assertThat(leases()).containsExactly(expired);
    }

    @Test
    void aSecondCallerWaitsForTheRunningResetBeforeStartCliWorkIsStopped() throws Exception {
        // review T053 r1 #3: SIGTERM while the reset after stdin closed still runs
        Lease own = active("dev/node1", SELF);
        seed(own);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Path> opened = new CopyOnWriteArrayList<>();

        try (Wiring wiring = wiring(twoNodes("PT10S", "PT10S"), new NodeLockHook(lock -> {
            opened.add(lock.getFileName());
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }), refusingLauncher(), CapabilityMatrix.production())) {
            Thread stdinClosed = Thread.ofPlatform().start(wiring::resetLeasesOnShutdown);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread hook = Thread.ofPlatform().start(wiring::shutdownHook);

            hook.join(500);
            assertThat(hook.isAlive()).as("the hook waits for the running reset").isTrue();
            assertThat(wiring.cliWorkStopped()).isFalse();

            release.countDown();
            stdinClosed.join(5_000);
            hook.join(5_000);
            assertThat(hook.isAlive()).isFalse();
            assertThat(wiring.cliWorkStopped()).isTrue();
        } finally {
            release.countDown();
        }

        assertThat(opened).as("one reset").hasSize(1);
        assertThat(onlyLease().state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(shutdownRecords(temp.resolve("audit"), own)).hasSize(1);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** The formatted messages {@code type}'s logger writes while {@code action} runs. */
    private static List<String> logOf(Class<?> type, ThrowingRunnable action) throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(type);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.setContext(context);
        logs.start();
        logger.addAppender(logs);
        try {
            action.run();
            synchronized (logs) {
                return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private Path home() {
        return temp.resolve("home");
    }

    /** Profile acme with group dev (8.1) of {@code nodes}, no StartCLI client. */
    private String profile(String nodes) {
        return """
            profile:
              name: acme
            auditDirectory: %s
            workspace: %s
            groups:
              - name: dev
                versionLine: V8_1
                nodes:
            %s""".formatted(temp.resolve("audit"), temp.resolve("workspace"), nodes);
    }

    private String twoNodes(String firstTimeout, String secondTimeout) {
        return profile("""
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    cliTimeout: %s
                  - name: node2
                    baseUrl: https://inubit.example.test:8444
                    cliTimeout: %s
            """.formatted(firstTimeout, secondTimeout));
    }

    private Wiring wiring(String yaml, PrivateFileSystem files, ProcessLauncher launcher,
        CapabilityMatrix matrix) throws IOException {
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        return new Wiring(config, TestSecrets.store(CREDENTIALS), new SecretScrubber(),
            ClockProvider.system(), Files::exists, Platform.windows(), launcher,
            Map.of("PATH", Platform.searchPath()), () -> {
                throw new AssertionError("no AWS node");
            }, TestWiring.TEST_CONFIG_LOCATION, home(), new NoJob(), files, matrix);
    }

    private static ProcessLauncher refusingLauncher() {
        return spec -> {
            throw new AssertionError("nothing is launched: " + spec.command());
        };
    }

    private LeaseStore store() {
        return new LeaseStore(LeaseStore.defaultDirectory(home(), "acme"), PrivateFiles.forHost(),
            Clock.systemUTC());
    }

    private void seed(Lease... leases) {
        store().update(stored -> List.of(leases), Duration.ofSeconds(5));
    }

    private List<Lease> leases() {
        return store().read();
    }

    private Lease onlyLease() {
        assertThat(leases()).hasSize(1);
        return leases().get(0);
    }

    /** An unexpired {@code ACTIVE} lease of {@code server} on INUBIT node node1. */
    private static Lease active(String server, LeaseOwner owner) {
        return active(server, owner,
            Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(10)));
    }

    /** An {@code ACTIVE} lease of {@code server} on INUBIT node node1 set 10 minutes earlier. */
    private static Lease active(String server, LeaseOwner owner, Instant expiresAt) {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        loggers.put(new LoggerName("com.inubit.ibis.acme.Orders"), TemporaryLevel.DEBUG);
        return new Lease(UUID.randomUUID(), 1, NodeId.parse(server), "node1", loggers,
            expiresAt.minus(Duration.ofMinutes(10)), expiresAt, owner, LeaseState.ACTIVE,
            UUID.randomUUID(), Optional.empty(), Optional.empty());
    }

    /** The process that started this JVM: it runs as long as the test. */
    private static LeaseOwner liveOtherInstance() {
        ProcessHandle parent = ProcessHandle.current().parent().orElseThrow();
        return new LeaseOwner(parent.pid(), parent.info().startInstant()
            .map(start -> start.truncatedTo(ChronoUnit.MILLIS)));
    }

    /** The audit lines of {@code lease}'s automatic resets with trigger {@code shutdown}. */
    private static List<String> shutdownRecords(Path audit, Lease lease) throws IOException {
        try (Stream<Path> files = Files.list(audit)) {
            return files.flatMap(file -> {
                try {
                    return Files.readAllLines(file).stream();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).filter(line -> line.contains(lease.leaseId().toString())
                && line.contains("\"trigger\":\"shutdown\"")).toList();
        }
    }

    /** Called before a node's lock file is opened, i.e. when a reset of that node begins. */
    private interface LockOpening {
        void opening(Path lockFile) throws IOException;
    }

    /** The host's private files; {@link LockOpening} runs before a node lock file is opened. */
    private static final class NodeLockHook implements PrivateFileSystem {

        private final PrivateFileSystem host = PrivateFiles.forHost();
        private final LockOpening hook;

        NodeLockHook(LockOpening hook) {
            this.hook = hook;
        }

        @Override
        public FileChannel openLockFileNoFollow(Path file) throws IOException {
            if (file.getParent().getFileName().toString().equals("nodes")) {
                hook.opening(file);
            }
            return host.openLockFileNoFollow(file);
        }

        @Override
        public FileChannel openLockFile(Path file) throws IOException {
            return host.openLockFile(file);
        }

        @Override
        public Path createDirectory(Path directory) throws IOException {
            return host.createDirectory(directory);
        }

        @Override
        public boolean createDirectories(Path directory) throws IOException {
            return host.createDirectories(directory);
        }

        @Override
        public Path createTempDirectory(Path parent, String prefix) throws IOException {
            return host.createTempDirectory(parent, prefix);
        }

        @Override
        public Path createFile(Path file) throws IOException {
            return host.createFile(file);
        }

        @Override
        public void tightenOnPosix(Path path) throws IOException {
            host.tightenOnPosix(path);
        }

        @Override
        public List<String> check(Path path) throws IOException {
            return host.check(path);
        }

        @Override
        public Optional<String> othersMayAccess(Path path) throws IOException {
            return host.othersMayAccess(path);
        }

        @Override
        public boolean ownedByCurrentUser(Path path) throws IOException {
            return host.ownedByCurrentUser(path);
        }

        @Override
        public Optional<String> currentUserSid() {
            return host.currentUserSid();
        }
    }
}
