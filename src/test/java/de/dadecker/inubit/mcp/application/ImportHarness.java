package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81ImportArchives;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliImportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ImportAdapter;
import de.dadecker.inubit.mcp.adapter.xslt.SaxonXsltRunner;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Test support of the import tests (feature 004): a workspace exported from a fixture into a
 * real git history, a {@link FakeInubit} behind the real 8.1 adapters on a
 * {@link ScriptedProcessLauncher} (so every StartCLI call is scripted and nothing unscripted can
 * run), a recording audit, real backups, and the {@link ImportService} on top.
 */
public final class ImportHarness {

    public static final NodeId DEV = NodeId.parse("dev/node1");
    public static final NodeId TEST = NodeId.parse("test/node1");
    public static final String PASSWORD = "S3cr3t-import-harness";
    public static final String EXPORT_OK = "JAVA_HOME is set\nPassword: \n"
        + "1-OK: Workflow group exported successfully.\n";
    public static final String MODULE_OK = "JAVA_HOME is set\nPassword: \n"
        + "1-OK: Module exported successfully.\n";

    public final Path root;
    public final Path cliHome;
    public final ExportHarness exports;
    public final FakeInubit inubit;
    public final ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
    public final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    public final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    public final BackupStore backups;
    public final Map<String, OwnerKind> owners = new HashMap<>();
    public final Set<String> users = new java.util.HashSet<>(Set.of("jdoe"));
    public final List<String> targetModules = new ArrayList<>();
    public final List<String> targetDiagrams = new ArrayList<>();
    public final String owner;
    public final String diagramGroup;
    public WritePolicy.Confirmation confirmation = WritePolicy.Confirmation.CLIENT;
    /** Wraps the archive port (e.g. to inject an unexpected failure). */
    public java.util.function.UnaryOperator<de.dadecker.inubit.mcp.domain.port.ImportArchivePort>
        archives = java.util.function.UnaryOperator.identity();
    private final List<UUID> ids = new CopyOnWriteArrayList<>();
    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;

    /**
     * @param temp    a temporary directory of the test
     * @param fixture the export the workspace and the fake server start from
     */
    public ImportHarness(Path temp, byte[] fixture, String owner, String diagramGroup)
        throws IOException {
        this(temp, fixture, owner, diagramGroup, null, null);
    }

    /** The harness on a module-only export of {@code module} (plugin type {@code pluginType}). */
    public static ImportHarness module(Path temp, byte[] fixture, String owner, String module,
        String pluginType) throws IOException {
        return new ImportHarness(temp, fixture, owner, "-", module, pluginType);
    }

