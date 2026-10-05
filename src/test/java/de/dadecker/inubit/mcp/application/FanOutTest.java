package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

@Timeout(30)
class FanOutTest {

    private static final NodeId SLOW = NodeId.parse("qa/slow");
    private static final NodeId FAST = NodeId.parse("qa/fast");

    private final FanOut fanOut = new FanOut();

    /**
     * Captures the FanOut logger for every test: the intentional WARN events of the failure tests
     * are asserted on, and none of them reaches the console (additivity off while capturing).
     */
    private final Logger logger = (Logger) LoggerFactory.getLogger(FanOut.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
    }

    @AfterEach
    void releaseLog() {
        logger.setAdditive(true);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    @Test
    void slowServerTimesOutWhileTheOthersReturnWithinTheMaxDeadlinePlusOneSecond() {
        // SC-002: servers delayed by 6 s and 0 s against a 5 s deadline
        Map<NodeId, Duration> delays = Map.of(SLOW, Duration.ofSeconds(6), FAST, Duration.ZERO);
        long start = System.nanoTime();

        List<NodeResult<String>> results = fanOut.run(List.of(SLOW, FAST), Duration.ofSeconds(5),
            server -> {
                sleep(delays.get(server));
                return "ok " + server;
            });

        long elapsed = millisSince(start);
        assertThat(elapsed).isLessThanOrEqualTo(6_000);
        assertThat(elapsed).isGreaterThanOrEqualTo(4_900);
        assertThat(results).extracting(NodeResult::node).containsExactly(SLOW, FAST);
        assertThat(results.get(0).error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.node()).contains(SLOW);
            assertThat(error.message()).contains("qa/slow", "5 s");
        });
        assertThat(results.get(1).payload()).contains("ok qa/fast");
    }

    @Test
    void callsRunInParallelOnVirtualThreads() {
        List<NodeId> servers = IntStream.range(0, 8)
            .mapToObj(i -> NodeId.of("stage", "server" + i)).toList();
        Set<Boolean> virtual = ConcurrentHashMap.newKeySet();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        long start = System.nanoTime();

        List<NodeResult<Integer>> results = fanOut.run(servers, Duration.ofSeconds(5), server -> {
            virtual.add(Thread.currentThread().isVirtual());
            threads.add(Thread.currentThread().toString());
            sleep(Duration.ofSeconds(1));
            return 1;
        });

        // sequential execution would take 8 s
        assertThat(millisSince(start)).isLessThan(3_000);
        assertThat(virtual).containsExactly(true);
        assertThat(threads).hasSize(8);
        assertThat(results).allSatisfy(result -> assertThat(result.payload()).contains(1));
    }

    @Test
    void eachServerHasItsOwnDeadline() {
        Map<NodeId, Duration> deadlines = Map.of(SLOW, Duration.ofMillis(300),
            FAST, Duration.ofSeconds(3));
        long start = System.nanoTime();

        List<NodeResult<String>> results = fanOut.run(List.of(SLOW, FAST), deadlines::get,
            server -> {
                sleep(Duration.ofMillis(1_000));
                return "done";
            });

        assertThat(millisSince(start)).isLessThan(3_000);
        assertThat(results.get(0).error()).hasValueSatisfying(
            error -> assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThat(results.get(1).payload()).contains("done");
    }

    @Test
    void resultsKeepTheInputOrderRegardlessOfCompletionOrder() {
        List<NodeId> servers = List.of(NodeId.parse("a/one"), NodeId.parse("a/two"),
            NodeId.parse("a/three"));
        Map<NodeId, Integer> delayMillis = Map.of(servers.get(0), 400, servers.get(1), 200,
            servers.get(2), 0);

        List<NodeResult<String>> results = fanOut.run(servers, Duration.ofSeconds(5), server -> {
            Thread.sleep(delayMillis.get(server));
            return server.name();
        });

        assertThat(results).extracting(r -> r.payload().orElseThrow())
            .containsExactly("one", "two", "three");
    }

    @Test
    void toolErrorsBecomePartialResultsWithTheServerSet() {
        ToolError notFound = ToolError.of(ErrorCode.NOT_FOUND, "gone", "cause", "next");

        List<NodeResult<String>> results = fanOut.run(List.of(SLOW, FAST), Duration.ofSeconds(5),
            server -> {
                if (server.equals(SLOW)) {
                    throw new ToolErrorException(notFound);
                }
                return "fine";
            });

        assertThat(results.get(0).error()).contains(notFound.withNode(SLOW));
        assertThat(results.get(1).payload()).contains("fine");
    }

    @Test
    void unexpectedExceptionsBecomeInternalErrorsWithoutTheirMessage() {
        List<NodeResult<String>> results = fanOut.run(List.of(SLOW), Duration.ofSeconds(5),
            server -> {
                throw new IllegalStateException("contains s3cr3t value");
            });

        assertThat(results.get(0).error()).hasValueSatisfying(error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(error.node()).contains(SLOW);
            assertThat(error.message()).contains("IllegalStateException").doesNotContain("s3cr3t");
        });
    }

    @Test
    void unexpectedExceptionsAreLoggedAtWarnWithTheThrowable() {
        List<NodeResult<String>> results = fanOut.run(List.of(SLOW), Duration.ofSeconds(5),
            server -> {
                throw new IllegalStateException("boom");
            });

        assertThat(results.get(0).error()).hasValueSatisfying(error ->
            assertThat(error.nextStep()).contains("log"));
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("qa/slow");
            assertThat(event.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
        });
    }

    @Test
    void toolErrorsAreNotLoggedAsWarnings() {
        fanOut.run(List.of(SLOW), Duration.ofSeconds(5), server -> {
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND, "m", "c", "n"));
        });

        assertThat(appender.list).isEmpty();
    }

    @Test
    void nullResultBecomesAnInternalError() {
        List<NodeResult<String>> results = fanOut.run(List.of(FAST), Duration.ofSeconds(5),
            server -> null);

        assertThat(results.get(0).error()).hasValueSatisfying(
            error -> assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL));
    }

    @Test
    void timedOutCallsAreInterrupted() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);

        fanOut.run(List.of(SLOW), Duration.ofMillis(200), server -> {
            try {
                sleep(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return "late";
        });

        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void callIgnoringInterruptsDoesNotDelayTheResult() {
        AtomicBoolean release = new AtomicBoolean();
        long start = System.nanoTime();
        try {
            List<NodeResult<String>> results = fanOut.run(List.of(SLOW, FAST),
                Duration.ofMillis(500), server -> {
                    if (server.equals(SLOW)) {
                        while (!release.get()) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException ignored) {
                                // deliberately ignores the interrupt sent at the deadline
                            }
                        }
                    }
                    return "x";
                });

            assertThat(millisSince(start)).isLessThan(1_500);
            assertThat(results.get(0).error()).hasValueSatisfying(
                error -> assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT));
            assertThat(results.get(1).payload()).contains("x");
        } finally {
            release.set(true);
        }
    }

    @Test
    void noServersGiveNoResults() {
        assertThat(fanOut.run(List.of(), Duration.ofSeconds(1), server -> "x")).isEmpty();
    }
}
