package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81ImportArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.git.GitCli;
import de.dadecker.inubit.mcp.adapter.xslt.SaxonXsltRunner;
import de.dadecker.inubit.mcp.application.ArtifactCheckService;
import de.dadecker.inubit.mcp.application.BackupStore;
import de.dadecker.inubit.mcp.application.ConfirmationRegistry;
import de.dadecker.inubit.mcp.application.DevelopmentGuard;
import de.dadecker.inubit.mcp.application.DiagnosisService;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.HealthService;
import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.application.InventoryCache;
import de.dadecker.inubit.mcp.application.InventoryService;
import de.dadecker.inubit.mcp.application.OwnerKindResolver;
import de.dadecker.inubit.mcp.application.ProcessControlService;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.WorkspaceService;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpServerFactory;
import de.dadecker.inubit.mcp.mcp.ResultMapper;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.tools.CheckArtifactsTool;
import de.dadecker.inubit.mcp.mcp.tools.ExportArtifactsTool;
import de.dadecker.inubit.mcp.mcp.tools.FindProcessesTool;
import de.dadecker.inubit.mcp.mcp.tools.GetHealthTool;
import de.dadecker.inubit.mcp.mcp.tools.GetInventoryItemTool;
import de.dadecker.inubit.mcp.mcp.tools.ImportArtifactsTool;
import de.dadecker.inubit.mcp.mcp.tools.KillProcessTool;
import de.dadecker.inubit.mcp.mcp.tools.ListInventoryTool;
import de.dadecker.inubit.mcp.mcp.tools.ListNodesTool;
import de.dadecker.inubit.mcp.mcp.tools.QueryLogsTool;
import de.dadecker.inubit.mcp.mcp.tools.RestartProcessTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Composition root: builds the shared components from a validated configuration and the tool
 * handlers on top of them. This is the only place that knows the concrete adapters.
 *
 * <p><b>Extension point:</b> each user-story phase adds its handlers in {@link #toolHandlers()}
 * (US1: {@code list_nodes}, {@code get_health}; US2: {@code find_processes},
 * {@code query_logs}; US3: {@code list_inventory}, {@code get_inventory_item}; feature 003:
 * {@code export_artifacts}, only if a node has a CLI installation, and {@code check_artifacts}).
 * The write tools
 * of US4 ({@code restart_process}, {@code kill_process}) are added only if
 * {@link #anyWriteEnabled()} (contracts/mcp-tools.md, Story 4 / AS 6); their service, the write
 * guard and the audit log ({@code auditDirectory}, written only on a write call) are built in any
 * case. Feature 004: {@code import_artifacts} only if {@link #anyDevelopmentNode()}; its service
 * shares the audit log and the check service, and keeps its backups in
 * {@code ~/.inubit-mcp/<profile>/backups} (created on the first import).
 *
 * <p>The JVM shutdown hook that stops StartCLI work is registered as the last step of the
 * constructor, so that a failing constructor leaves no hook behind (US3 re-review N2).
 */
final class Wiring implements AutoCloseable {

    private final ProfileInfo profile;
    private final SecretScrubber scrubber;
    private final List<EffectiveNodeConfig> servers;
    private final TargetResolver targets;
    private final FanOut fanOut;
    private final ResultLimiter limiter;
    private final ClockProvider clock;
    private final AdapterGatewayFactory gateways;
    private final List<NodeSummary> summaries;
    private final HealthService health;
    private final DiagnosisService diagnosis;
    private final InventoryService inventory;
    private final ProcessControlService processControl;
    private final ExportArtifactsTool exportArtifacts;
    private final CheckArtifactsTool checkArtifacts;
    private final ImportArtifactsTool importArtifacts;
    private final CliResources cliResources;
    private final Thread cleanupHook;

    /**
     * @param config  a configuration that {@code ConfigValidator} reported no errors for
     * @param exists  file-system check for the CLI availability of {@code list_nodes}
     * @param windows whether the server runs on Windows ({@code startcli.bat})
     */
    Wiring(ProfileConfig config, CredentialResolution credentials, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows) {
        this(config, credentials, scrubber, clock, exists, windows, new SystemProcessLauncher(),
            System.getenv());
    }

    /**
     * As above, with the launcher of StartCLI and the environment its allowlist is copied from
     * (tests replace StartCLI with a fake).
     */
    Wiring(ProfileConfig config, CredentialResolution credentials, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows, ProcessLauncher launcher,
        Map<String, String> environment) {
        this.profile = config.profileInfo();
        this.scrubber = scrubber;
        // 002 research D-8: the export directories carry the profile name
        this.cliResources = new CliResources(profile.name());
        this.cleanupHook = new Thread(cliResources::close, "inubit-mcp-cli-cleanup");
        Terminology terms = profile.terminology();
        this.servers = config.effectiveNodes();
        this.targets = new TargetResolver(servers.stream().map(EffectiveNodeConfig::id).toList(),
            terms);
        this.fanOut = new FanOut();
        this.limiter = new ResultLimiter(config.resultLimits().maxItems(),
            config.resultLimits().maxChars());
        this.clock = clock;
        this.gateways = new AdapterGatewayFactory(servers, credentials, scrubber, clock.clock(),
            new CliRunner(launcher, environment, windows, cliResources));
        this.summaries = servers.stream().map(server -> server.summary(exists, windows)).toList();
        Map<NodeId, Duration> timeouts = new HashMap<>();
        servers.forEach(server -> timeouts.put(server.id(), server.timeout()));
        this.health = new HealthService(gateways, targets, fanOut, timeouts::get, clock.clock());
        Map<NodeId, Duration> hangingThresholds = new HashMap<>();
        servers.forEach(server -> hangingThresholds.put(server.id(), server.hangingThreshold()));
        this.diagnosis = new DiagnosisService(gateways, targets, fanOut, limiter, timeouts::get,
            hangingThresholds::get, clock.clock(), ResultJson::size);
        Map<NodeId, EffectiveNodeConfig> byId = new HashMap<>();
        servers.forEach(server -> byId.put(server.id(), server));
        this.inventory = new InventoryService(gateways, targets, fanOut, limiter,
            new InventoryCache(clock.clock(), id -> byId.get(id).inventory().cacheTtl()),
            id -> byId.get(id).inventory().owner(), timeouts::get,
            id -> byId.get(id).cliExportTimeout(),
            CliRunner.KILL_GRACE.multipliedBy(2), ResultJson::size, terms);
        // a plain HashMap is fine: it is filled here once, never modified afterwards, and safely
        // published to the request threads through the final fields of this object and the
        // services; lookups of unknown ids return null, which the guard treats as no write access
        Map<NodeId, WritePolicy> policies = new HashMap<>();
        servers.forEach(server -> policies.put(server.id(), policy(server, credentials)));
        AuditLog audit = new AuditLog(config.auditDirectory(), scrubber);
        this.processControl = new ProcessControlService(
            new WriteGuard(targets, policies::get, gateways), policies::get, gateways,
            new ConfirmationRegistry(clock.clock()), audit, clock.clock(), UUID::randomUUID,
            profile.name());
        // feature 003: the workspace history uses the system git (not the StartCLI launcher)
        Path workspace = config.workspace();
        this.exportArtifacts = new ExportArtifactsTool(new WorkspaceService(workspace,
            new GitCli(workspace, profile.name(), new SystemProcessLauncher(), environment),
            new ArchiveCodec(), gateways::artifacts, gateways::inventory), targets,
            id -> byId.get(id).inventory().owner(), limiter);
        ArtifactCheckService checks = new ArtifactCheckService(workspace,
            new WorkspaceInspector(), new SaxonXsltRunner(workspace), group -> servers.stream()
                .map(EffectiveNodeConfig::id).filter(id -> id.group().equals(group)).findFirst(),
            gateways::inventory, id -> byId.get(id).inventory().owner(), limiter,
            clock.clock());
        this.checkArtifacts = new CheckArtifactsTool(checks);
        // feature 004: the development tools (offered only with a development node)
        Map<NodeId, DevelopmentPolicy> development = new HashMap<>();
        servers.forEach(server -> development.put(server.id(), server.developmentPolicy()));
        this.importArtifacts = new ImportArtifactsTool(new ImportService(
            new ImportService.Dependencies(workspace, profile.name(),
                new DevelopmentGuard(targets, development::get,
                    node -> gateways.imports(node).checkAvailable()),
                development::get,
                new GitCli(workspace, profile.name(), new SystemProcessLauncher(), environment),
                new WorkspaceInspector(), checks, new ArchiveCodec(), new V81ImportArchives(),
                gateways::artifacts, gateways::imports, gateways::inventory,
                new OwnerKindResolver(config.owners(), gateways::users),
                id -> byId.get(id).inventory().owner(),
                id -> new ImportService.Account(policies.get(id).account().orElse("unknown"),
                    byId.get(id).baseUrl().getHost()),
                new WriteChallengeRegistry(clock.clock()),
                new BackupStore(BackupStore.defaultRoot(Path.of(System.getProperty(
                    "user.home")), profile.name()), clock.clock()),
                audit, clock.clock(), UUID::randomUUID)));
        // last step (N2): SIGTERM (and System.exit) stop running StartCLI work and delete the
        // export directories
        Runtime.getRuntime().addShutdownHook(cleanupHook);
    }

    /** The write settings of {@code server} with the INUBIT account for the audit. */
    private static WritePolicy policy(EffectiveNodeConfig server,
        CredentialResolution credentials) {
        Optional<String> account = credentials.all().stream()
            .filter(resolved -> resolved.node().equals(server.id()))
            .findFirst()
            .flatMap(resolved -> resolved.username().map(SourcedValue::value));
        return new WritePolicy(server.id(), server.production(), server.write().enabled(),
            server.write().productionOptIn(),
            server.write().confirmation() == ConfirmationMode.CLIENT
                ? WritePolicy.Confirmation.CLIENT : WritePolicy.Confirmation.SERVER,
            server.confirmationTtl(), account);
    }

    /** The tools to register. */
    List<ToolHandler> toolHandlers() {
        List<ToolHandler> handlers = new ArrayList<>();
        handlers.add(new ListNodesTool(profile, summaries));
        handlers.add(new GetHealthTool(health));
        handlers.add(new FindProcessesTool(diagnosis));
        handlers.add(new QueryLogsTool(diagnosis));
        handlers.add(new ListInventoryTool(inventory));
        handlers.add(new GetInventoryItemTool(inventory));
        if (servers.stream().anyMatch(server -> server.cli().home().isPresent())) {
            handlers.add(exportArtifacts);
        }
        handlers.add(checkArtifacts);
        if (anyWriteEnabled()) {
            handlers.add(new RestartProcessTool(processControl));
            handlers.add(new KillProcessTool(processControl));
        }
        if (anyDevelopmentNode()) {
            handlers.add(importArtifacts);
        }
        return List.copyOf(handlers);
    }

    /** The profile this process serves. */
    ProfileInfo profile() {
        return profile;
    }

    /**
     * The MCP server for {@code handlers} (normally {@link #toolHandlers()}): the profile's
     * server info, instructions and rendered tool descriptions, the classpath schemas and the
     * shared scrubber.
     */
    McpServerFactory serverFactory(String version, List<ToolHandler> handlers) {
        return new McpServerFactory(version, profile, handlers, SchemaResources.forClasspath(),
            new ResultMapper(scrubber));
    }

    GatewayFactory gateways() {
        return gateways;
    }

    TargetResolver targets() {
        return targets;
    }

    FanOut fanOut() {
        return fanOut;
    }

    ResultLimiter limiter() {
        return limiter;
    }

    ClockProvider clock() {
        return clock;
    }

    /** True if at least one node is a development stage (feature 004, FR-001). */
    boolean anyDevelopmentNode() {
        return servers.stream().anyMatch(server -> server.development().enabled());
    }

    /** True if at least one server has effective write access (Story 4 / AS 6). */
    boolean anyWriteEnabled() {
        return servers.stream().anyMatch(EffectiveNodeConfig::effectiveWriteEnabled);
    }

    /**
     * Stops the running StartCLI processes and deletes the export directories (review I1); called
     * as soon as stdin is closed, before the MCP session is shut down. Later CLI calls are refused.
     */
    void stopCliWork() {
        cliResources.close();
    }

    @Override
    public void close() {
        cliResources.close();
        gateways.close();
        try {
            Runtime.getRuntime().removeShutdownHook(cleanupHook);
        } catch (IllegalStateException e) {
            // the JVM is already shutting down; the hook runs (or ran) anyway
        }
    }
}
