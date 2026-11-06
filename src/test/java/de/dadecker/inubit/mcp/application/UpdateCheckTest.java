package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.ReleaseSource.LatestRelease;
import de.dadecker.inubit.mcp.application.ReleaseSource.ReleaseCheckException;
import de.dadecker.inubit.mcp.application.UpdateCache.CachedRelease;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class UpdateCheckTest {

    private static final Instant T0 = Instant.parse("2026-05-01T08:00:00Z");

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final MutableClock clock = new MutableClock(T0);
    private final UpdateStatus status = new UpdateStatus();
    private final RecordingCache cache = new RecordingCache();
    private final Deque<Object> answers = new ArrayDeque<>();
    private final UpdateCheck check = new UpdateCheck(this::answer, v("0.7.1"), status, cache, clock);
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        Logger logger = logger();
        logs.setContext(logger.getLoggerContext());
        logs.start();
        logger.addAppender(logs);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        Logger logger = logger();
        logger.detachAppender(logs);
        logs.stop();
        logger.setLevel(previousLevel);
        check.close();
    }

    @Test
    void aNewerReleaseIsRecordedCachedAndAnnouncedOnce() {
        answers.add(v("0.8.0"));
        answers.add(v("0.8.0"));
        check.checkOnce();
        clock.advance(Duration.ofHours(24));
        check.checkOnce();

        ServerUpdate update = status.current().orElseThrow();
        assertThat(update.updateAvailable()).isTrue();
        assertThat(update.latestVersion()).isEqualTo("0.8.0");
        assertThat(update.checkedAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(warnings()).singleElement().asString()
            .contains("0.8.0 is available (running 0.7.1)");
        assertThat(cache.writes).containsExactly("0.8.0@" + T0,
            "0.8.0@" + T0.plus(Duration.ofHours(24)));
    }

    @Test
    void theSameOrAnOlderReleaseIsRecordedWithoutWarning() {
        answers.add(v("0.7.1"));
        check.checkOnce();
        assertThat(status.current().orElseThrow().updateAvailable()).isFalse();
        answers.add(v("0.6.0"));
        check.checkOnce();
        assertThat(status.current().orElseThrow().latestVersion()).isEqualTo("0.6.0");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void aFailureIsLoggedAtDebugAndKeepsTheLastStatus() {
        answers.add(v("0.8.0"));
        answers.add(new ReleaseCheckException("HTTP 403"));
        answers.add(new IllegalStateException("boom"));
        check.checkOnce();
        check.checkOnce();
        check.checkOnce();

        assertThat(status.current().orElseThrow().latestVersion()).isEqualTo("0.8.0");
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.DEBUG)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Update check failed: HTTP 403",
                "Update check failed: IllegalStateException");
        assertThat(cache.writes).hasSize(1);
    }

    @Test
    void aStillNewerReleaseIsAnnouncedAgain() {
        answers.add(v("0.8.0"));
        answers.add(v("0.9.0"));
        check.checkOnce();
        check.checkOnce();
        assertThat(warnings()).hasSize(2).last().asString().contains("0.9.0 is available");
    }

    @Test
    void aCachedNewerReleaseSeedsTheStatusWithoutNetwork() {
        check.seed(Optional.of(new CachedRelease(v("0.8.0"), T0, Optional.empty())));
        assertThat(status.current().orElseThrow().checkedAt()).isEqualTo(T0);
        assertThat(warnings()).hasSize(1);
        answers.add(v("0.8.0"));
        check.checkOnce();
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void aCachedOlderOrEqualReleaseIsIgnored() {
        check.seed(Optional.of(new CachedRelease(v("0.7.1"), T0, Optional.empty())));
        check.seed(Optional.empty());
        assertThat(status.current()).isEmpty();
    }

    @Test
    void startSchedulesAnImmediateCheckAndThenEvery24Hours() {
        RecordingExecutor executor = new RecordingExecutor();
        check.start(executor);
        assertThat(executor.initialDelay).isZero();
        assertThat(executor.delay).isEqualTo(Duration.ofHours(24).toMillis());
        assertThat(executor.unit).isEqualTo(TimeUnit.MILLISECONDS);
        assertThatIllegalStateException().isThrownBy(() -> check.start(executor));

        answers.add(v("0.7.1"));
        executor.task.run();
        assertThat(warnings()).isEmpty();
        answers.add(v("0.8.0"));
        executor.task.run();
        assertThat(warnings()).hasSize(1);

        check.close();
        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    void theDaemonExecutorUsesANamedDaemonThread() throws Exception {
        var executor = UpdateCheck.daemonExecutor();
        try {
            Thread thread = executor.submit(Thread::currentThread).get();
            assertThat(thread.isDaemon()).isTrue();
            assertThat(thread.getName()).isEqualTo("inubit-mcp-update-check");
        } finally {
            executor.shutdownNow();
        }
    }

    private LatestRelease answer() throws ReleaseCheckException {
        Object next = answers.removeFirst();
        if (next instanceof ReleaseCheckException e) {
            throw e;
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return new LatestRelease((SemVer) next);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static Logger logger() {
        return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(UpdateCheck.class);
    }

    private static SemVer v(String version) {
        return SemVer.parse(version).orElseThrow();
    }

    private static final class RecordingCache implements UpdateCache {

        final List<String> writes = new ArrayList<>();

        @Override
        public Optional<CachedRelease> read() {
            return Optional.empty();
        }

        @Override
        public void write(SemVer latestVersion, Instant checkedAt) {
            writes.add(latestVersion + "@" + checkedAt);
        }

        @Override
        public void dismiss(SemVer version) {
        }
    }

    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {

        Runnable task;
        long initialDelay;
        long delay;
        TimeUnit unit;

        RecordingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
            long delay, TimeUnit unit) {
            this.task = command;
            this.initialDelay = initialDelay;
            this.delay = delay;
            this.unit = unit;
            return null;
        }
    }
}
