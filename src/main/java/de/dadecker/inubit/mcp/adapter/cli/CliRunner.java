package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs StartCLI for one server (research R-6, R-12; Constitution I, II, V).
 *
 * <ul>
 *   <li>Argument array, never a shell:
 *       {@code [startcli.sh, -u, <user>, [--trustStoreFilePath, <p12>],
 *       [--disableHostNameVerification], --execCommand, <command>, <cliUrl>]}. There is never a
 *       {@code -p}; the password plus {@code \n} is written to stdin, which is then closed.
 *   <li>The child environment is an allowlist: {@code PATH}, {@code HOME}, {@code TMPDIR},
 *       {@code LANG}, {@code LC_ALL}, {@code USER} (copied if set), {@code JAVA_HOME} (from
 *       {@code cli.javaHome}, else the server's own) and
 *       {@code JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US}. No credential variable
 *       ever reaches StartCLI.
 *   <li>The working directory is the CLI home. On timeout (or interruption) the process tree is
 *       destroyed; any member still alive after {@link #KILL_GRACE} is killed forcibly.
 *   <li>Windows is not supported in this version (known limitation, research R-6):
 *       {@code CLI_UNAVAILABLE}.
 *   <li>stdout and stderr are drained completely but kept only up to
 *       {@link #MAX_OUTPUT_BYTES} each.
 * </ul>
 */
public final class CliRunner {

    public static final int MAX_OUTPUT_BYTES = 1 << 20;
    public static final Duration KILL_GRACE = Duration.ofSeconds(2);
    public static final String JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US";
    static final String WINDOWS_UNSUPPORTED =
        "CLI tools are not supported on Windows in this version";
    private static final Duration POLL = Duration.ofMillis(20);

    /** Variables copied from the server's own environment, if set. */
    static final List<String> INHERITED_VARIABLES =
        List.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "USER");

    private final ProcessLauncher launcher;
    private final Map<String, String> parentEnvironment;
    private final boolean windows;
    private final CliResources resources;

    /** @param resources registry of the running StartCLI processes, stopped on shutdown */
    public CliRunner(ProcessLauncher launcher, Map<String, String> parentEnvironment,
        boolean windows, CliResources resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.launcher = launcher;
        this.parentEnvironment = Map.copyOf(parentEnvironment);
        this.windows = windows;
    }

    /** The registry of running StartCLI processes (and export directories). */
    public CliResources resources() {
        return resources;
    }

    /**
     * Logs in as {@code username} and executes {@code command}, without the credential guard;
     * private so that every caller goes through the guarded {@link #run(EffectiveNodeConfig,
     * String, Secret, CliCommand, Duration, CredentialGuard)} (Phase 3 re-review N1).
     */
    private CliResult runUnguarded(EffectiveNodeConfig server, String username,
        Secret password, CliCommand command, Duration timeout, String timeoutSetting) {
        List<String> arguments = new ArrayList<>(List.of("-u", username));
        server.tls().trustStore().ifPresent(trustStore -> {
            arguments.add("--trustStoreFilePath");
            arguments.add(trustStore.toString());
        });
        if (server.tls().disableHostnameVerification()) {
            arguments.add("--disableHostNameVerification");
        }
        arguments.add("--execCommand");
        arguments.add(command.commandLine());
        arguments.add(server.cli().url().toString());
        return execute(server, arguments, Optional.of(password), timeout, timeoutSetting);
    }

    /**
     * Logs in as {@code username} and executes {@code command}, guarded by the server's
     * {@link CredentialGuard} shared with its REST client (Phase 3 review M2): within the pause
     * after a rejected login the cached {@code AUTH_FAILED} is thrown without starting
     * StartCLI; a {@code LoginFailure} rejects and an exit code 0 confirms the credentials.
     *
     * @throws ToolErrorException {@code AUTH_FAILED} from the guard, {@code CLI_UNAVAILABLE} if
     *     StartCLI cannot be started, {@code TIMEOUT} if it does not finish within
     *     {@code timeout}
     */
    public CliResult run(EffectiveNodeConfig server, String username, Secret password,
        CliCommand command, Duration timeout, CredentialGuard guard) {
        return run(server, username, password, command, timeout, "cliTimeout", guard);
    }

    /**
     * As above; {@code timeoutSetting} names the setting that {@code timeout} comes from (e.g.
     * {@code cliExportTimeout}) in the next step of a {@code TIMEOUT}.
     */
    public CliResult run(EffectiveNodeConfig server, String username, Secret password,
        CliCommand command, Duration timeout, String timeoutSetting, CredentialGuard guard) {
        try (CredentialGuard.Permit permit = guard.acquire()) {
            CliResult result = runUnguarded(server, username, password, command, timeout,
                timeoutSetting);
            if (CliOutputClassifier.isLoginFailure(result)) {
                permit.rejected();
            } else if (result.exitCode() == 0) {
                permit.accepted();
            }
            return result;
        }
    }

    /**
     * Runs {@code startcli <arguments>}; with {@code stdinSecret} empty, stdin is closed at once.
     */
    CliResult execute(EffectiveNodeConfig server, List<String> arguments,
        Optional<Secret> stdinSecret, Duration timeout) {
        return execute(server, arguments, stdinSecret, timeout, "cliTimeout");
    }

    private CliResult execute(EffectiveNodeConfig server, List<String> arguments,
        Optional<Secret> stdinSecret, Duration timeout, String timeoutSetting) {
        LaunchTarget target = launchTarget(server);
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(target.script().toString());
        command.addAll(arguments);
        LaunchSpec spec = new LaunchSpec(command, childEnvironment(target.javaHome()),
            target.home());
        return launchAndWait(server, spec, stdinSecret, timeout, timeoutSetting);
    }

    /**
     * Checks that StartCLI can be used for {@code server} without launching anything, e.g.
     * before preparing an export directory.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} on Windows, without CLI home, without
     *     {@code bin/startcli.sh} or without a JDK for StartCLI
     */
    public void checkAvailable(EffectiveNodeConfig server) {
        launchTarget(server);
    }

    /** Where and with which JDK StartCLI runs for one server. */
    private record LaunchTarget(Path home, Path script, String javaHome) {
    }

    private LaunchTarget launchTarget(EffectiveNodeConfig server) {
        if (windows) {
            throw unavailable(server, "StartCLI cannot be used for " + server.id(),
                WINDOWS_UNSUPPORTED, "Run the CLI-backed tools from macOS or Linux; the REST"
                    + " tools work on Windows");
        }
        Path home = server.cli().home().orElseThrow(() -> unavailable(server,
            "No CLI home is configured for " + server.id(),
            "cliHome / cli.home is not set", "Set cliHome to the INUBIT client directory"));
        Path script = server.startCliScript(windows).orElseThrow();
        if (!Files.isRegularFile(script)) {
            throw unavailable(server, "StartCLI not found: " + script,
                "cliHome does not point to an INUBIT client installation",
                "Set cliHome to the client directory that contains bin/" + script.getFileName());
        }
        String javaHome = server.cli().javaHome().map(Path::toString)
            .or(() -> Optional.ofNullable(parentEnvironment.get("JAVA_HOME"))
                .filter(value -> !value.isBlank()))
            .orElseThrow(() -> unavailable(server, "No JDK for StartCLI on " + server.id(),
                "Neither cli.javaHome (cliJavaHome) nor JAVA_HOME is set",
                "Set cli.javaHome to the JDK the INUBIT client needs (8.1: JDK 17)"));
        return new LaunchTarget(home, script, javaHome);
    }

    private Map<String, String> childEnvironment(String javaHome) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : INHERITED_VARIABLES) {
            String value = parentEnvironment.get(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.put("JAVA_HOME", javaHome);
        environment.put("JAVA_TOOL_OPTIONS", JAVA_TOOL_OPTIONS);
        return environment;
    }

    private CliResult launchAndWait(EffectiveNodeConfig server, LaunchSpec spec,
        Optional<Secret> stdinSecret, Duration timeout, String timeoutSetting) {
        long start = System.nanoTime();
        LaunchedProcess process;
        // announced before the launch, so that a concurrent shutdown waits for and stops it;
        // refused (nothing started) once the resources are closed (Phase 6 review W2)
        CliResources.Reservation reservation;
        try {
            reservation = resources.reserve();
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(server.id()));
        }
        try {
            process = launcher.launch(spec);
        } catch (IOException e) {
            reservation.close();
            throw unavailable(server, "StartCLI could not be started ("
                    + e.getClass().getSimpleName() + ")",
                "The script is not executable or the JDK is missing",
                "Check cliHome, cli.javaHome and the file permissions of bin/startcli.sh");
        } catch (RuntimeException | Error e) {
            reservation.close();
            throw e;
        }
        CliResources.Registration registration;
        try {
            registration = reservation.process(process);
        } catch (ToolErrorException e) {
            // close() gave up waiting for this launch; the process was stopped (US4 R1)
            throw new ToolErrorException(e.error().withNode(server.id()));
        }
        try (registration) {
            CliResult result = waitFor(server, process, stdinSecret, timeout, timeoutSetting,
                start);
            if (resources.closed()) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "The MCP server is shutting down; StartCLI on " + server.id()
                        + " was stopped",
                    "The MCP client closed the connection or the MCP server was terminated",
                    "Retry after the MCP server was restarted").withNode(server.id()));
            }
            return result;
        }
    }

    private CliResult waitFor(EffectiveNodeConfig server, LaunchedProcess process,
        Optional<Secret> stdinSecret, Duration timeout, String timeoutSetting, long start) {
        BoundedSink stdout = BoundedSink.drain(process.stdout());
        BoundedSink stderr = BoundedSink.drain(process.stderr());
        writeStdin(process, stdinSecret);
        boolean exited;
        try {
            exited = process.waitFor(timeout);
        } catch (InterruptedException e) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw timeout(server, "The StartCLI call on " + server.id() + " was cancelled",
                timeoutSetting);
        }
        if (!exited) {
            terminate(process);
            throw timeout(server, "StartCLI did not finish within " + Durations.human(timeout)
                + " on " + server.id(), timeoutSetting);
        }
        stdout.await(KILL_GRACE);
        stderr.await(KILL_GRACE);
        return new CliResult(process.exitValue(), stdout.text(), stderr.text(),
            Duration.ofNanos(System.nanoTime() - start), stdout.truncated() || stderr.truncated());
    }

    private static void writeStdin(LaunchedProcess process, Optional<Secret> secret) {
        try (OutputStream stdin = process.stdin()) {
            if (secret.isPresent()) {
                byte[] bytes = (secret.get().reveal() + "\n").getBytes(StandardCharsets.UTF_8);
                try {
                    stdin.write(bytes);
                    stdin.flush();
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (IOException e) {
            // the process exited before reading stdin; its output tells why
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

    private static ToolErrorException unavailable(EffectiveNodeConfig server, String message,
        String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE, message,
            likelyCause, nextStep).withNode(server.id()));
    }

    private static ToolErrorException timeout(EffectiveNodeConfig server, String message,
        String timeoutSetting) {
        return new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, message,
            "The INUBIT server is slow or unreachable, or StartCLI waits for input",
            "Check get_health; raise " + timeoutSetting + " if the INUBIT server is just slow")
            .withNode(server.id()));
    }

    /** Reads a stream to its end on a virtual thread, keeping at most the first 1 MB. */
    private static final class BoundedSink {

        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private boolean overflow;
        private Thread reader;

        static BoundedSink drain(InputStream in) {
            BoundedSink sink = new BoundedSink();
            sink.reader = Thread.ofVirtual().start(() -> sink.consume(in));
            return sink;
        }

        private void consume(InputStream in) {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    synchronized (this) {
                        int room = MAX_OUTPUT_BYTES - kept.size();
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

        synchronized String text() {
            return kept.toString(StandardCharsets.UTF_8);
        }

        synchronized boolean truncated() {
            return overflow;
        }
    }
}
