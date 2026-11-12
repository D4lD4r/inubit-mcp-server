package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs one process to its end (feature 020, research D-3): the pipeline of {@link CliRunner},
 * shared by StartCLI and the queue-entry helper.
 *
 * <ul>
 *   <li>The run is announced to the {@link CliResources} before the launch and registered after
 *       it, so that a shutdown stops the process; after the shutdown nothing is started.
 *   <li>stdout and stderr are drained completely but kept only up to the output bound
 *       ({@link #MAX_OUTPUT_BYTES} unless a {@link Call} says otherwise); the result says
 *       whether one of them was cut.
 *   <li>stdin receives the given bytes in one write and is closed; the bytes are zero-filled
 *       once written and on every other way out of the run.
 *   <li>On timeout (or interruption) the process tree is destroyed; whatever of it is still
 *       alive after {@link #KILL_GRACE} is killed forcibly.
 *   <li>The {@code TIMEOUT}, {@code CLI_UNAVAILABLE} and {@code INTERNAL} texts name the process
 *       by its label ({@code StartCLI}, {@code the queue-entry helper}).
 * </ul>
 */
public final class ProcessRun {

    public static final int MAX_OUTPUT_BYTES = 1 << 20;
    public static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final Duration POLL = Duration.ofMillis(20);

    private final ProcessLauncher launcher;
    private final CliResources resources;

    /** @param resources registry of the running processes, stopped on shutdown */
    public ProcessRun(ProcessLauncher launcher, CliResources resources) {
        this.launcher = launcher;
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /** Starts the launched process; a StartCLI write starts it inside its hand-off (017 D-24). */
    @FunctionalInterface
    interface StartHook {
        StartHook DIRECT = Launch::launch;

        LaunchedProcess start(Launch launch) throws IOException;
    }

    @FunctionalInterface
    interface Launch {
        LaunchedProcess launch() throws IOException;
    }

    /** Explains a timeout by the output kept so far (StartCLI's password prompt on Windows). */
    @FunctionalInterface
    interface TimeoutRefinement {
        /** The error to throw instead of the plain {@code TIMEOUT} with {@code message}, if any. */
        Optional<ToolErrorException> refine(String stdout, String message);
    }

    /**
     * How one run reports and keeps its output; {@link #of} gives the defaults of
     * {@link ProcessRun#run(LaunchSpec, byte[], Duration, String)}.
     *
     * @param processLabel the process in the texts: a name ({@code StartCLI}) or a noun phrase
     *     with {@code the} ({@code the queue-entry helper})
     * @param node the node the errors carry and the texts name, if any
     * @param timeoutSetting the setting a {@code TIMEOUT} tells to raise
     */
    record Call(String processLabel, Optional<NodeId> node, String timeoutSetting,
        int maxOutputBytes, OutputDecoder decoder, StartHook hook, String launchFailureCause,
        String launchFailureNextStep, Optional<TimeoutRefinement> timeoutRefinement) {

        Call {
            Objects.requireNonNull(processLabel, "processLabel");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(timeoutSetting, "timeoutSetting");
            Objects.requireNonNull(decoder, "decoder");
            Objects.requireNonNull(hook, "hook");
            Objects.requireNonNull(launchFailureCause, "launchFailureCause");
            Objects.requireNonNull(launchFailureNextStep, "launchFailureNextStep");
            Objects.requireNonNull(timeoutRefinement, "timeoutRefinement");
        }

        /**
         * No node, {@code cliTimeout}, {@link #MAX_OUTPUT_BYTES} decoded as UTF-8, started
         * directly, no timeout refinement.
         */
        static Call of(String processLabel) {
            return new Call(processLabel, Optional.empty(), "cliTimeout", MAX_OUTPUT_BYTES,
                OutputDecoder.posix(), StartHook.DIRECT,
                "The program or its working directory is missing or not accessible",
                "Check cli.home, cli.javaHome and their file permissions", Optional.empty());
        }

        Call forNode(NodeId newNode) {
            return new Call(processLabel, Optional.of(newNode), timeoutSetting, maxOutputBytes,
                decoder, hook, launchFailureCause, launchFailureNextStep, timeoutRefinement);
        }

        Call withTimeoutSetting(String setting) {
            return new Call(processLabel, node, setting, maxOutputBytes, decoder, hook,
                launchFailureCause, launchFailureNextStep, timeoutRefinement);
        }

        Call withOutput(int bound, OutputDecoder newDecoder) {
            return new Call(processLabel, node, timeoutSetting, bound, newDecoder, hook,
                launchFailureCause, launchFailureNextStep, timeoutRefinement);
        }

        Call startedBy(StartHook newHook) {
            return new Call(processLabel, node, timeoutSetting, maxOutputBytes, decoder, newHook,
                launchFailureCause, launchFailureNextStep, timeoutRefinement);
        }

        Call withLaunchFailure(String cause, String nextStep) {
            return new Call(processLabel, node, timeoutSetting, maxOutputBytes, decoder, hook,
                cause, nextStep, timeoutRefinement);
        }

        Call refiningTimeouts(TimeoutRefinement refinement) {
            return new Call(processLabel, node, timeoutSetting, maxOutputBytes, decoder, hook,
                launchFailureCause, launchFailureNextStep, Optional.of(refinement));
        }

        private String subject() {
            return processLabel.substring(0, 1).toUpperCase(Locale.ROOT)
                + processLabel.substring(1);
        }

        private String onNode() {
            return node.map(id -> " on " + id).orElse("");
        }

        private ToolErrorException located(ToolError error) {
            return new ToolErrorException(node.map(error::withNode).orElse(error));
        }

        private ToolErrorException launchFailed(IOException e) {
            return located(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                subject() + " could not be started (" + e.getClass().getSimpleName() + ")",
                launchFailureCause, launchFailureNextStep));
        }

        private ToolErrorException timeout(String message) {
            return located(ToolError.of(ErrorCode.TIMEOUT, message,
                "The INUBIT server is slow or unreachable, or " + processLabel
                    + " waits for input",
                "Check get_health; raise " + timeoutSetting
                    + " if the INUBIT server is just slow"));
        }

        private ToolErrorException cancelled() {
            String name = processLabel.startsWith("the ") ? processLabel.substring(4)
                : processLabel;
            return timeout("The " + name + " call" + onNode() + " was cancelled");
        }

        private ToolErrorException stoppedByShutdown() {
            return located(ToolError.of(ErrorCode.INTERNAL,
                "The MCP server is shutting down; " + processLabel + onNode() + " was stopped",
                "The MCP client closed the connection or the MCP server was terminated",
                "Retry after the MCP server was restarted"));
        }
    }

    /**
     * Runs {@code spec} with {@code stdin}, keeping {@link #MAX_OUTPUT_BYTES} per stream (UTF-8),
     * within {@code timeout} ({@code cliTimeout} in the next step); {@code stdin} is zero-filled
     * when this returns or throws.
     *
     * @param processLabel names the process in the error texts, e.g. {@code the queue-entry
     *     helper}
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} if the process cannot be started,
     *     {@code TIMEOUT} if it does not finish within {@code timeout} or the call is
     *     interrupted (the process tree is killed), {@code INTERNAL} while the MCP server shuts
     *     down
     */
    public CliResult run(LaunchSpec spec, byte[] stdin, Duration timeout, String processLabel) {
        return run(spec, stdin, timeout, Call.of(processLabel));
    }

    /**
     * As above, for {@code node}: the errors carry it and the {@code TIMEOUT} and shutdown texts
     * name it ({@code … on <node>}).
     */
    public CliResult run(LaunchSpec spec, byte[] stdin, Duration timeout, String processLabel,
        NodeId node) {
        return run(spec, stdin, timeout, Call.of(processLabel).forNode(node));
    }

    /** As above; a {@code TIMEOUT} tells to raise {@code timeoutSetting}. */
    public CliResult run(LaunchSpec spec, byte[] stdin, Duration timeout, String processLabel,
        NodeId node, String timeoutSetting) {
        return run(spec, stdin, timeout,
            Call.of(processLabel).forNode(node).withTimeoutSetting(timeoutSetting));
    }

    /** As above, reporting and keeping the output as {@code call} says. */
    CliResult run(LaunchSpec spec, byte[] stdin, Duration timeout, Call call) {
        try {
            return launchAndWait(spec, stdin, timeout, call);
        } finally {
            Arrays.fill(stdin, (byte) 0);
        }
    }

    private CliResult launchAndWait(LaunchSpec spec, byte[] stdin, Duration timeout,
        Call call) {
        long start = System.nanoTime();
        LaunchedProcess process;
        // announced before the launch, so that a concurrent shutdown waits for and stops it;
        // refused (nothing started) once the resources are closed (Phase 6 review W2)
        CliResources.Reservation reservation;
        try {
            reservation = resources.reserve();
        } catch (ToolErrorException e) {
            throw call.located(e.error());
        }
        try {
            process = call.hook().start(() -> launcher.launch(spec));
        } catch (IOException e) {
            reservation.close();
            throw call.launchFailed(e);
        } catch (RuntimeException | Error e) {
            reservation.close();
            throw e;
        }
        CliResources.Registration registration;
        try {
            registration = reservation.process(process);
        } catch (ToolErrorException e) {
            // close() gave up waiting for this launch; the process was stopped (US4 R1)
            throw call.located(e.error());
        }
        try (registration) {
            CliResult result = waitFor(process, stdin, timeout, call, start);
            if (resources.closed()) {
                throw call.stoppedByShutdown();
            }
            return result;
        }
    }

    private static CliResult waitFor(LaunchedProcess process, byte[] stdin, Duration timeout,
        Call call, long start) {
        BoundedSink stdout = BoundedSink.drain(process.stdout(), call.maxOutputBytes());
        BoundedSink stderr = BoundedSink.drain(process.stderr(), call.maxOutputBytes());
        writeStdin(process, stdin);
        boolean exited;
        try {
            exited = process.waitFor(timeout);
        } catch (InterruptedException e) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw call.cancelled();
        }
        if (!exited) {
            terminate(process);
            String message = call.subject() + " did not finish within "
                + Durations.human(timeout) + call.onNode();
            if (call.timeoutRefinement().isPresent()) {
                stdout.await(KILL_GRACE);
                Optional<ToolErrorException> refined = call.timeoutRefinement().get()
                    .refine(stdout.text(call.decoder()), message);
                if (refined.isPresent()) {
                    throw refined.get();
                }
            }
            throw call.timeout(message);
        }
        stdout.await(KILL_GRACE);
        stderr.await(KILL_GRACE);
        return new CliResult(process.exitValue(), stdout.text(call.decoder()),
            stderr.text(call.decoder()), Duration.ofNanos(System.nanoTime() - start),
            stdout.truncated() || stderr.truncated(), call.maxOutputBytes());
    }

    /**
     * Writes {@code bytes} in one buffer, zero-fills them and closes stdin. The whole input is
     * written up front, without waiting for a prompt of the process.
     */
    private static void writeStdin(LaunchedProcess process, byte[] bytes) {
        try (OutputStream stdin = process.stdin()) {
            stdin.write(bytes);
            stdin.flush();
        } catch (IOException e) {
            // the process exited before reading stdin; its output tells why
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * {@code destroy} the process tree; whatever of it is still alive after {@link #KILL_GRACE}
     * (the root or any descendant, e.g. a JVM that ignores SIGTERM) is killed forcibly.
     */
    private static void terminate(LaunchedProcess process) {
        process.destroy();
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + KILL_GRACE.toNanos();
            while (process.treeAlive() && System.nanoTime() < deadline) {
                if (process.isAlive()) {
                    process.waitFor(POLL);
                } else {
                    Thread.sleep(POLL.toMillis());
                }
            }
            if (process.treeAlive()) {
                process.destroyForcibly();
                process.waitFor(KILL_GRACE);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Reads a stream to its end on a virtual thread, keeping at most the first {@code bound}
     * bytes.
     */
    private static final class BoundedSink {

        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final int bound;
        private boolean overflow;
        private Thread reader;

        private BoundedSink(int bound) {
            this.bound = bound;
        }

        static BoundedSink drain(InputStream in, int bound) {
            BoundedSink sink = new BoundedSink(bound);
            sink.reader = Thread.ofVirtual().start(() -> sink.consume(in));
            return sink;
        }

        private void consume(InputStream in) {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    synchronized (this) {
                        int room = bound - kept.size();
                        if (n > room) {
                            overflow = true;
                        }
                        kept.write(buffer, 0, Math.min(n, Math.max(room, 0)));
                    }
                }
            } catch (IOException e) {
                // stream closed: keep what was read
            }
        }

        void await(Duration timeout) {
            try {
                if (!reader.join(timeout)) {
                    // a grandchild still holds the pipe; keep what was read so far
                    reader.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized String text(OutputDecoder decoder) {
            return decoder.decode(kept.toByteArray());
        }

        synchronized boolean truncated() {
            return overflow;
        }
    }
}
