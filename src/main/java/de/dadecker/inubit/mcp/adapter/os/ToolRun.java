package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a store tool ({@code security}, {@code secret-tool}) through {@link ProcessLauncher} and
 * collects its output (feature 011, research R-3, R-5): never through a shell, stdin closed at once,
 * stdout and stderr read on their own threads, the whole process tree destroyed when the timeout
 * passes or a descendant keeps a pipe open after the exit. A read may end up to about two seconds
 * after its timeout (the kill grace). The output may contain a secret value; callers decide what of
 * it may ever be logged and wipe it when done.
 */
final class ToolRun {

    /** How long a destroyed tool gets to exit before it is killed. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(1);

    /** What happened to the run. */
    sealed interface Outcome {

        /** The tool exited with {@code code}. */
        record Exited(int code, byte[] stdout, byte[] stderr) implements Outcome {

            @Override
            public String toString() {
                return "Exited[code=" + code + "]";
            }
        }

        /** The tool did not end in time (or the caller was interrupted); {@code reason} says how. */
        record TimedOut(String reason) implements Outcome {
        }

        /** The tool could not be started, e.g. it is not installed. */
        record NotStarted(String reason) implements Outcome {
        }
    }

    private ToolRun() {
    }

    static Outcome run(ProcessLauncher launcher, List<String> command,
        Map<String, String> environment, Path workingDirectory, Duration timeout) {
        LaunchedProcess process;
        try {
            process = launcher.launch(new LaunchSpec(command, environment, workingDirectory));
        } catch (IOException e) {
            return new Outcome.NotStarted(e.getClass().getSimpleName());
        }
        try {
            process.stdin().close();
        } catch (IOException e) {
            // the tool reads nothing; a closed or broken stdin is fine
        }
        CompletableFuture<byte[]> stdout = drain(process.stdout());
        CompletableFuture<byte[]> stderr = drain(process.stderr());
        try {
            if (!process.waitFor(timeout)) {
                kill(process);
                return new Outcome.TimedOut("did not exit within " + timeout.toSeconds() + " s");
            }
            byte[] out = finish(stdout);
            byte[] err = finish(stderr);
            if (out == null || err == null) {
                // a descendant still holds a pipe: the output is incomplete and must not be read as
                // "not found" or "no password line"
                kill(process);
                wipe(out);
                wipe(err);
                return new Outcome.TimedOut("the output was still open " + KILL_GRACE.toSeconds()
                    + " s after the tool exited");
            }
            return new Outcome.Exited(process.exitValue(), out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(process);
            return new Outcome.TimedOut("interrupted");
        }
    }

    private static CompletableFuture<byte[]> drain(InputStream stream) {
        CompletableFuture<byte[]> bytes = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (InputStream in = stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                bytes.complete(out.toByteArray());
            } catch (IOException e) {
                bytes.complete(new byte[0]);
            }
        });
        return bytes;
    }

    /**
     * The drained output, or {@code null} if the pipe is still open {@link #KILL_GRACE} after the
     * exit (a descendant holds it).
     */
    private static byte[] finish(CompletableFuture<byte[]> bytes) throws InterruptedException {
        try {
            return bytes.get(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    /** Overwrites output that may contain a value; {@code null} is ignored. */
    static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** The variables of {@code names} that {@code environment} sets, nothing else. */
    static Map<String, String> allowed(Map<String, String> environment, List<String> names) {
        Map<String, String> passed = new LinkedHashMap<>();
        for (String name : names) {
            String value = environment.get(name);
            if (value != null) {
                passed.put(name, value);
            }
        }
        return Map.copyOf(passed);
    }

    private static void kill(LaunchedProcess process) {
        process.destroy();
        try {
            process.waitFor(KILL_GRACE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.treeAlive()) {
            process.destroyForcibly();
        }
    }
}
