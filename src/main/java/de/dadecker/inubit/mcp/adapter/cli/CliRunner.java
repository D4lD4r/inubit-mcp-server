package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Built;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Cause;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.TooLong;
import de.dadecker.inubit.mcp.config.CliChoice;
import de.dadecker.inubit.mcp.config.CliChoice.AddressKind;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.WindowsArguments;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs StartCLI for one server (research R-6, R-12; Constitution I, II, V).
 *
 * <ul>
 *   <li>Argument array, never a shell (macOS, Linux):
 *       {@code [startcli.sh, -u, <user>, [--trustStoreFilePath, <p12>],
 *       [--disableHostNameVerification], --execCommand, <command>, <cliUrl>]}. There is never a
 *       {@code -p}; the password plus {@code \n} is written to stdin, which is then closed.
 *   <li>An interactive session on INUBIT 6.1 ({@link #runSession}, feature 012, research D-5):
 *       {@code [startcli.sh, -u, <user>, [--trustStoreFilePath, <path>],
 *       [--disableHostNameVerification], <cliUrl>]}; stdin receives the password line, one line
 *       per {@link CliSessionCommand} and {@code exit}, and is then closed. Environment,
 *       timeout, credential guard and (on Windows) launch and refusals are those of the
 *       {@code --execCommand} run; the output bound is passed per call (research D-10).
 *       The client trust options follow its {@link CliClientOptions} (research D-8): a client
 *       without {@code --trustStoreFilePath} gets the trust store through
 *       {@code -Djavax.net.ssl.trustStore} and {@code -Djavax.net.ssl.trustStoreType} appended
 *       to {@code JAVA_TOOL_OPTIONS}; never a trust-store password.
 *   <li>Windows (feature 013, contracts/windows-invocation.md): the same StartCLI arguments reach
 *       {@code bin\startcli.bat} through the interpreter call of {@link WindowsCommandLine}.
 *       Before anything starts, each call refuses with {@code CLI_UNAVAILABLE} (naming the
 *       setting and the character class, never the value): a character
 *       {@link WindowsArguments#refusal} refuses in {@code cli.home}, {@code tls.trustStore} or
 *       {@code cli.url}, such a character or a {@code ;} in the effective Java home (it goes into
 *       {@code PATH}), a network {@code cli.home}, an unreadable system directory and a command
 *       line the configured values alone make too long; a command line the tool input makes too
 *       long is {@code INVALID_INPUT}. A username with such a character and a password with a
 *       non-ASCII character are {@code AUTH_FAILED} before the credential guard, so they never
 *       count as a failed login (FR-003, research R-9).
 *   <li>Every platform: while the {@link ProcessContainment} is {@code Unavailable}, StartCLI is
 *       {@code CLI_UNAVAILABLE} (only possible on Windows).
 *   <li>The child environment is the allowlist of {@link StartCliEnvironment}, with
 *       {@code JAVA_HOME} from {@code cli.javaHome}, else the server's own (on Windows in any
 *       spelling). No credential variable ever reaches StartCLI.
 *   <li>The working directory is the CLI home. On timeout (or interruption) the process tree is
 *       destroyed; any member still alive after {@link #KILL_GRACE} is killed forcibly. On
 *       Windows a timeout whose last output is the client's password prompt names both
 *       possibilities: the client waits for the password at its console, or the server is slow.
 *   <li>stdout and stderr are drained completely but kept only up to
 *       {@link #MAX_OUTPUT_BYTES} each ({@code --execCommand} runs), or up to the bound a
 *       session is given, then decoded by {@link OutputDecoder}.
 *   <li>Feature 017 (research D-3, D-4): every call reads the node's effective version once and
 *       chooses the client, JDK and address for it ({@link #select}); everything above sees the
 *       chosen {@code cli} settings.
 * </ul>
 */
public final class CliRunner {

    public static final int MAX_OUTPUT_BYTES = 1 << 20;
    public static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final Logger LOG = LoggerFactory.getLogger(CliRunner.class);
    private static final Duration POLL = Duration.ofMillis(20);
    private static final byte[] JKS_MAGIC =
        {(byte) 0xfe, (byte) 0xed, (byte) 0xfe, (byte) 0xed};
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
    private final Function<NodeId, Optional<InubitVersion>> versions;

    /** A runner for which every node's version is unknown. */
    CliRunner(ProcessLauncher launcher, Map<String, String> parentEnvironment,
        boolean windows, CliSystem system, ProcessContainment containment,
        CliResources resources) {
        this(launcher, parentEnvironment, windows, system, containment, resources,
            new HandshakeStartCliPinProbe(), id -> Optional.empty());
    }

    /**
     * @param windows whether StartCLI runs on Windows ({@code bin\startcli.bat} through the
     *     interpreter of {@code system}'s system directory)
     * @param system the system directory and OEM code page (Windows)
     * @param containment whether StartCLI's processes end with the server; read on each call
     * @param resources registry of the running StartCLI processes, stopped on shutdown
     * @param pinProbe checks the StartCLI address of a server of a stage with
     *     {@code tls.pinPerServer} before StartCLI is started (feature 014, research R-15)
     * @param versions the effective version of a node, read once per call to choose its
     *     StartCLI client (feature 017, research D-4)
     */
    public CliRunner(ProcessLauncher launcher, Map<String, String> parentEnvironment,
        boolean windows, CliSystem system, ProcessContainment containment,
        CliResources resources, StartCliPinProbe pinProbe,
        Function<NodeId, Optional<InubitVersion>> versions) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.pinProbe = Objects.requireNonNull(pinProbe, "pinProbe");
        this.versions = Objects.requireNonNull(versions, "versions");
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
     * The StartCLI choice for one call (feature 017, research D-4, D-5, D-19).
     *
     * @param server         the node with the chosen {@code cli} settings
     * @param key            the chosen client directory and configured JDK
     * @param addressWarning set when an explicit {@code cli.url} names the servlet address on
     *     INUBIT 9 or later
     * @param version        the version the client was chosen for
     */
    public record Selection(EffectiveNodeConfig server, CliChoice choice, ClientKey key,
        Optional<String> addressWarning, Optional<InubitVersion> version) {

        public Selection {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(choice, "choice");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(addressWarning, "addressWarning");
            Objects.requireNonNull(version, "version");
        }

        /**
         * The {@code defaults.cliClients} key the messages name for a missing client (research
         * D-8): the version's major.minor, if known, when {@code cliClients} is configured or
         * the major is 9 or later; else empty, and the 0.8.0 texts apply. A line without minor
         * never chooses an entry (research D-3), so it names none either.
         */
        public Optional<MajorMinor> entryHint() {
            if (server.versionLine() instanceof VersionSetting.Line line
                && line.minor().isEmpty()) {
                return Optional.empty();
            }
            return version.filter(known -> !server.cliSources().clients().isEmpty()
                || known.major() >= 9).map(MajorMinor::of);
        }
    }

    /**
     * Chooses StartCLI for {@code server} at {@code version} without reading the version source;
     * idempotent: selecting the selected server again gives the same selection.
     */
    public Selection select(EffectiveNodeConfig server, Optional<InubitVersion> version) {
        CliChoice choice = server.cliFor(version);
        EffectiveNodeConfig.Cli cli = choice.cli();
        ClientKey key = new ClientKey(cli.home().map(Path::toString).orElse(""),
            cli.javaHome().map(Path::toString).orElse(""));
        boolean servletOnNine = choice.address() == AddressKind.CONFIGURED
            && cli.url().toString().contains(EffectiveNodeConfig.DEFAULT_CLI_PATH)
            && choice.major().isPresent() && choice.major().getAsInt() >= 9;
        Optional<String> warning = servletOnNine
            ? Optional.of("cli.url of " + server.id() + " uses "
                + EffectiveNodeConfig.DEFAULT_CLI_PATH + ", which INUBIT 9.0.2 and later"
                + " removed; remove cli.url or set it to the base URL")
            : Optional.empty();
        return new Selection(server.withCli(cli), choice, key, warning, version);
    }

    /**
     * Chooses StartCLI for {@code server} with one read of its effective version; for callers
     * that check before the launch what it uses (the 6.1 connection, feature 017 T013).
     */
    public Selection select(EffectiveNodeConfig server) {
        return select(server, versions.apply(server.id()));
    }

    /**
     * Logs in as {@code username} and executes {@code command}, without the credential guard;
     * private so that every caller goes through the guarded {@link #run(EffectiveNodeConfig,
     * String, Secret, CliCommand, Duration, CredentialGuard)} (Phase 3 re-review N1).
     */
    private CliResult runUnguarded(Selection selection, String username,
        Secret password, CliCommand command, Duration timeout, String timeoutSetting) {
        EffectiveNodeConfig server = selection.server();
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
        return execute(selection, arguments, Optional.of(password), List.of(), timeout,
            timeoutSetting, "", MAX_OUTPUT_BYTES);
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
        if (windows) {
            refuseWindowsCredentials(server, username, password);
        }
        Selection selection = select(server);
        // before the guard: a refused address must neither wait for nor count as a login
        if (server.tls().pinPerServer()) {
            pinProbe.check(selection.server());
        }
        return guarded(guard, () -> runUnguarded(selection, username, password, command,
            timeout, timeoutSetting));
    }

    /**
     * Logs in as {@code username} in StartCLI's interactive mode (INUBIT 6.1, feature 012,
     * research D-5) and runs {@code commands}, then {@code exit}, in one process, guarded like
     * {@link #run(EffectiveNodeConfig, String, Secret, CliCommand, Duration, CredentialGuard)}.
     * Neither the password nor a command appears in the argument array. The trust store and the
     * host-name option are passed as {@code options} allow (research D-8); every refusal is
     * made by the caller before (feature 012, {@code V61Connection}), and so is the TLS
     * handshake with the server's own pin that also serves as the pin check of a stage with
     * {@code tls.pinPerServer} (feature 014, research R-15); this method runs no second one.
     * stdout and stderr are kept up to {@code maxOutputBytes} each (research D-10:
     * {@link #MAX_OUTPUT_BYTES} for health, more for process listings).
     *
     * @throws ToolErrorException {@code AUTH_FAILED} from the guard or, on Windows, for a
     *     credential StartCLI cannot receive (feature 013, FR-003; before the guard),
     *     {@code CLI_UNAVAILABLE} if StartCLI cannot be started or a Windows refusal applies,
     *     {@code TIMEOUT} if it does not finish within {@code timeout}
     * @throws IllegalArgumentException if the trust store has to go through
     *     {@code JAVA_TOOL_OPTIONS} and its path contains whitespace (the caller refuses such a
     *     node first); nothing is launched
     */
    public CliResult runSession(EffectiveNodeConfig server, String username, Secret password,
        CliClientOptions options, List<CliSessionCommand> commands, Duration timeout,
        int maxOutputBytes, CredentialGuard guard) {
        return runSession(select(server), username, password, options, commands, timeout,
            maxOutputBytes, guard);
    }

    /** As above, with the client the caller chose at the start of its call (feature 017 T013). */
    public CliResult runSession(Selection selection, String username, Secret password,
        CliClientOptions options, List<CliSessionCommand> commands, Duration timeout,
        int maxOutputBytes, CredentialGuard guard) {
        EffectiveNodeConfig server = selection.server();
        if (windows) {
            refuseWindowsCredentials(server, username, password);
        }
        Optional<Path> trustStore = server.tls().trustStore();
        String extraJavaToolOptions = options.trustStoreFilePath() ? ""
            : trustStore.map(CliRunner::trustStoreProperties).orElse("");
        List<String> arguments = new ArrayList<>(List.of("-u", username));
        if (options.trustStoreFilePath()) {
            trustStore.ifPresent(path -> {
                arguments.add("--trustStoreFilePath");
                arguments.add(path.toString());
            });
        }
        if (options.disableHostNameVerification() && server.tls().disableHostnameVerification()
            && server.tls().pinnedCertificateSha256().isPresent()) {
            arguments.add("--disableHostNameVerification");
        }
        arguments.add(server.cli().url().toString());
        List<CliSessionCommand> lines = new ArrayList<>(commands);
        lines.add(CliSessionCommand.EXIT);
        return guarded(guard, () -> execute(selection, arguments, Optional.of(password), lines,
            timeout, "cliTimeout", extraJavaToolOptions, maxOutputBytes));
    }

    /**
     * {@code -Djavax.net.ssl.trustStore=<path> -Djavax.net.ssl.trustStoreType=<type>} with a
     * leading space. {@code JAVA_TOOL_OPTIONS} is split at whitespace without quoting on Java
     * 7/8, so a path with whitespace is never passed.
     */
    private static String trustStoreProperties(Path trustStore) {
        String path = trustStore.toString();
        if (path.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("A trust-store path with whitespace cannot be"
                + " passed in JAVA_TOOL_OPTIONS");
        }
        return " -Djavax.net.ssl.trustStore=" + path + " -Djavax.net.ssl.trustStoreType="
            + trustStoreType(trustStore);
    }

    /**
     * {@code JKS} for a file that starts with the JKS magic number, else {@code PKCS12} (the
     * format of {@code tls.trustStore}); an unreadable file counts as PKCS12, the TLS check
     * before the session reports it. Also the format check of {@code V61Connection}.
     */
    public static String trustStoreType(Path trustStore) {
        try (InputStream in = Files.newInputStream(trustStore)) {
            return Arrays.equals(in.readNBytes(JKS_MAGIC.length), JKS_MAGIC) ? "JKS" : "PKCS12";
        } catch (IOException e) {
            return "PKCS12";
        }
    }

    /**
     * Runs {@code launch} under the guard: a {@code LoginFailure} rejects and an exit code 0
     * confirms the credentials.
     */
    private static CliResult guarded(CredentialGuard guard, Supplier<CliResult> launch) {
        try (CredentialGuard.Permit permit = guard.acquire()) {
            CliResult result = launch.get();
            if (CliOutputClassifier.isLoginFailure(result)) {
                permit.rejected();
            } else if (result.exitCode() == 0) {
                permit.accepted();
            }
            return result;
        }
    }

    /**
     * Feature 013 (FR-003, research R-9): the username reaches StartCLI through the command
     * interpreter, and a password outside ASCII is refused until the live check (R-13) shows it
     * arrives unchanged. Names the entry and the cause, never the value.
     */
    /**
     * On Windows, refuses a username or password that cannot reach StartCLI unchanged with
     * {@code AUTH_FAILED}, as {@link #runSession} does; for callers that contact the node before
     * the session (the 6.1 TLS handshake, feature 013 T028). Off Windows it does nothing.
     */
    public void checkCredentials(EffectiveNodeConfig server, String username, Secret password) {
        if (windows) {
            refuseWindowsCredentials(server, username, password);
        }
    }

    private static void refuseWindowsCredentials(EffectiveNodeConfig server, String username,
        Secret password) {
        SecretLabels labels = server.secretLabels();
        WindowsArguments.refusal(username).ifPresent(characterClass -> {
            String entry = labels.label(CredentialNames.USERNAME);
            throw refusedCredential(server, "the username in " + entry + " contains a character"
                    + " the Windows command interpreter interprets (" + characterClass + ")",
                "On Windows the username reaches StartCLI through the command interpreter, which"
                    + " would change or execute " + REFUSED_CHARACTERS,
                "Use an INUBIT account whose username has none of these characters and store it in "
                    + entry + "; then restart the MCP server");
        });
        if (password.reveal().chars().anyMatch(c -> c > 0x7f)) {
            String entry = labels.label(CredentialNames.PASSWORD);
            throw refusedCredential(server, "the password in " + entry + " contains a non-ASCII"
                    + " character",
                "On Windows a password outside ASCII is not yet known to reach StartCLI unchanged",
                "Give the INUBIT account a password of ASCII characters only and store it in "
                    + entry + "; then restart the MCP server");
        }
    }

    /**
     * The refusal of a credential StartCLI cannot receive. Unlike a rejected login it re-reads
     * nothing (it never reaches the {@link CredentialGuard}), so a corrected entry takes effect
     * after a restart.
     */
    private static ToolErrorException refusedCredential(EffectiveNodeConfig server, String what,
        String cause, String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
            "StartCLI cannot log in to " + server.id() + ": " + what, cause, nextStep)
            .withNode(server.id()));
    }

    /**
     * Runs {@code startcli <arguments>}; with {@code stdinSecret} empty, stdin is closed at once.
     */
    CliResult execute(EffectiveNodeConfig server, List<String> arguments,
        Optional<Secret> stdinSecret, Duration timeout) {
        return execute(select(server), arguments, stdinSecret, timeout);
    }

    /** As above, with the client chosen by the caller (the probes, feature 017 research D-5). */
    CliResult execute(Selection selection, List<String> arguments,
        Optional<Secret> stdinSecret, Duration timeout) {
        return execute(selection, arguments, stdinSecret, List.of(), timeout, "cliTimeout", "",
            MAX_OUTPUT_BYTES);
    }

    /**
     * @param extraJavaToolOptions appended to the platform's {@code JAVA_TOOL_OPTIONS} (empty,
     *     or the trust-store properties of a 6.1 session, each with a leading space)
     */
    private CliResult execute(Selection selection, List<String> arguments,
        Optional<Secret> stdinSecret, List<CliSessionCommand> stdinCommands, Duration timeout,
        String timeoutSetting, String extraJavaToolOptions, int maxOutputBytes) {
        EffectiveNodeConfig server = selection.server();
        LaunchTarget target = launchTarget(selection);
        CliChoice choice = selection.choice();
        LOG.debug("{}: StartCLI client {} ({}), address {}", server.id(), target.home(),
            choice.source().name().toLowerCase(Locale.ROOT),
            choice.address().name().toLowerCase(Locale.ROOT));
        LaunchSpec spec = windows ? windowsSpec(server, target, arguments, extraJavaToolOptions)
            : posixSpec(target, arguments, extraJavaToolOptions);
        return launchAndWait(server, spec, stdinSecret, stdinCommands, timeout, timeoutSetting,
            maxOutputBytes).forClientMajor(choice.major());
    }

    private LaunchSpec posixSpec(LaunchTarget target, List<String> arguments,
        String extraJavaToolOptions) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(target.script().toString());
        command.addAll(arguments);
        return new LaunchSpec(command,
            StartCliEnvironment.posix(parentEnvironment, target.javaHome(),
                extraJavaToolOptions), target.home(), arguments);
    }

    /** The interpreter call; a line over the limit is refused before anything starts (R-4). */
    private LaunchSpec windowsSpec(EffectiveNodeConfig server, LaunchTarget target,
        List<String> arguments, String extraJavaToolOptions) {
        String systemDirectory = target.systemDirectory().orElseThrow();
        return switch (WindowsCommandLine.build(systemDirectory, target.script().toString(),
            arguments)) {
            case Built built -> new LaunchSpec(built.command(),
                StartCliEnvironment.windows(parentEnvironment, target.javaHome(),
                    systemDirectory, extraJavaToolOptions), target.home(), arguments);
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
        checkAvailable(select(server));
    }

    /** As above, for the client chosen by the caller. */
    public void checkAvailable(Selection selection) {
        launchTarget(selection);
    }

    /**
     * Where and with which JDK StartCLI runs for one server; {@code systemDirectory} only on
     * Windows.
     */
    private record LaunchTarget(Path home, Path script, String javaHome,
        Optional<String> systemDirectory) {
    }

    private LaunchTarget launchTarget(Selection selection) {
        EffectiveNodeConfig server = selection.server();
        Path home = server.cli().home().orElseThrow(() -> noHome(selection));
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
        String javaHome = javaHome(server)
            .orElseThrow(() -> unavailable(server, "No JDK for StartCLI on " + server.id(),
                "Neither cli.javaHome (cliJavaHome) nor JAVA_HOME is set",
                "Set cli.javaHome to the JDK the INUBIT client needs (8.1: JDK 17, 9.0:"
                    + " JDK 21)"));
        // type patterns, not record patterns: javac 21 emits an invalid stack map for the record
        // patterns of this method (VerifyError at class load on JDK 21.0.13)
        if (containment.state() instanceof ProcessContainment.Unavailable refused) {
            throw unavailable(server, "StartCLI cannot be used for " + server.id()
                    + ": StartCLI processes could not be bound to the server ("
                    + refused.reason() + ")",
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
        return switch (system.systemDirectory()) {
            case CliSystem.Read read ->
                new LaunchTarget(home, script, javaHome, Optional.of(read.path()));
            case CliSystem.Unreadable unreadable -> throw unavailable(server,
                "StartCLI cannot be used for " + server.id()
                    + ": the Windows system directory could not be read ("
                    + unreadable.reason() + ")",
                "The command interpreter cannot be located",
                "Restart the MCP server; the REST tools work meanwhile");
        };
    }

    /** No client from any source (FR-005, research D-8). */
    private static ToolErrorException noHome(Selection selection) {
        EffectiveNodeConfig server = selection.server();
        Optional<MajorMinor> entry = selection.entryHint();
        if (entry.isEmpty()) {
            return unavailable(server, "No CLI home is configured for " + server.id(),
                "cliHome / cli.home is not set", "Set cliHome to the INUBIT client directory");
        }
        String line = entry.get().text();
        return unavailable(server, "No CLI home is configured for " + server.id()
                + " (INUBIT " + selection.version().orElseThrow().raw() + ")",
            "Neither cli.home, defaults.cliClients.\"" + line + "\" nor cliHome is set",
            "Add defaults.cliClients.\"" + line + "\".home (the INUBIT " + line
                + " client directory) or set cliHome");
    }

    /**
     * The Java runtime StartCLI runs with: {@code cli.javaHome}, else the server's own
     * {@code JAVA_HOME} (on Windows in any spelling).
     */
    Optional<String> javaHome(EffectiveNodeConfig server) {
        return server.cli().javaHome().map(Path::toString)
            .or(() -> Optional.ofNullable(windows
                    ? StartCliEnvironment.valueIgnoringCase(parentEnvironment, "JAVA_HOME")
                    : parentEnvironment.get("JAVA_HOME"))
                .filter(value -> !value.isBlank()));
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
        Optional<Secret> stdinSecret, List<CliSessionCommand> stdinCommands, Duration timeout,
        String timeoutSetting, int maxOutputBytes) {
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
            CliResult result = waitFor(server, process, stdinSecret, stdinCommands, timeout,
                timeoutSetting, start, maxOutputBytes);
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
        Optional<Secret> stdinSecret, List<CliSessionCommand> stdinCommands, Duration timeout,
        String timeoutSetting, long start, int maxOutputBytes) {
        BoundedSink stdout = BoundedSink.drain(process.stdout(), maxOutputBytes);
        BoundedSink stderr = BoundedSink.drain(process.stderr(), maxOutputBytes);
        writeStdin(process, stdinSecret, stdinCommands);
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
            String message = "StartCLI did not finish within " + Durations.human(timeout)
                + " on " + server.id();
            if (windows) {
                stdout.await(KILL_GRACE);
                if (CliOutputClassifier.endsAtThePasswordPrompt(stdout.text(decoder()))) {
                    throw timeoutAtThePasswordPrompt(server, message, timeoutSetting);
                }
            }
            throw timeout(server, message, timeoutSetting);
        }
        stdout.await(KILL_GRACE);
        stderr.await(KILL_GRACE);
        return new CliResult(process.exitValue(), stdout.text(decoder()), stderr.text(decoder()),
            Duration.ofNanos(System.nanoTime() - start), stdout.truncated() || stderr.truncated(),
            maxOutputBytes);
    }

    /**
     * Writes the password line, then one line per session command, and closes stdin. The whole
     * input is written up front, without waiting for StartCLI's {@code Password:} or command
     * prompt; the 6.1 prompt behaviour is reconstructed from documentation (research D-5), so
     * this is the one place to adjust once recordings show otherwise.
     */
    private static void writeStdin(LaunchedProcess process, Optional<Secret> secret,
        List<CliSessionCommand> commands) {
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
            for (CliSessionCommand command : commands) {
                stdin.write((command.text() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            stdin.flush();
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

    /**
     * Windows (feature 013, FR-005, research R-10): the last output was the password prompt. The
     * server cannot tell a client waiting for the password at its console from a slow server.
     */
    private static ToolErrorException timeoutAtThePasswordPrompt(EffectiveNodeConfig server,
        String message, String timeoutSetting) {
        return new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, message,
            "StartCLI's last output was its password prompt: it may be waiting for the password"
                + " on its console instead of reading it from standard input, or the INUBIT"
                + " server is slow",
            "Check get_health; raise " + timeoutSetting + " if the INUBIT server is just slow;"
                + " if the call keeps stopping at the prompt, run the StartCLI live check on"
                + " Windows in docs/live-tests.md")
            .withNode(server.id()));
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