    private ImportHarness(Path temp, byte[] fixture, String owner, String diagramGroup,
        String module, String pluginType) throws IOException {
        this.root = Files.createDirectories(temp.resolve("workspace"));
        this.cliHome = Files.createDirectories(temp.resolve("client"));
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        this.owner = owner;
        this.diagramGroup = diagramGroup;
        this.exports = new ExportHarness(root);
        if (module == null) {
            exports.artifacts.exports.put(diagramGroup, fixture);
            exports.service().export(new WorkspaceService.ExportRequest(DEV, owner,
                List.of(diagramGroup), List.of()));
        } else {
            exports.artifacts.exports.put(module, fixture);
            exports.service().export(new WorkspaceService.ExportRequest(DEV, owner, List.of(),
                List.of(new WorkspaceService.ModuleRef(module, Optional.of(pluginType)))));
        }
        this.inubit = new FakeInubit(fixture);
        this.backups = new BackupStore(temp.resolve("backups"), clock);
        this.server = TestNodeConfig.node().id(DEV.value()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).cliExportTimeout(Duration.ofSeconds(2))
            .build();
        this.credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    /** The harness on {@code grp-a.zip} without its edit mode (GRP-01 of jdoe). */
    public static ImportHarness grpA(Path temp) throws IOException {
        return new ImportHarness(temp, withoutEditMode(), "jdoe", "GRP-01");
    }

    /** {@code grp-a.zip} with {@code Workflow-0002} no longer in edit mode. */
    public static byte[] withoutEditMode() {
        return ExportHarness.rewrite("grp-a.zip", "workflow/workflow.xml",
            xml -> xml.replace("<CheckoutUser>jdoe</CheckoutUser>", ""));
    }

    public ImportService service() {
        CredentialGuard guard = new CredentialGuard(new CredentialVariables("INUBIT", DEV),
            clock);
        CliRunner runner = new CliRunner(cli, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        CliOutputClassifier classifier = new CliOutputClassifier(new SecretScrubber());
        ArtifactPort artifacts = new V81ArtifactAdapter(new CliExportRunner(server, credentials,
            guard, runner, classifier), () -> { });
        ImportPort imports = new V81ImportAdapter(new CliImportRunner(server, credentials,
            guard, runner, classifier), () -> { });
        DevelopmentPolicy policy = new DevelopmentPolicy(DEV, false, true, confirmation,
            Duration.ofMinutes(5), E2ePolicy.FORBIDDEN, Optional.empty());
        DevelopmentPolicy test = new DevelopmentPolicy(TEST, false, false,
            WritePolicy.Confirmation.SERVER, Duration.ofMinutes(5), E2ePolicy.FORBIDDEN,
            Optional.empty());
        Map<NodeId, DevelopmentPolicy> policies = Map.of(DEV, policy, TEST, test);
        WorkspaceInspector inspector = new WorkspaceInspector();
        return new ImportService(new ImportService.Dependencies(root, "acme",
            new DevelopmentGuard(new TargetResolver(List.of(DEV, TEST)), policies::get,
                node -> imports.checkAvailable()),
            policies::get, exports.history, inspector,
            new ArtifactCheckService(root, inspector, new SaxonXsltRunner(root),
                group -> Optional.of(DEV), node -> inventory(), node -> Optional.of(owner),
                ResultLimiter.withDefaults(), clock),
            new ArchiveCodec(), archives.apply(new V81ImportArchives()), node -> artifacts,
            node -> imports,
            node -> inventory(), new OwnerKindResolver(owners, node -> () -> users),
            node -> Optional.of(owner),
            node -> new ImportService.Account("jdoe", "inubit-dev-1.example.test"),
            new WriteChallengeRegistry(clock), backups, audit::add, clock, this::nextId));
    }

    private UUID nextId() {
        UUID id = UUID.randomUUID();
        ids.add(id);
        return id;
    }

    private InventoryPort inventory() {
        return new InventoryPort() {
            @Override
            public List<InventoryItem> listDiagrams(String owner) {
                return targetDiagrams.stream().map(name -> InventoryItem.diagram(DEV, name,
                    "technical", diagramGroup, owner)).toList();
            }

            @Override
            public DiagramDetail diagramDetail(String owner, String name) {
                throw new AssertionError("not used");
            }

            @Override
            public DiagramMetadata diagramMetadata(String name) {
                throw new AssertionError("not used");
            }

            @Override
            public VersionHistory versionHistory(String owner, String type, String group) {
                throw new AssertionError("not used");
            }

            @Override
            public List<ModuleEntry> listModules(String owner) {
                return targetModules.stream().map(name -> new ModuleEntry(new InventoryItem(
                    DEV, InventoryKind.MODULE, name, "Assign", "Assign", owner,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    Optional.empty(), Optional.empty(), new ConnectorFlags(false, false, false),
                    Optional.empty())).toList();
            }
        };
    }

    // --- scripting ---------------------------------------------------------------------------

    /** The next StartCLI call is an export of the diagram group from the fake server. */
    public ImportHarness exportGroup() {
        cli.expect("export --exportWorkflowUser '" + owner + "' --exportWorkflowType"
            + " 'technical' --exportWorkflowGroup '" + diagramGroup + "'")
            .then(spec -> write(ScriptedProcessLauncher.exportFile(spec),
                inubit.exportWorkflowGroup())).replying(EXPORT_OK, "", 0);
        return this;
    }

    /** The next StartCLI call is a module export from the fake server. */
    public ImportHarness exportModule(String pluginType, String name) {
        cli.expect("export --exportModule '" + name + "' --exportModuleGroup '" + pluginType
            + "' --exportModuleUser '" + owner + "'")
            .then(spec -> write(ScriptedProcessLauncher.exportFile(spec),
                inubit.exportModule(pluginType, name))).replying(MODULE_OK, "", 0);
        return this;
    }

    /** The next StartCLI call is an export that fails (the spike's broken group). */
    public ImportHarness exportFails() {
        cli.expect("export ").replying("JAVA_HOME is set\nPassword: \n"
            + "2-NOK: Workflow group not found: " + diagramGroup + "\n", "", 1);
        return this;
    }

    /** The next StartCLI call is an import that the fake server applies. */
    public ImportHarness importApplied(String expectedOptions) {
        cli.expect(java.util.regex.Pattern.compile("^import --importFile '[^']+' "
            + java.util.regex.Pattern.quote(expectedOptions) + "$"))
            .capturingImportFile()
            .replyingWith(spec -> inubit.importArchive(read(
                ScriptedProcessLauncher.importFile(spec))));
        return this;
    }

    /** A workflow import for the user {@code jdoe}. */
    public ImportHarness importApplied() {
        return importApplied("--importWorkflow --importUser '" + owner
            + "' --returnProtocol");
    }

    /** The next StartCLI call is an import that INUBIT refuses ({@code 1-NOK}). */
    public ImportHarness importRefused() {
        cli.expect("import ").replying("import_nok");
        return this;
    }

    // --- workspace ---------------------------------------------------------------------------

    public String workflow(String name) {
        return WorkspacePath.workflow(DEV.group(), owner, diagramGroup, name).toRelativePath()
            .toString().replace('\\', '/');
    }

    /** The directory of module {@code name}, whatever its plugin type. */
    public String moduleDirectory(String name) {
        try (Stream<Path> walk = Files.walk(root.resolve(DEV.group().value()))) {
            return walk.filter(Files::isDirectory)
                .filter(dir -> dir.getFileName().toString().equals(name))
                .map(dir -> root.relativize(dir).toString().replace('\\', '/')).findFirst()
                .orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void edit(String path, String from, String to) {
        try {
            Path file = root.resolve(path);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(text).as(path).contains(from);
            Files.writeString(file, text.replace(from, to), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String read(String path) {
        try {
            return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(String path, String content) {
        write(root.resolve(path), content.getBytes(StandardCharsets.UTF_8));
    }

    static void write(Path file, byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A request for the diagram group. */
    public ImportService.ImportRequest group(String reason) {
        return new ImportService.ImportRequest(DEV.value(), Optional.empty(),
            Optional.of(diagramGroup), List.of(), reason, Optional.empty(), Optional.empty());
    }

    /** The same request with a confirmation code. */
    public static ImportService.ImportRequest confirmed(ImportService.ImportRequest request,
        String code) {
        return new ImportService.ImportRequest(request.node(), request.owner(),
            request.diagramGroup(), request.modules(), request.reason(), Optional.of(code),
            request.mcpClient());
    }

    /** The audit records of one capability. */
    public List<AuditRecord> audit(String capability) {
        return audit.stream().filter(record -> record.capability().equals(capability))
            .toList();
    }
}
