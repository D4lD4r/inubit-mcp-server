package de.dadecker.inubit.mcp.adapter.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Starts operating-system processes. The production implementation is
 * {@link SystemProcessLauncher}; tests replay recorded StartCLI runs (research R-17).
 */
public interface ProcessLauncher {

    /** Starts {@code spec}; never through a shell. */
    LaunchedProcess launch(LaunchSpec spec) throws IOException;

    /**
     * What to start.
     *
     * @param command the argument array; element 0 is the executable
     * @param environment the complete child environment (nothing is inherited)
     * @param workingDirectory the child's working directory
     */
    record LaunchSpec(List<String> command, Map<String, String> environment,
        Path workingDirectory) {

        public LaunchSpec {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
        }

        /** Only the executable and the argument count: arguments may carry user data. */
        @Override
        public String toString() {
            return "LaunchSpec[" + command.get(0) + " +" + (command.size() - 1) + " args]";
        }
    }

    /** A started process. */
    interface LaunchedProcess {

        OutputStream stdin();

        InputStream stdout();

        InputStream stderr();

        /** Waits up to {@code timeout}; true if the process has exited. */
        boolean waitFor(Duration timeout) throws InterruptedException;

        /** The exit code; only valid after the process has exited. */
        int exitValue();

        boolean isAlive();

        /**
         * Asks the process and its descendants to terminate (SIGTERM). The process tree is
         * snapshotted at this moment, so that {@link #destroyForcibly()} still reaches
         * descendants that were re-parented when the root exited.
         */
        void destroy();

        /**
         * True while the process, a process of the {@link #destroy()} snapshot or one of their
         * current descendants is alive.
         */
        boolean treeAlive();

        /**
         * Kills (SIGKILL) every process of the {@link #destroy()} snapshot that is still alive,
         * their current descendants and the process itself, whether or not the root has exited.
         */
        void destroyForcibly();
    }
}
