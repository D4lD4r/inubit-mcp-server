package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.FakeProcess;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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

    private Path exportDir(String name) throws IOException {
        Path dir = Files.createDirectory(root.resolve(name));
        Files.writeString(dir.resolve("modules.zip"), "zip");
        return dir;
    }

    private static long deadPid() throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
        process.waitFor();
        long pid = process.pid();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        return pid;
    }

    /** The current user, looked up like production does (not the owner of java.io.tmpdir: on
     * Linux that is the root-owned /tmp). */
    private static UserPrincipal me() throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"));
    }

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
        CliResources resources = new CliResources("acme");
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
        CliResources resources = new CliResources("acme");
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
        CliResources resources = new CliResources("acme");
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
        CliResources resources = new CliResources("acme");
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
        CliResources resources = new CliResources("acme");
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
        CliResources resources = new CliResources("acme");
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
        CliResources resources = new CliResources("acme", Duration.ofMillis(100));
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
        CliResources resources = new CliResources("acme", Duration.ofMillis(100));
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
        CliResources resources = new CliResources("acme");
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
        CliResources resources = new CliResources("acme");
        Path dir = exportDir(ProcessHandle.current().pid());
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.writeString(locked.resolve("history.zip"), "zip");
        locked.toFile().setWritable(false);
        try {
            CliResources.Registration registration = resources.reserve().directory(dir);

            registration.close();

            assertThat(dir).exists();
            assertThat(resources.registeredDirectories()).as("deregistered only after deletion")
                .containsExactly(dir);
        } finally {
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
        CliResources resources = new CliResources("acme");
        resources.close();

        assertThatThrownBy(resources::reserve).isInstanceOf(ToolErrorException.class)
            .hasMessageContaining("shutting down");
    }

    @Test
    void theExportPrefixOfThisProcessCarriesItsProfileAndItsPid() {
        assertThat(new CliResources("acme").exportPrefix()).isEqualTo(CliResources.EXPORT_PREFIX
            + "acme-" + ProcessHandle.current().pid() + "-");
        assertThat(new CliResources("globex").exportPrefix()).isEqualTo(
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

        int deleted = CliResources.sweepStale(root, "acme", me());

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
            assertThatThrownBy(() -> new CliResources(name)).as(name)
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CliResources.sweepStale(root, name, me())).as(name)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anotherProfilesSweepLeavesThisProfilesStaleDirectoriesAlone() throws Exception {
        long dead = deadPid();
        Path acme = exportDir("acme", dead);
        Path globex = exportDir("globex", dead);

        assertThat(CliResources.sweepStale(root, "globex", me())).isEqualTo(1);

        assertThat(globex).doesNotExist();
        assertThat(acme).exists();
        assertThat(CliResources.sweepStale(root, "acme", me())).isEqualTo(1);
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

        int deleted = CliResources.sweepStale(root, "acme", me());

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
        Path stale = exportDir(deadPid());
        UserPrincipal someoneElse = root.getFileSystem().getUserPrincipalLookupService()
            .lookupPrincipalByName("root");

        assertThat(CliResources.sweepStale(root, "acme", someoneElse)).isZero();
        assertThat(stale).exists();
    }

    @Test
    void aMissingRootIsNothingToSweep() throws IOException {
        assertThat(CliResources.sweepStale(root.resolve("missing"), "acme", me())).isZero();
    }
}
