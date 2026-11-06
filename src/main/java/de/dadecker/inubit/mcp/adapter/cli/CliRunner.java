package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Built;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Cause;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.TooLong;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.WindowsArguments;
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
 *   <li>Argument array, never a shell (macOS, Linux):
 *       {@code [startcli.sh, -u, <user>, [--trustStoreFilePath, <p12>],
 *       [--disableHostNameVerification], --execCommand, <command>, <cliUrl>]}. There is never a
 *       {@code -p}; the password plus {@code \n} is written to stdin, which is then closed.
 *   <li>Windows (feature 013, contracts/windows-invocation.md): the same StartCLI arguments reach
 *       {@code bin\startcli.bat} through the interpreter call of {@link WindowsCommandLine}.
 *       Before anything starts, each call refuses with {@code CLI_UNAVAILABLE} (naming the
 *       setting and the character class, never the value): a character
 *       {@link WindowsArguments#refusal} refuses in {@code cli.home}, {@code tls.trustStore} or
 *       {@code cli.url}, such a character or a {@code ;} in the effective Java home (it goes into
 *       {@code PATH}), a network {@code cli.home}, an unreadable system directory and a command
 *       line the configured values alone make too long; a command line the tool input makes too
 *       long is {@code INVALID_INPUT}.
 *   <li>Every platform: while the {@link ProcessContainment} is {@code Unavailable}, StartCLI is
 *       {@code CLI_UNAVAILABLE} (only possible on Windows).
 *   <li>The child environment is the allowlist of {@link StartCliEnvironment}, with
 *       {@code JAVA_HOME} from {@code cli.javaHome}, else the server's own (on Windows in any
 *       spelling). No credential variable ever reaches StartCLI.
 *   <li>The working directory is the CLI home. On timeout (or interruption) the process tree is
 *       destroyed; any member still alive after {@link #KILL_GRACE} is killed forcibly.
 *   <li>stdout and stderr are drained completely but kept only up to
 *       {@link #MAX_OUTPUT_BYTES} each, then decoded by {@link OutputDecoder}.
 * </ul>
 */
public final class CliRunner {

    public static final int MAX_OUTPUT_BYTES = 1 << 20;
    public static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final Duration POLL = Duration.ofMillis(20);
    /** The characters a value passed outside the command must not contain on Windows (R-2). */
    private static final String REFUSED_CHARACTERS =
        "\" % ! ^ & | < >, control characters or non-ASCII characters";

    private final ProcessLauncher launcher;
    private final Map<String, String> parentEnvironment;
    private final boolean windows;
    private final CliSystem system;
    private final ProcessContainment containment;
    private final CliResources resources;
    /** Created on the first decoded output: building a runner never loads native code. */
    private volatile OutputDecoder decoder;
    private final StartCliPinProbe pinProbe;

    /**
     * @param windows whether StartCLI runs on Windows ({@code bin\startcli.bat} through the
     *     interpreter of {@code system}'s system directory)
     * @param system the system directory and OEM code page (Windows)
     * @param containment whether StartCLI's processes end with the server; read on each call
     * @param resources registry of the running StartCLI processes, stopped on shutdown
     */
    public CliRunner(ProcessLauncher launcher, Map<String, String> parentEnvironment,
        boolean windows, CliSystem system, ProcessContainment containment,
        CliResources resources) {
        this(launcher, parentEnvironment, windows, system, containment, resources,
            new HandshakeStartCliPinProbe());
    }

    /**
     * @param pinProbe checks the StartCLI address of a server of a stage with
     *     {@code tls.pinPerServer} before StartCLI is started (feature 014, research R-15)
     */
    public CliRunner(ProcessLauncher launcher, Map<String, String> parentEnvironment,
        boolean windows, CliSystem system, ProcessContainment containment,
        CliResources resources, StartCliPinProbe pinProbe) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.pinProbe = Objects.requireNonNull(pinProbe, "pinProbe");
        this.launcher = launcher;
        this.parentEnvironment = Map.copyOf(parentEnvironment);
        this.windows = windows;
        this.system = Objects.requireNonNull(system, "system");
        this.containment = Objects.requireNonNull(containment, "containment");
        this.decoder = windows ? null : OutputDecoder.posix();
    }

    private OutputDecoder decoder() {
        OutputDecoder current = decoder;
        if (current == null) {
            current = OutputDecoder.windows(system.oemCharset());
            decoder = current;
        }
        return current;
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
     * <p>For a server of a stage with {@code tls.pinPerServer} the certificate at the StartCLI
     * address is checked against the server's own pin first, before the guard (feature 014,
     * research R-15).
     *
     * @throws ToolErrorException {@code AUTH_FAILED} from the guard, {@code CLI_UNAVAILABLE} if
     *     StartCLI cannot be started, {@code TIMEOUT} if it does not finish within
     *     {@code timeout}; {@code TLS_ERROR}, {@code UNREACHABLE} or {@code TIMEOUT} from the
     *     pin check, StartCLI then not started
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
        // before the guard: a refused address must neither wait for nor count as a login
        if (server.tls().pinPerServer()) {
            pinProbe.check(server);
        }
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
        LaunchSpec spec = windows ? windowsSpec(server, target, arguments)
            : posixSpec(target, arguments);
        return launchAndWait(server, spec, stdinSecret, timeout, timeoutSetting);
    }

    private LaunchSpec posixSpec(LaunchTarget target, List<String> arguments) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(target.script().toString());
        command.addAll(arguments);
        return new LaunchSpec(command,
            StartCliEnvironment.posix(parentEnvironment, target.javaHome()), target.home(),
            arguments);
    }

    /** The interpreter call; a line over the limit is refused before anything starts (R-4). */
    private LaunchSpec windowsSpec(EffectiveNodeConfig server, LaunchTarget target,
        List<String> arguments) {
        String systemDirectory = target.systemDirectory().orElseThrow();
        return switch (WindowsCommandLine.build(systemDirectory, target.script().toString(),
            arguments)) {
            case Built built -> new LaunchSpec(built.command(),
                StartCliEnvironment.windows(parentEnvironment, target.javaHome(),
                    systemDirectory), target.home(), arguments);
            case TooLong(Cause cause, int length) when cause == Cause.TOOL_INPUT ->
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "The StartCLI command line for " + server.id() + " would have " + length
                        + " characters; Windows allows at most "
                        + WindowsCommandLine.MAX_INNER_LENGTH,
                    "The tool input makes the command too long for the Windows command"
                        + " interpreter",
                    "Pass fewer or shorter values in one call").withNode(server.id()));
            case TooLong tooLong -> {
                String setting = longestSetting(server, arguments);
                throw unavailable(server, "StartCLI cannot be used for " + server.id()
                        + ": the StartCLI command line would exceed "
                        + WindowsCommandLine.MAX_INNER_LENGTH + " characters (" + setting + ")",
                    "The configured values alone make the command line longer than the Windows"
                        + " command interpreter allows",
                    "Shorten " + setting);
            }
        };
    }

    /**
     * The longest of the configured values in the command line: {@code cli.home} (in the script
     * path), {@code tls.trustStore}, {@code cli.url} and the username, which {@link
     * #runUnguarded} passes after {@code -u} as the first two arguments.
     */
    private static String longestSetting(EffectiveNodeConfig server, List<String> arguments) {
        Map<String, Integer> lengths = new LinkedHashMap<>();
        lengths.put("cli.home", server.cli().home().map(home -> home.toString().length())
            .orElse(0));
        lengths.put("tls.trustStore", server.tls().trustStore()
            .map(trustStore -> trustStore.toString().length()).orElse(0));
        lengths.put("cli.url", server.cli().url().toString().length());
        if (arguments.size() > 1 && arguments.get(0).equals("-u")) {
            lengths.put("the username", arguments.get(1).length());
        }
        return lengths.entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElseThrow();
    }

    /**
     * Checks that StartCLI can be used for {@code server} without launching anything, e.g.
     * before preparing an export directory.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} without CLI home, without
     *     {@code bin/startcli.sh} ({@code bin\startcli.bat}) or without a JDK for StartCLI, while
     *     the containment is unavailable, and on Windows for the refusals of the class comment
     *     except the line length, which needs the call
     */
    public void checkAvailable(EffectiveNodeConfig server) {
        launchTarget(server);
    }

    /**
     * Where and with which JDK StartCLI runs for one server; {@code systemDirectory} only on
     * Windows.
     */
    private record LaunchTarget(Path home, Path script, String javaHome,
        Optional<String> systemDirectory) {
    }

    private LaunchTarget launchTarget(EffectiveNodeConfig server) {
        Path home = server.cli().home().orElseThrow(() -> unavailable(server,
            "No CLI home is configured for " + server.id(),
            "cliHome / cli.home is not set", "Set cliHome to the INUBIT client directory"));
        if (windows) {
            if (WindowsArguments.isUnc(home)) {
                throw unavailable(server, "StartCLI cannot be used for " + server.id()
                        + ": cli.home is a network path (\\\\…)",
                    "The Windows command interpreter does not accept a network path as its"
                        + " working directory",
                    "StartCLI needs a local drive path on Windows; install the INUBIT client on"
                        + " a local drive and set cli.home to it");
            }
            refuseOnWindows(server, "cli.home", WindowsArguments.refusal(home.toString()), "");
        }
        Path script = server.startCliScript(windows).orElseThrow();
        if (!Files.isRegularFile(script)) {
            throw unavailable(server, "StartCLI not found: " + script,
                "cliHome does not point to an INUBIT client installation",
                "Set cliHome to the client directory that contains " + scriptName());
        }
        String javaHome = server.cli().javaHome().map(Path::toString)
            .or(() -> Optional.ofNullable(windows
                    ? StartCliEnvironment.valueIgnoringCase(parentEnvironment, "JAVA_HOME")
                    : parentEnvironment.get("JAVA_HOME"))
                .filter(value -> !value.isBlank()))
            .orElseThrow(() -> unavailable(server, "No JDK for StartCLI on " + server.id(),
                "Neither cli.javaHome (cliJavaHome) nor JAVA_HOME is set",
                "Set cli.javaHome to the JDK the INUBIT client needs (8.1: JDK 17)"));
        if (containment.state() instanceof ProcessContainment.Unavailable(String reason)) {
            throw unavailable(server, "StartCLI cannot be used for " + server.id()
                    + ": StartCLI processes could not be bound to the server (" + reason + ")",
                "Windows refused the job that ends StartCLI's processes with the MCP server",
                "Start the MCP server outside a job that forbids nested jobs; the REST tools"
                    + " work meanwhile");
        }
        if (!windows) {
            return new LaunchTarget(home, script, javaHome, Optional.empty());
        }
        refuseOnWindows(server, server.cli().javaHome().isPresent() ? "cli.javaHome"
            : "JAVA_HOME", WindowsArguments.refusal(javaHome)
                .or(() -> javaHome.indexOf(';') >= 0 ? Optional.of("semicolon")
                    : Optional.empty()), " or ;");
        server.tls().trustStore().ifPresent(trustStore -> refuseOnWindows(server,
            "tls.trustStore", WindowsArguments.refusal(trustStore.toString()), ""));
        refuseOnWindows(server, "cli.url",
            WindowsArguments.refusal(server.cli().url().toString()), "");
        Optional<String> systemDirectory = system.systemDirectory();
        if (systemDirectory.isEmpty()) {
            throw unavailable(server, "StartCLI cannot be used for " + server.id()
                    + ": the Windows system directory could not be read",
                "GetSystemDirectoryW failed, so the command interpreter cannot be located",
                "Restart the MCP server; the REST tools work meanwhile");
        }
        return new LaunchTarget(home, script, javaHome, systemDirectory);
    }

    /**
     * Throws {@code CLI_UNAVAILABLE} naming {@code setting} and the class of {@code refusal};
     * {@code alsoRefused} extends the list of refused characters in the next step.
     */
    private static void refuseOnWindows(EffectiveNodeConfig server, String setting,
        Optional<String> refusal, String alsoRefused) {
        if (refusal.isPresent()) {
            throw unavailable(server, "StartCLI cannot be used for " + server.id() + ": "
                    + setting + " contains a character the Windows command interpreter"
                    + " interprets (" + refusal.get() + ")",
                "The command interpreter that starts bin\\startcli.bat would change the value",
                "Use a " + setting + " without " + REFUSED_CHARACTERS + alsoRefused);
        }
    }

    /** Whether StartCLI runs on Windows ({@code bin\startcli.bat} through the interpreter). */
    public boolean windows() {
        return windows;
    }

    /** The StartCLI script below the CLI home as the next steps name it. */
    String scriptName() {
        return windows ? "bin\\startcli.bat" : "bin/startcli.sh";
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
                windows ? "The Windows command interpreter " + spec.command().get(0)
                    + " could not be started with cli.home as its working directory"
                    : "The script is not executable or the JDK is missing",
                windows ? "Check that cli.home is an existing local directory your account can"
                    + " access"
                    : "Check cliHome, cli.javaHome and the file permissions of bin/startcli.sh");
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
        return new CliResult(process.exitValue(), stdout.text(decoder()), stderr.text(decoder()),
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

        synchronized String text(OutputDecoder decoder) {
            return decoder.decode(kept.toByteArray());
        }

        synchronized boolean truncated() {
            return overflow;
        }
    }
}
