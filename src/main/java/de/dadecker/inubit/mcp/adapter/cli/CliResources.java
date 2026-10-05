package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The StartCLI processes and export directories that are in use (Phase 5 review I1), so that they
 * do not outlive the MCP server.
 *
 * <ul>
 *   <li>{@link CliRunner} announces every StartCLI launch ({@link #reserve()}) before it starts
 *       the process and registers it; {@link CliExportRunner} does the same for every export
 *       directory; both deregister when they are done.
 *   <li>{@link #close()} (on stdin EOF and in the JVM shutdown hook, i.e. also on SIGTERM)
 *       destroys the registered process trees, kills whatever is still alive after
 *       {@link CliRunner#KILL_GRACE} forcibly, and deletes the directories. It runs once; every
 *       caller waits for it to complete. Afterwards every new reservation is refused, so nothing
 *       is started any more; a reserved launch or directory that registers only after close
 *       has given up waiting ({@link #RESERVATION_WAIT}) is stopped or deleted at once and
 *       refused.
 *   <li>A SIGKILL leaves no chance to clean up, so export directories are named
 *       {@code inubit-mcp-export-<profile>-<pid>-<random>} ({@link #exportPrefix()}), and
 *       {@link #sweepStale} deletes, at startup, the directories of this profile and this user
 *       whose MCP server process is no longer alive (plus such directories of feature 001);
 *       never those of another profile (002 research D-8).
 *   <li>Deleting never follows symbolic links: a link is removed, its target stays.
 * </ul>
 */
public final class CliResources implements AutoCloseable {

    public static final String EXPORT_PREFIX = "inubit-mcp-export-";
    /**
     * The names of feature 001, {@code inubit-mcp-export-<pid>-<random>}: exactly two numeric
     * parts, so that no 002 name ({@code <profile>-<pid>-<random>}, three or more parts) matches.
     */
    private static final Pattern LEGACY_DIRECTORY =
        Pattern.compile("^" + EXPORT_PREFIX + "([0-9]{1,19})-[0-9]+$");
    private static final Duration POLL = Duration.ofMillis(20);
    private static final Logger LOG = LoggerFactory.getLogger(CliResources.class);

    /** Ends one registration; idempotent. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** How long {@link #close()} waits for announced launches and directories to register. */
    static final Duration RESERVATION_WAIT = Duration.ofSeconds(10);

    private final Set<LaunchedProcess> processes = ConcurrentHashMap.newKeySet();
    private final Set<Path> directories = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    /** Announced, not yet registered (or cancelled) processes and directories; under lock. */
    private int reserved;
    /** Completed when the one real close has stopped everything; under lock. */
    private CompletableFuture<Void> closing;
    private volatile boolean closed;
    /**
     * Set under the lock once {@link #close()} has finished waiting for the reservations
     * (US4 re-review R1): what registers afterwards (a launch that missed the capped wait) is no
     * longer in the snapshot that close stops, so it is stopped at once instead.
     */
    private boolean stopped;
    /** How long {@link #close()} waits for announced launches; {@link #RESERVATION_WAIT}. */
    private final Duration reservationWait;
    /** The profile this MCP server serves; part of the export directory names. */
    private final String profile;

    /**
     * Resources whose {@link #close()} waits at most {@link #RESERVATION_WAIT}.
     *
     * @param profile the profile name ({@code ^[a-z0-9][a-z0-9-]{0,31}$}), part of the export
     *     directory names (002 research D-8)
     */
    public CliResources(String profile) {
        this(profile, RESERVATION_WAIT);
    }

    /** For tests: resources whose {@link #close()} waits at most {@code reservationWait}. */
    CliResources(String profile, Duration reservationWait) {
        this.profile = validProfile(profile);
        this.reservationWait = reservationWait;
    }

    /**
     * Announces a StartCLI process or export directory that is about to be created (Phase 6
     * review W2), <em>before</em> it exists: {@link #close()} waits (at most
     * {@link #RESERVATION_WAIT}) until every announcement is registered or cancelled, so that a
     * launch racing with the shutdown is stopped as well. Close the reservation without
     * registering if the creation failed.
     *
     * @throws ToolErrorException {@code INTERNAL} after {@link #close()}; nothing may be
     *     created then
     */
    public Reservation reserve() {
        synchronized (lock) {
            if (closed) {
                throw shuttingDown();
            }
            reserved++;
        }
        return new Reservation();
    }

    /** One announced process or directory; see {@link #reserve()}. */
    public final class Reservation implements AutoCloseable {

        private boolean done;

        private Reservation() {
        }

        /**
         * Registers the launched process; the reservation is used up.
         *
         * @throws ToolErrorException {@code INTERNAL} if {@link #close()} has already stopped
         *     the registered processes (it gave up waiting for this one); the process is then
         *     stopped at once
         */
        Registration process(LaunchedProcess process) {
            boolean late;
            synchronized (lock) {
                late = stopped;
                if (!late) {
                    processes.add(process);
                }
                release();
            }
            if (late) {
                terminate(List.of(process));
                throw shuttingDown();
            }
            return () -> processes.remove(process);
        }

        /**
         * Registers the created export directory; the reservation is used up. Closing the
         * registration deletes the directory <em>while it is still registered</em> and
         * deregisters it only once it is gone (Phase 7 review P1): a shutdown that runs at the
         * same time therefore always sees it, and a directory that could not be deleted is
         * deleted again by {@link #close()}.
         *
         * @throws ToolErrorException {@code INTERNAL} if {@link #close()} has already deleted
         *     the registered directories (it gave up waiting for this one); the directory is then
         *     deleted at once
         */
        public Registration directory(Path directory) {
            boolean late;
            synchronized (lock) {
                late = stopped;
                if (!late) {
                    directories.add(directory);
                }
                release();
            }
            if (late) {
                deleteRecursively(directory);
                throw shuttingDown();
            }
            AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    deleteRecursively(directory);
                    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                        directories.remove(directory);
                    }
                }
            };
        }

        /** Cancels the reservation if nothing was registered (the creation failed). */
        @Override
        public void close() {
            synchronized (lock) {
                release();
            }
        }

        private void release() {
            if (!done) {
                done = true;
                reserved--;
                lock.notifyAll();
            }
        }
    }

    /**
     * Registers a running StartCLI process that was not announced.
     *
     * @throws ToolErrorException {@code INTERNAL} after {@link #close()}; the process is then
     *     stopped at once
     */
    Registration register(LaunchedProcess process) {
        Reservation reservation;
        try {
            reservation = reserve();
        } catch (ToolErrorException e) {
            terminate(List.of(process));
            throw e;
        }
        return reservation.process(process);
    }

    /**
     * Registers an export directory that was not announced.
     *
     * @throws ToolErrorException {@code INTERNAL} after {@link #close()}; the directory is then
     *     deleted at once
     */
    public Registration registerDirectory(Path directory) {
        Reservation reservation;
        try {
            reservation = reserve();
        } catch (ToolErrorException e) {
            deleteRecursively(directory);
            throw e;
        }
        return reservation.directory(directory);
    }

    /** The registered export directories (tests). */
    Set<Path> registeredDirectories() {
        return Set.copyOf(directories);
    }

    /** True once {@link #close()} was called. */
    public boolean closed() {
        return closed;
    }

    /**
     * The name prefix of this process's export directories,
     * {@code inubit-mcp-export-<profile>-<pid>-} (002 research D-8); the JDK appends a random
     * number.
     */
    public String exportPrefix() {
        return EXPORT_PREFIX + profile + "-" + ProcessHandle.current().pid() + "-";
    }

    /** The name goes into a path passed to StartCLI: only valid profile names are accepted. */
    private static String validProfile(String profile) {
        if (!ProfileInfo.isValidName(profile)) {
            throw new IllegalArgumentException("Invalid profile name for export directories");
        }
        return profile;
    }

    /**
     * {@code inubit-mcp-export-<profile>-<pid>-<random>} of exactly this profile: the profile name
     * is matched literally and followed by exactly two numeric parts, so that no other profile's
     * directory matches (not even one whose name starts with this one, such as {@code acme-2}
     * for {@code acme}).
     */
    private static Pattern profileDirectory(String profile) {
        return Pattern.compile("^" + Pattern.quote(EXPORT_PREFIX + profile + "-")
            + "([0-9]{1,19})-[0-9]+$");
    }

    /**
     * Stops every registered process tree and deletes every registered directory. Close once:
     * the first caller does the work (after waiting for announced launches, see
     * {@link #reserve()}); every caller — the main thread on stdin EOF, the JVM shutdown hook on
     * SIGTERM, {@code Wiring.close()} — returns only when that work is complete, so that the JVM
     * cannot exit while a tree is still being stopped (Phase 6 review W2). Afterwards nothing new
     * can be reserved.
     */
    @Override
    public void close() {
        CompletableFuture<Void> done;
        boolean first;
        synchronized (lock) {
            closed = true;
            first = closing == null;
            if (first) {
                closing = new CompletableFuture<>();
            }
            done = closing;
        }
        if (!first) {
            done.join();
            return;
        }
        try {
            awaitReservations();
            List<LaunchedProcess> running = new ArrayList<>(processes);
            processes.removeAll(running);
            terminate(running);
            List<Path> left = new ArrayList<>(directories);
            directories.removeAll(left);
            left.forEach(CliResources::deleteRecursively);
        } finally {
            done.complete(null);
        }
    }

    private void awaitReservations() {
        boolean interrupted = false;
        long deadline = System.nanoTime() + reservationWait.toNanos();
        synchronized (lock) {
            while (reserved > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOG.warn("{} StartCLI launch(es) did not register within {}; stopping the"
                        + " registered ones", reserved, reservationWait);
                    break;
                }
                try {
                    lock.wait(Math.max(1, remaining / 1_000_000));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            // from here on, late registrations are stopped by the registering thread
            stopped = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@code destroy} the trees; whatever is still alive after {@link CliRunner#KILL_GRACE} is
     * killed forcibly.
     */
    static void terminate(Collection<LaunchedProcess> running) {
        if (running.isEmpty()) {
            return;
        }
        running.forEach(LaunchedProcess::destroy);
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + CliRunner.KILL_GRACE.toNanos();
        try {
            while (running.stream().anyMatch(LaunchedProcess::treeAlive)
                && System.nanoTime() < deadline) {
                Thread.sleep(POLL.toMillis());
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            running.stream().filter(LaunchedProcess::treeAlive)
                .forEach(LaunchedProcess::destroyForcibly);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Deletes {@code directory} with its content, deepest first, without following links; every
     * entry is attempted, failures are logged, never thrown.
     */
    public static void deleteRecursively(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException e) {
            // already gone
        } catch (IOException e) {
            LOG.warn("Export directory {} could not be deleted ({})", directory,
                e.getClass().getSimpleName());
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Export file {} could not be deleted ({})", path,
                e.getClass().getSimpleName());
        }
    }

    /**
     * Deletes the export directories below {@code root} that belong to {@code owner} and to an
     * MCP server process that is no longer alive (left behind by a SIGKILL). Only real
     * directories of {@code profile} ({@code inubit-mcp-export-<profile>-<pid>-<random>}) and
     * legacy directories of feature 001 ({@code inubit-mcp-export-<pid>-<random>}, which no
     * running 002 server owns) are considered; another profile's directories, links and files
     * are left alone (002 research D-8).
     *
     * <p>PID reuse (US3 re-review N3): the check is conservative. A directory is deleted only if
     * no process with its pid is alive; if the pid of a killed MCP server has meanwhile been
     * reused by any other process, its directory is kept (left for a later sweep or the OS's
     * temp cleaning) rather than risking the export directory of a running server. The sweep
     * never deletes the directories of the current process.
     *
     * @return the number of directories deleted
     */
    public static int sweepStale(Path root, String profile, UserPrincipal owner) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        long self = ProcessHandle.current().pid();
        Pattern own = profileDirectory(validProfile(profile));
        int deleted = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                Matcher name = own.matcher(fileName);
                if (!name.matches()) {
                    name = LEGACY_DIRECTORY.matcher(fileName);
                }
                if (!name.matches()) {
                    continue;
                }
                long pid;
                try {
                    pid = Long.parseLong(name.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == self || ProcessHandle.of(pid).map(ProcessHandle::isAlive)
                    .orElse(false)) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(entry,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()
                    || !owner.equals(Files.getOwner(entry, LinkOption.NOFOLLOW_LINKS))) {
                    continue;
                }
                deleteRecursively(entry);
                deleted++;
            }
        } catch (IOException e) {
            LOG.warn("Stale export directories in {} could not be listed ({})", root,
                e.getClass().getSimpleName());
        }
        return deleted;
    }

    /**
     * {@link #sweepStale(Path, String, UserPrincipal)} for the current user ({@code user.name}).
     */
    public static int sweepStale(Path root, String profile) {
        try {
            UserPrincipal me = root.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
            return sweepStale(root, profile, me);
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warn("Stale export directories are not swept ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    private static ToolErrorException shuttingDown() {
        return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
            "The MCP server is shutting down; StartCLI was stopped",
            "The MCP client closed the connection or the MCP server was terminated",
            "Retry after the MCP server was restarted"));
    }
}
