package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.NodeSecrets;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81ImportArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81WorkflowArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.adapter.aws.SsmParameterStore;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliClientSelector;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.CliVersionProbe;
import de.dadecker.inubit.mcp.adapter.cli.HandshakeStartCliPinProbe;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61PortSet;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61QueryRules;
import de.dadecker.inubit.mcp.adapter.os.JnaDriveTypeApi;
import de.dadecker.inubit.mcp.adapter.os.NoJob;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.adapter.os.PosixSystem;
import de.dadecker.inubit.mcp.adapter.os.WindowsNetworkPaths;
import de.dadecker.inubit.mcp.adapter.os.WindowsSystem;
import de.dadecker.inubit.mcp.adapter.soap.SoapE2eClient;
import de.dadecker.inubit.mcp.adapter.git.GitCli;
import de.dadecker.inubit.mcp.adapter.xslt.SaxonXsltRunner;
import de.dadecker.inubit.mcp.application.ArchiveExportAudit;
import de.dadecker.inubit.mcp.application.ArchiveExportService;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter;
import de.dadecker.inubit.mcp.application.ArtifactCheckService;
import de.dadecker.inubit.mcp.application.BackupStore;
import de.dadecker.inubit.mcp.application.ConfirmationRegistry;
import de.dadecker.inubit.mcp.application.DeployGuard;
import de.dadecker.inubit.mcp.application.DeployService;
import de.dadecker.inubit.mcp.application.DeploymentLedger;
import de.dadecker.inubit.mcp.application.DevelopmentGuard;
import de.dadecker.inubit.mcp.application.DiagnosisService;
import de.dadecker.inubit.mcp.application.DiagramGroupTagger;
import de.dadecker.inubit.mcp.application.E2eTestService;
import de.dadecker.inubit.mcp.application.FanOut;
import de.dadecker.inubit.mcp.application.HealthService;
import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.application.InventoryCache;
import de.dadecker.inubit.mcp.application.InventoryService;
import de.dadecker.inubit.mcp.application.NodeDeployer;
import de.dadecker.inubit.mcp.application.NodeOverview;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.application.PackageWriter;
import de.dadecker.inubit.mcp.application.ProcessControlService;
import de.dadecker.inubit.mcp.application.ReleaseDiscovery;
import de.dadecker.inubit.mcp.application.ReleasePlanner;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.SecretTiming;
import de.dadecker.inubit.mcp.application.TagService;
import de.dadecker.inubit.mcp.application.TargetFolderRules;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.UpdateStatus;
import de.dadecker.inubit.mcp.application.WorkspaceService;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.config.ConfigLocation;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.ParameterStore;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.ClientSelectionPort;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.domain.port.E2ePort;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.NetworkPathProbe;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpServerFactory;
import de.dadecker.inubit.mcp.mcp.ResultMapper;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.UpdateNotice;
import de.dadecker.inubit.mcp.mcp.tools.CheckArtifactsTool;
import de.dadecker.inubit.mcp.mcp.tools.ExportArchivesTool;
import de.dadecker.inubit.mcp.mcp.tools.ExportArtifactsTool;
import de.dadecker.inubit.mcp.mcp.tools.FindProcessesTool;
import de.dadecker.inubit.mcp.mcp.tools.GetHealthTool;
import de.dadecker.inubit.mcp.mcp.tools.GetInventoryItemTool;
import de.dadecker.inubit.mcp.mcp.tools.DeployReleaseTool;
import de.dadecker.inubit.mcp.mcp.tools.ImportArtifactsTool;
import de.dadecker.inubit.mcp.mcp.tools.KillProcessTool;
import de.dadecker.inubit.mcp.mcp.tools.ListInventoryTool;
import de.dadecker.inubit.mcp.mcp.tools.ListNodesTool;
import de.dadecker.inubit.mcp.mcp.tools.QueryLogsTool;
import de.dadecker.inubit.mcp.mcp.tools.RestoreBackupTool;
import de.dadecker.inubit.mcp.mcp.tools.SetActiveTool;
import de.dadecker.inubit.mcp.mcp.tools.RunE2eTestTool;
import de.dadecker.inubit.mcp.mcp.tools.TagArtifactsTool;
import de.dadecker.inubit.mcp.mcp.tools.RestartProcessTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Composition root: builds the shared components from a validated configuration and the tool
 * handlers on top of them. This is the only place that knows the concrete adapters.
 *
 * <p><b>Extension point:</b> each user-story phase adds its handlers in {@link #toolHandlers()}
 * (US1: {@code list_nodes}, {@code get_health}; US2: {@code find_processes},
 * {@code query_logs}; US3: {@code list_inventory}, {@code get_inventory_item}; feature 003:
 * {@code export_artifacts}, only if a node may use a StartCLI client home (feature 017: for
 * some version, {@link ClientSelectionPort#anyCandidate}), and {@code check_artifacts};
 * feature 015: {@code export_archives} under the same condition as {@code export_artifacts}).
 * Feature 012 (research D-12): the read tools other than {@code list_nodes}, {@code get_health}
 * and {@code check_artifacts} are announced only if some node may run their capability
 * ({@link #possiblyCapable}).
 * The write tools
 * of US4 ({@code restart_process}, {@code kill_process}) are added only if
 * {@link #anyWriteEnabled()} (contracts/mcp-tools.md, Story 4 / AS 6); their service, the write
 * guard and the audit log ({@code auditDirectory}, written only on a write call) are built in any
 * case. Feature 004: {@code import_artifacts}, {@code restore_backup}, {@code set_active} and
 * {@code tag_artifacts} only if {@link #anyDevelopmentNode()} ({@code restore_backup} also if
 * {@link #anyDeploymentTarget()}, feature 005), {@code run_e2e_test} only if
 * {@link #anyE2eNode()} (its SOAP clients are created per node on first use); their services
 * share one
 * challenge registry and the audit log; the import service uses the check service and keeps
 * its backups in
 * {@code ~/.inubit-mcp/<profile>/backups} (created on the first import). Feature 009 (research
 * D-12): these conditions ignore nodes pinned to a version on which the tool's capability is
 * unsupported ({@link #possiblyCapable}); {@code deploy_release} needs such a target node.
 *
 * <p>The JVM shutdown hook that stops StartCLI work is registered as the last step of the
 * constructor, so that a failing constructor leaves no hook behind (US3 re-review N2).
 */
final class Wiring implements AutoCloseable {

    private final ProfileInfo profile;
    /** Feature 012 (research D-12): the capability matrix, injectable for tests. */
    private final CapabilityMatrix matrix;
    private final SecretScrubber scrubber;
    private final List<EffectiveNodeConfig> servers;
    private final TargetResolver targets;
    private final FanOut fanOut;
    private final ResultLimiter limiter;
    private final ClockProvider clock;
    private final AdapterGatewayFactory gateways;
    /** Feature 009: the StartCLI client probe for {@code get_health} and the version gate. */
    private final ClientVersionPort clientVersions;
    /** Feature 009 (research D-9): the {@code list_nodes} view with the version state. */
    private final NodeOverview overview;
    private final HealthService health;
    /** Feature 016: the last update check, shown by {@code get_health}. */
    private final UpdateStatus updateStatus = new UpdateStatus();
    private final DiagnosisService diagnosis;
    private final InventoryService inventory;
    private final ProcessControlService processControl;
    private final ClientSelectionPort clientSelection;
    private final ExportArtifactsTool exportArtifacts;
    /** Feature 015: offered under the condition of {@code export_artifacts} (research R-16). */
    private final ExportArchivesTool exportArchives;
    private final CheckArtifactsTool checkArtifacts;
    private final ImportArtifactsTool importArtifacts;
    private final RestoreBackupTool restoreBackup;
    /** Feature 005: the stage chain of the configuration (empty: none). */
    private final StageChain chain;
    private final SetActiveTool setActive;
    private final TagArtifactsTool tagArtifacts;
    private final RunE2eTestTool runE2eTest;
    /** Feature 005: offered if a group receives deployments. */
    private final Optional<DeployReleaseTool> deployRelease;
    /** The SOAP test clients, created on first use per node and closed with the wiring. */
    private final Map<NodeId, SoapE2eClient> e2eClients = new java.util.concurrent
        .ConcurrentHashMap<>();
    private final CliResources cliResources;
    private final Thread cleanupHook;
    /** Feature 015 (research R-9): the configuration roots an archive target stays out of. */
    private final ConfigLocation configLocation;
    /**
     * Feature 015 (research R-10): tells whether a drive letter is a mapped network drive; the
     * native API is loaded only on Windows, on the first drive letter asked.
     */
    private final NetworkPathProbe networkPathProbe;

    /**
     * @param config  a configuration that {@code ConfigValidator} reported no errors for
     * @param credentialStore the credential store of the running system (feature 011); nothing is
     *     read here
     * @param exists  file-system check for the CLI availability of {@code list_nodes}
     * @param windows whether the server runs on Windows ({@code startcli.bat})
     * @param configLocation the default configuration directory and the configuration file in use
     *     (feature 015, research R-9)
     */
    Wiring(ProfileConfig config, CredentialStore credentialStore, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows,
        ConfigLocation configLocation) {
        this(config, credentialStore, scrubber, clock, exists, windows, new SystemProcessLauncher(),
            System.getenv(), SsmParameterStore::new, configLocation);
    }

    /**
     * As above, with the launcher of StartCLI and the environment its allowlist is copied from
     * (tests replace StartCLI with a fake).
     */
    Wiring(ProfileConfig config, CredentialStore credentialStore, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows, ProcessLauncher launcher,
        Map<String, String> environment, ConfigLocation configLocation) {
        this(config, credentialStore, scrubber, clock, exists, windows, launcher, environment,
            SsmParameterStore::new, configLocation);
    }

    /**
     * As above, with the Parameter Store of the AWS nodes (feature 010), created only if a node
     * reads its secrets from it; nothing is fetched here (FR-006a). StartCLI's processes count as
     * contained ({@link NoJob}): tests never bind their JVM to a job.
     */
    Wiring(ProfileConfig config, CredentialStore credentialStore, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows, ProcessLauncher launcher,
        Map<String, String> environment, Supplier<ParameterStore> parameterStore,
        ConfigLocation configLocation) {
        this(config, credentialStore, scrubber, clock, exists, windows, launcher, environment,
            parameterStore, configLocation, new NoJob());
    }

    /** As above, with a capability matrix other than the production one (tests, feature 012). */
    Wiring(ProfileConfig config, CredentialStore credentialStore, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows, ProcessLauncher launcher,
        Map<String, String> environment, Supplier<ParameterStore> parameterStore,
        ConfigLocation configLocation, CapabilityMatrix matrix) {
        this(config, credentialStore, scrubber, clock, exists, windows, launcher, environment,
            parameterStore, configLocation, new NoJob(), OsPrivateFileSystem.forHost(), matrix);
    }

    /**
     * As above, with the containment of StartCLI's processes that {@code Launcher} set up once for
     * the process (feature 013, research R-7): the StartCLI runner refuses to start StartCLI while
     * it is unavailable. The runner's system queries are those of the platform ({@code windows}).
     */
    Wiring(ProfileConfig config, CredentialStore credentialStore, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows, ProcessLauncher launcher,
        Map<String, String> environment, Supplier<ParameterStore> parameterStore,
        ConfigLocation configLocation, ProcessContainment containment) {
        this(config, credentialStore, scrubber, clock, exists, windows, launcher, environment,
            parameterStore, configLocation, containment, OsPrivateFileSystem.forHost());
    }

    /**
     * As above, with the private file system that {@code Launcher} uses too (feature 013,
     * FR-007): the export and import directories and the audit directory and log are created
     * through it.
     */
    Wiring(ProfileConfig config, CredentialStore credentialStore, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows, ProcessLauncher launcher,
        Map<String, String> environment, Supplier<ParameterStore> parameterStore,
        ConfigLocation configLocation, ProcessContainment containment,
        PrivateFileSystem privateFiles) {
        this(config, credentialStore, scrubber, clock, exists, windows, launcher, environment,
            parameterStore, configLocation, containment, privateFiles,
            CapabilityMatrix.production());
    }

    /** As above, with the capability matrix of the INUBIT versions (feature 012). */
    Wiring(ProfileConfig config, CredentialStore credentialStore, SecretScrubber scrubber,
        ClockProvider clock, Predicate<Path> exists, boolean windows, ProcessLauncher launcher,
        Map<String, String> environment, Supplier<ParameterStore> parameterStore,
        ConfigLocation configLocation, ProcessContainment containment,
        PrivateFileSystem privateFiles, CapabilityMatrix matrix) {
        Objects.requireNonNull(containment, "containment");
        Objects.requireNonNull(privateFiles, "privateFiles");
        this.configLocation = Objects.requireNonNull(configLocation, "configLocation");
        this.networkPathProbe = new WindowsNetworkPaths(windows, JnaDriveTypeApi::new);
        this.profile = config.profileInfo();
        this.matrix = matrix;
        this.scrubber = scrubber;
        // 002 research D-8: the export directories carry the profile name
        // feature 013 (FR-007): the export and import directories are private on every platform
        this.cliResources = new CliResources(profile.name(), privateFiles);
        this.cleanupHook = new Thread(cliResources::close, "inubit-mcp-cli-cleanup");
        Terminology terms = profile.terminology();
        this.servers = config.effectiveNodes();
        this.targets = new TargetResolver(servers.stream().map(EffectiveNodeConfig::id).toList(),
            terms);
        this.fanOut = new FanOut();
        this.limiter = new ResultLimiter(config.resultLimits().maxItems(),
            config.resultLimits().maxChars());
        this.clock = clock;
        Map<NodeId, EffectiveNodeConfig> byId = new HashMap<>();
        servers.forEach(server -> byId.put(server.id(), server));
        // feature 009 (research D-4): the per-node version state, fed by the adapters
        NodeVersions versions = new NodeVersions(clock.clock(),
            id -> byId.get(id).versionLine(), id -> byId.get(id).inventory().cacheTtl());
        // feature 017 (research D-4): StartCLI is chosen per call by the effective version; a
        // write's process is started under the node's write lock (FR-018, research D-24)
        CliSystem cliSystem = windows ? new WindowsSystem() : new PosixSystem();
        CliRunner cliRunner = new CliRunner(launcher, environment, windows, cliSystem,
            containment, cliResources, new HandshakeStartCliPinProbe(),
            versions::effectiveVersion, versions, versions);
        // feature 009 (research D-16): the 8.1 port set, as default for every version; feature
        // 017 (research D-14): explicitly for 9.0 too; feature 012 (research D-4): the 6.1 port
        // set and health port, CLI-only (no REST client)
        Map<NodeId, NodeSecrets> secrets = secrets(servers, credentialStore, scrubber, clock,
            parameterStore);
        // one client-option probe for all 6.1 nodes and one concurrency limiter for every node's
        // 6.1 sessions and queue-entry helpers (D-8, D-9; feature 020 research D-17)
        CliClientProbe clientOptions = new CliClientProbe(cliRunner, byId::get, matrix,
            clock.clock());
        CliConcurrencyLimiter cliLimiter = new CliConcurrencyLimiter(matrix);
        // feature 020 (research D-17): the queue-entry helper of the 8.1/9.0 port set runs on
        // the runner's host (environment, system, containment, private resources) and takes its
        // StartCLI slot from that limiter; credentials and guard are the node's (port set)
        HelperInstallation.Host helperHost = new HelperInstallation.Host(windows, environment,
            cliSystem, containment, cliResources, Path.of(System.getProperty("java.io.tmpdir")));
        PortSetRegistry portSets = PortSetRegistry.v81Default(helperHost,
            new ProcessRun(launcher, cliResources), cliLimiter);
        this.gateways = new AdapterGatewayFactory(servers, secrets, scrubber, clock.clock(),
            cliRunner, versions, portSets.withV90()
                .register(V61PortSet.RANGE, V61PortSet.factory(clientOptions, cliLimiter),
                    V61QueryRules.INSTANCE, V61PortSet.cliHealth(clientOptions, cliLimiter)));
        // feature 009 (research D-7): the StartCLI client probe, on the same runner so that a
        // shutdown also stops a running probe
        this.clientVersions = new CliVersionProbe(cliRunner, byId::get);
        // feature 009 (FR-002): get_health and the gate probe the client of nodes with StartCLI
        // installed; outside get_health the probe is bounded by the node's cliTimeout. Feature
        // 017 (research D-7): the client of a node is chosen by the version at hand
        ClientSelectionPort clientSelection =
            new CliClientSelector(cliRunner, byId::get, exists, windows);
        this.clientSelection = clientSelection;
        VersionGate versionGate = new VersionGate(versions, gateways, matrix, clientVersions,
            clientSelection, id -> byId.get(id).cliTimeout(), terms,
            servers.stream().anyMatch(server -> !server.cliSources().clients().isEmpty()));
        Map<NodeId, Duration> timeouts = new HashMap<>();
        servers.forEach(server -> timeouts.put(server.id(), server.timeout()));
        // feature 011 (research R-8): get_health names the credential store a read waits for
        Map<NodeId, String> stores = new HashMap<>();
        servers.stream().filter(server -> !server.secretPlan().aws())
            .forEach(server -> stores.put(server.id(), credentialStore.kind().displayName()));
        this.health = new HealthService(gateways, targets, fanOut, timeouts::get, clock.clock(),
            versionGate, versions, clientVersions, clientSelection,
            id -> Optional.ofNullable(stores.get(id)));
        Map<NodeId, Duration> hangingThresholds = new HashMap<>();
        servers.forEach(server -> hangingThresholds.put(server.id(), server.hangingThreshold()));
        // feature 010/011 (research R-8, FR-008): a fan-out branch waits for its node's first
        // read as long as its store may take, but only while the credentials are not held
        Map<NodeId, Duration> fetchWaits = new HashMap<>();
        secrets.forEach((id, nodeSecrets) -> fetchWaits.put(id, nodeSecrets.timeout()));
        SecretTiming secretTiming = new SecretTiming(fetchWaits,
            id -> secrets.get(id).held().isPresent(), stores);
        this.diagnosis = new DiagnosisService(gateways, targets, fanOut, limiter, timeouts::get,
            id -> byId.get(id).cliTimeout(), hangingThresholds::get, clock.clock(),
            ResultJson::size, versionGate, secretTiming);
        this.inventory = new InventoryService(gateways, targets, fanOut, limiter,
            new InventoryCache(clock.clock(), id -> byId.get(id).inventory().cacheTtl()),
            id -> byId.get(id).inventory().owner(), timeouts::get,
            id -> byId.get(id).cliExportTimeout(), id -> byId.get(id).cliTimeout(),
            CliRunner.KILL_GRACE.multipliedBy(2), ResultJson::size, terms, versionGate,
            secretTiming);
        // feature 010 (research R-16): built per lookup, so that the account of an AWS node is
        // the username fetched after startup; a lookup never fetches. Unknown ids give null,
        // which the guard treats as no write access
        Function<NodeId, WritePolicy> policies = id -> byId.containsKey(id)
            ? policy(byId.get(id), secrets.get(id)) : null;
        AuditLog audit = new AuditLog(config.auditDirectory(), scrubber, privateFiles);
        this.processControl = new ProcessControlService(
            new WriteGuard(targets, policies, gateways, versionGate), policies,
            gateways, new ConfirmationRegistry(clock.clock()), audit, clock.clock(),
            UUID::randomUUID, profile.name());
        // feature 003: the workspace history uses the system git (not the StartCLI launcher)
        Path workspace = config.workspace();
        this.exportArtifacts = new ExportArtifactsTool(new WorkspaceService(workspace,
            new GitCli(workspace, profile.name(), new SystemProcessLauncher(), environment,
                privateFiles),
            new ArchiveCodec(), gateways::artifacts, gateways::inventory, versionGate), targets,
            id -> byId.get(id).inventory().owner(), limiter);
        // feature 015: the archives go to a user-chosen local folder outside the server's
        // directories (research R-9); production only with archives.productionOptIn (R-15);
        // the shared audit log
        TargetFolderRules folders = new TargetFolderRules(
            Path.of(System.getProperty("user.home")), workspace, config.auditDirectory(),
            configLocation.roots(), environment, networkPathProbe, windows);
        this.exportArchives = new ExportArchivesTool(new ArchiveExportService(targets,
            id -> byId.get(id).inventory().owner(), id -> policies.apply(id).account(),
            id -> byId.get(id).production(), id -> byId.get(id).archivesProductionOptIn(),
            folders, gateways::artifacts, new V81WorkflowArchives(), versionGate,
            new ArchiveFolderWriter(), limiter, new ArchiveExportAudit.Dependencies(audit,
                clock.clock(), profile.name(), UUID::randomUUID)));
        ArtifactCheckService checks = new ArtifactCheckService(workspace,
            new WorkspaceInspector(), new SaxonXsltRunner(workspace), group -> servers.stream()
                .map(EffectiveNodeConfig::id).filter(id -> id.group().equals(group)).findFirst(),
            gateways::inventory, id -> byId.get(id).inventory().owner(), limiter,
            clock.clock(), versionGate);
        this.checkArtifacts = new CheckArtifactsTool(checks);
        // feature 004: the development tools (offered only with a development node)
        Map<NodeId, DevelopmentPolicy> development = new HashMap<>();
        servers.forEach(server -> development.put(server.id(), server.developmentPolicy()));
        StageChain chain = config.stageChain();
        this.chain = chain;
        // feature 009 (FR-004): list_nodes reads the version state, it never contacts a node
        this.overview = new NodeOverview(servers.stream()
            .map(server -> server.summary(exists, windows)).toList(), versions, matrix,
            (id, withClient) -> allowed(byId.get(id), withClient), clientSelection);
        DevelopmentGuard developmentGuard = new DevelopmentGuard(targets, development::get,
            node -> gateways.imports(node).checkAvailable(), versionGate,
            group -> Optional.ofNullable(
                chain.targets().get(group)).map(StageChain.ChainLink::mode),
            config.defaults().effectiveDeployConfirmationTtl());
        Function<NodeId, ImportService.Account> accounts = id -> new ImportService.Account(
            policies.apply(id).account().orElse("unknown"), byId.get(id).baseUrl().getHost());
        WriteChallengeRegistry challenges = new WriteChallengeRegistry(clock.clock());
        Path backups = BackupStore.defaultRoot(Path.of(System.getProperty("user.home")),
            profile.name());
        this.tagArtifacts = new TagArtifactsTool(new TagService(new TagService.Dependencies(
            workspace, profile.name(), developmentGuard, gateways::tags,
            id -> byId.get(id).inventory().owner(), accounts, challenges, audit, clock.clock(),
            UUID::randomUUID)));
        Function<NodeId, E2ePort> e2ePorts = id -> {
            // feature 010 (FR-006a, research R-7): both reads before computeIfAbsent, so a slow
            // read blocks no other entry and a failed one is not cached as a client; feature 011
            // (research R-7, review T013-T015 r2): each read gets the node's store timeout of its
            // own, so that the access dialogs of the main read (username, password, trust store
            // password) cannot use up the time of the end-to-end read; the call may take twice
            // the store timeout on a fresh node, which E2eTestService allows (it has no overall
            // deadline)
            NodeSecrets nodeSecrets = secrets.get(id);
            Optional<de.dadecker.inubit.mcp.infra.Secret> trustStorePassword = nodeSecrets
                .current(nodeSecrets.timeout()).trustStorePassword()
                .map(SourcedValue::value);
            Optional<SoapE2eClient.BasicAuth> auth = nodeSecrets.e2e(nodeSecrets.timeout())
                .map(e2e -> new SoapE2eClient.BasicAuth(e2e.username().value(),
                    e2e.password().value()));
            return e2eClients.computeIfAbsent(id, node -> new SoapE2eClient(byId.get(node),
                development.get(node).soapBaseUrl().orElseThrow(), trustStorePassword, auth,
                versions));
        };
        this.runE2eTest = new RunE2eTestTool(new E2eTestService(new E2eTestService.Dependencies(
            workspace, backups, profile.name(), developmentGuard, e2ePorts,
            gateways::logs, gateways::processes, hangingThresholds::get, accounts, challenges,
            audit, clock.clock(), UUID::randomUUID)));
        // feature 005: deploy_release along the stage chain (offered only with a chain)
        Optional<NodeDeployer> deployer = Optional.empty();
        if (chain.isEmpty()) {
            this.deployRelease = Optional.empty();
        } else {
            Path deployments = DeploymentLedger.defaultDirectory(
                Path.of(System.getProperty("user.home")), profile.name());
            ArchiveCodec codec = new ArchiveCodec();
            V81ReleaseArchives releases = new V81ReleaseArchives();
            V81ImportArchives importArchives = new V81ImportArchives();
            DeploymentLedger ledger = new DeploymentLedger(deployments, privateFiles);
            ReleasePlanner planner = new ReleasePlanner(new ReleasePlanner.Dependencies(
                gateways::artifacts, gateways::inventory, codec, releases, importArchives,
                (releaseRoot, paths) -> new ArtifactCheckService(releaseRoot,
                    new WorkspaceInspector(), new SaxonXsltRunner(releaseRoot), group ->
                        Optional.empty(), gateways::inventory, id -> Optional.empty(), limiter,
                    clock.clock(), versionGate).checkPaths(paths, false), accounts, workspace,
                clock.clock()));
            NodeDeployer nodeDeployer = new NodeDeployer(new NodeDeployer.Dependencies(planner,
                releases, importArchives, codec, gateways::imports, gateways::tags, accounts,
                new BackupStore(backups, clock.clock(), privateFiles), ledger, audit,
                profile.name(), clock.clock(), UUID::randomUUID, workspace,
                new DiagramGroupTagger(workspace), new PackageWriter(
                    deployments.resolveSibling("packages"), clock.clock(), privateFiles)));
            deployer = Optional.of(nodeDeployer);
            DeployService deploy = new DeployService(new DeployService.Dependencies(workspace,
                deployments, profile.name(), new DeployGuard(chain, targets, group ->
                    servers.stream().filter(server -> server.id().group().equals(group))
                        .findFirst().flatMap(server -> server.inventory().owner()), audit,
                    versionGate, profile.name(), clock.clock(), UUID::randomUUID),
                new ReleaseDiscovery(gateways::artifacts, codec, releases, workspace), planner,
                ledger, challenges, config.defaults().effectiveDeployConfirmationTtl(), audit,
                clock.clock(), UUID::randomUUID, nodeDeployer, new GitCli(workspace, profile.name(),
                    new SystemProcessLauncher(), environment, privateFiles), importArchives,
                privateFiles));
            this.deployRelease = Optional.of(new DeployReleaseTool(deploy,
                config.resultLimits().maxItems()));
        }
        // feature 004 (feature 005, T026: and the restore of deployment backups)
        ImportService imports = new ImportService(
            new ImportService.Dependencies(workspace, profile.name(), developmentGuard,
                development::get,
                new GitCli(workspace, profile.name(), new SystemProcessLauncher(), environment,
                    privateFiles),
                new WorkspaceInspector(), checks, new ArchiveCodec(), new V81ImportArchives(),
                new V81ReleaseArchives(), gateways::artifacts, gateways::imports, gateways::tags,
                gateways::inventory,
                id -> byId.get(id).inventory().owner(), accounts, challenges,
                new BackupStore(backups, clock.clock(), privateFiles),
                audit, clock.clock(), UUID::randomUUID, deployer));
        this.importArtifacts = new ImportArtifactsTool(imports);
        this.restoreBackup = new RestoreBackupTool(imports);
        this.setActive = new SetActiveTool(imports);
        // last step (N2): SIGTERM (and System.exit) stop running StartCLI work and delete the
        // export directories
        Runtime.getRuntime().addShutdownHook(cleanupHook);
    }

    /**
     * Feature 010 (research R-7), feature 011 (research R-7): one {@link NodeSecrets} per node,
     * read on first use: credential store nodes from {@code credentialStore}, AWS nodes from the
     * one Parameter Store, which is created only if there is an AWS node.
     */
    private static Map<NodeId, NodeSecrets> secrets(List<EffectiveNodeConfig> servers,
        CredentialStore credentialStore, SecretScrubber scrubber, ClockProvider clock,
        Supplier<ParameterStore> parameterStore) {
        ParameterStore store = servers.stream().anyMatch(server -> server.secretPlan().aws())
            ? parameterStore.get() : null;
        Map<NodeId, NodeSecrets> secrets = new HashMap<>();
        for (EffectiveNodeConfig server : servers) {
            secrets.put(server.id(), server.secretPlan().aws()
                ? NodeSecrets.aws(server.secretPlan(), store, scrubber, clock.clock())
                : NodeSecrets.credentialStore(server, credentialStore, scrubber, clock.clock()));
        }
        return secrets;
    }

    /**
     * The write settings of {@code server} with the INUBIT account for the audit (research
     * R-16): the username {@code secrets} hold (read on first use), else the name it is read from:
     * {@code (Parameter Store <username parameter>)} for an AWS node, {@code (credential store
     * <node entry> or <group entry>)} for a credential store node (feature 011, research R-17);
     * never reads.
     */
    private static WritePolicy policy(EffectiveNodeConfig server, NodeSecrets secrets) {
        Optional<String> account = secrets.held()
            .flatMap(held -> held.username().map(SourcedValue::value));
        if (account.isEmpty() && server.secretPlan().aws()) {
            account = Optional.of("(Parameter Store " + server.secretPlan().names().orElseThrow()
                .username().orElseThrow() + ")");
        } else if (account.isEmpty()) {
            CredentialNames names = server.credentialNames();
            account = Optional.of("(credential store " + names.nodeName(CredentialNames.USERNAME)
                + " or " + names.groupName(CredentialNames.USERNAME) + ")");
        }
        return new WritePolicy(server.id(), server.production(), server.write().enabled(),
            server.write().productionOptIn(),
            server.write().confirmation() == ConfirmationMode.CLIENT
                ? WritePolicy.Confirmation.CLIENT : WritePolicy.Confirmation.SERVER,
            server.confirmationTtl(), account);
    }

    /** The tools to register. */
    List<ToolHandler> toolHandlers() {
        List<ToolHandler> handlers = new ArrayList<>();
        handlers.add(new ListNodesTool(profile, overview::nodes));
        handlers.add(new GetHealthTool(health, updateStatus::current));
        // feature 012 (research D-12): a read tool only if some node may run its capability
        if (anyPossiblyCapable(Capability.PROCESS_DIAGNOSIS)) {
            handlers.add(new FindProcessesTool(diagnosis));
        }
        if (anyPossiblyCapable(Capability.LOG_DIAGNOSIS)) {
            handlers.add(new QueryLogsTool(diagnosis));
        }
        if (anyPossiblyCapable(Capability.INVENTORY)) {
            handlers.add(new ListInventoryTool(inventory));
            handlers.add(new GetInventoryItemTool(inventory));
        }
        // feature 017 (research D-7): a client home some version of the node may use
        if (servers.stream().anyMatch(server -> clientSelection.anyCandidate(server.id())
            && possiblyCapable(server, Capability.WORKSPACE_EXPORT))) {
            handlers.add(exportArtifacts);
            handlers.add(exportArchives);
        }
        handlers.add(checkArtifacts);
        if (anyWriteEnabled()) {
            handlers.add(new RestartProcessTool(processControl));
            handlers.add(new KillProcessTool(processControl));
        }
        if (anyDevelopmentNode()) {
            handlers.add(importArtifacts);
            handlers.add(restoreBackup);
            handlers.add(setActive);
            handlers.add(tagArtifacts);
        } else if (anyDeploymentTarget()) {
            handlers.add(restoreBackup); // feature 005 (T026): deployment backups
        }
        if (anyE2eNode()) {
            handlers.add(runE2eTest);
        }
        if (anyDeploymentTargetNode()) {
            deployRelease.ifPresent(handlers::add);
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
        return serverFactory(version, handlers, UpdateNotice.NONE);
    }

    /** As above, with the update notice of feature 016 (FR-017 to FR-019). */
    McpServerFactory serverFactory(String version, List<ToolHandler> handlers,
        UpdateNotice updateNotice) {
        return new McpServerFactory(version, profile, handlers, SchemaResources.forClasspath(),
            new ResultMapper(scrubber), updateNotice);
    }

    GatewayFactory gateways() {
        return gateways;
    }

    /** The configuration location the server runs with (feature 015, research R-9). */
    /** Feature 016: the status the update check records and {@code get_health} shows. */
    UpdateStatus updateStatus() {
        return updateStatus;
    }

    ConfigLocation configLocation() {
        return configLocation;
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

    /**
     * True if at least one node is a development stage (feature 004, FR-001) that is not pinned
     * to a version without development writes ({@link #possiblyCapable}).
     */
    boolean anyDevelopmentNode() {
        return servers.stream().anyMatch(server -> server.development().enabled()
            && possiblyCapable(server, Capability.DEVELOPMENT_WRITE));
    }

    /**
     * Feature 005 (T026): true if a group receives deployments in mode {@code EXECUTE}; its
     * nodes get deployment backups that {@code restore_backup} restores. Such a node must also
     * not be pinned to a version without deployments ({@link #possiblyCapable}).
     */
    boolean anyDeploymentTarget() {
        return servers.stream().anyMatch(server -> chain.link(server.id().group())
            .filter(link -> link.mode() == DeployMode.EXECUTE).isPresent()
            && possiblyCapable(server, Capability.DEPLOYMENT));
    }

    /** True if a node of a group that receives deployments (in either mode) may deploy. */
    private boolean anyDeploymentTargetNode() {
        return servers.stream().anyMatch(server -> chain.link(server.id().group()).isPresent()
            && possiblyCapable(server, Capability.DEPLOYMENT));
    }

    /**
     * True if at least one development node allows end-to-end tests ({@code e2eTests} other
     * than {@code FORBIDDEN}, feature 004, contracts/mcp-tools-delta.md), or a non-production
     * node of a group that receives deployments (feature 005, T027); that node must also not be
     * pinned to a version without end-to-end tests ({@link #possiblyCapable}).
     */
    boolean anyE2eNode() {
        return servers.stream().anyMatch(server -> possiblyCapable(server, Capability.E2E_TEST)
            && e2eNode(server));
    }

    /** True if {@code server} allows end-to-end tests (see {@link #anyE2eNode()}). */
    private boolean e2eNode(EffectiveNodeConfig server) {
        DevelopmentPolicy policy = server.developmentPolicy();
        return policy.e2eTests() != E2ePolicy.FORBIDDEN && (policy.enabled()
            || (!policy.production() && chain.link(policy.node().group()).isPresent()));
    }

    /**
     * Feature 009 (FR-003, FR-004): the capabilities the configuration of {@code server} allows,
     * before its version is considered: the reads always, the workspace export with the StartCLI
     * client of its current version installed ({@code withClient}, feature 017), development
     * writes on a development stage, deployments in a group that receives them, end-to-end tests
     * where allowed, process control with write access.
     */
    private Set<Capability> allowed(EffectiveNodeConfig server, boolean withClient) {
        Set<Capability> allowed = EnumSet.of(Capability.MONITORING,
            Capability.PROCESS_DIAGNOSIS, Capability.LOG_DIAGNOSIS, Capability.INVENTORY);
        if (withClient) {
            allowed.add(Capability.WORKSPACE_EXPORT);
        }
        if (server.development().enabled() && !server.production()) {
            allowed.add(Capability.DEVELOPMENT_WRITE);
        }
        if (chain.link(server.id().group()).isPresent()) {
            allowed.add(Capability.DEPLOYMENT);
        }
        if (e2eNode(server)) {
            allowed.add(Capability.E2E_TEST);
        }
        if (server.effectiveWriteEnabled()) {
            allowed.add(Capability.PROCESS_CONTROL);
        }
        return allowed;
    }

    /**
     * True if at least one server has effective write access (Story 4 / AS 6) and is not pinned
     * to a version without process control ({@link #possiblyCapable}).
     */
    boolean anyWriteEnabled() {
        return servers.stream().anyMatch(server -> server.effectiveWriteEnabled()
            && possiblyCapable(server, Capability.PROCESS_CONTROL));
    }

    /** True if some node may run {@code capability} ({@link #possiblyCapable}). */
    private boolean anyPossiblyCapable(Capability capability) {
        return servers.stream().anyMatch(server -> possiblyCapable(server, capability));
    }

    /**
     * Feature 009 (research D-12, FR-007): false only if {@code server} is pinned to a line or
     * version on which the matrix declares {@code capability} unsupported; an {@code AUTO} node
     * may turn out to run a capable version.
     */
    private boolean possiblyCapable(EffectiveNodeConfig server, Capability capability) {
        return switch (server.versionLine()) {
            case VersionSetting.Auto auto -> true;
            case VersionSetting.Line line -> matrix.stateOfLine(line.major(), line.minor(),
                capability) != SupportState.UNSUPPORTED;
            case VersionSetting.Pinned pinned -> matrix.state(Optional.of(pinned.version()),
                capability) != SupportState.UNSUPPORTED;
        };
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
        e2eClients.values().forEach(SoapE2eClient::close);
        cliResources.close();
        gateways.close();
        try {
            Runtime.getRuntime().removeShutdownHook(cleanupHook);
        } catch (IllegalStateException e) {
            // the JVM is already shutting down; the hook runs (or ran) anyway
        }
    }
}
