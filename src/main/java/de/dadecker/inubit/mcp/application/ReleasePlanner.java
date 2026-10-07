package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodePlan;
import de.dadecker.inubit.mcp.domain.model.NodePlan.Kind;
import de.dadecker.inubit.mcp.domain.model.NodePlan.PlanError;
import de.dadecker.inubit.mcp.domain.model.NodePlan.PlannedArtifact;
import de.dadecker.inubit.mcp.domain.model.NodePlan.Warning;
import de.dadecker.inubit.mcp.domain.model.NodePlan.WarningKind;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Plans a release on one target node (feature 005, FR-010–FR-013, FR-015a, FR-016, research
 * D-5): nothing is written to INUBIT.
 *
 * <ol>
 *   <li><b>Exclusions</b>: a workflow of an excluded diagram group or with an excluded name
 *       (glob: {@code *} within a segment, {@code **} across), a module with an excluded name or
 *       used only by excluded workflows, a repository file matching an excluded path, key
 *       material and certificates ({@link ReleaseArchivePort#keyMaterial}), and a repository
 *       file outside {@code /Root/<owner>/} (another owner's area, warning
 *       {@code OUTSIDE_OWNER_REPOSITORY}; stage 2 ruling) are {@code EXCLUDED}: never sent.
 *   <li><b>Target state</b>: the node's export of every release diagram group (a missing group
 *       is all new), of every release module not contained in them (module export, missing =
 *       new; in plugin type and name order) and of every repository file that is not excluded
 *       by a path rule (missing = new), rendered with the codec for the target group. Any
 *       export failure but {@code NOT_FOUND} fails the plan: a node that cannot be read is no
 *       partial preview.
 *   <li><b>Classes</b> ({@link ArtifactClassifier}); workflows and modules of the target's
 *       release diagram groups that the release does not have are {@code ONLY_ON_TARGET}. The
 *       resulting {@code IsActive}: an existing workflow keeps the node's, a new one takes the
 *       release's.
 *   <li><b>Errors</b> (the plan is not executable): a deployed workflow in Workbench edit mode
 *       ({@code CONFLICT}); a release workflow name in another diagram group of the node (REST
 *       diagram list; {@code PRECONDITION_FAILED}); a new module whose name the owner has with
 *       another plugin type (module list); a module a release workflow runs that is excluded or
 *       not in the release and missing on the node; key material missing on the node
 *       ({@code SECRET_UNRESOLVED}); a file outside the owner's area missing on the node
 *       ({@code PRECONDITION_FAILED}); every failure of building the import archives with the
 *       node's own secrets ({@code SECRET_UNRESOLVED} names artifact and path, never a value).
 *   <li><b>Warnings</b>: a changed property of a deployed workflow or changed module whose
 *       name or value looks stage-specific ({@code STAGE_SPECIFIC_VALUE}, names only); a
 *       changed module that workflows of the node outside the release run as well
 *       ({@code SHARED_MODULE}, module usage of feature 001, REST).
 *   <li><b>Reports</b> {@code .reports/deploy-<auditId>/<group>-<node>.diff} (the deployed
 *       artifacts, placeholders only, never key material) and {@code …txt} (the plan).
 * </ol>
 *
 * <p>{@link #checkRelease} runs the checks of feature 003 on the rendered release (FR-012a).
 */
public final class ReleasePlanner {

    private static final String REPORTS = ".reports";
    private static final String META = WorkspacePath.META_DIRECTORY + "/";
    private static final int USAGE_CONCURRENCY = 4;
    private static final java.time.Duration USAGE_BUDGET = java.time.Duration.ofSeconds(60);
    private static final DateTimeFormatter COMMENT_TIME =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    /** The checks of feature 003 on files below {@code root} (workspace-relative paths). */
    @FunctionalInterface
    public interface ReleaseChecker {
        List<CheckFinding> check(Path root, List<String> paths);
    }

    /**
     * @param accounts the INUBIT user and host of a node, for the check-in comment of the
     *                 import archives built to resolve the node's secrets
     * @param root     the workspace root (reports)
     */
    public record Dependencies(Function<NodeId, ArtifactPort> artifacts,
        Function<NodeId, InventoryPort> inventory, ArchiveCodecPort codec,
        ReleaseArchivePort releases, ImportArchivePort archives, ReleaseChecker checks,
        Function<NodeId, ImportService.Account> accounts, Path root, Clock clock) {
        public Dependencies {
            Objects.requireNonNull(artifacts, "artifacts");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(releases, "releases");
            Objects.requireNonNull(archives, "archives");
            Objects.requireNonNull(checks, "checks");
            Objects.requireNonNull(accounts, "accounts");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(clock, "clock");
        }
    }

    private final Dependencies d;
    private final ArtifactClassifier classifier;

    public ReleasePlanner(Dependencies dependencies) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
        this.classifier = new ArtifactClassifier(dependencies.releases());
    }

    /** One release module: plugin type and rendered files. */
    private record ReleaseModule(String pluginType, SortedMap<String, byte[]> files) {
    }

    /** The release split into artifacts (rendered for the target group). */
    private record Parts(Map<String, String> workflowPaths, Map<String, String> workflowGroups,
        Map<String, ReleaseModule> modules, Map<String, byte[]> repository) {
    }

    /**
     * The plan of {@code release} on {@code node}; see the class description.
     *
     * @throws ToolErrorException an export failure of the node other than {@code NOT_FOUND}
     *     ({@code UNREACHABLE}, {@code AUTH_FAILED}, {@code TIMEOUT}, {@code CLI_UNAVAILABLE},
     *     …), with the node id
     */
    public NodePlan plan(DeployGuard.Admitted admitted, ReleaseDiscovery.Release release,
        NodeId node, UUID auditId) {
        GroupId group = admitted.target();
        String owner = admitted.owner();
        Parts parts = parts(release, group);
        Map<String, List<String>> runs = new LinkedHashMap<>();
        parts.workflowPaths().forEach((name, path) -> runs.put(name, d.releases().modulesOf(
            release.files().get(path))));

        // exclusions
        Set<String> excludedWorkflows = new TreeSet<>();
        parts.workflowPaths().keySet().forEach(name -> {
            if (excluded(admitted.exclude(), StageChain.Exclusion.Kind.DIAGRAM_GROUP,
                parts.workflowGroups().get(name)) || excluded(admitted.exclude(),
                StageChain.Exclusion.Kind.NAME, name)) {
                excludedWorkflows.add(name);
            }
        });
        Set<String> usedByDeployed = new TreeSet<>();
        Set<String> usedByExcluded = new TreeSet<>();
        runs.forEach((name, modules) -> (excludedWorkflows.contains(name) ? usedByExcluded
            : usedByDeployed).addAll(modules));
        Set<String> excludedModules = new TreeSet<>();
        parts.modules().keySet().forEach(name -> {
            if (excluded(admitted.exclude(), StageChain.Exclusion.Kind.NAME, name)
                || (usedByExcluded.contains(name) && !usedByDeployed.contains(name))) {
                excludedModules.add(name);
            }
        });
        String ownArea = "/Root/" + owner + "/";
        Set<String> pathExcluded = new TreeSet<>();
        Set<String> keyMaterial = new TreeSet<>();
        Set<String> outsideOwner = new TreeSet<>();
        parts.repository().forEach((path, content) -> {
            if (excluded(admitted.exclude(), StageChain.Exclusion.Kind.REPOSITORY_PATH, path)) {
                pathExcluded.add(path);
            } else if (d.releases().keyMaterial(path, content)) {
                keyMaterial.add(path);
            } else if (!path.startsWith(ownArea)) {
                outsideOwner.add(path);
            }
        });

        // the node's state
        ArtifactPort port = d.artifacts().apply(node);
        List<byte[]> raws = new ArrayList<>();
        for (String diagramGroup : release.diagramGroups()) {
            exported(node, () -> port.exportWorkflowGroup(owner, diagramGroup))
                .ifPresent(raws::add);
        }
        SortedMap<String, byte[]> groupFiles = raws.isEmpty() ? new TreeMap<>()
            : d.codec().prepare(group, owner, raws).files();
        Set<String> inGroups = moduleNames(groupFiles);
        List<String> separately = new ArrayList<>(parts.modules().keySet().stream()
            .filter(name -> !inGroups.contains(name) && !excludedModules.contains(name))
            .toList());
        separately.sort((a, b) -> {
            int type = parts.modules().get(a).pluginType().compareTo(
                parts.modules().get(b).pluginType());
            return type != 0 ? type : a.compareTo(b);
        });
        for (String name : separately) {
            exported(node, () -> port.exportModule(owner, parts.modules().get(name)
                .pluginType(), name)).ifPresent(raws::add);
        }
        ArchiveCodecPort.PreparedExport prepared = d.codec().prepare(group, owner, raws);
        SortedMap<String, byte[]> target = new TreeMap<>(prepared.files());
        Map<String, byte[]> targetRepository = new TreeMap<>();
        for (String path : parts.repository().keySet()) {
            if (pathExcluded.contains(path)) {
                continue;
            }
            exported(node, () -> port.exportRepository(path)).map(zip -> d.releases()
                .repositoryFiles(zip).get(path)).ifPresent(content ->
                    targetRepository.put(path, content));
        }

        // classes and flags
        List<PlannedArtifact> artifacts = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        List<PlanError> errors = new ArrayList<>();
        Map<String, ArtifactClass> workflowClasses = new LinkedHashMap<>();
        parts.workflowPaths().forEach((name, path) -> {
            String diagramGroup = parts.workflowGroups().get(name);
            if (excludedWorkflows.contains(name)) {
                artifacts.add(new PlannedArtifact(Kind.WORKFLOW, name, Optional.of(diagramGroup),
                    ArtifactClass.EXCLUDED, Optional.empty(), false));
                return;
            }
            byte[] targetFile = target.get(path);
            ArtifactClass artifactClass = classifier.workflow(path, release.files().get(path),
                targetFile);
            workflowClasses.put(name, artifactClass);
            boolean kept = targetFile != null;
            Optional<Boolean> active = Optional.of(d.archives().active(kept ? targetFile
                : release.files().get(path)).orElse(false));
            artifacts.add(new PlannedArtifact(Kind.WORKFLOW, name, Optional.of(diagramGroup),
                artifactClass, active, kept));
            String user = prepared.inEditMode().get(name);
            if (user != null && artifactClass.deployed()) {
                errors.add(new PlanError(ErrorCode.CONFLICT, name, name + " is in Workbench edit"
                    + " mode on " + node + " (by " + user + "); publishing it later would"
                    + " overwrite the deployment"));
            }
        });
        Map<String, ArtifactClass> moduleClasses = new LinkedHashMap<>();
        parts.modules().forEach((name, module) -> {
            if (excludedModules.contains(name)) {
                artifacts.add(new PlannedArtifact(Kind.MODULE, name,
                    Optional.of(module.pluginType()), ArtifactClass.EXCLUDED, Optional.empty(),
                    false));
                return;
            }
            String prefix = moduleDirectory(group, owner, module.pluginType(), name);
            ArtifactClass artifactClass = classifier.module(module.files(), below(target,
                prefix));
            moduleClasses.put(name, artifactClass);
            artifacts.add(new PlannedArtifact(Kind.MODULE, name,
                Optional.of(module.pluginType()), artifactClass, Optional.empty(), false));
        });
        Map<String, ArtifactClass> repositoryClasses = new LinkedHashMap<>();
        parts.repository().forEach((path, content) -> {
            if (pathExcluded.contains(path) || keyMaterial.contains(path)
                || outsideOwner.contains(path)) {
                artifacts.add(new PlannedArtifact(Kind.REPOSITORY_FILE, path, Optional.empty(),
                    ArtifactClass.EXCLUDED, Optional.empty(), false));
                if (keyMaterial.contains(path) && !targetRepository.containsKey(path)) {
                    errors.add(new PlanError(ErrorCode.SECRET_UNRESOLVED, path, "The key"
                        + " material " + path + " is missing on " + node + "; key material is"
                        + " never deployed, the target keeps its own"));
                }
                if (outsideOwner.contains(path)) {
                    warnings.add(new Warning(WarningKind.OUTSIDE_OWNER_REPOSITORY, path,
                        "outside /Root/" + owner + "/: never deployed"));
                    if (!targetRepository.containsKey(path)) {
                        errors.add(new PlanError(ErrorCode.PRECONDITION_FAILED, path, "The"
                            + " repository file " + path + " of another owner's area is missing"
                            + " on " + node + "; the release references it but never"
                            + " deploys it"));
                    }
                }
                return;
            }
            ArtifactClass artifactClass = classifier.repository(content,
                targetRepository.get(path));
            repositoryClasses.put(path, artifactClass);
            artifacts.add(new PlannedArtifact(Kind.REPOSITORY_FILE, path, Optional.empty(),
                artifactClass, Optional.empty(), false));
        });
        onlyOnTarget(target, parts, group, owner, artifacts);

        // errors from the node's inventory and the import archives
        Lazy<Map<String, String>> moduleTypes = new Lazy<>(() -> {
            Map<String, String> types = new HashMap<>();
            d.inventory().apply(node).listModules(owner).forEach(entry -> types.put(
                entry.item().name(), entry.item().type()));
            return types;
        });
        List<InventoryItem> diagrams = d.inventory().apply(node).listDiagrams(owner);
        otherDiagramGroups(node, diagrams, parts, excludedWorkflows, errors);
        moduleClasses.forEach((name, artifactClass) -> {
            String type = artifactClass == ArtifactClass.NEW ? moduleTypes.get().get(name) : null;
            String pluginType = parts.modules().get(name).pluginType();
            if (type != null && !type.equals(pluginType)) {
                errors.add(new PlanError(ErrorCode.PRECONDITION_FAILED, name, "The module "
                    + name + " exists on " + node + " as " + type + "; the release has it as "
                    + pluginType + " (INUBIT matches modules by name)"));
            }
        });
        Set<String> onTarget = moduleNames(target);
        runs.forEach((workflow, modules) -> {
            if (excludedWorkflows.contains(workflow)) {
                return;
            }
            for (String module : modules) {
                boolean deployed = parts.modules().containsKey(module)
                    && !excludedModules.contains(module);
                if (!deployed && !onTarget.contains(module)
                    && !moduleTypes.get().containsKey(module)) {
                    errors.add(new PlanError(ErrorCode.PRECONDITION_FAILED, workflow, workflow
                        + " runs the module " + module + ", which is " + (parts.modules()
                            .containsKey(module) ? "excluded" : "not in the release")
                        + " and missing on " + node));
                }
            }
        });
        errors.addAll(assemble(admitted, release, node, parts, runs, workflowClasses,
            moduleClasses, raws));

        // warnings (T018): stage-specific values, shared modules
        workflowClasses.forEach((name, artifactClass) -> {
            String path = parts.workflowPaths().get(name);
            if (artifactClass == ArtifactClass.CHANGED || artifactClass
                == ArtifactClass.LAYOUT_ONLY) {
                stageSpecific(name, Map.of(path, release.files().get(path)), target, warnings);
            }
        });
        moduleClasses.forEach((name, artifactClass) -> {
            if (artifactClass == ArtifactClass.CHANGED) {
                stageSpecific(name, parts.modules().get(name).files(), target, warnings);
            }
        });
        sharedModules(node, owner, diagrams, parts, moduleClasses, warnings);

        String fingerprint = fingerprint(target, targetRepository, prepared.inEditMode());
        String base = REPORTS + "/deploy-" + auditId + "/" + node.group() + "-" + node.name();
        writeDiff(base + ".diff", node, release, parts, target, targetRepository, artifacts,
            group, owner);
        NodePlan plan = new NodePlan(node, artifacts, warnings, errors, fingerprint,
            artifactStates(artifacts, parts, target, targetRepository, group, owner),
            base + ".diff", base + ".txt");
        writeSummary(plan, admitted, auditId);
        return plan;
    }

    /**
     * The ERROR findings of the feature-003 checks on the rendered release (FR-012a), written to
     * {@code .reports/deploy-<auditId>/release/} for that purpose (placeholders only).
     */
    public List<PlanError> checkRelease(ReleaseDiscovery.Release release, UUID auditId) {
        Path releaseRoot = d.root().resolve(REPORTS).resolve("deploy-" + auditId)
            .resolve("release");
        Set<String> tops = new TreeSet<>();
        try {
            for (Map.Entry<String, byte[]> file : release.files().entrySet()) {
                Path path = releaseRoot.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.write(path, file.getValue());
                if (!file.getKey().startsWith(META)) {
                    tops.add(file.getKey().substring(0, file.getKey().indexOf('/')));
                }
            }
        } catch (IOException e) {
            throw reportFailure(releaseRoot, e);
        }
        List<PlanError> errors = new ArrayList<>();
        for (CheckFinding finding : d.checks().check(releaseRoot, List.copyOf(tops))) {
            if (finding.severity() == CheckFinding.Severity.ERROR) {
                errors.add(new PlanError(ErrorCode.PRECONDITION_FAILED, finding.path(),
                    finding.code() + ": " + finding.message()));
            }
        }
        return List.copyOf(errors);
    }

    // --- release parts -------------------------------------------------------------------------

    private Parts parts(ReleaseDiscovery.Release release, GroupId group) {
        Map<String, String> workflowPaths = new TreeMap<>();
        Map<String, String> workflowGroups = new HashMap<>();
        Map<String, ReleaseModule> modules = new TreeMap<>();
        release.files().forEach((path, content) -> {
            Optional<WorkspacePath> parsed = parse(path);
            if (parsed.isEmpty()) {
                return;
            }
            WorkspacePath workspacePath = parsed.get();
            switch (workspacePath.kind()) {
                case WORKFLOW -> {
                    workflowPaths.put(workspacePath.segments().get(1), path);
                    workflowGroups.put(workspacePath.segments().get(1),
                        workspacePath.segments().get(0));
                }
                case MODULE, MODULE_INDEX, EMBEDDED -> modules.computeIfAbsent(
                    workspacePath.segments().get(1), name -> new ReleaseModule(
                        workspacePath.segments().get(0), new TreeMap<>()))
                    .files().put(path, content);
                case REPOSITORY -> { }
            }
        });
        return new Parts(workflowPaths, workflowGroups, modules,
            new TreeMap<>(d.releases().repositoryFiles(release.export())));
    }

    private static Optional<WorkspacePath> parse(String path) {
        if (path.startsWith(META)) {
            return Optional.empty();
        }
        try {
            return Optional.of(WorkspacePath.parse(Path.of(path)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Set<String> moduleNames(SortedMap<String, byte[]> files) {
        Set<String> names = new TreeSet<>();
        files.keySet().forEach(path -> parse(path).filter(p -> p.kind()
            == WorkspacePath.Kind.MODULE_INDEX).ifPresent(p -> names.add(p.segments().get(1))));
        return names;
    }

    private static String moduleDirectory(GroupId group, String owner, String pluginType,
        String name) {
        Path index = WorkspacePath.moduleIndex(group, owner, pluginType, name).toRelativePath();
        return index.getParent().toString().replace('\\', '/') + "/";
    }

    private static SortedMap<String, byte[]> below(SortedMap<String, byte[]> files,
        String prefix) {
        SortedMap<String, byte[]> result = new TreeMap<>();
        files.forEach((path, content) -> {
            if (path.startsWith(prefix)) {
                result.put(path, content);
            }
        });
        return result;
    }

    // --- exclusions ----------------------------------------------------------------------------

    private static boolean excluded(List<StageChain.Exclusion> rules,
        StageChain.Exclusion.Kind kind, String value) {
        return rules.stream().filter(rule -> rule.kind() == kind).anyMatch(rule ->
            kind == StageChain.Exclusion.Kind.DIAGRAM_GROUP ? rule.pattern().equals(value)
                : glob(rule.pattern()).matcher(value).matches());
    }

    /** {@code *} any characters within a segment, {@code **} any characters. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    // --- node checks ---------------------------------------------------------------------------

    /** Workflows and modules of the target's release diagram groups that the release lacks. */
    private static void onlyOnTarget(SortedMap<String, byte[]> target, Parts parts, GroupId group,
        String owner, List<PlannedArtifact> artifacts) {
        Set<String> seen = new LinkedHashSet<>();
        target.keySet().forEach(path -> parse(path).ifPresent(p -> {
            if (p.kind() == WorkspacePath.Kind.WORKFLOW
                && !parts.workflowPaths().containsKey(p.segments().get(1))
                && seen.add("W" + p.segments().get(1))) {
                artifacts.add(new PlannedArtifact(Kind.WORKFLOW, p.segments().get(1),
                    Optional.of(p.segments().get(0)), ArtifactClass.ONLY_ON_TARGET,
                    Optional.empty(), false));
            }
            if (p.kind() == WorkspacePath.Kind.MODULE_INDEX
                && !parts.modules().containsKey(p.segments().get(1))
                && seen.add("M" + p.segments().get(1))) {
                artifacts.add(new PlannedArtifact(Kind.MODULE, p.segments().get(1),
                    Optional.of(p.segments().get(0)), ArtifactClass.ONLY_ON_TARGET,
                    Optional.empty(), false));
            }
        }));
    }

    /** A release workflow that the node has in another diagram group (REST diagram list). */
    private static void otherDiagramGroups(NodeId node, List<InventoryItem> diagrams,
        Parts parts, Set<String> excludedWorkflows, List<PlanError> errors) {
        for (InventoryItem item : diagrams) {
            String releaseGroup = parts.workflowGroups().get(item.name());
            if (releaseGroup != null && !excludedWorkflows.contains(item.name())
                && !releaseGroup.equals(item.group())) {
                errors.add(new PlanError(ErrorCode.PRECONDITION_FAILED, item.name(),
                    item.name() + " exists on " + node + " in diagram group " + item.group()
                        + ", the release has it in " + releaseGroup + "; INUBIT matches"
                        + " workflows by name and would move it"));
            }
        }
    }

    /**
     * Builds the import archives of the node in memory (D-7: one per diagram group with its
     * deployed workflows and their deployed modules, one for the remaining deployed modules)
     * with the node's own secrets; every failure becomes an error. The archives are dropped.
     */
    private List<PlanError> assemble(DeployGuard.Admitted admitted,
        ReleaseDiscovery.Release release, NodeId node, Parts parts,
        Map<String, List<String>> runs, Map<String, ArtifactClass> workflowClasses,
        Map<String, ArtifactClass> moduleClasses, List<byte[]> raws) {
        ImportService.Account account = d.accounts().apply(node);
        String reason = "deploy " + admitted.tag() + " from " + admitted.source();
        String time = COMMENT_TIME.format(d.clock().instant().atZone(ZoneId.systemDefault()));
        Set<String> remaining = new TreeSet<>();
        moduleClasses.forEach((name, artifactClass) -> {
            if (artifactClass.deployed()) {
                remaining.add(name);
            }
        });
        Map<String, List<String>> byGroup = new TreeMap<>();
        workflowClasses.forEach((name, artifactClass) -> {
            if (artifactClass.deployed()) {
                byGroup.computeIfAbsent(parts.workflowGroups().get(name),
                    g -> new ArrayList<>()).add(name);
            }
        });
        List<PlanError> errors = new ArrayList<>();
        byGroup.forEach((diagramGroup, workflows) -> {
            List<String> modules = new ArrayList<>();
            workflows.forEach(workflow -> runs.get(workflow).stream().filter(remaining::contains)
                .forEach(modules::add));
            remaining.removeAll(modules);
            build(admitted, release, Optional.of(diagramGroup), workflows.stream().map(name ->
                new ImportArchivePort.Artifact(name, Optional.empty(), workflowClasses.get(name)
                    == ArtifactClass.NEW)).toList(), modules(parts, moduleClasses,
                    new ArrayList<>(new LinkedHashSet<>(modules))), raws, reason, account, time,
                diagramGroup, errors);
        });
        if (!remaining.isEmpty()) {
            build(admitted, release, Optional.empty(), List.of(), modules(parts, moduleClasses,
                List.copyOf(remaining)), raws, reason, account, time, "modules", errors);
        }
        return errors;
    }

    private static List<ImportArchivePort.Artifact> modules(Parts parts,
        Map<String, ArtifactClass> classes, List<String> names) {
        return names.stream().map(name -> new ImportArchivePort.Artifact(name,
            Optional.of(parts.modules().get(name).pluginType()),
            classes.get(name) == ArtifactClass.NEW)).toList();
    }

    private void build(DeployGuard.Admitted admitted, ReleaseDiscovery.Release release,
        Optional<String> diagramGroup, List<ImportArchivePort.Artifact> workflows,
        List<ImportArchivePort.Artifact> modules, List<byte[]> raws, String reason,
        ImportService.Account account, String time, String scope, List<PlanError> errors) {
        try {
            d.archives().assemble(new ImportArchivePort.Build(admitted.target(),
                admitted.owner(), diagramGroup, workflows, modules, release.files(), raws,
                reason, account.user(), account.host(), time, Set.of(), true));
        } catch (ToolErrorException e) {
            errors.add(new PlanError(e.error().code(), scope, e.error().message()));
        }
    }

    // --- warnings ------------------------------------------------------------------------------

    /**
     * {@code STAGE_SPECIFIC_VALUE} for {@code artifact} if a changed simple property of one of
     * its files looks like a host, URL, port or login ({@link StageValueHeuristics}); the
     * warning names the properties, the values are in the difference file only.
     */
    private void stageSpecific(String artifact, Map<String, byte[]> files,
        SortedMap<String, byte[]> target, List<Warning> warnings) {
        Set<String> properties = new LinkedHashSet<>();
        files.forEach((path, content) -> {
            byte[] before = target.get(path);
            if (before != null) {
                d.releases().changedProperties(path, content, before).forEach(change -> {
                    if (StageValueHeuristics.looksStageSpecific(change.property(),
                        change.releaseValue(), change.targetValue())) {
                        properties.add(change.property());
                    }
                });
            }
        });
        if (!properties.isEmpty()) {
            warnings.add(new Warning(WarningKind.STAGE_SPECIFIC_VALUE, artifact,
                "stage-specific value? changed propert" + (properties.size() == 1 ? "y " : "ies ")
                    + String.join(", ", properties) + " (the release's value is deployed)"));
        }
    }

    /**
     * {@code SHARED_MODULE} for a changed module that workflows of the node outside the release
     * run as well (module usage of feature 001: the nodes of each such workflow, REST).
     */
    private void sharedModules(NodeId node, String owner, List<InventoryItem> diagrams,
        Parts parts, Map<String, ArtifactClass> moduleClasses, List<Warning> warnings) {
        List<String> changed = moduleClasses.entrySet().stream()
            .filter(e -> e.getValue() == ArtifactClass.CHANGED).map(Map.Entry::getKey).toList();
        List<String> outside = diagrams.stream().map(InventoryItem::name)
            .filter(name -> !parts.workflowPaths().containsKey(name)).distinct().toList();
        if (changed.isEmpty() || outside.isEmpty()) {
            return;
        }
        ModuleUsage usage = ModuleUsageIndexer.build(node, outside, workflow -> d.inventory()
            .apply(node).diagramDetail(owner, workflow).modules(), USAGE_CONCURRENCY,
            USAGE_BUDGET);
        for (String module : changed) {
            Set<String> users = new TreeSet<>(ModuleUsage.ORDER);
            users.addAll(usage.uses().getOrDefault(module, Map.of()).keySet());
            if (!users.isEmpty()) {
                warnings.add(new Warning(WarningKind.SHARED_MODULE, module, "shared module,"
                    + " also used by " + String.join(", ", users) + " on " + node));
            } else if (!usage.complete()) {
                warnings.add(new Warning(WarningKind.SHARED_MODULE, module, "the module usage"
                    + " of " + node + " is incomplete; other workflows may use it"));
            }
        }
    }

    // --- state and reports ---------------------------------------------------------------------

    /** The fingerprint of the node's current version of every release artifact it has. */
    private Map<String, String> artifactStates(List<PlannedArtifact> artifacts, Parts parts,
        SortedMap<String, byte[]> target, Map<String, byte[]> targetRepository, GroupId group,
        String owner) {
        Map<String, String> states = new TreeMap<>();
        for (PlannedArtifact artifact : artifacts) {
            SortedMap<String, byte[]> files = new TreeMap<>();
            switch (artifact.kind()) {
                case WORKFLOW -> {
                    String path = parts.workflowPaths().get(artifact.name());
                    if (path != null && target.containsKey(path)) {
                        files.put(path, target.get(path));
                    }
                }
                case MODULE -> files.putAll(below(target, moduleDirectory(group, owner,
                    artifact.group().orElseThrow(), artifact.name())));
                case REPOSITORY_FILE -> {
                    byte[] content = targetRepository.get(artifact.name());
                    if (content != null) {
                        files.put("repository:" + artifact.name(), content);
                    }
                }
            }
            if (!files.isEmpty() && artifact.artifactClass() != ArtifactClass.ONLY_ON_TARGET) {
                states.put(NodePlan.key(artifact), ConflictDetector.fingerprint(
                    ReleaseDiscovery.canonical(d.releases(), files)));
            }
        }
        return states;
    }

    private String fingerprint(SortedMap<String, byte[]> target,
        Map<String, byte[]> repository, Map<String, String> editMode) {
        SortedMap<String, byte[]> state = ReleaseDiscovery.canonical(d.releases(), target);
        repository.forEach((path, content) -> state.put("repository:" + path, content));
        editMode.forEach((workflow, user) -> state.put("editMode:" + workflow,
            user.getBytes(StandardCharsets.UTF_8)));
        return ConflictDetector.fingerprint(state);
    }

    private void writeDiff(String relative, NodeId node, ReleaseDiscovery.Release release,
        Parts parts, SortedMap<String, byte[]> target, Map<String, byte[]> targetRepository,
        List<PlannedArtifact> artifacts, GroupId group, String owner) {
        StringBuilder out = new StringBuilder();
        for (PlannedArtifact artifact : artifacts) {
            if (!artifact.artifactClass().deployed()) {
                continue;
            }
            switch (artifact.kind()) {
                case WORKFLOW -> {
                    String path = parts.workflowPaths().get(artifact.name());
                    diff(out, path, node, target.get(path), release.files().get(path));
                }
                case MODULE -> {
                    String prefix = moduleDirectory(group, owner, artifact.group().orElseThrow(),
                        artifact.name());
                    SortedMap<String, byte[]> before = below(target, prefix);
                    SortedMap<String, byte[]> after = parts.modules().get(artifact.name())
                        .files();
                    TreeSet<String> paths = new TreeSet<>(before.keySet());
                    paths.addAll(after.keySet());
                    paths.forEach(path -> {
                        if (before.get(path) == null || after.get(path) == null
                            || !d.releases().equivalent(path, after.get(path),
                                before.get(path))) {
                            diff(out, path, node, before.get(path), after.get(path));
                        }
                    });
                }
                case REPOSITORY_FILE -> diff(out, "repository:" + artifact.name(), node,
                    targetRepository.get(artifact.name()), parts.repository()
                        .get(artifact.name()));
            }
        }
        write(relative, out.toString());
    }

    /** The lines between the common head and tail of the node's and the release's file. */
    private static void diff(StringBuilder out, String path, NodeId node, byte[] before,
        byte[] after) {
        out.append("--- ").append(path).append(" (").append(node).append(before == null
            ? ": missing" : "").append(")\n");
        out.append("+++ ").append(path).append(" (release").append(after == null
            ? ": missing" : "").append(")\n");
        List<String> a = lines(before);
        List<String> b = lines(after);
        int head = 0;
        while (head < a.size() && head < b.size() && a.get(head).equals(b.get(head))) {
            head++;
        }
        int tail = 0;
        while (tail < a.size() - head && tail < b.size() - head
            && a.get(a.size() - 1 - tail).equals(b.get(b.size() - 1 - tail))) {
            tail++;
        }
        out.append("@@ line ").append(head + 1).append(" @@\n");
        a.subList(head, a.size() - tail).forEach(line -> out.append('-').append(line)
            .append('\n'));
        b.subList(head, b.size() - tail).forEach(line -> out.append('+').append(line)
            .append('\n'));
    }

    private static List<String> lines(byte[] content) {
        return content == null ? List.of()
            : new String(content, StandardCharsets.UTF_8).lines().toList();
    }

    private void writeSummary(NodePlan plan, DeployGuard.Admitted admitted, UUID auditId) {
        StringBuilder out = new StringBuilder();
        out.append("Deployment preview ").append(auditId).append(": ").append(admitted.tag())
            .append(" from ").append(admitted.source()).append(" into ").append(plan.node())
            .append(plan.executable() ? " (executable)" : " (NOT executable)").append('\n');
        plan.counts().forEach((artifactClass, count) -> out.append(artifactClass).append(": ")
            .append(count).append('\n'));
        out.append('\n');
        plan.artifacts().forEach(artifact -> {
            out.append(artifact.artifactClass()).append(' ').append(artifact.kind()).append(' ')
                .append(artifact.name());
            artifact.group().ifPresent(group -> out.append(" [").append(group).append(']'));
            artifact.active().ifPresent(active -> out.append(" active: ").append(active)
                .append(artifact.kept() ? " (kept)" : " (from the release)"));
            out.append('\n');
        });
        if (!plan.warnings().isEmpty()) {
            out.append("\nWarnings:\n");
            plan.warnings().forEach(warning -> out.append("  - ").append(warning.kind())
                .append(' ').append(warning.artifact()).append(": ").append(warning.detail())
                .append('\n'));
        }
        if (!plan.errors().isEmpty()) {
            out.append("\nErrors:\n");
            plan.errors().forEach(error -> out.append("  - ").append(error.code()).append(' ')
                .append(error.artifact()).append(": ").append(error.message()).append('\n'));
        }
        write(plan.summaryFile(), out.toString());
    }

    /** Rewrites the summary with the final warnings and errors (after the service added some). */
    void rewriteSummary(NodePlan plan, DeployGuard.Admitted admitted, UUID auditId) {
        writeSummary(plan, admitted, auditId);
    }

    private void write(String relative, String text) {
        Path file = d.root().resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw reportFailure(file, e);
        }
    }

    private static ToolErrorException reportFailure(Path file, IOException e) {
        return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
            "The report " + file + " cannot be written (" + e.getClass().getSimpleName()
                + "); nothing was sent",
            "The workspace is not writable", "Check the workspace directory"));
    }

    /** {@code export}, empty for {@code NOT_FOUND}; other failures name the node. */
    private static Optional<byte[]> exported(NodeId node,
        java.util.function.Supplier<byte[]> export) {
        try {
            return Optional.of(export.get());
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(node));
        }
    }

    /** A value computed once, on first use. */
    private static final class Lazy<T> {
        private final java.util.function.Supplier<T> supplier;
        private T value;

        Lazy(java.util.function.Supplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            if (value == null) {
                value = supplier.get();
            }
            return value;
        }
    }
}
