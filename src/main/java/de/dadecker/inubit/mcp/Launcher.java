package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.adapter.aws.SsmParameterStore;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliVersionProbe;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigException;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigSummary;
import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.config.ParameterCheck;
import de.dadecker.inubit.mcp.config.ParameterStore;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.SecretPlan;
import de.dadecker.inubit.mcp.config.ValidationReport;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.BuildInfo;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.LogLevels;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpServerFactory;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The command line behind {@link Main} (contracts/configuration.md → Command-line arguments).
 *
 * <p>Order: arguments, config file, credentials from the environment, validation (all errors on
 * stderr, exit 1), log level, audit directory (owner-only), wiring, MCP server. The server runs
 * until stdin is closed. {@link Main} has already set the locale and installed the stdout guard
 * before this class (and with it Logback) is initialized.
 *
 * <p>Exit codes: 0 success, 1 configuration or startup error, 2 usage error.
 */
final class Launcher {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;

    static final String USAGE = """
        Usage: java -jar inubit-mcp-server.jar [--config <path> | --profile <name>]
                                               [--check-config | --version]
          --config <path>   configuration file
          --profile <name>  the profile's file <name>.yaml in ~/.config/inubit-mcp
                            (Windows: %APPDATA%\\inubit-mcp); its profile.name must be <name>
          --check-config    validate the configuration, print a summary (variable and
                            parameter names only, no values) and exit 0 (valid) or 1 (errors)
          --version         print the server version and exit
        Without --config/--profile: $INUBIT_MCP_CONFIG (a path), then $INUBIT_MCP_PROFILE (a
        profile name), then ~/.config/inubit-mcp/config.yaml.
        Without --check-config/--version the MCP server runs on stdio until stdin is closed.""";

    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);
    private static final Set<PosixFilePermission> OWNER_ONLY =
        PosixFilePermissions.fromString("rwx------");

    /**
     * Everything the process touches, injectable for tests.
     *
     * @param out the protocol stdout (also used for {@code --version}/{@code --check-config})
     * @param parameterStore creates the AWS Parameter Store client of the server (feature 010);
     *     called only if a node reads its secrets from it
     */
    record Context(Map<String, String> environment, Path userHome, boolean windows,
        InputStream in, PrintStream out, PrintStream err, SecretScrubber scrubber,
        Predicate<Path> exists, Supplier<ParameterStore> parameterStore) {

        Context {
            environment = Map.copyOf(environment);
            Objects.requireNonNull(userHome, "userHome");
            Objects.requireNonNull(in, "in");
            Objects.requireNonNull(out, "out");
            Objects.requireNonNull(err, "err");
            Objects.requireNonNull(scrubber, "scrubber");
            Objects.requireNonNull(exists, "exists");
            Objects.requireNonNull(parameterStore, "parameterStore");
        }

        static Context system(PrintStream protocolOut) {
            return new Context(System.getenv(), Path.of(System.getProperty("user.home")),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                System.in, protocolOut, System.err, SecretScrubber.global(), Files::exists,
                SsmParameterStore::new);
        }
    }

    /** Parsed command line. */
    private record Arguments(Optional<String> config, Optional<String> profile,
        boolean checkConfig, boolean version) {
    }

    /** A usage error; the message names the offending argument. */
    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message, null, false, false);
        }
    }

    private final Context context;
    private final Function<Wiring, List<ToolHandler>> toolHandlers;

    Launcher(Context context) {
        this(context, Wiring::toolHandlers);
    }

    /** @param toolHandlers selects the handlers to register (tests inject failing ones) */
    Launcher(Context context, Function<Wiring, List<ToolHandler>> toolHandlers) {
        this.context = Objects.requireNonNull(context, "context");
        this.toolHandlers = Objects.requireNonNull(toolHandlers, "toolHandlers");
    }

    /** Runs the command line; in server mode this blocks until stdin is closed. */
    int run(String... args) {
        Arguments arguments;
        try {
            arguments = parse(args);
        } catch (UsageException e) {
            context.err().println(e.getMessage());
            context.err().println(USAGE);
            return EXIT_USAGE;
        }
        if (arguments.version()) {
            context.out().println(McpServerFactory.SERVER_NAME + " " + BuildInfo.version());
            return EXIT_OK;
        }
        ConfigLoader loader =
            new ConfigLoader(context.environment(), context.userHome(), context.windows());
        LoadedConfig loaded;
        try {
            loaded = loader.load(arguments.config().orElse(null),
                arguments.profile().orElse(null));
        } catch (ConfigException e) {
            context.err().println(e.getMessage());
            return EXIT_ERROR;
        }
        // 002 T031 and US2 review P1/P2: the other profile files of the default directory, read
        // once; their variables are no "unmatched" variables of this profile
        List<LoadedConfig> otherProfiles = loader.otherProfiles(loaded.source());
        Set<String> otherVariables = new HashSet<>();
        otherProfiles.forEach(other -> otherVariables.addAll(
            other.config().credentialVariableNames()));
        CredentialResolution credentials = new CredentialResolver(context.environment(),
            context.scrubber(), loaded.config().terminology().effectiveOrDefault(),
            loaded.config().credentialPrefix(), loaded.config().secretPlans())
            .resolve(loaded.config().nodeIds(), otherVariables);
        ValidationReport report = new ConfigValidator(context.exists(), context.environment(),
            context.windows(), Path.of(System.getProperty("java.io.tmpdir")),
            source -> otherProfiles).validate(loaded, credentials);
        if (arguments.checkConfig()) {
            Map<NodeId, ClientVersion> clients = new HashMap<>();
            Map<NodeId, CliClientOptions> options = new HashMap<>();
            ValidationReport withProbes =
                probeClients(loaded.config(), report, clients, options);
            // feature 010 (research R-12): the Parameter Store client only for AWS nodes
            Collection<SecretPlan> plans = loaded.config().secretPlans().values();
            ParameterCheck.Result parameters = plans.stream().anyMatch(SecretPlan::aws)
                ? ParameterCheck.run(plans, context.parameterStore().get())
                : ParameterCheck.Result.NONE;
            ValidationReport checked = parameters.addTo(withProbes);
            context.out().print(new ConfigSummary(context.exists(), context.windows())
                .render(loaded, credentials, checked, clients, options, parameters));
            context.out().flush();
            return checked.hasErrors() ? EXIT_ERROR : EXIT_OK;
        }
        if (report.hasErrors()) {
            printFindings("Configuration errors in " + loaded.source() + " (the MCP server does not"
                + " start):", report.errors());
            printFindings("Warnings:", report.warnings());
            context.err().println("Run with --check-config for a summary.");
            return EXIT_ERROR;
        }
        printFindings("Warnings:", report.warnings());
        try {
            return serve(loaded.config(), credentials, report.warnings());
        } catch (RuntimeException e) {
            context.err().println(oneLine("Startup failed: " + e.getClass().getSimpleName()
                + ": " + e.getMessage()));
            LOG.error("Startup failed", e);
            return EXIT_ERROR;
        }
    }

    /**
     * Feature 009 (FR-011a, research D-13): runs {@code startcli -v} for every node with a
     * StartCLI installation, bounded by the node's {@code cliTimeout}, and puts the successes into
     * {@code clients}. No credentials, no server URL, no INUBIT contact. A failed probe is a
     * warning. Feature 011 (FR-012a, research D-8): a node pinned to a CLI-only version (6.1)
     * also gets {@code startcli -h} and its runtime's {@code release} read ({@link
     * CliClientProbe}); its options go into {@code options}, and what its client cannot do
     * becomes the warnings of {@link ConfigValidator#clientOptionWarnings}.
     *
     * @return {@code report} with a warning per failed probe and per client option warning
     */
    private ValidationReport probeClients(ProfileConfig config, ValidationReport report,
        Map<NodeId, ClientVersion> clients, Map<NodeId, CliClientOptions> options) {
        List<EffectiveNodeConfig> withClient = config.resolvableNodes().stream()
            .filter(server -> server.cliAvailable(context.exists(), context.windows())).toList();
        if (withClient.isEmpty()) {
            return report;
        }
        Map<NodeId, EffectiveNodeConfig> byId = new HashMap<>();
        withClient.forEach(server -> byId.put(server.id(), server));
        List<String> warnings = new ArrayList<>(report.warnings());
        // the profile name may be invalid here (an error of the report); it only names export
        // directories, which the probe never creates
        try (CliResources resources = new CliResources("check-config")) {
            CliRunner runner = new CliRunner(new SystemProcessLauncher(), context.environment(),
                context.windows(), resources);
            CliVersionProbe probe = new CliVersionProbe(runner, byId::get);
            CliClientProbe optionProbe = new CliClientProbe(runner, byId::get,
                CapabilityMatrix.production(), Clock.systemUTC());
            for (EffectiveNodeConfig server : withClient) {
                try {
                    clients.put(server.id(), probe.probe(server.id(), server.cliTimeout()));
                } catch (ToolErrorException e) {
                    warnings.add(server.id() + ": StartCLI client probe failed: "
                        + e.error().message());
                }
                optionProbe.optionsFor(server.id(), server.cliTimeout()).ifPresent(read -> {
                    options.put(server.id(), read);
                    warnings.addAll(ConfigValidator.clientOptionWarnings(
                        config.terminology().effectiveOrDefault(), server, read,
                        server.tls().trustStore().map(CliRunner::trustStoreType).orElse("")));
                });
            }
        }
        return new ValidationReport(report.errors(), warnings, report.workspace());
    }

    private int serve(ProfileConfig config, CredentialResolution credentials,
        List<String> warnings) {
        LogLevels.apply(config.logLevel().name());
        warnings.forEach(warning -> LOG.warn("Configuration: {}", warning));
        try {
            createAuditDirectory(config.auditDirectory());
        } catch (IOException | RuntimeException e) {
            context.err().println("Cannot create the audit directory " + config.auditDirectory()
                + " (" + e.getClass().getSimpleName() + "); the MCP server does not start");
            return EXIT_ERROR;
        }
        if (!context.windows()) {
            // 002 research D-8: only this profile's and legacy (001) directories
            int swept = CliResources.sweepStale(Path.of(System.getProperty("java.io.tmpdir")),
                config.profile().name());
            if (swept > 0) {
                LOG.info("Deleted {} export director{} left behind by stopped MCP servers",
                    swept, swept == 1 ? "y" : "ies");
            }
        }
        try (Wiring wiring = new Wiring(config, credentials, context.scrubber(),
            ClockProvider.system(), context.exists(), context.windows(),
            new SystemProcessLauncher(), System.getenv(), context.parameterStore())) {
            McpServerFactory factory = wiring.serverFactory(BuildInfo.version(),
                toolHandlers.apply(wiring));
            try (McpServerFactory.RunningServer server = factory.start(context.in(),
                context.out())) {
                LOG.info("INUBIT MCP server {} started for profile {} with {} node(s)",
                    BuildInfo.version(), config.profile().name(), config.nodeIds().size());
                server.awaitInputClosed();
                LOG.info("stdin closed; shutting down");
                wiring.stopCliWork();
            }
            return EXIT_OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_ERROR;
        }
    }

    /**
     * Creates the directory; on POSIX, every directory created here — the audit directory and its
     * missing parents, e.g. {@code ~/.inubit-mcp} and {@code ~/.inubit-mcp/<profile>} (002 US2
     * review P3c) — is owner-only, and an existing audit directory is tightened. Existing parents
     * are not modified.
     */
    private static void createAuditDirectory(Path directory) throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(directory);
            return;
        }
        Path absolute = directory.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        for (Path path = absolute; path != null && !Files.exists(path); path = path.getParent()) {
            missing.addFirst(path);
        }
        for (Path path : missing) {
            try {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (FileAlreadyExistsException e) {
                continue; // created concurrently: not ours, not modified
            }
            Files.setPosixFilePermissions(path, OWNER_ONLY); // independent of the umask
        }
        Files.setPosixFilePermissions(absolute, OWNER_ONLY);
    }

    /** Scrubbed and on a single line. */
    private String oneLine(String text) {
        return context.scrubber().scrub(text).replaceAll("\\s*\\R\\s*", " ").strip();
    }

    private void printFindings(String title, List<String> findings) {
        if (!findings.isEmpty()) {
            context.err().println(title);
            findings.forEach(finding -> context.err().println("  - " + finding));
        }
    }

    private static Arguments parse(String[] args) throws UsageException {
        Optional<String> config = Optional.empty();
        Optional<String> profile = Optional.empty();
        boolean checkConfig = false;
        boolean version = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        throw new UsageException("--config needs a file path");
                    }
                    if (config.isPresent()) {
                        throw new UsageException("--config is given twice");
                    }
                    config = Optional.of(args[++i]);
                }
                case "--profile" -> {
                    // the value is not echoed: it could be a password typed by mistake
                    if (i + 1 >= args.length || !ProfileInfo.isValidName(args[i + 1])) {
                        throw new UsageException("--profile needs a profile name matching "
                            + ProfileInfo.NAME_RULE);
                    }
                    if (profile.isPresent()) {
                        throw new UsageException("--profile is given twice");
                    }
                    profile = Optional.of(args[++i]);
                }
                case "--check-config" -> checkConfig = true;
                case "--version" -> version = true;
                // a stray value or an option's '=value' part is not echoed: it could be a
                // password typed by mistake
                default -> throw new UsageException(args[i].startsWith("-")
                    ? "Unknown argument: " + args[i].split("=", 2)[0]
                    : "Unexpected value at argument position " + (i + 1));
            }
        }
        if (checkConfig && version) {
            throw new UsageException("--check-config and --version cannot be combined");
        }
        if (config.isPresent() && profile.isPresent()) {
            // 002 research D-9: one of the two selects the file
            throw new UsageException("--config and --profile cannot be combined");
        }
        return new Arguments(config, profile, checkConfig, version);
    }
}
