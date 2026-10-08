package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactRef;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort.LocalChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 *       or new modules that these workflows reference. Module scope: the named modules (each
 *       must be in the workspace). Every other change of the owner is listed as not imported.
 *   <li>A modified artifact's base is its own last server state; a new artifact (never in a
 *       server state) has none and takes the scope's (review M4). Without any server state of
 *       the scope: {@code PRECONDITION_FAILED} "export the scope first".
 *   <li>Refused with {@code INVALID_INPUT}: a deleted file of an artifact in the scope (deleting
 *       artifacts is not supported), any change below {@code repository/} (research D-24), a new
 *       module without {@code index.xml} or {@code module.xml}.
 * </ul>
 */
public final class ChangeSetBuilder {

    private static final String INDEX = "index.xml";
    private static final String MODULE = "module.xml";
    private static final String TECHNICAL = "technical";

    private final Path root;
    private final VersionHistoryPort history;
    private final ArtifactInspectorPort inspector;
    private final ContentEquivalence equivalence;

    /** Compares byte by byte. */
    public ChangeSetBuilder(Path root, VersionHistoryPort history,
        ArtifactInspectorPort inspector) {
        this(root, history, inspector, ContentEquivalence.BYTES);
    }

    /**
     * @param root        the workspace root
     * @param history     its history
     * @param inspector   reads the workflows (referenced modules)
     * @param equivalence what counts as the same content
     */
    public ChangeSetBuilder(Path root, VersionHistoryPort history,
        ArtifactInspectorPort inspector, ContentEquivalence equivalence) {
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
        this.root = Objects.requireNonNull(root, "root");
        this.history = Objects.requireNonNull(history, "history");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    /**
     * The change set of {@code scope}.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} without a base export,
     *     {@code INVALID_INPUT} for deletions, repository changes, an incomplete new module or
     *     a named module that is not in the workspace
     */
    public ChangeSet build(ImportScope scope) {
        GroupId group = scope.group();
        String ownerDirectory = group.value() + "/" + NameCodec.encode(scope.owner());
        String scopeDirectory = scope.diagramGroup()
            .map(name -> slash(WorkspacePath.workflow(group, scope.owner(), name, "x")
                .toRelativePath().getParent()))
            .orElse(ownerDirectory + "/modules");
        String base = history.serverStateOf(group, scopeDirectory);

        List<LocalChange> changes = history.localChanges(group, ownerDirectory).stream()
            .filter(change -> differs(group, change)).toList();
        List<String> repository = new ArrayList<>();
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
                case REPOSITORY -> repository.add(change.path());
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
        if (!repository.isEmpty()) {
            throw invalid("Changes below repository/ are not imported (" + String.join(", ",
                    cut(repository)) + "); nothing was sent",
                "Repository files are out of scope of the development tools in this version",
                "Restore the repository files (git restore) or change them in the Workbench");
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
        return new ChangeSet(scope, base, changedWorkflows, changedModules,
            List.copyOf(notImported));
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
