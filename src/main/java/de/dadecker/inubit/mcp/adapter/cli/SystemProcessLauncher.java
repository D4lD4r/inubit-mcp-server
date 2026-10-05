package de.dadecker.inubit.mcp.adapter.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Starts processes with {@link ProcessBuilder}: the argument array as given (no shell), the
 * environment replaced by exactly the given map, and separate stdout/stderr pipes.
 * {@code destroy}/{@code destroyForcibly} also reach the descendants, because
 * {@code startcli.sh} runs the JVM as a child process: {@code destroy} snapshots the whole tree,
 * and {@code destroyForcibly} kills every snapshot member still alive (also after the root has
 * exited and its children were re-parented) plus their current descendants.
 */
public final class SystemProcessLauncher implements ProcessLauncher {

    @Override
    public LaunchedProcess launch(LaunchSpec spec) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(spec.command())
            .directory(spec.workingDirectory().toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE);
        builder.environment().clear();
        builder.environment().putAll(spec.environment());
        return new SystemProcess(builder.start());
    }

    private static final class SystemProcess implements LaunchedProcess {

        private final Process process;
        /** The process tree at the time of {@link #destroy()}. */
        private volatile List<ProcessHandle> snapshot = List.of();

        SystemProcess(Process process) {
            this.process = process;
        }

        @Override
        public OutputStream stdin() {
            return process.getOutputStream();
        }

        @Override
        public InputStream stdout() {
            return process.getInputStream();
        }

        @Override
        public InputStream stderr() {
            return process.getErrorStream();
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int exitValue() {
            return process.exitValue();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void destroy() {
            List<ProcessHandle> tree = new ArrayList<>();
            tree.add(process.toHandle());
            tree.addAll(process.descendants().toList());
            snapshot = List.copyOf(tree);
            tree.forEach(ProcessHandle::destroy);
        }

        @Override
        public boolean treeAlive() {
            return !aliveTree().isEmpty();
        }

        @Override
        public void destroyForcibly() {
            aliveTree().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        /** Root, snapshot and their current descendants that are still alive. */
        private Set<ProcessHandle> aliveTree() {
            Set<ProcessHandle> alive = new LinkedHashSet<>();
            List<ProcessHandle> roots = new ArrayList<>(snapshot);
            roots.add(process.toHandle());
            for (ProcessHandle handle : roots) {
                if (handle.isAlive()) {
                    alive.add(handle);
                }
                handle.descendants().filter(ProcessHandle::isAlive).forEach(alive::add);
            }
            return alive;
        }
    }
}
