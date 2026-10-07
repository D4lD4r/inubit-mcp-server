package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliImportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTagRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ImportAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81TagAdapter;
import de.dadecker.inubit.mcp.adapter.git.GitCli;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Test support of the deployment tests (feature 005, research D-16, tasks T010, stage 2
 * review #7): one {@link FakeServer} per node — the source nodes {@code dev/node1} and
 * {@code dev/node2}, the chained targets {@code int/node1}…{@code int/node3} and the package-only
 * {@code prod/node1} — each behind the real 8.1 adapters on its own
 * {@link ScriptedProcessLauncher}, so that every StartCLI call is scripted per node and a call on
 * a node that should not be written fails the test; plus a fake REST inventory per node (diagram
 * list with diagram groups, module list, the modules of each workflow), a real git workspace,
 * real backups, a recording audit and a {@link MutableClock}.
 *
 * <p>By default every server holds diagram group {@code GRP-01} of owner {@code jdoe}
 * ({@code grp-a.zip} without edit mode); on the source nodes {@code Module-0005} imports the
 * repository file {@link #RELEASE_XSL} (version {@code 1.0}, {@link #RELEASE_XSL_V1}), which the
 * targets do not have. {@link #DeployHarness(Path, boolean) With empty targets} the targets have
 * no artifact at all. {@link #DeployHarness(Path, java.util.function.Function) Any servers} can
 * be given, e.g. {@link FakeServer#synthetic} for SC-007.
 */
public final class DeployHarness {

    public static final NodeId SOURCE = NodeId.parse("dev/node1");
    public static final NodeId SOURCE2 = NodeId.parse("dev/node2");
    /** The nodes of the source group {@code dev}, in configuration order. */
    public static final List<NodeId> SOURCES = List.of(NodeId.parse("dev/node1"),
        NodeId.parse("dev/node2"));
    public static final NodeId INT1 = NodeId.parse("int/node1");
    public static final NodeId INT2 = NodeId.parse("int/node2");
    public static final NodeId INT3 = NodeId.parse("int/node3");
    public static final NodeId PROD = NodeId.parse("prod/node1");
    /** The nodes of the chained target group {@code int}, in configuration order. */
    public static final List<NodeId> TARGETS = List.of(INT1, INT2, INT3);
    public static final String OWNER = "jdoe";
    public static final String GROUP = "GRP-01";
    public static final String RELEASE_XSL = "/Root/jdoe/xsd/release.xsl";
    public static final String RELEASE_XSL_V1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\">"
        + "<xsl:variable name=\"releaseVersion\" select=\"'v1'\"/></xsl:stylesheet>";
    public static final String PASSWORD = "S3cr3t-deploy-harness";
    static final String EXPORT_OK = "JAVA_HOME is set\nPassword: \n"
        + "1-OK: Workflow group exported successfully.\n";
    static final String MODULE_OK = "JAVA_HOME is set\nPassword: \n"
        + "1-OK: Module exported successfully.\n";
    static final String REPOSITORY_OK = "JAVA_HOME is set\nPassword: \n"
        + "1-OK: Repository path exported successfully.\n";

    public final Path root;
    public final Path cliHome;
    /** Stand-in for {@code ~/.inubit-mcp/<profile>} (backups, deployments, packages). */
    public final Path profileHome;
    public final Map<NodeId, FakeServer> servers = new LinkedHashMap<>();
    /** The REST reads of every node, e.g. {@code int/node1 diagrams jdoe}. */
    public final List<String> restCalls = new CopyOnWriteArrayList<>();
    public final Map<NodeId, ScriptedProcessLauncher> cli = new LinkedHashMap<>();
    public final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    public final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    public final BackupStore backups;
    public final GitCli history;
    /** Plain git helpers ({@code log}, {@code snapshot}, {@code git}) on {@link #root}. */
    public final ExportHarness workspace;

    /** The harness with targets that hold the diagram group (head of {@code grp-a.zip}). */
    public DeployHarness(Path temp) throws IOException {
        this(temp, false);
    }

    /**
     * @param emptyTargets true: the target and package-only nodes have no artifact at all
     */
    public DeployHarness(Path temp, boolean emptyTargets) throws IOException {
        this(temp, node -> {
            if (node.group().value().equals("dev")) {
                FakeServer source = FakeServer.of(withRepositoryReference(
                    ImportHarness.withoutEditMode()));
                source.putRepositoryFile(RELEASE_XSL, RELEASE_XSL_V1);
                return source;
            }
            return emptyTargets ? new FakeServer() : FakeServer.of(
                ImportHarness.withoutEditMode());
        });
    }

    /** The harness with the server {@code servers} gives for each node. */
    public DeployHarness(Path temp, java.util.function.Function<NodeId, FakeServer> servers)
        throws IOException {
        this.root = Files.createDirectories(temp.resolve("workspace"));
        this.cliHome = Files.createDirectories(temp.resolve("client"));
        this.profileHome = Files.createDirectories(temp.resolve("profile"));
        Files.writeString(Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh"),
            "#!/bin/sh\n");
        this.backups = new BackupStore(profileHome.resolve("backups"), clock);
        this.workspace = new ExportHarness(root);
        this.history = workspace.history;
        this.ledger = new DeploymentLedger(profileHome.resolve("deployments"));
        for (NodeId node : List.of(SOURCE, SOURCE2, INT1, INT2, INT3, PROD)) {
            this.servers.put(node, servers.apply(node));
            cli.put(node, new ScriptedProcessLauncher());
        }
    }

    /** {@code export} with {@code Module-0005} importing {@link #RELEASE_XSL}. */
    static byte[] withRepositoryReference(byte[] export) {
        Map<String, byte[]> entries = new LinkedHashMap<>(
            de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.entries(export));
        String anchor = "  &lt;xsl:output method=\"xml\" encoding=\"UTF-8\"/>";
        String module = new String(entries.get("module/module-0005.xml"),
            java.nio.charset.StandardCharsets.UTF_8);
        if (!module.contains(anchor)) {
            throw new IllegalStateException("module-0005.xml changed");
        }
        entries.put("module/module-0005.xml", module.replace(anchor,
            "  &lt;xsl:import href=\"inubitrepository:" + RELEASE_XSL + "\"/>\n" + anchor)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.zip(entries);
    }

    // --- ports of a node (real 8.1 adapters on the node's scripted StartCLI) ------------------

    private EffectiveNodeConfig server(NodeId node) {
        return TestNodeConfig.node().id(node.value()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).cliExportTimeout(Duration.ofSeconds(2))
            .build();
    }

    private NodeCredentials credentials(NodeId node) {
        return new NodeCredentials(node,
            Optional.of(new SourcedValue<>(OWNER, "HARNESS_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "HARNESS_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private CliRunner runner(NodeId node) {
        return new CliRunner(cli.get(node), Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
    }

    private CredentialGuard guard(NodeId node) {
        return new CredentialGuard(new CredentialVariables("INUBIT", node), clock);
    }

    private CliExportRunner exports(NodeId node) {
        return new CliExportRunner(server(node), credentials(node), guard(node), runner(node),
            new CliOutputClassifier(new SecretScrubber()));
    }

    /** The artifact port of {@code node}. */
    public ArtifactPort artifacts(NodeId node) {
        return new V81ArtifactAdapter(exports(node), () -> { });
    }

    /** The import port of {@code node}. */
    public ImportPort imports(NodeId node) {
        return new V81ImportAdapter(new CliImportRunner(server(node), credentials(node),
            guard(node), runner(node), new CliOutputClassifier(new SecretScrubber())), () -> { });
    }

    /** The tag port of {@code node}. */
    public TagPort tags(NodeId node) {
        return new V81TagAdapter(node, exports(node), new CliTagRunner(server(node),
            credentials(node), guard(node), runner(node),
            new CliOutputClassifier(new SecretScrubber())), () -> { });
    }

    /**
     * The REST inventory of {@code node}: the owner's diagram list with diagram groups, the
     * module list, and the modules each workflow runs; every read is recorded in
     * {@link #restCalls}.
     */
    public InventoryPort inventory(NodeId node) {
        FakeServer server = servers.get(node);
        return new InventoryPort() {
            @Override
            public List<InventoryItem> listDiagrams(String owner) {
                restCalls.add(node + " diagrams " + owner);
                return server.diagrams().entrySet().stream().map(e -> InventoryItem.diagram(
                    node, e.getKey(), "technical", e.getValue(), owner)).toList();
            }

            @Override
            public DiagramDetail diagramDetail(String owner, String name) {
                restCalls.add(node + " diagram " + name);
                List<String> used = server.modulesOf(name);
                List<ModuleRef> refs = new java.util.ArrayList<>();
                for (int i = 0; i < used.size(); i++) {
                    refs.add(new ModuleRef(used.get(i), "tw" + server.pluginType(used.get(i))
                        .orElse("Unknown").replace(" ", ""), Integer.toString(i + 1)));
                }
                return new DiagramDetail(name, Optional.of("technical"), Optional.empty(), refs);
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
                restCalls.add(node + " modules " + owner);
                return server.moduleTypes().entrySet().stream().map(e -> new ModuleEntry(
                    new InventoryItem(node, InventoryKind.MODULE, e.getKey(), e.getValue(),
                        e.getValue(), owner, Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty()), Optional.empty(), Optional.empty(),
                    new ConnectorFlags(false, false, false), Optional.empty())).toList();
            }
        };
    }

    // --- the deployment service (feature 005, stage 2) ----------------------------------------

    /** The codes the deployment service issues. */
    public final WriteChallengeRegistry challenges = new WriteChallengeRegistry(clock);
    /** The deployment ledger below {@link #profileHome}. */
    public final DeploymentLedger ledger;

    /** The deploy lock directory {@code <profile>/deployments}. */
    public Path deployments() {
        return profileHome.resolve("deployments");
    }

    /**
     * The chain {@code dev → int (exclude) → prod (package only)} with the nodes of this
     * harness.
     */
    public static de.dadecker.inubit.mcp.domain.model.StageChain chain(
        List<de.dadecker.inubit.mcp.domain.model.StageChain.Exclusion> exclude) {
        Map<de.dadecker.inubit.mcp.domain.model.GroupId,
            de.dadecker.inubit.mcp.domain.model.StageChain.ChainLink> targets =
            new LinkedHashMap<>();
        targets.put(INT1.group(), new de.dadecker.inubit.mcp.domain.model.StageChain.ChainLink(
            SOURCE.group(), de.dadecker.inubit.mcp.domain.model.DeployMode.EXECUTE, exclude));
        targets.put(PROD.group(), new de.dadecker.inubit.mcp.domain.model.StageChain.ChainLink(
            INT1.group(), de.dadecker.inubit.mcp.domain.model.DeployMode.PACKAGE_ONLY,
            List.of()));
        return new de.dadecker.inubit.mcp.domain.model.StageChain(targets);
    }

    /** The feature-003 checks on a rendered release below {@code root} (no server lookups). */
    public ReleasePlanner.ReleaseChecker releaseChecker() {
        de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector inspector =
            new de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector();
        return (releaseRoot, paths) -> new ArtifactCheckService(releaseRoot, inspector,
            new de.dadecker.inubit.mcp.adapter.xslt.SaxonXsltRunner(releaseRoot),
            group -> Optional.empty(), node -> {
                throw new AssertionError("no server lookups");
            }, node -> Optional.empty(), ResultLimiter.withDefaults(), clock)
            .checkPaths(paths, false);
    }

    /** The release planner on the real 8.1 archive adapters. */
    public ReleasePlanner planner() {
        return new ReleasePlanner(new ReleasePlanner.Dependencies(this::artifacts,
            this::inventory, new de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec(),
            new de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives(),
            new de.dadecker.inubit.mcp.adapter.archive.v81.V81ImportArchives(),
            releaseChecker(), node -> new ImportService.Account(OWNER, node.name()
                + ".example.test"), root, clock));
    }

    /** {@code deploy_release} on this harness with the chain {@link #chain}. */
    public DeployService deployService(
        List<de.dadecker.inubit.mcp.domain.model.StageChain.Exclusion> exclude) {
        return deployService(exclude, audit::add);
    }

    /** As {@link #deployService(List)} with the audit port {@code serviceAudit}. */
    public DeployService deployService(
        List<de.dadecker.inubit.mcp.domain.model.StageChain.Exclusion> exclude,
        de.dadecker.inubit.mcp.domain.port.AuditPort serviceAudit) {
        de.dadecker.inubit.mcp.domain.model.StageChain chain = chain(exclude);
        List<NodeId> nodes = new java.util.ArrayList<>(servers.keySet());
        DeployGuard guard = new DeployGuard(chain, new TargetResolver(nodes),
            group -> Optional.of(OWNER), audit::add, "acme", clock, java.util.UUID::randomUUID);
        return new DeployService(new DeployService.Dependencies(root, deployments(), "acme",
            guard, new ReleaseDiscovery(this::artifacts,
                new de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec(),
                new de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives(), root),
            planner(), ledger, challenges, java.time.Duration.ofMinutes(30), serviceAudit, clock,
            java.util.UUID::randomUUID, deployer(serviceAudit), history));
    }

    /** The node deployer on the real 8.1 adapters of this harness. */
    public NodeDeployer deployer(de.dadecker.inubit.mcp.domain.port.AuditPort serviceAudit) {
        return new NodeDeployer(new NodeDeployer.Dependencies(planner(),
            new de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives(),
            new de.dadecker.inubit.mcp.adapter.archive.v81.V81ImportArchives(),
            new de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec(), this::imports,
            this::tags, node -> new ImportService.Account(OWNER, node.name() + ".example.test"),
            backups, ledger, serviceAudit, "acme", clock, java.util.UUID::randomUUID, root,
            new DiagramGroupTagger(root)));
    }

    // --- scripting (per node, in launch order) -----------------------------------------------

    /** The next call on {@code node} is the release export of {@code tag}. */
    public DeployHarness exportRelease(NodeId node, String tag) {
        FakeServer server = servers.get(node);
        cli.get(node).expect("export --exportWorkflowUser '" + OWNER + "' --exportWorkflowType"
            + " 'technical' --exportWorkflowGroup '' --exportTag '" + tag + "'")
            .then(spec -> ImportHarness.write(ScriptedProcessLauncher.exportFile(spec),
                server.exportRelease(tag)))
            .replying(EXPORT_OK, "", 0);
        return this;
    }

    /**
     * The next call on {@code node} is the export of the diagram group (head); like StartCLI it
     * answers {@code Workflow group not found} if the server does not have it.
     */
    public DeployHarness exportGroup(NodeId node) {
        return exportGroup(node, GROUP);
    }

    /** As {@link #exportGroup(NodeId)} for diagram group {@code group}. */
    public DeployHarness exportGroup(NodeId node, String group) {
        FakeServer server = servers.get(node);
        cli.get(node).expect("export --exportWorkflowUser '" + OWNER + "' --exportWorkflowType"
            + " 'technical' --exportWorkflowGroup '" + group + "' --exportFile")
            .answering(spec -> {
                Optional<byte[]> export = server.exportWorkflowGroup(group);
                if (export.isEmpty()) {
                    return new ScriptedProcessLauncher.Reply("JAVA_HOME is set\nPassword: \n"
                        + "EXECUTION ERROR\nInternal INUBIT error!\n2-NOK: Workflow group not"
                        + " found: " + group + "\n", "", 1);
                }
                ImportHarness.write(ScriptedProcessLauncher.exportFile(spec), export.get());
                return new ScriptedProcessLauncher.Reply(EXPORT_OK, "", 0);
            });
        return this;
    }

    /** The next call on {@code node} is a module export ({@code NOT_FOUND} if missing). */
    public DeployHarness exportModule(NodeId node, String pluginType, String name) {
        FakeServer server = servers.get(node);
        cli.get(node).expect("export --exportModule '" + name + "' --exportModuleGroup '"
            + pluginType + "' --exportModuleUser '" + OWNER + "'")
            .answering(spec -> {
                Optional<byte[]> export = server.exportModule(pluginType, name);
                if (export.isEmpty()) {
                    return new ScriptedProcessLauncher.Reply("JAVA_HOME is set\nPassword: \n"
                        + "EXECUTION ERROR\nInternal INUBIT error!\n2-NOK: The module " + name
                        + " not found\n", "", 1);
                }
                ImportHarness.write(ScriptedProcessLauncher.exportFile(spec), export.get());
                return new ScriptedProcessLauncher.Reply(MODULE_OK, "", 0);
            });
        return this;
    }

    /**
     * The next call on {@code node} is the repository export of {@code path}; a path the server
     * does not have fails like the recorded {@code Path not found}.
     */
    public DeployHarness exportRepository(NodeId node, String path) {
        FakeServer server = servers.get(node);
        cli.get(node).expect("export --exportRepositoryPath '" + path + "'")
            .answering(spec -> {
                Optional<byte[]> zip = server.exportRepository(path);
                if (zip.isEmpty()) {
                    return new ScriptedProcessLauncher.Reply(
                        FakeProcessLauncher.fixtureText("export_repository_not_found.stdout"),
                        FakeProcessLauncher.fixtureText("export_repository_not_found.stderr")
                            .replace("//ibis:Root/jdoe/xsd/no-such-dir", "//ibis:"
                                + path.substring(1)), 1);
                }
                ImportHarness.write(ScriptedProcessLauncher.exportFile(spec), zip.get());
                return new ScriptedProcessLauncher.Reply(REPOSITORY_OK, "", 0);
            });
        return this;
    }

    /**
     * The next call on {@code node} is a workflow or module import with exactly
     * {@code options} after the file; the server applies it with the active flag the options
     * name.
     */
    public DeployHarness importApplied(NodeId node, String options) {
        FakeServer server = servers.get(node);
        Boolean active = options.contains("--importWorkflowActive") ? Boolean.TRUE
            : options.contains("--importWorkflowInactive") ? Boolean.FALSE : null;
        cli.get(node).expect(Pattern.compile("^import --importFile '[^']+' "
                + Pattern.quote(options) + "$"))
            .capturingImportFile()
            .replyingWith(spec -> server.importArchive(ImportHarness.read(
                ScriptedProcessLauncher.importFile(spec)), active));
        return this;
    }

    /** The next call on {@code node} is a repository import into {@code /Root/jdoe}. */
    public DeployHarness importRepositoryApplied(NodeId node) {
        FakeServer server = servers.get(node);
        cli.get(node).expect(Pattern.compile("^import --importFile '[^']+'"
                + " --importRepositoryPath '/Root/" + OWNER + "'$"))
            .capturingImportFile()
            .replyingWith(spec -> server.importRepository(ImportHarness.read(
                ScriptedProcessLauncher.importFile(spec)), OWNER));
        return this;
    }

    /** The next call on {@code node} is a refused import ({@code 1-NOK}). */
    public DeployHarness importRefused(NodeId node) {
        cli.get(node).expect("import ").replying("import_nok");
        return this;
    }

    /**
     * The next call on {@code node} tags diagram group {@code GRP-01} with {@code tag}; the
     * server records the tagged state (and the referenced repository files) like INUBIT.
     */
    public DeployHarness tagMoved(NodeId node, String tag) {
        return tagMoved(node, GROUP, tag);
    }

    /** As {@link #tagMoved(NodeId, String)} for diagram group {@code group}. */
    public DeployHarness tagMoved(NodeId node, String group, String tag) {
        FakeServer server = servers.get(node);
        cli.get(node).expect("tag --tagMove '" + tag + "' --tagWorkflowGroup '" + group
                + "' --tagWorkflowType 'technical' --tagUser '" + OWNER + "'")
            .then(spec -> server.tag(group, tag))
            .replying("tag_ok");
        return this;
    }

    /**
     * The next calls on {@code node} tag {@code group} with {@code tag} and read its history
     * for the verification of the tag ({@code DiagramGroupTagger}).
     */
    public DeployHarness tagVerified(NodeId node, String group, String tag) {
        tagMoved(node, group, tag);
        return exportHistory(node, group);
    }

    /** The next call on {@code node} is the history export of {@code group}. */
    public DeployHarness exportHistory(NodeId node, String group) {
        FakeServer server = servers.get(node);
        cli.get(node).expect("export --exportWorkflowUser '" + OWNER + "' --exportWorkflowType"
            + " 'technical' --exportWorkflowGroup '" + group + "' --includeHistory")
            .answering(spec -> {
                ImportHarness.write(ScriptedProcessLauncher.exportFile(spec),
                    server.exportHistory(group).orElseThrow());
                return new ScriptedProcessLauncher.Reply(EXPORT_OK, "", 0);
            });
        return this;
    }

    /** The next call on {@code node} is any export, which fails as if the node were down. */
    public DeployHarness exportFails(NodeId node) {
        cli.get(node).expect("export ").replying("unreachable");
        return this;
    }

    /** Tags {@code group} with {@code tag} on every source node, as a person did before. */
    public DeployHarness tagged(String group, String tag) {
        SOURCES.forEach(node -> servers.get(node).tag(group, tag));
        return this;
    }

    /** Fails if a node saw an unexpected call or a scripted call did not happen. */
    public void verifyComplete() {
        cli.forEach((node, launcher) -> {
            try {
                launcher.verifyComplete();
            } catch (AssertionError e) {
                throw new AssertionError(node + ": " + e.getMessage(), e);
            }
        });
    }

    /** The {@code --execCommand} lines of every node, prefixed with the node id, in node order. */
    public List<String> launches() {
        return cli.entrySet().stream().flatMap(entry -> entry.getValue().execCommands().stream()
            .map(line -> entry.getKey() + " " + line)).toList();
    }
}
