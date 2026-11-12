package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.FakeProcess;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.WindowsAcl;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Review I1: running StartCLI processes and export directories are registered, so that a
 * shutdown (stdin EOF, SIGTERM via the JVM shutdown hook) stops and deletes them; stale export
 * directories of dead MCP server processes (SIGKILL) are swept at startup.
 */
@Timeout(30)
class CliResourcesTest {

    @TempDir
    Path root;

    private static FakeProcess launch(FakeProcessLauncher launcher) throws IOException {
        return (FakeProcess) launcher.launch(new LaunchSpec(List.of("/bin/startcli.sh"),
            Map.of(), Path.of("/")));
    }

    /** A directory named as by feature 001: {@code inubit-mcp-export-<pid>-<random>}. */
    private Path exportDir(long pid) throws IOException {
        return exportDir(CliResources.EXPORT_PREFIX + pid + "-12345");
    }

    /** {@code inubit-mcp-export-<profile>-<pid>-<random>} (002 research D-8). */
    private Path exportDir(String profile, long pid) throws IOException {
        return exportDir(CliResources.EXPORT_PREFIX + profile + "-" + pid + "-12345");
    }

    /**
     * Created as the server creates it (feature 013, R-8): on Windows its owner is then the token
     * user, also on an elevated account whose new objects belong to the administrators otherwise.
     */
    private Path exportDir(String name) throws IOException {
        Path dir = FILES.createDirectory(root.resolve(name));
        Files.writeString(dir.resolve("modules.zip"), "zip");
        return dir;
    }

    private static long deadPid() throws Exception {
        long pid = Platform.deadPid();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        return pid;
    }

    /** The private file system of the host, as production sweeps and creates with it. */
    private static final PrivateFileSystem FILES = PrivateFiles.forHost();

