package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 024-T044 (research D-12): the one cross-process lock - an in-process lock per lock-file path,
 * then a file lock polled until the wait has run out; the lock file is created owner-only, never
 * deleted and never opened through a link; on a timeout the caller's error is thrown.
 */
@Timeout(30)
class OwnerOnlyFileLockTest {

    private static final Duration SHORT = Duration.ofMillis(300);

    @TempDir
    Path dir;

    private Path lockFile() {
        return dir.resolve("some.lock");
    }

    private static OwnerOnlyFileLock lock() {
        return new OwnerOnlyFileLock(PrivateFiles.forHost(), "the test state", "the test directory");
    }

    private static final Supplier<ToolErrorException> BUSY = () -> new ToolErrorException(
        ToolError.of(ErrorCode.PRECONDITION_FAILED, "the caller's busy text", "why", "hint"));

    private static void assertCallersBusy(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ToolErrorException.class,
            e -> assertThat(e.error().message()).isEqualTo("the caller's busy text"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theLockFileIsCreatedOwnerOnlyAndStaysAfterRelease() throws IOException {
        try (OwnerOnlyFileLock.Held held = lock().acquire(lockFile(), SHORT, BUSY)) {
            assertThat(held).isNotNull();
        }

        assertThat(lockFile()).isRegularFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(lockFile())))
            .isEqualTo("rw-------");
    }

    @Test
    void aSecondThreadWaitsUntilTheFirstReleases() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Void> other;
        try (OwnerOnlyFileLock.Held held = lock().acquire(lockFile(), SHORT, BUSY)) {
            other = CompletableFuture.runAsync(() -> {
                waiting.countDown();
                lock().acquire(lockFile(), Duration.ofSeconds(10), BUSY).close();
            });
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(other).isNotDone();
        }

        other.get(10, TimeUnit.SECONDS);
    }

    @Test
    void twoIndependentInstancesOnTheSamePathSerialize() throws Exception {
        OwnerOnlyFileLock first = lock();
        OwnerOnlyFileLock second = lock();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            OwnerOnlyFileLock instance = i % 2 == 0 ? first : second;
            runs.add(CompletableFuture.runAsync(() -> {
                try (OwnerOnlyFileLock.Held held =
                         instance.acquire(lockFile(), Duration.ofSeconds(10), BUSY)) {
                    most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    Thread.sleep(20);
                    inside.decrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        assertThat(most.get()).isEqualTo(1);
    }

    @Test
    void aLockHeldInTheProcessTimesOutWithTheCallersError() throws Exception {
        try (OwnerOnlyFileLock.Held held = lock().acquire(lockFile(), SHORT, BUSY)) {
            long start = System.nanoTime();
            CompletableFuture<Void> other = CompletableFuture.runAsync(
                () -> assertCallersBusy(() -> lock().acquire(lockFile(), SHORT, BUSY)));

            other.get(10, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(SHORT);
        }
        assertThat(OwnerOnlyFileLock.heldInProcess(lockFile())).isFalse();
    }

    @Test
    void aFileLockHeldElsewhereTimesOutWithTheCallersErrorAndLeavesNoLockBehind()
        throws Exception {
        Path file = Files.writeString(lockFile(), "");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
             FileLock elsewhere = channel.lock()) {
            long start = System.nanoTime();

            assertCallersBusy(() -> lock().acquire(lockFile(), SHORT, BUSY));

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(SHORT);
            assertThat(elsewhere.isValid()).isTrue();
        }
        assertThat(OwnerOnlyFileLock.heldInProcess(lockFile())).isFalse();
        lock().acquire(lockFile(), SHORT, BUSY).close();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAtTheLockPathIsRefusedAndItsTargetUntouched(@TempDir Path outside)
        throws IOException {
        Path target = Files.writeString(outside.resolve("target"), "kept");
        Files.createSymbolicLink(lockFile(), target);

        assertThatThrownBy(() -> lock().acquire(lockFile(), SHORT, BUSY))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).isEqualTo("The lock file some.lock in the test"
                    + " directory cannot be used (it is a symbolic link); nothing was written");
                assertThat(e.error().likelyCause()).isEqualTo("This file serializes the writes to the"
                    + " test state; a link there is never followed");
                assertThat(e.error().nextStep()).isEqualTo("Remove some.lock from the test directory"
                    + " by hand (it is created again on the next write), then retry");
                assertThat(e.error().toString()).as("no path is shown")
                    .doesNotContain(dir.toString(), outside.toString());
            });
        assertThat(Files.readString(target)).isEqualTo("kept");
        assertThat(Files.isSymbolicLink(lockFile())).isTrue();
        assertThat(OwnerOnlyFileLock.heldInProcess(lockFile())).isFalse();
    }
}
