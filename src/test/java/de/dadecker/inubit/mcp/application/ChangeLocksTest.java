package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T050, research D-17, edge case "second change"): a change holds the key
 * {@code node|area|object} while it runs; a second change with the same key is refused with
 * {@code PRECONDITION_FAILED}, and the key is free again once the first change ended, also after
 * an exception.
 */
class ChangeLocksTest {

    private static final NodeId NODE1 = NodeId.parse("test/node1");
    private static final NodeId NODE2 = NodeId.parse("test/node2");
    private static final ActionSpec.Area OPERATION = ActionSpec.Area.OPERATION;

    private final ChangeLocks locks = new ChangeLocks();

    @Test
    void aHeldKeyRefusesASecondChangeWithPreconditionFailed() {
        try (var lock = locks.acquire(NODE1, OPERATION, "maintenance")) {
            ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
                () -> locks.acquire(NODE1, OPERATION, "maintenance"));

            assertThat(refused.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(refused.error().node()).isEqualTo(Optional.of(NODE1));
            assertThat(refused.error().message())
                .contains("maintenance").contains("test/node1").contains("nothing was changed");
            TestSecrets.assertNoLeak(refused.error().toString());
        }
    }

    @Test
    void otherNodeAreaOrObjectIsAnotherKey() {
        try (var lock = locks.acquire(NODE1, OPERATION, "tag:release@group/type")) {
            locks.acquire(NODE2, OPERATION, "tag:release@group/type").close();
            locks.acquire(NODE1, ActionSpec.Area.SETTINGS, "tag:release@group/type").close();
            locks.acquire(NODE1, OPERATION, "tag:release@repo/path").close();
            locks.acquire(NODE1, OPERATION, "*").close();
        }
    }

    @Test
    void theKeyIsFreeAgainAfterTheChange() {
        try (var lock = locks.acquire(NODE1, OPERATION, "activeTag:admin")) {
            assertThat(lock).isNotNull();
        }

        locks.acquire(NODE1, OPERATION, "activeTag:admin").close();
    }

    @Test
    void theKeyIsReleasedAfterAnException() {
        assertThatThrownBy(() -> {
            try (var lock = locks.acquire(NODE1, OPERATION, "maintenance")) {
                throw new IllegalStateException("change failed");
            }
        }).isInstanceOf(IllegalStateException.class);

        locks.acquire(NODE1, OPERATION, "maintenance").close();
    }

    @Test
    void closingTwiceDoesNotReleaseTheKeyOfALaterChange() {
        var first = locks.acquire(NODE1, OPERATION, "maintenance");
        first.close();
        try (var second = locks.acquire(NODE1, OPERATION, "maintenance")) {
            first.close();

            assertThatThrownBy(() -> locks.acquire(NODE1, OPERATION, "maintenance"))
                .isInstanceOf(ToolErrorException.class);
        }
    }

    @Test
    void ofParallelChangesWithTheSameKeyExactlyOneRuns() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allTried = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try (var lock = locks.acquire(NODE1, OPERATION, "maintenance")) {
                        allTried.countDown();
                        // hold the key until every other thread has tried
                        assertThat(allTried.await(10, TimeUnit.SECONDS)).isTrue();
                        return true;
                    } catch (ToolErrorException e) {
                        assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                        allTried.countDown();
                        return false;
                    }
                }));
            }
            start.countDown();

            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        locks.acquire(NODE1, OPERATION, "maintenance").close();
    }
}
