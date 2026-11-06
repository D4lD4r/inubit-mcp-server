package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.adapter.aws.SsmParameterStore;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.CliVersionProbe;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.os.CredentialStores;
import de.dadecker.inubit.mcp.adapter.os.NoJob;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.adapter.os.PosixSystem;
import de.dadecker.inubit.mcp.adapter.os.WindowsJob;
import de.dadecker.inubit.mcp.adapter.os.WindowsSystem;
import de.dadecker.inubit.mcp.config.ConfigException;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ConfigLocation;
import de.dadecker.inubit.mcp.config.ConfigSummary;
import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.EntryCheck;
import de.dadecker.inubit.mcp.config.GroupConfig;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.config.ParameterCheck;
import de.dadecker.inubit.mcp.config.ParameterStore;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.SecretPlan;
import de.dadecker.inubit.mcp.config.StartCliHost;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.config.ValidationReport;
import de.dadecker.inubit.mcp.config.WorkspaceDirectory;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.BuildInfo;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.LogLevels;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpServerFactory;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The command line behind {@link Main} (contracts/configuration.md → Command-line arguments).
 *
 * <p>Order: arguments, config file, validation (all errors on stderr, exit 1), containment of
 * StartCLI's processes (feature 013: the kill-on-close job on Windows), log level, audit
 * directory (owner-only), wiring, MCP server. The server runs until stdin is closed.
 * {@link Main} has already set the locale and installed the stdout guard before this class (and
 * with it Logback) is initialized.
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
          --check-config    validate the configuration, read the credentials, print a summary
                            (credential store entry and parameter names only, no values) and
                            exit 0 (valid) or 1 (errors)
          --version         print the server version and exit
        Without --config/--profile: $INUBIT_MCP_CONFIG (a path), then $INUBIT_MCP_PROFILE (a
        profile name), then ~/.config/inubit-mcp/config.yaml.
        Without --check-config/--version the MCP server runs on stdio until stdin is closed.""";

    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);
    /**
     * Everything the process touches, injectable for tests.
     *
     * @param out the protocol stdout (also used for {@code --version}/{@code --check-config})
     * @param parameterStore creates the AWS Parameter Store client of the server (feature 010);
     *     called only if a node reads its secrets from it
     * @param credentialStore creates the credential store of the running system (feature 011);
     *     building it reads nothing
     * @param containment sets up the containment of StartCLI's processes (feature 013, research
     *     R-7): on Windows it binds this process to a kill-on-close job; called once, before the
     *     process starts StartCLI: when the server starts, or for {@code --check-config} before
     *     the client probe (tests pass a fixed state)
     * @param privateFiles creates the private files and directories of the server and checks
     *     existing ones (feature 013, FR-007)
     */
    record Context(Map<String, String> environment, Path userHome, boolean windows,
        InputStream in, PrintStream out, PrintStream err, SecretScrubber scrubber,
        Predicate<Path> exists, Supplier<ParameterStore> parameterStore,
        Supplier<CredentialStore> credentialStore, Supplier<ProcessContainment> containment,
        PrivateFileSystem privateFiles) {

        Context {
            environment = Map.copyOf(environment);
            Objects.requireNonNull(userHome, "userHome");
            Objects.requireNonNull(in, "in");
            Objects.requireNonNull(out, "out");
            Objects.requireNonNull(err, "err");
            Objects.requireNonNull(scrubber, "scrubber");
            Objects.requireNonNull(exists, "exists");
            Objects.requireNonNull(parameterStore, "parameterStore");
            Objects.requireNonNull(credentialStore, "credentialStore");
            Objects.requireNonNull(containment, "containment");
            Objects.requireNonNull(privateFiles, "privateFiles");
        }

        /** With the private files of the host. */
        Context(Map<String, String> environment, Path userHome, boolean windows,
            InputStream in, PrintStream out, PrintStream err, SecretScrubber scrubber,
            Predicate<Path> exists, Supplier<ParameterStore> parameterStore,
            Supplier<CredentialStore> credentialStore,
            Supplier<ProcessContainment> containment) {
            this(environment, userHome, windows, in, out, err, scrubber, exists, parameterStore,
                credentialStore, containment, new OsPrivateFileSystem(windows));
        }

        static Context system(PrintStream protocolOut) {
            Path home = Path.of(System.getProperty("user.home"));
            boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            return new Context(System.getenv(), home, windows, System.in, protocolOut,
                System.err, SecretScrubber.global(), Files::exists, SsmParameterStore::new,
                () -> CredentialStores.forOs(System.getProperty("os.name"),
                    new SystemProcessLauncher(), System.getenv(), home),
                windows ? WindowsJob::bindCurrentProcess : NoJob::new,
                new OsPrivateFileSystem(windows));
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
        // 002 T031: the other profile files of the default directory, read once
        List<LoadedConfig> otherProfiles = loader.otherProfiles(loaded.source());
        // feature 011 (research R-10): no credential is resolved at startup; every node reads its
        // credentials on first use
        if (arguments.checkConfig()) {
            // feature 013 (T015, research R-7): the containment first, so that the check reports
            // it and the client probe's processes end with this process
            ProcessContainment containment = context.containment().get();
            CliSystem system = context.windows() ? new WindowsSystem() : new PosixSystem();
            ValidationReport report = validator(otherProfiles,
                context.windows() ? startCliHost(system, containment) : StartCliHost.unchecked())
                .validate(loaded);
            Map<NodeId, ClientVersion> clients = new HashMap<>();
            ValidationReport withProbes = probeClients(loaded.config(),
                withPrivateObjects(loaded.config(), report), clients, system, containment);
            // feature 010 (research R-12): the Parameter Store client only for AWS nodes
            Collection<SecretPlan> plans = loaded.config().secretPlans().values();
            ParameterCheck.Result parameters = plans.stream().anyMatch(SecretPlan::aws)
                ? ParameterCheck.run(plans, context.parameterStore().get())
                : ParameterCheck.Result.NONE;
            // feature 011 (research R-13): the credential store only for credential store nodes
            List<EffectiveNodeConfig> nodes = loaded.config().resolvableNodes();
            EntryCheck.Result entries = plans.stream().anyMatch(plan -> !plan.aws())
                ? EntryCheck.run(nodes, context.credentialStore().get(), context.windows())
                : EntryCheck.Result.none(StoreKind.forOs(System.getProperty("os.name")));
            ValidationReport checked = entries.addTo(parameters.addTo(withProbes));
            context.out().print(new ConfigSummary(context.exists(), context.windows())
                .render(loaded, checked, clients, parameters, entries));
            context.out().flush();
            return checked.hasErrors() ? EXIT_ERROR : EXIT_OK;
        }
        ValidationReport report = withPrivateObjects(loaded.config(),
            validator(otherProfiles, StartCliHost.unchecked()).validate(loaded));
        if (report.hasErrors()) {
            printFindings("Configuration errors in " + loaded.source() + " (the MCP server does not"
                + " start):", report.errors());
            printFindings("Warnings:", report.warnings());
            context.err().println("Run with --check-config for a summary.");
            return EXIT_ERROR;
        }
        printFindings("Warnings:", report.warnings());
        try {
            return serve(loaded.config(), new ConfigLocation(loader.defaultDirectory(),
                loaded.source()), report.warnings());
        } catch (RuntimeException e) {
            context.err().println(oneLine("Startup failed: " + e.getClass().getSimpleName()
                + ": " + e.getMessage()));
            LOG.error("Startup failed", e);
            return EXIT_ERROR;
        }
    }

    /**
     * {@code report} with a warning per existing private object of the server that grants access
     * to other principals (feature 013, FR-007, US3 AS4): the default audit directory and its
     * logs; nothing is changed.
     */
    private ValidationReport withPrivateObjects(ProfileConfig config, ValidationReport report) {
        List<String> found = new PrivateObjectCheck(context.privateFiles(),
            System.getProperty("user.name", "")).warnings(config.auditDirectory(),
            context.userHome(), config.profile().name());
        if (found.isEmpty()) {
            return report;
        }
        List<String> warnings = new ArrayList<>(report.warnings());
        warnings.addAll(found);
        return new ValidationReport(report.errors(), warnings, report.workspace());
    }

    private ConfigValidator validator(List<LoadedConfig> otherProfiles, StartCliHost host) {
        return new ConfigValidator(context.exists(), context.environment(), context.windows(),
            Path.of(System.getProperty("java.io.tmpdir")), source -> otherProfiles,
            WorkspaceDirectory::prepare, host);
    }

    /**
     * What the Windows host reports before StartCLI can start (feature 013, T015): the reasons
     * the system directory cannot be read and the processes cannot be bound.
     */
    private static StartCliHost startCliHost(CliSystem system, ProcessContainment containment) {
        Optional<String> systemDirectory = system.systemDirectory()
            instanceof CliSystem.Unreadable(String reason) ? Optional.of(reason)
            : Optional.empty();
        Optional<String> bound = containment.state()
            instanceof ProcessContainment.Unavailable(String reason) ? Optional.of(reason)
            : Optional.empty();
        return new StartCliHost(systemDirectory, bound);
    }

    /**
     * Feature 009 (FR-011a, research D-13): runs {@code startcli -v} for every node with a
     * StartCLI installation, bounded by the node's {@code cliTimeout}, and puts the successes into
     * {@code clients}. No credentials, no server URL, no INUBIT contact. A failed probe is a
     * warning. Feature 013 (research R-7): {@code containment} is set up before, so that on
     * Windows the probe's processes end with this process too; while it is unavailable no probe
     * starts.
     *
     * @return {@code report} with a warning per failed probe
     */
    private ValidationReport probeClients(ProfileConfig config, ValidationReport report,
        Map<NodeId, ClientVersion> clients, CliSystem system, ProcessContainment containment) {
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
        try (CliResources resources = new CliResources("check-config",
            context.privateFiles())) {
            CliVersionProbe probe = new CliVersionProbe(new CliRunner(new SystemProcessLauncher(),
                context.environment(), context.windows(), system, containment, resources),
                byId::get);
            for (EffectiveNodeConfig server : withClient) {
                try {
                    clients.put(server.id(), probe.probe(server.id(), server.cliTimeout()));
                } catch (ToolErrorException e) {
                    warnings.add(server.id() + ": StartCLI client probe failed: "
                        + e.error().message());
                }
            }
        }
        return new ValidationReport(report.errors(), warnings, report.workspace());
    }

    private int serve(ProfileConfig config, ConfigLocation location, List<String> warnings) {
        // feature 013 (research R-7): first, so that every process the server starts is bound
        ProcessContainment containment = context.containment().get();
        LogLevels.apply(config.logLevel().name());
        warnings.forEach(warning -> LOG.warn("Configuration: {}", warning));
        try {
            createAuditDirectory(config.auditDirectory());
        } catch (IOException | RuntimeException e) {
            context.err().println("Cannot create the audit directory " + config.auditDirectory()
                + " (" + e.getClass().getSimpleName() + "); the MCP server does not start");
            return EXIT_ERROR;
        }
        // 002 research D-8: only this profile's and legacy (001) directories; feature 013
        // (R-10): on Windows too, the owner compared with the user of the process token
        int swept = CliResources.sweepStale(Path.of(System.getProperty("java.io.tmpdir")),
            config.profile().name(), context.privateFiles());
        if (swept > 0) {
            LOG.info("Deleted {} export director{} left behind by stopped MCP servers",
                swept, swept == 1 ? "y" : "ies");
        }
        try (Wiring wiring = new Wiring(config, context.credentialStore().get(), context.scrubber(),
            ClockProvider.system(), context.exists(), context.windows(),
            new SystemProcessLauncher(), System.getenv(), context.parameterStore(),
            location, containment, context.privateFiles())) {
            McpServerFactory factory = wiring.serverFactory(BuildInfo.version(),
                toolHandlers.apply(wiring));
            try (McpServerFactory.RunningServer server = factory.start(context.in(),
                context.out())) {
                LOG.info("INUBIT MCP server {} started for profile {} with {} node(s)",
                    BuildInfo.version(), config.profile().name(), config.nodeIds().size());
                String marked = config.groups().stream().filter(group -> group.tls().marksStage())
                    .map(GroupConfig::name).collect(Collectors.joining(", "));
                if (!marked.isEmpty()) {
                    LOG.info("Stages with one pinned certificate per server (tls.pinPerServer): {}",
                        marked);
                }
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
     * Creates the directory through the private file system (feature 013, FR-007): every
     * directory created here — the audit directory and its missing parents, e.g.
     * {@code ~/.inubit-mcp} and {@code ~/.inubit-mcp/<profile>} (002 US2 review P3c) — is
     * private (POSIX {@code rwx------}; Windows the current user and {@code SYSTEM}), and an
     * existing audit directory is tightened on POSIX. Existing parents are not modified. A file
     * system without private objects gets the directories without restricted access, as before.
     */
    private void createAuditDirectory(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        PrivateFileSystem files = context.privateFiles();
        try {
            files.createDirectories(absolute);
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(absolute);
            return;
        }
        files.tightenOnPosix(absolute);
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
