package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 019-T026 (research D-12): one write at a time per knowledge directory - first an in-process
 * lock, then a file lock on {@code <knowledge>/.lock} polled until the wait has run out; the lock
 * file is created owner-only, never deleted and never opened through a link.
 */
@Timeout(30)
class KnowledgeLockTest {

    private static final Duration SHORT = Duration.ofMillis(300);

    @TempDir
    Path root;

    private KnowledgeLock lock() {
        return new KnowledgeLock(root, PrivateFiles.forHost());
    }

    private static void assertBusy(ThrowingAction action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(e.error().message())
                .isEqualTo("Another write to the local knowledge is in progress; nothing was"
                    + " written");
        });
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theLockFileIsCreatedOwnerOnlyAndStaysAfterRelease() throws IOException {
        try (KnowledgeLock.Held held = lock().acquire(SHORT)) {
            assertThat(held).isNotNull();
        }

        Path file = root.resolve(".lock");
        assertThat(file).isRegularFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            .isEqualTo("rw-------");
    }

    @Test
    void anExistingLockFileIsUsedAndTheLockCanBeTakenAgainAfterRelease() throws IOException {
        Files.writeString(root.resolve(".lock"), "");

        lock().acquire(SHORT).close();
        try (KnowledgeLock.Held again = lock().acquire(SHORT)) {
            assertThat(again).isNotNull();
        }
    }

    @Test
    void aSecondWriterInTheProcessWaitsAndGivesUpWhenTheWaitRunsOut() throws Exception {
        try (KnowledgeLock.Held held = lock().acquire(SHORT)) {
            long start = System.nanoTime();
            CompletableFuture<Void> other = CompletableFuture.runAsync(
                () -> assertBusy(() -> new KnowledgeLock(root, PrivateFiles.forHost())
                    .acquire(SHORT)));

            other.get(10, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(SHORT);
        }
    }

    @Test
    void aWaitingWriterGetsTheLockOnceTheFirstReleasesIt() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Void> other;
        try (KnowledgeLock.Held held = lock().acquire(SHORT)) {
            other = CompletableFuture.runAsync(() -> {
                waiting.countDown();
                lock().acquire(Duration.ofSeconds(10)).close();
            });
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(other).isNotDone();
        }

        other.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aFileLockHeldElsewhereIsPolledUntilTheWaitRunsOut() throws Exception {
        Path file = Files.writeString(root.resolve(".lock"), "");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
             FileLock elsewhere = channel.lock()) {
            long start = System.nanoTime();

            assertBusy(() -> lock().acquire(SHORT));

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(SHORT);
            assertThat(elsewhere.isValid()).isTrue();
        }
        try (KnowledgeLock.Held held = lock().acquire(SHORT)) {
            assertThat(held).as("the in-process lock was given back on the timeout").isNotNull();
        }
    }

    @Test
    void aWriterThatGaveUpLeavesNoLockBehind() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try (KnowledgeLock.Held held = lock().acquire(SHORT)) {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(100);
        assertBusy(() -> lock().acquire(Duration.ofMillis(100)));
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        lock().acquire(SHORT).close();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkInPlaceOfTheLockFileIsRefusedAndItsTargetUntouched(@TempDir Path outside)
        throws IOException {
        Path target = Files.writeString(outside.resolve("target"), "kept");
        Files.createSymbolicLink(root.resolve(".lock"), target);

        assertThatThrownBy(() -> lock().acquire(SHORT))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).contains(".lock").contains("link");
                assertThat(e.error().toString()).as("no path outside the knowledge directory")
                    .doesNotContain(root.toString(), outside.toString());
            });
        assertThat(Files.readString(target)).isEqualTo("kept");
        assertThat(Files.isSymbolicLink(root.resolve(".lock"))).isTrue();
        lockIsFree();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aDanglingLinkInPlaceOfTheLockFileIsRefusedAndNothingIsCreated(@TempDir Path outside) {
        Path target = outside.resolve("missing");
        try {
            Files.createSymbolicLink(root.resolve(".lock"), target);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThatThrownBy(() -> lock().acquire(SHORT)).isInstanceOf(ToolErrorException.class);
        assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void aDirectoryInPlaceOfTheLockFileIsRefused() throws IOException {
        Files.createDirectory(root.resolve(".lock"));
        String real = root.toRealPath().toString();

        assertThatThrownBy(() -> lock().acquire(SHORT))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).contains(".lock");
                assertThat(e.error().toString()).as("the directory's path is not shown")
                    .doesNotContain(root.toString(), real);
            });
        lockIsFree();
    }

    /** The in-process lock was given back: a lock on another, usable directory is not needed. */
    private void lockIsFree() {
        assertThat(KnowledgeLock.heldInProcess(root)).isFalse();
    }
}