    @Test
    void closeStopsRegisteredProcessTreesForciblyAfterGraceAndDeletesDirectories()
        throws Exception {
        FakeProcess polite = launch(FakeProcessLauncher.of("", "", 0).hanging());
        FakeProcess stubborn = launch(FakeProcessLauncher.of("", "", 0).ignoringDestroy());
        FakeProcess done = launch(FakeProcessLauncher.of("", "", 0).hanging());
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Path dir = exportDir(ProcessHandle.current().pid());
        Files.createSymbolicLink(dir.resolve("link"), outside);
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        resources.register(polite);
        resources.register(stubborn);
        resources.register(done).close();
        resources.registerDirectory(dir);

        resources.close();

        assertThat(polite.destroyed()).isTrue();
        assertThat(polite.isAlive()).isFalse();
        assertThat(stubborn.destroyedForcibly()).isTrue();
        assertThat(stubborn.forcibleKillDelay()).hasValueSatisfying(delay ->
            assertThat(delay).isGreaterThanOrEqualTo(Duration.ofMillis(1_500)));
        assertThat(done.destroyed()).as("deregistered").isFalse();
        assertThat(Files.exists(dir, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(outside.resolve("keep.txt")).as("links are not followed").exists();
    }

    @Test
    void afterCloseNewProcessesAndDirectoriesAreStoppedAtOnce() throws Exception {
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        resources.close();
        FakeProcess late = launch(FakeProcessLauncher.of("", "", 0).hanging());
        Path dir = exportDir(ProcessHandle.current().pid());

        assertThatThrownBy(() -> resources.register(late))
            .isInstanceOf(ToolErrorException.class);
        assertThatThrownBy(() -> resources.registerDirectory(dir))
            .isInstanceOf(ToolErrorException.class);
        assertThat(late.destroyed()).isTrue();
        assertThat(dir).doesNotExist();
        assertThat(resources.closed()).isTrue();
    }

    // --- Phase 6 review W2: close once, every closer waits, announced launches -----------------

    @Test
    void everyCloserWaitsUntilTheTreesAreStoppedAndTheDirectoriesDeleted() throws Exception {
        FakeProcess stubborn = launch(FakeProcessLauncher.of("", "", 0).ignoringDestroy());
        Path dir = exportDir(ProcessHandle.current().pid());
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        resources.register(stubborn);
        resources.registerDirectory(dir);
        // first closer, e.g. the main thread on stdin EOF
        Thread first = Thread.ofPlatform().start(resources::close);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!stubborn.destroyed() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(stubborn.destroyed()).as("the first closer is terminating").isTrue();

        resources.close(); // second closer, e.g. the shutdown hook on SIGTERM

        assertThat(stubborn.destroyedForcibly()).as("the second closer waited").isTrue();
        assertThat(stubborn.treeAlive()).isFalse();
        assertThat(Files.exists(dir, LinkOption.NOFOLLOW_LINKS)).isFalse();
        first.join(10_000);
        assertThat(first.isAlive()).isFalse();
    }

    @Test
    void closeWaitsForAnAnnouncedLaunchAndStopsIt() throws Exception {
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        CliResources.Reservation reservation = resources.reserve();
        Thread closer = Thread.ofPlatform().start(resources::close);

        closer.join(300);
        assertThat(closer.isAlive()).as("close waits for the announced launch").isTrue();
        FakeProcess late = launch(FakeProcessLauncher.of("", "", 0).hanging());
        CliResources.Registration registration = reservation.process(late);
        closer.join(10_000);

        assertThat(closer.isAlive()).isFalse();
        assertThat(late.destroyed()).isTrue();
        assertThat(late.isAlive()).isFalse();
        registration.close();
    }

    @Test
    void closeWaitsForAnAnnouncedDirectoryAndDeletesIt() throws Exception {
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        CliResources.Reservation reservation = resources.reserve();
        Thread closer = Thread.ofPlatform().start(resources::close);
        closer.join(300);
        Path dir = exportDir(ProcessHandle.current().pid());

        reservation.directory(dir);
        closer.join(10_000);

        assertThat(closer.isAlive()).isFalse();
        assertThat(dir).doesNotExist();
    }

    @Test
    void aCancelledReservationDoesNotBlockClose() throws Exception {
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        CliResources.Reservation reservation = resources.reserve();
        Thread closer = Thread.ofPlatform().start(resources::close);
        closer.join(200);

        reservation.close(); // the launch failed
        closer.join(10_000);

        assertThat(closer.isAlive()).isFalse();
    }

    // --- US4 re-review R1: a reservation that registers after the capped wait --------------

    @Test
    void aProcessRegisteredAfterTheCappedWaitIsStoppedAtOnceAndRefused() throws Exception {
        CliResources resources =
            new CliResources("acme", Duration.ofMillis(100), PrivateFiles.forHost());
        CliResources.Reservation reservation = resources.reserve();

        resources.close(); // gives up waiting after the (short) cap
        FakeProcess late = launch(FakeProcessLauncher.of("", "", 0).hanging());

        assertThatThrownBy(() -> reservation.process(late))
            .isInstanceOf(ToolErrorException.class).hasMessageContaining("shutting down");
        assertThat(late.destroyed()).isTrue();
        assertThat(late.isAlive()).isFalse();
    }

    @Test
    void aDirectoryRegisteredAfterTheCappedWaitIsDeletedAtOnceAndRefused() throws Exception {
        CliResources resources =
            new CliResources("acme", Duration.ofMillis(100), PrivateFiles.forHost());
        CliResources.Reservation reservation = resources.reserve();

        resources.close();
        Path dir = exportDir(ProcessHandle.current().pid());

        assertThatThrownBy(() -> reservation.directory(dir))
            .isInstanceOf(ToolErrorException.class).hasMessageContaining("shutting down");
        assertThat(Files.exists(dir, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    // --- Phase 7 review P1: a directory is deleted while it is still registered ------------

    @Test
    void closingADirectoryRegistrationDeletesTheDirectoryAndThenDeregistersIt()
        throws Exception {
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        Path dir = exportDir(ProcessHandle.current().pid());
        CliResources.Registration registration = resources.reserve().directory(dir);
        assertThat(resources.registeredDirectories()).containsExactly(dir);

        registration.close();

        assertThat(Files.exists(dir, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(resources.registeredDirectories()).isEmpty();
        registration.close(); // idempotent
    }

    @Test
    void aDirectoryThatCouldNotBeDeletedStaysRegisteredSoThatTheShutdownTriesAgain()
        throws Exception {
        // root ignores the missing write permission, so the directory could be deleted
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
            "running as root: a read-only directory cannot block the deletion");
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        Path dir = exportDir(ProcessHandle.current().pid());
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Path file = Files.writeString(locked.resolve("history.zip"), "zip");
        // Windows ignores a read-only directory when deleting its files; a file held open
        // without delete sharing (as FileInputStream opens it) blocks the deletion instead
        InputStream held = Platform.windows() ? new FileInputStream(file.toFile()) : null;
        if (held == null) {
            locked.toFile().setWritable(false);
        }
        try {
            CliResources.Registration registration = resources.reserve().directory(dir);

            registration.close();

            assertThat(dir).exists();
            assertThat(resources.registeredDirectories()).as("deregistered only after deletion")
                .containsExactly(dir);
        } finally {
            if (held != null) {
                held.close();
            }
            locked.toFile().setWritable(true);
        }
        resources.close();
        assertThat(Files.exists(dir, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void theDefaultCapIsTenSeconds() {
        assertThat(CliResources.RESERVATION_WAIT).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void afterCloseNothingCanBeReserved() {
        CliResources resources = new CliResources("acme", PrivateFiles.forHost());
        resources.close();

        assertThatThrownBy(resources::reserve).isInstanceOf(ToolErrorException.class)
            .hasMessageContaining("shutting down");
    }

    @Test
    void theExportPrefixOfThisProcessCarriesItsProfileAndItsPid() {
        assertThat(new CliResources("acme",
            PrivateFiles.forHost()).exportPrefix()).isEqualTo(CliResources.EXPORT_PREFIX
            + "acme-" + ProcessHandle.current().pid() + "-");
        assertThat(new CliResources("globex", PrivateFiles.forHost()).exportPrefix()).isEqualTo(
            "inubit-mcp-export-globex-" + ProcessHandle.current().pid() + "-");
    }

    @Test
    void theSweepDeletesOnlyItsOwnProfilesAndLegacyDirectoriesOfDeadProcesses()
        throws Exception {
        // 002 research D-8: never another profile's directory, even of a dead process
        long dead = deadPid();
        long self = ProcessHandle.current().pid();
        Path own = exportDir("acme", dead);
        Path legacy = exportDir(dead);
        Path ownRunning = exportDir("acme", self);
        Path otherProfile = exportDir("globex", dead);
        Path longerName = exportDir("acme-2", dead);
        Path numericName = exportDir("123", dead);
        Path legacyRunning = exportDir(self);

        int deleted = CliResources.sweepStale(root, "acme", FILES);

        assertThat(deleted).isEqualTo(2);
        assertThat(own).doesNotExist();
        assertThat(legacy).as("feature 001 name, owner gone").doesNotExist();
        assertThat(ownRunning).as("this process is alive").exists();
        assertThat(otherProfile).as("another profile's").exists();
        assertThat(longerName).as("profile acme-2 is not acme").exists();
        assertThat(numericName).as("profile 123, not a legacy pid").exists();
        assertThat(legacyRunning).exists();
    }

    @Test
    void onlyValidProfileNamesBecomePartOfExportDirectoryNames() {
        for (String name : List.of("", "../x", "a/b", "Acme", "acme*")) {
            assertThatThrownBy(() -> new CliResources(name, PrivateFiles.forHost())).as(name)
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CliResources.sweepStale(root, name, FILES)).as(name)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anotherProfilesSweepLeavesThisProfilesStaleDirectoriesAlone() throws Exception {
        long dead = deadPid();
        Path acme = exportDir("acme", dead);
        Path globex = exportDir("globex", dead);

        assertThat(CliResources.sweepStale(root, "globex", FILES)).isEqualTo(1);

        assertThat(globex).doesNotExist();
        assertThat(acme).exists();
        assertThat(CliResources.sweepStale(root, "acme", FILES)).isEqualTo(1);
        assertThat(acme).doesNotExist();
    }

    @Test
    void theSweepDeletesOnlyOwnExportDirectoriesOfDeadProcessesAndNeverFollowsLinks()
        throws Exception {
        long dead = deadPid();
        Path stale = exportDir(dead);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(stale.resolve("link"), outside);
        Path running = exportDir(ProcessHandle.current().pid());
        Path otherName = Files.createDirectory(root.resolve("inubit-mcp-exports-" + dead + "-1"));
        Path noPid = Files.createDirectory(root.resolve(CliResources.EXPORT_PREFIX + "abc-1"));
        Path linkToDir = Files.createSymbolicLink(root.resolve(CliResources.EXPORT_PREFIX + dead
            + "-link"), outside);
        Path file = Files.writeString(root.resolve(CliResources.EXPORT_PREFIX + dead + "-file"),
            "x");

        int deleted = CliResources.sweepStale(root, "acme", FILES);

        assertThat(deleted).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(outside.resolve("keep.txt")).exists();
        assertThat(running).as("this process is alive").exists();
        assertThat(otherName).exists();
        assertThat(noPid).exists();
        assertThat(Files.isSymbolicLink(linkToDir)).as("a link is not an export dir").isTrue();
        assertThat(file).exists();
    }

    @Test
    void theSweepLeavesDirectoriesOfOtherOwnersAlone() throws Exception {
        // feature 013 (R-10): the owner is whatever the private file system says, on Windows the
        // token user; simulated here, so that it also runs where no second account exists
        long dead = deadPid();
        Path mine = exportDir("acme", dead);
        Path theirs = exportDir(CliResources.EXPORT_PREFIX + "acme-" + dead + "-67890");
        List<Path> asked = new CopyOnWriteArrayList<>();

        int deleted = CliResources.sweepStale(root, "acme", path -> {
            asked.add(path);
            return path.equals(mine);
        });

        assertThat(deleted).isEqualTo(1);
        assertThat(mine).doesNotExist();
        assertThat(theirs).exists();
        assertThat(asked).containsExactlyInAnyOrder(mine, theirs);
    }

    @Test
    void anOwnerThatCannotBeReadIsNotTheCurrentUsers() throws Exception {
        Path stale = exportDir(deadPid());

        assertThat(CliResources.sweepStale(root, "acme", path -> {
            throw new java.nio.file.AccessDeniedException(path.toString());
        })).isZero();
        assertThat(stale).exists();
    }

    // --- feature 020 (D-8): queue-entry helper directories may hold entry content ---

    /** {@code inubit-mcp-queue-<profile>-<pid>-<random>} (feature 020, research D-8). */
    private Path queueDir(String profile, long pid) throws IOException {
        return exportDir(CliResources.QUEUE_PREFIX + profile + "-" + pid + "-12345");
    }

    @Test
    void theQueuePrefixOfThisProcessCarriesItsProfileAndItsPid() {
        assertThat(new CliResources("acme", PrivateFiles.forHost()).queuePrefix())
            .isEqualTo("inubit-mcp-queue-acme-" + ProcessHandle.current().pid() + "-");
    }

    @Test
    void theSweepDeletesOnlyItsOwnProfilesQueueDirectoriesOfDeadProcessesAndNeverFollowsLinks()
        throws Exception {
        long dead = deadPid();
        long self = ProcessHandle.current().pid();
        Path own = queueDir("acme", dead);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(own.resolve("link"), outside);
        Path ownRunning = queueDir("acme", self);
        Path otherProfile = queueDir("globex", dead);
        Path longerName = queueDir("acme-2", dead);
        Path noProfile = exportDir(CliResources.QUEUE_PREFIX + dead + "-12345");
        Path linkToDir = Files.createSymbolicLink(root.resolve(CliResources.QUEUE_PREFIX
            + "acme-" + dead + "-1"), outside);
        Path file = Files.writeString(root.resolve(CliResources.QUEUE_PREFIX + "acme-" + dead
            + "-2"), "x");

        int deleted = CliResources.sweepStale(root, "acme", FILES);

        assertThat(deleted).isEqualTo(1);
        assertThat(own).doesNotExist();
        assertThat(outside.resolve("keep.txt")).exists();
        assertThat(ownRunning).as("this process is alive").exists();
        assertThat(otherProfile).as("another profile's").exists();
        assertThat(longerName).as("profile acme-2 is not acme").exists();
        assertThat(noProfile).as("queue directories have no legacy names").exists();
        assertThat(Files.isSymbolicLink(linkToDir)).as("a link is not a queue dir").isTrue();
        assertThat(file).exists();
    }

    @Test
    void theSweepLeavesQueueDirectoriesOfOtherOrUnreadableOwnersAlone() throws Exception {
        long dead = deadPid();
        Path mine = queueDir("acme", dead);
        Path theirs = exportDir(CliResources.QUEUE_PREFIX + "acme-" + dead + "-67890");
        Path unreadable = exportDir(CliResources.QUEUE_PREFIX + "acme-" + dead + "-24680");

        int deleted = CliResources.sweepStale(root, "acme", path -> {
            if (path.equals(unreadable)) {
                throw new java.nio.file.AccessDeniedException(path.toString());
            }
            return path.equals(mine);
        });

        assertThat(deleted).isEqualTo(1);
        assertThat(mine).doesNotExist();
        assertThat(theirs).exists();
        assertThat(unreadable).exists();
    }

    // --- feature 021 (D-7): the private copies of downloaded server logs ---

    @Test
    void aReservedLogDirectoryIsNamedByProfileAndPidAndOwnerOnlyBelowTheTempDirectory()
        throws Exception {
        CliResources resources = new CliResources("acme", FILES);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path dir;
        CliResources.Registration registration;
        try (CliResources.Reservation reservation = resources.reserve()) {
            dir = resources.files().createTempDirectory(tmp, resources.logPrefix());
            registration = reservation.directory(dir);
        }
        try {
            assertThat(dir.getParent()).isEqualTo(tmp);
            assertThat(dir.getFileName().toString())
                .matches("inubit-mcp-log-acme-" + ProcessHandle.current().pid() + "-[0-9]+");
            if (Platform.windows()) {
                WindowsAcl.assertPrivate(dir, WindowsAcl.DIRECTORY_INHERITANCE);
            } else {
                assertThat(Files.getPosixFilePermissions(dir))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            }
        } finally {
            registration.close();
        }
        assertThat(dir).doesNotExist();
    }

    @Test
    void theSweepDeletesAStaleLogDirectoryOfADeadProcessAndKeepsOneOfTheRunningProcess()
        throws Exception {
        long dead = deadPid();
        long self = ProcessHandle.current().pid();
        Path stale = exportDir(CliResources.LOG_PREFIX + "acme-" + dead + "-12345");
        Path running = exportDir(CliResources.LOG_PREFIX + "acme-" + self + "-12345");
        Path otherProfile = exportDir(CliResources.LOG_PREFIX + "globex-" + dead + "-12345");

        int deleted = CliResources.sweepStale(root, "acme", FILES);

        assertThat(deleted).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(running).as("this process is alive").exists();
        assertThat(otherProfile).as("another profile's").exists();
    }

    // --- feature 024 (D-24): the private directories of the node tools' helper runs ---

    @Test
    void aNodeDirectoryIsNamedByProfileAndPid() {
        CliResources resources = new CliResources("acme", FILES);

        assertThat(resources.nodePrefix())
            .isEqualTo("inubit-mcp-node-acme-" + ProcessHandle.current().pid() + "-");
    }

    @Test
    void theSweepDeletesAStaleNodeDirectoryOfADeadProcessAndKeepsOneOfTheRunningProcess()
        throws Exception {
        long dead = deadPid();
        long self = ProcessHandle.current().pid();
        Path stale = exportDir(CliResources.NODE_PREFIX + "acme-" + dead + "-12345");
        Path running = exportDir(CliResources.NODE_PREFIX + "acme-" + self + "-12345");
        Path otherProfile = exportDir(CliResources.NODE_PREFIX + "globex-" + dead + "-12345");

        int deleted = CliResources.sweepStale(root, "acme", FILES);

        assertThat(deleted).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(running).as("this process is alive").exists();
        assertThat(otherProfile).as("another profile's").exists();
    }

    @Test
    void aMissingRootIsNothingToSweep() throws IOException {
        assertThat(CliResources.sweepStale(root.resolve("missing"), "acme", FILES)).isZero();
    }

    // --- feature 013 (FR-008, R-10): a file held open is retried, then the directory logged ---

    /** Fails for {@code held} the first {@code failures} times, as Windows does for open files. */
    private static CliResources.Deleter holding(Path held, int failures, List<Path> attempts) {
        AtomicInteger left = new AtomicInteger(failures);
        return path -> {
            attempts.add(path);
            if (path.equals(held) && left.getAndDecrement() > 0) {
                throw new java.nio.file.FileSystemException(path.toString(), null,
                    "The process cannot access the file because it is being used by another"
                        + " process");
            }
            Files.deleteIfExists(path);
        };
    }

    @Test
    void aFileHeldForAWhileIsRetriedUntilTheDirectoryIsGone() throws Exception {
        Path dir = exportDir(ProcessHandle.current().pid());
        Path held = dir.resolve("modules.zip");
        List<Path> attempts = new CopyOnWriteArrayList<>();
        List<Duration> pauses = new CopyOnWriteArrayList<>();

        boolean deleted = CliResources.deleteRecursively(dir, holding(held, 3, attempts),
            pauses::add);

        assertThat(deleted).isTrue();
        assertThat(dir).doesNotExist();
        assertThat(attempts.stream().filter(held::equals)).hasSize(4);
        assertThat(pauses).hasSize(3);
    }

    @Test
    void aFileHeldLongerIsTriedFiveTimesWithinTwoSecondsThenOnlyTheDirectoryIsLogged()
        throws Exception {
        Path dir = exportDir(ProcessHandle.current().pid());
        Path held = dir.resolve("modules.zip");
        List<Path> attempts = new CopyOnWriteArrayList<>();
        List<Duration> pauses = new CopyOnWriteArrayList<>();
        ListAppender<ILoggingEvent> logs = capture();
        try {
            boolean deleted = CliResources.deleteRecursively(dir,
                holding(held, Integer.MAX_VALUE, attempts), pauses::add);

            assertThat(deleted).isFalse();
        } finally {
            release(logs);
        }

        assertThat(held).exists();
        assertThat(attempts.stream().filter(held::equals)).hasSize(5);
        assertThat(pauses).hasSize(4);
        assertThat(pauses.stream().reduce(Duration.ZERO, Duration::plus))
            .isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(dir.getFileName().toString())
                .doesNotContain("modules.zip");
        });
    }

    @Test
    void anInterruptedRetryStopsAndKeepsTheInterrupt() throws Exception {
        Path dir = exportDir(ProcessHandle.current().pid());
        Path held = dir.resolve("modules.zip");
        List<Path> attempts = new CopyOnWriteArrayList<>();
        try {
            boolean deleted = CliResources.deleteRecursively(dir,
                holding(held, Integer.MAX_VALUE, attempts), pause -> {
                    throw new InterruptedException();
                });

            assertThat(deleted).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(attempts.stream().filter(held::equals)).hasSize(1);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aDirectoryJunctionIsRemovedWithoutDeletingItsTarget() throws Exception {
        // Windows: a junction is a directory to walkFileTree, which would descend into it
        Path dir = exportDir(ProcessHandle.current().pid());
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Process mklink = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
            dir.resolve("junction").toString(), outside.toString()).redirectErrorStream(true)
            .start();
        mklink.getOutputStream().close();
        String output = new String(mklink.getInputStream().readAllBytes());
        assertThat(mklink.waitFor()).as(output).isZero();

        assertThat(CliResources.deleteRecursively(dir)).isTrue();

        assertThat(dir).doesNotExist();
        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void aSweptDirectoryWithAHeldFileIsNotCountedAndStaysForTheNextStart() throws Exception {
        Path stale = exportDir(deadPid());
        Path held = stale.resolve("modules.zip");

        int deleted = CliResources.sweepStale(root, "acme", FILES::ownedByCurrentUser,
            holding(held, Integer.MAX_VALUE, new CopyOnWriteArrayList<>()), pause -> { });

        assertThat(deleted).isZero();
        assertThat(held).exists();
    }

    private static ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(CliResources.class)).addAppender(logs);
        return logs;
    }

    private static void release(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(CliResources.class)).detachAppender(logs);
        logs.stop();
    }
}
