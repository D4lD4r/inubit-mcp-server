package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.OwnerOnlyFileLock;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 024-T045 (research D-12): one log-level change per server at a time, across instances of the
 * profile - a lock file per server in {@code log-levels/nodes/}; busy is
 * {@code PRECONDITION_FAILED} after the wait. The node lock is taken before the store lock and is
 * independent of it.
 */
@Timeout(30)
class NodeLogLevelLocksTest {

    private static final Duration SHORT = Duration.ofMillis(300);
    private static final NodeId NODE1 = NodeId.of("test", "node1");
    private static final NodeId NODE2 = NodeId.of("test", "node2");

    @TempDir
    Path home;

    private Path directory() {
        return LeaseStore.defaultDirectory(home, "acme");
    }

    private NodeLogLevelLocks locks() {
        return new NodeLogLevelLocks(directory(), PrivateFiles.forHost());
    }

    private static void assertBusy(NodeId server, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(e.error().message())
                .isEqualTo("Another log-level change for " + server.value() + " is running");
        });
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theLockFileOfAServerIsCreatedOwnerOnlyInTheNodesDirectory() throws IOException {
        locks().acquire(NODE1, SHORT).close();

        assertThat(directory().resolve(".lock")).doesNotExist();
        Path nodes = directory().resolve("nodes");
        assertThat(nodes.resolve("test.node1.lock")).isRegularFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(nodes)))
            .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory())))
            .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(
            Files.getPosixFilePermissions(nodes.resolve("test.node1.lock"))))
            .isEqualTo("rw-------");
    }

    @Test
    void twoInstancesOnOneDirectoryExcludeEachOtherPerNodeButNotAcrossNodes() throws Exception {
        NodeLogLevelLocks first = locks();
        NodeLogLevelLocks second = locks();
        try (OwnerOnlyFileLock.Held held = first.acquire(NODE1, SHORT)) {
            CompletableFuture.runAsync(() -> {
                assertBusy(NODE1, () -> second.acquire(NODE1, SHORT));
                second.acquire(NODE2, SHORT).close();
            }).get(10, TimeUnit.SECONDS);
        }

        second.acquire(NODE1, SHORT).close();
    }

    @Test
    void aBusyNodeLockIsGivenUpAfterTheWait() throws Exception {
        try (OwnerOnlyFileLock.Held held = locks().acquire(NODE1, SHORT)) {
            long start = System.nanoTime();
            CompletableFuture.runAsync(() -> assertBusy(NODE1, () -> locks().acquire(NODE1, SHORT)))
                .get(10, TimeUnit.SECONDS);

            assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(SHORT).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void aWaitingChangeGetsTheNodeLockWhenTheHolderReleasesIt() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Void> other;
        try (OwnerOnlyFileLock.Held held = locks().acquire(NODE1, SHORT)) {
            other = CompletableFuture.runAsync(() -> {
                waiting.countDown();
                locks().acquire(NODE1, Duration.ofSeconds(10)).close();
            });
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(other).isNotDone();
        }

        other.get(10, TimeUnit.SECONDS);
    }

    @Test
    void theStoreLockCanBeTakenUnderANodeLockAndNeverWaitsForOne() throws Exception {
        LeaseStore store = new LeaseStore(directory(), PrivateFiles.forHost(), Clock.systemUTC());
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> storeHolder;
        try (OwnerOnlyFileLock.Held node = locks().acquire(NODE1, SHORT)) {
            // the order of every change: node lock, then store lock
            assertThat(store.update(leases -> List.of(lease()), SHORT)).hasSize(1);

            // a holder of the store lock waits for no node lock, so none can wait for it in turn
            storeHolder = CompletableFuture.runAsync(() -> store.update(leases -> {
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return leases;
            }, SHORT));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            CompletableFuture.runAsync(() -> locks().acquire(NODE2, SHORT).close())
                .get(10, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(SHORT);
        } finally {
            release.countDown();
        }

        storeHolder.get(10, TimeUnit.SECONDS);
        // the store lock file exists because a lease was written
        assertThat(List.of(directory().resolve(".lock"), directory().resolve("leases.json"),
            directory().resolve("nodes"))).allMatch(Files::exists);
    }

    private static Lease lease() {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        loggers.put(new LoggerName("com.inubit.ibis.acme.Orders"), TemporaryLevel.DEBUG);
        Instant now = Instant.now();
        return new Lease(UUID.randomUUID(), 1, NODE1, "node1", loggers, now,
            now.plus(Duration.ofMinutes(10)), new LeaseOwner(4711, Optional.empty()),
            LeaseState.ACTIVE, UUID.randomUUID(), Optional.empty(), Optional.empty());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAtTheNodesDirectoryIsRefusedAndNothingIsCreatedThere() throws IOException {
        Path target = Files.createDirectories(home.resolve("elsewhere"));
        Files.createDirectories(directory());
        Path nodes = directory().resolve("nodes");
        Files.createSymbolicLink(nodes, target);

        assertThatThrownBy(() -> locks().acquire(NODE1, SHORT))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).isEqualTo("The log-levels nodes directory "
                    + nodes + " is a symbolic link; nothing was changed");
            });

        try (Stream<Path> names = Files.list(target)) {
            assertThat(names).isEmpty();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAtTheLogLevelsDirectoryIsRefusedAndNothingIsCreatedThere() throws IOException {
        Path target = Files.createDirectories(home.resolve("elsewhere"));
        Files.createDirectories(directory().getParent());
        Files.createSymbolicLink(directory(), target);

        assertThatThrownBy(() -> locks().acquire(NODE1, SHORT))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).isEqualTo("The log-levels directory "
                    + directory() + " is a symbolic link; nothing was changed");
            });

        try (Stream<Path> names = Files.list(target)) {
            assertThat(names).isEmpty();
        }
    }
}
