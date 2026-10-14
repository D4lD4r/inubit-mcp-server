package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactRef;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.RepositoryClosure;
import de.dadecker.inubit.mcp.domain.model.RepositoryPaths;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort.LocalChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The change set of one import scope (feature 004, research D-4, D-24, D-25, data-model.md →
 * ChangeSet), from the committed workspace (the caller records uncommitted edits as local
 * changes first):
 *
 * <ul>
 *   <li>Candidates are the owner's files whose newest history entry is not a server state of the
 *       group ({@link VersionHistoryPort#localChanges}); a candidate counts only if its reviewed
 *       content ({@link ContentEquivalence}) differs from the newest server state of its
 *       artifact (an undone edit is no change). That is the state of the workflow file or of the
 *       module directory (0.4.2): an export that wrote a file unchanged is no history entry of
 *       the file, but of its module.
 *   <li>Diagram-group scope: the changed and new workflows of the diagram group, and the changed
 *       or new modules that these workflows reference; the unchanged modules of the workspace
 *       they reference are listed as {@link ChangeSet#referenced()} (0.4.3). Module scope: the
 *       named modules (each must be in the workspace). Every other change of the owner is listed
 *       as not imported.
 *   <li>Repository files (feature 008, research D-3, D-4): for a repository-paths scope the
 *       changed files at or below the paths; for a diagram-group or module scope the changed files
 *       in the {@link RepositoryClosure} of the scope's modules (for a diagram group: every module
 *       its workspace workflows use, changed or not, and those workflows, as the export starts
 *       its closure). Every other repository change of the owner is listed as not imported.
 *   <li>A modified artifact's base is its own last server state; a new artifact (never in a
 *       server state) has none and takes the scope's (review M4). A repository file's base is
 *       its own last server state ({@link LocalChange#serverState}); without one it is new. A
 *       repository-paths scope takes the owner's newest server state as its base. Without any
 *       server state of the scope: {@code PRECONDITION_FAILED} "export the scope first".
 *   <li>Refused with {@code INVALID_INPUT}: a deleted file of an artifact in the scope (deleting
 *       artifacts is not supported), a new module without {@code index.xml} or
 *       {@code module.xml}, any change below another owner's repository root; for the repository
 *       files of the scope: a deletion, a path StartCLI refuses ({@link RepositoryPaths#passable}),
 *       more than 50 modified or 200 files, a file over 10 MiB, key material
 *       ({@link ReleaseArchivePort#keyMaterial}) and a {@code ${secret:…}} placeholder.
 *       {@code PRECONDITION_FAILED} "export the path first" for a modified repository file whose
 *       {@code .meta} record has no readable metadata.
 * </ul>
 */
public final class ChangeSetBuilder {

    private static final String INDEX = "index.xml";
    private static final String MODULE = "module.xml";
    private static final String TECHNICAL = "technical";
    private static final String ROOT = "Root";
    private static final int MAX_MODIFIED_FILES = 50;
    private static final int MAX_REPOSITORY_FILES = 200;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final String SECRET = "${secret:";

    private final Path root;
    private final VersionHistoryPort history;
    private final ArtifactInspectorPort inspector;
    private final ReleaseArchivePort releases;
    private final ContentEquivalence equivalence;

    /** Compares byte by byte. */
    public ChangeSetBuilder(Path root, VersionHistoryPort history,
        ArtifactInspectorPort inspector, ReleaseArchivePort releases) {
        this(root, history, inspector, releases, ContentEquivalence.BYTES);
    }

    /**
     * @param root        the workspace root
     * @param history     its history
     * @param inspector   reads the workflows (referenced modules), the repository references
     *                    and the metadata of repository files
     * @param releases    recognises key material ({@link ReleaseArchivePort#keyMaterial})
     * @param equivalence what counts as the same content
     */
    public ChangeSetBuilder(Path root, VersionHistoryPort history,
        ArtifactInspectorPort inspector, ReleaseArchivePort releases,
        ContentEquivalence equivalence) {
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
        this.root = Objects.requireNonNull(root, "root");
        this.history = Objects.requireNonNull(history, "history");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.releases = Objects.requireNonNull(releases, "releases");
    }

    /**
     * The change set of {@code scope}.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} without a base export or for a
     *     modified repository file without readable metadata, {@code INVALID_INPUT} for
     *     deletions, an incomplete new module, a named module that is not in the workspace and
     *     the repository refusals (see the class description)
     */
    public ChangeSet build(ImportScope scope) {
        GroupId group = scope.group();
        String ownerDirectory = ownerDirectory(scope);
        String scopeDirectory = scopeDirectory(scope, ownerDirectory);
        String base = history.serverStateOf(group, scopeDirectory);

        List<LocalChange> changes = history.localChanges(group, ownerDirectory).stream()
            .map(change -> withRecordState(group, change))
            .filter(change -> differs(group, change)).toList();
        Map<String, LocalChange> repository = new TreeMap<>();
        List<String> foreign = new ArrayList<>();
        Map<String, LocalChange> workflows = new TreeMap<>();
        Map<List<String>, List<LocalChange>> modules = new TreeMap<>(
            Comparator.comparing((List<String> key) -> key.get(1))
                .thenComparing(key -> key.get(0)));
        Set<String> notImported = new TreeSet<>();
        for (LocalChange change : changes) {
            Optional<WorkspacePath> parsed = parse(change.path());
            if (parsed.isEmpty() || !parsed.get().owner().equals(scope.owner())) {
                notImported.add(change.path());
                continue;
            }
            WorkspacePath path = parsed.get();
            switch (path.kind()) {
                case REPOSITORY -> {
                    if (path.segments().size() > 2 && path.segments().get(0).equals(ROOT)
                        && path.segments().get(1).equals(scope.owner())) {
                        repository.put(change.path(), change);
                    } else {
                        foreign.add(change.path());
                    }
                }
                case WORKFLOW -> {
                    if (scope.diagramGroup().filter(path.segments().get(0)::equals).isPresent()) {
                        workflows.put(change.path(), change);
                    } else {
                        notImported.add(change.path());
                    }
                }
                default -> modules.computeIfAbsent(List.of(path.segments().get(0),
                    path.segments().get(1)), key -> new ArrayList<>()).add(change);
            }
        }
        if (!foreign.isEmpty()) {
            throw invalid("Repository files outside /Root/" + scope.owner() + "/ are never"
                    + " imported (" + String.join(", ", cut(foreign)) + "); nothing was sent",
                "Only the owner's own repository root is written; the files of another owner"
                    + " belong to that owner",
                "Remove the files from the workspace, or restore them (git restore)");
        }

        List<ChangedArtifact> changedWorkflows = new ArrayList<>();
        Set<String> referenced = new HashSet<>();
        for (LocalChange change : workflows.values()) {
            WorkspacePath path = parse(change.path()).orElseThrow();
            String name = path.segments().get(1);
            if (change.kind() == PathChange.Kind.DELETED) {
                throw deleted(name, change.path());
            }
            changedWorkflows.add(new ChangedArtifact(ArtifactRef.workflow(group, scope.owner(),
                path.segments().get(0), name), kind(change.serverState()), List.of(
                    change.path()), change.serverState()));
            WorkflowGraph graph = inspector.workflow(root.resolve(change.path()));
            graph.nodes().stream().filter(node -> node.moduleType().equals(TECHNICAL))
                .forEach(node -> referenced.add(node.moduleName()));
        }

        Set<List<String>> wanted = new HashSet<>();
        for (ImportScope.Module module : scope.modules()) {
            wanted.add(locate(scope, ownerDirectory, module));
        }
        List<ChangedArtifact> changedModules = new ArrayList<>();
        modules.forEach((key, files) -> {
            boolean inScope = scope.diagramGroup().isPresent() ? referenced.contains(key.get(1))
                : wanted.contains(key);
            if (!inScope) {
                files.forEach(change -> notImported.add(change.path()));
                return;
            }
            changedModules.add(module(scope, key, files));
        });
        Set<String> sent = new HashSet<>();
        changedModules.forEach(module -> sent.add(module.name()));
        List<ArtifactRef> unchanged = new ArrayList<>();
        new TreeSet<>(referenced).stream().filter(name -> !sent.contains(name))
            .forEach(name -> unchanged.addAll(workspaceModules(scope, ownerDirectory, name)));
        List<ChangedArtifact> changedRepository = repository.isEmpty() ? List.of()
            : repository(scope, ownerDirectory, scopeDirectory, wanted, repository, notImported);
        return new ChangeSet(scope, base, changedWorkflows, changedModules, changedRepository,
            List.copyOf(notImported), List.of(), unchanged);
    }

    /**
     * The repository files of the scope among {@code changes} (workspace path → change), checked
     * (see the class description); the others are added to {@code notImported}.
     */
    private List<ChangedArtifact> repository(ImportScope scope, String ownerDirectory,
        String scopeDirectory, Set<List<String>> wanted, Map<String, LocalChange> changes,
        Set<String> notImported) {
        Predicate<String> inScope = scope.repositoryPaths().isEmpty()
            ? closure(scope, start(scope, ownerDirectory, scopeDirectory, wanted))
                .included()::contains
            : inPaths(scope);
        List<LocalChange> files = new ArrayList<>();
        changes.forEach((path, change) -> {
            if (inScope.test(fullPath(change))) {
                files.add(change);
            } else {
                notImported.add(path);
            }
        });
        long modified = files.stream().filter(change -> change.serverState().isPresent())
            .count();
        for (LocalChange change : files) {
            if (change.kind() == PathChange.Kind.DELETED
                || !Files.isRegularFile(root.resolve(change.path()))) {
                throw invalid("deleting repository files is not supported: " + fullPath(change)
                        + " (" + change.path() + ") was deleted in the workspace; nothing was"
                        + " sent",
                    "The development tools never delete anything in an INUBIT repository",
                    "restore the file (git restore " + change.path() + ") or delete it in the"
                        + " Workbench");
            }
        }
        if (modified > MAX_MODIFIED_FILES || files.size() > MAX_REPOSITORY_FILES) {
            throw invalid("The scope holds " + files.size() + " changed repository files, "
                    + modified + " of them modified; at most " + MAX_MODIFIED_FILES
                    + " modified and " + MAX_REPOSITORY_FILES + " in total are imported in one"
                    + " call; nothing was sent",
                "The limits keep the node read, the preview and the rollback bounded",
                "Import narrower repository paths in several calls");
        }
        List<ChangedArtifact> result = new ArrayList<>();
        for (LocalChange change : files) {
            result.add(repositoryFile(scope, change));
        }
        return result;
    }

    /** A repository file of the scope, checked before anything is read from the node. */
    private ChangedArtifact repositoryFile(ImportScope scope, LocalChange change) {
        WorkspacePath path = parse(change.path()).orElseThrow();
        String full = fullPath(change);
        if (path.segments().stream().anyMatch(segment -> segment.contains("/"))
            || !RepositoryPaths.passable(full)) {
            throw invalid("The repository path of " + change.path() + " is not one StartCLI can"
                    + " take; nothing was sent",
                "StartCLI takes path segments of letters, digits, '_', '.', '-' and spaces that"
                    + " do not start with '-' or a space",
                "Rename the file in the workspace");
        }
        Path file = root.resolve(change.path());
        byte[] content;
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                throw invalid(full + " has " + size + " bytes, more than 10 MiB; nothing was"
                        + " sent",
                    "One repository file is imported with at most 10 MiB",
                    "Change the file in the Workbench");
            }
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (releases.keyMaterial(full, content)) {
            throw invalid(full + " is key material (a keystore, private key or certificate);"
                    + " nothing was sent",
                "Key material is never written to an INUBIT repository by this server",
                "Restore or remove the file in the workspace (git restore " + change.path()
                    + ") and manage key material in the Workbench");
        }
        if (new String(content, StandardCharsets.ISO_8859_1).contains(SECRET)) {
            throw invalid(full + " contains a " + SECRET + "…} placeholder; nothing was sent",
                "Placeholders are resolved only in module properties, never in repository"
                    + " files",
                "Write the value into the file through the Workbench, or remove the"
                    + " placeholder");
        }
        if (change.serverState().isPresent()
            && inspector.repositoryMetadata(root, path).isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The .meta record of " + full + " (" + slash(path.metaPath()) + ") is missing or"
                    + " its metadata is not readable; nothing was sent",
                "Without the exported metadata the repository file cannot be rebuilt faithfully",
                "export the path first (export_artifacts with repositoryPaths), reapply the edit"
                    + " from the workspace history, then repeat the call"));
        }
        return new ChangedArtifact(ArtifactRef.repositoryFile(scope.group(), scope.owner(),
            full), kind(change.serverState()), List.of(change.path()), change.serverState());
    }

    /**
     * Where the repository files {@code paths} (full repository paths, the files to send) are
     * used (feature 008, FR-011, FR-012, edge cases "Shared use" and "Reference chains"):
     *
     * <ul>
     *   <li>{@code referrers}: every file of the group's workspace, of every owner, is scanned
     *       once, and only if {@code paths} is not empty; a file uses a repository file if it
     *       references it directly or through a chain of repository files. The artifacts of the
     *       scope (for a diagram group its workflows and every module they use, for a module
     *       scope the modules, and the owner's repository files of the scope's closure or below
     *       the repository paths) are left out; the artifacts of other owners never belong to
     *       the scope.
     *   <li>{@code outsideOwner}: the references the closure of the scope (for repository paths:
     *       of {@code paths}) did not follow because they leave the owner's root.
     * </ul>
     *
     * @throws ToolErrorException {@code INVALID_INPUT} as {@link #build} for a named module that
     *     is not in the workspace or an owner StartCLI cannot take
     */
    RepositoryUse repositoryUse(ImportScope scope, Collection<String> paths) {
        String ownerDirectory = ownerDirectory(scope);
        Set<String> inScope = new HashSet<>();
        Predicate<String> repositoryInScope;
        RepositoryClosure closure;
        if (scope.repositoryPaths().isEmpty()) {
            Set<List<String>> wanted = new HashSet<>();
            for (ImportScope.Module module : scope.modules()) {
                wanted.add(locate(scope, ownerDirectory, module));
            }
            List<String> start = start(scope, ownerDirectory, scopeDirectory(scope,
                ownerDirectory), wanted);
            inScope.addAll(start);
            closure = closure(scope, start);
            repositoryInScope = closure.included()::contains;
        } else {
            repositoryInScope = inPaths(scope);
            closure = closure(scope, paths.stream().map(path -> slash(WorkspacePath.repository(
                scope.group(), scope.owner(), path).toRelativePath())).toList());
        }

        Map<String, List<WorkspacePath>> usedBy = new HashMap<>();
        for (String file : paths.isEmpty() ? List.<String>of() : files(scope.group().value())) {
            parse(file).ifPresent(from -> inspector.repositoryReferences(from,
                read(root.resolve(file))).forEach(reference -> usedBy.computeIfAbsent(
                    reference.to(), to -> new ArrayList<>()).add(from)));
        }
        SortedMap<String, SortedSet<String>> referrers = new TreeMap<>();
        for (String path : paths) {
            SortedSet<String> names = new TreeSet<>();
            Set<String> seen = new HashSet<>(List.of(path));
            Deque<String> worklist = new ArrayDeque<>(seen);
            while (!worklist.isEmpty()) {
                for (WorkspacePath from : usedBy.getOrDefault(worklist.removeFirst(), List.of())) {
                    boolean own = from.owner().equals(scope.owner());
                    if (from.kind() == WorkspacePath.Kind.REPOSITORY) {
                        String full = "/" + String.join("/", from.segments());
                        if (seen.add(full)) {
                            worklist.add(full);
                        }
                        if (!own || !repositoryInScope.test(full)) {
                            names.add(artifactName(scope.owner(), from));
                        }
                    } else if (!own || !inScope.contains(slash(from.toRelativePath()))) {
                        names.add(artifactName(scope.owner(), from));
                    }
                }
            }
            names.remove(path);
            if (!names.isEmpty()) {
                referrers.put(path, names);
            }
        }
        return new RepositoryUse(referrers, closure.outsideOwner());
    }

    /**
     * See {@link #repositoryUse}.
     *
     * @param referrers    full repository path → the names of the workspace artifacts outside
     *                     the scope that use it ({@link ImportPreview.RepositoryReferences}); only
     *                     paths with any
     * @param outsideOwner the references that were not followed
     */
    record RepositoryUse(SortedMap<String, SortedSet<String>> referrers,
        List<RepositoryReference> outsideOwner) {

        RepositoryUse {
            referrers = Collections.unmodifiableSortedMap(new TreeMap<>(referrers));
            outsideOwner = List.copyOf(outsideOwner);
        }
    }

    /**
     * The name of the artifact of {@code file} in {@link RepositoryUse#referrers}: a workflow as
     * {@code <workflow> (<diagram group>)}, a module as {@code <module> (<plugin type> module)},
     * a repository file by its full path; another owner's artifact with {@code owner <name>}
     * (e.g. {@code Mapping-0007 (GRP-02, owner bob)}, {@code /Root/bob/y.xsd (owner bob)}).
     */
    private static String artifactName(String owner, WorkspacePath file) {
        List<String> segments = file.segments();
        String other = file.owner().equals(owner) ? "" : "owner " + file.owner();
        return switch (file.kind()) {
            case WORKFLOW -> segments.get(1) + " (" + segments.get(0)
                + (other.isEmpty() ? "" : ", " + other) + ")";
            case REPOSITORY -> "/" + String.join("/", segments)
                + (other.isEmpty() ? "" : " (" + other + ")");
            default -> segments.get(1) + " (" + segments.get(0) + " module"
                + (other.isEmpty() ? "" : ", " + other) + ")";
        };
    }

    /** {@code <group>/<owner>}, workspace-relative. */
    private static String ownerDirectory(ImportScope scope) {
        return scope.group().value() + "/" + NameCodec.encode(scope.owner());
    }

    /** The directory whose last server state is the base of the scope. */
    private static String scopeDirectory(ImportScope scope, String ownerDirectory) {
        return scope.diagramGroup()
            .map(name -> slash(WorkspacePath.workflow(scope.group(), scope.owner(), name, "x")
                .toRelativePath().getParent()))
            .orElse(ownerDirectory + (scope.repositoryPaths().isEmpty() ? "/modules" : ""));
    }

    /** True for a full repository path at or below the repository paths of {@code scope}. */
    private static Predicate<String> inPaths(ImportScope scope) {
        List<String> prefixes = new ArrayList<>();
        for (String relative : scope.repositoryPaths()) {
            try {
                prefixes.add(RepositoryPaths.full(scope.owner(), relative));
            } catch (IllegalArgumentException e) {
                throw invalid("The owner " + scope.owner() + " is no repository root StartCLI"
                        + " can take; nothing was sent",
                    "Repository paths are imported below /Root/<owner>",
                    "Give an owner of letters, digits, '_', '.', '-' and spaces");
            }
        }
        return full -> prefixes.stream().anyMatch(prefix -> full.equals(prefix)
            || full.startsWith(prefix + "/"));
    }

    /**
     * The workspace files a diagram-group or module scope starts its closure from (research
     * D-3): for a diagram group the workflows of {@code scopeDirectory} and every file of every
     * module they use, for a module scope every file of the modules {@code wanted}.
     */
    private List<String> start(ImportScope scope, String ownerDirectory, String scopeDirectory,
        Set<List<String>> wanted) {
        List<String> start = new ArrayList<>();
        if (scope.diagramGroup().isPresent()) {
            Set<String> used = new TreeSet<>();
            for (String workflow : files(scopeDirectory)) {
                if (parse(workflow).filter(p -> p.kind() == WorkspacePath.Kind.WORKFLOW)
                    .isEmpty()) {
                    continue;
                }
                start.add(workflow);
                inspector.workflow(root.resolve(workflow)).nodes().stream()
                    .filter(node -> node.moduleType().equals(TECHNICAL))
                    .forEach(node -> used.add(node.moduleName()));
            }
            for (String name : used) {
                moduleDirectories(ownerDirectory, name).forEach(dir -> start.addAll(files(dir)));
            }
        } else {
            for (List<String> key : wanted) {
                start.addAll(files(slash(WorkspacePath.moduleIndex(scope.group(), scope.owner(),
                    key.get(0), key.get(1)).toRelativePath().getParent())));
            }
        }
        return start;
    }

    /** The repository files that the workspace files {@code start} reach (research D-3). */
    private RepositoryClosure closure(ImportScope scope, List<String> start) {
        List<RepositoryReference> references = new ArrayList<>();
        for (String file : start) {
            parse(file).ifPresent(path -> references.addAll(inspector.repositoryReferences(path,
                read(root.resolve(file)))));
        }
        return RepositoryClosure.of(scope.owner(), references, repositoryPath -> {
            WorkspacePath path = WorkspacePath.repository(scope.group(), scope.owner(),
                repositoryPath);
            Path file = root.resolve(path.toRelativePath());
            return Files.isRegularFile(file)
                ? Optional.of(inspector.repositoryReferences(path, read(file)))
                : Optional.empty();
        });
    }

    /** The directories of the modules named {@code name} in the owner's workspace. */
    private List<String> moduleDirectories(String ownerDirectory, String name) {
        Path modules = root.resolve(ownerDirectory).resolve("modules");
        if (!Files.isDirectory(modules)) {
            return List.of();
        }
        try (Stream<Path> types = Files.list(modules)) {
            return types.map(type -> type.resolve(NameCodec.encode(name)))
                .filter(Files::isDirectory).map(dir -> slash(root.relativize(dir))).sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The full repository path ({@code /Root/<owner>/…}) of a repository change. */
    private static String fullPath(LocalChange change) {
        return "/" + String.join("/", parse(change.path()).orElseThrow().segments());
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The modules named {@code name} in the owner's workspace, of any plugin type. */
    private List<ArtifactRef> workspaceModules(ImportScope scope, String ownerDirectory,
        String name) {
        Path modules = root.resolve(ownerDirectory).resolve("modules");
        if (!Files.isDirectory(modules)) {
            return List.of();
        }
        try (Stream<Path> types = Files.list(modules)) {
            return types.filter(type -> Files.isRegularFile(type.resolve(NameCodec.encode(name))
                    .resolve(INDEX))).sorted()
                .map(type -> ArtifactRef.module(scope.group(), scope.owner(),
                    NameCodec.decode(type.getFileName().toString()), name)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A module of the scope with every current file of its directory. */
    private ChangedArtifact module(ImportScope scope, List<String> key, List<LocalChange> files) {
        String pluginType = key.get(0);
        String name = key.get(1);
        for (LocalChange change : files) {
            if (change.kind() == PathChange.Kind.DELETED) {
                throw deleted(name, change.path());
            }
        }
        String directory = slash(WorkspacePath.moduleIndex(scope.group(), scope.owner(),
            pluginType, name).toRelativePath().getParent());
        Optional<String> base = history.lastServerState(scope.group(), directory);
        List<String> paths = files(directory);
        if (base.isEmpty() && (!paths.contains(directory + "/" + INDEX)
            || !paths.contains(directory + "/" + MODULE))) {
            throw invalid("The new module " + name + " needs " + INDEX + " and " + MODULE
                    + " in " + directory + "; nothing was sent",
                "A new module is created from its module file and its module index entry",
                "Copy " + INDEX + " and " + MODULE + " of a module of the same plugin type and"
                    + " adapt them");
        }
        return new ChangedArtifact(ArtifactRef.module(scope.group(), scope.owner(), pluginType,
            name), kind(base), paths, base);
    }

    /** The {@code (plugin type, name)} of a named module, which must be in the workspace. */
    private List<String> locate(ImportScope scope, String ownerDirectory,
        ImportScope.Module module) {
        Path modules = root.resolve(ownerDirectory).resolve("modules");
        List<List<String>> found = new ArrayList<>();
        if (Files.isDirectory(modules)) {
            try (Stream<Path> types = Files.list(modules)) {
                for (Path type : types.filter(Files::isDirectory).sorted().toList()) {
                    String pluginType = NameCodec.decode(type.getFileName().toString());
                    if (module.pluginType().filter(t -> !t.equals(pluginType)).isPresent()) {
                        continue;
                    }
                    if (Files.isDirectory(type.resolve(NameCodec.encode(module.name())))) {
                        found.add(List.of(pluginType, module.name()));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (found.size() != 1) {
            throw invalid("The module " + module.name() + module.pluginType()
                    .map(type -> " (" + type + ")").orElse("") + (found.isEmpty()
                    ? " is not in the workspace of " + scope.owner()
                    : " exists with several plugin types; give pluginType") + "; nothing was sent",
                "Only modules exported into the workspace (or created there) can be imported",
                "Export the module first (export_artifacts), or give the exact name and"
                    + " pluginType");
        }
        return found.get(0);
    }

    /**
     * A repository file's base is the newest server state of its file or of its {@code .meta}
     * record (feature 008, FR-016): the write-back of a verified import rewrites the record but
     * leaves the content as it was, so that commit is no history entry of the file itself
     * (research D-8 step 5). An export or import that changes the content always changes the
     * record too ({@code contentMD5}), so the record's server state is never the older one.
     */
    private LocalChange withRecordState(GroupId group, LocalChange change) {
        Optional<WorkspacePath> path = parse(change.path())
            .filter(parsed -> parsed.kind() == WorkspacePath.Kind.REPOSITORY);
        if (path.isEmpty() || change.kind() == PathChange.Kind.DELETED) {
            return change;
        }
        return history.lastServerState(group, slash(path.get().metaPath()))
            .map(state -> new LocalChange(change.path(), PathChange.Kind.MODIFIED,
                Optional.of(state)))
            .orElse(change);
    }

    /** True if the candidate's content differs from the newest server state of its artifact. */
    private boolean differs(GroupId group, LocalChange change) {
        Path file = root.resolve(change.path());
        if (change.kind() == PathChange.Kind.DELETED || !Files.isRegularFile(file)) {
            return true;
        }
        String key = ConflictDetector.key(change.path());
        Optional<String> base = key == null ? change.serverState()
            : history.lastServerState(group, key).or(change::serverState);
        Optional<byte[]> before = base.flatMap(commit -> history.show(commit, change.path()));
        try {
            return before.isEmpty() || !equivalence.equivalent(change.path(), before.get(),
                Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> files(String directory) {
        Path path = root.resolve(directory);
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).map(file -> slash(root.relativize(file)))
                .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ChangedArtifact.Kind kind(Optional<String> base) {
        return base.isPresent() ? ChangedArtifact.Kind.MODIFIED : ChangedArtifact.Kind.NEW;
    }

    private static Optional<WorkspacePath> parse(String path) {
        try {
            return Optional.of(WorkspacePath.parse(Path.of(path)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<String> cut(List<String> paths) {
        return paths.size() <= 5 ? paths : List.of(String.join(", ", paths.subList(0, 5))
            + " … +" + (paths.size() - 5));
    }

    private static ToolErrorException deleted(String name, String path) {
        return invalid("deleting artifacts is not supported: " + name + " (" + path + ") was"
                + " deleted in the workspace; nothing was sent",
            "The development tools never delete artifacts in INUBIT",
            "restore the file (git restore " + path + ") or delete it in the Workbench");
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            likelyCause, nextStep));
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
