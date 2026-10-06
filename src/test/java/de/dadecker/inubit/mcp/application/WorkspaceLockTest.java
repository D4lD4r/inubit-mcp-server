package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T010 (research D-9, FR-020): one export or check per workspace, across processes. */
class WorkspaceLockTest {

    @TempDir
    Path root;

    private static void assertRefused(Path root) {
        assertThatThrownBy(() -> WorkspaceLock.acquire(root))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).contains("another export or check is running");
            });
    }

    @Test
    void theLockFileIsInTheWorkspaceRoot() {
        try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
            assertThat(root.resolve(".lock")).isRegularFile();
        }
    }

    @Test
    void aSecondAcquisitionInTheSameProcessIsRefusedAtOnce() {
        try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
            long start = System.nanoTime();
            assertRefused(root);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                .as("no waiting").isLessThan(2000);
        }
    }

    @Test
    void closingReleasesTheLockAlsoAfterAnException() {
        assertThatThrownBy(() -> {
            try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
                throw new IllegalStateException("export failed");
            }
        }).isInstanceOf(IllegalStateException.class);

        try (WorkspaceLock again = WorkspaceLock.acquire(root)) {
            assertThat(again).isNotNull();
        }
    }

    @Test
    void anIoErrorWhileLockingIsNotReportedAsBusy() {
        assertThatThrownBy(() -> WorkspaceLock.acquire(root, channel -> {
            throw new java.io.IOException("lock not supported");
        })).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(e.error().message()).contains(root.resolve(".lock").toString(),
                "cannot be locked").doesNotContain("another export or check is running");
        });
        // the failed attempt left nothing locked
        WorkspaceLock.acquire(root).close();
    }

    @Test
    void closingTwiceIsHarmless() {
        WorkspaceLock lock = WorkspaceLock.acquire(root);
        lock.close();
        lock.close();

        WorkspaceLock.acquire(root).close();
    }

    @Test
    void aLockHeldByAnotherProcessIsRefusedUntilThatProcessReleasesIt() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(List.of(java.toString(), "-cp",
            System.getProperty("java.class.path"), LockHolder.class.getName(), root.toString()))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(),
                StandardCharsets.UTF_8));
            assertThat(out.readLine()).isEqualTo("locked");

            assertRefused(root);

            holder.getOutputStream().close();
            assertThat(holder.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(holder.exitValue()).isZero();
            try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
                assertThat(lock).isNotNull();
            }
        } finally {
            holder.destroyForcibly();
        }
    }
}
