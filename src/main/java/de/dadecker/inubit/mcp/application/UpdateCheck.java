package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.ReleaseSource.ReleaseCheckException;
import de.dadecker.inubit.mcp.application.UpdateCache.CachedRelease;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks for a newer release of this server (feature 016): once at start and then every 24 hours
 * after the previous check (FR-001, FR-003), on a daemon thread so that neither the handshake nor
 * a tool call nor shutdown waits for it (FR-002). A newer version is logged once per process at
 * WARN (FR-007); a failure only at DEBUG, keeping the last result (FR-008). Every successful check
 * is written to the {@link UpdateCache} (FR-018). Nothing is downloaded (FR-005).
 */
public final class UpdateCheck implements AutoCloseable {

    /** The pause between the end of one check and the start of the next (FR-003). */
    public static final Duration INTERVAL = Duration.ofHours(24);

    private static final Logger LOG = LoggerFactory.getLogger(UpdateCheck.class);

    private final ReleaseSource source;
    private final SemVer running;
    private final UpdateStatus status;
    private final UpdateCache cache;
    private final Clock clock;
    private final Set<SemVer> announced = new HashSet<>();
    private ScheduledExecutorService executor;

    public UpdateCheck(ReleaseSource source, SemVer running, UpdateStatus status, UpdateCache cache,
        Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.running = Objects.requireNonNull(running, "running");
        this.status = Objects.requireNonNull(status, "status");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A single daemon thread for {@link #start}. */
    public static ScheduledExecutorService daemonExecutor() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "inubit-mcp-update-check");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Takes over a cached result from an earlier process (FR-018) if it names a newer release;
     * reads no network. Call before {@link #start}.
     */
    public synchronized void seed(Optional<CachedRelease> cached) {
        cached.filter(c -> c.latestVersion().compareTo(running) > 0)
            .ifPresent(c -> record(ServerUpdate.of(running, c.latestVersion(), c.checkedAt())));
    }

    /** Runs {@link #checkOnce} now and then every {@link #INTERVAL} on {@code executor}. */
    public synchronized void start(ScheduledExecutorService executor) {
        if (this.executor != null) {
            throw new IllegalStateException("The update check is already started");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
        executor.scheduleWithFixedDelay(this::checkOnce, 0, INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS);
    }

    /** One check; never throws. */
    public void checkOnce() {
        try {
            SemVer latest = source.latest().version();
            ServerUpdate update = ServerUpdate.of(running, latest, clock.instant());
            synchronized (this) {
                record(update);
            }
            cache.write(latest, update.checkedAt());
        } catch (ReleaseCheckException e) {
            LOG.debug("Update check failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.debug("Update check failed: {}", e.getClass().getSimpleName());
        }
    }

    private void record(ServerUpdate update) {
        status.record(update);
        SemVer latest = SemVer.parse(update.latestVersion()).orElseThrow();
        if (update.updateAvailable() && announced.add(latest)) {
            LOG.warn("Update available: {}", update.notice().orElseThrow());
        }
    }

    /** Stops the schedule; a running check is interrupted. */
    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
