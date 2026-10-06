package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Check;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph.Edge;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph.Node;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph.Reference;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The structure checks of {@code check_artifacts} (research D-13, FR-027): for every workflow,
 * module and repository file below the requested workspace paths.
 *
 * <ul>
 *   <li>{@code EDGE_TARGET_MISSING} (ERROR): a {@code Connection} targets a node that does not
 *       exist;
 *   <li>{@code ID_COLLISION} (ERROR): a {@code ModuleId} or {@code ConnectionId} is used twice
 *       (both share one id space);
 *   <li>{@code DEMUX_KEY_UNMATCHED} (ERROR): a condition key {@code <Name>(<id>)@@@…} or a
 *       {@code DefaultOutput} {@code <Name>(<id>)} does not name an outgoing edge of that node
 *       to a node with that name and id;
 *   <li>{@code PARENT_REF_MISSING} (ERROR): {@code ParentModule}, {@code EndLoopId} or
 *       {@code scopeChildId} names a node that does not exist;
 *   <li>{@code REPOSITORY_REF_MISSING} (ERROR): an {@code inubitrepository:} path is neither a
 *       repository file of the group (any owner) nor withheld as key material ({@code .meta});
 *   <li>{@code VARIABLE_UNRESOLVED} (WARNING): a variable reference names no declared variable;
 *       names starting with {@code IS} are INUBIT's implicit system variables (assumption);
 *   <li>{@code DERIVED_VALUE_MISMATCH} (WARNING): {@code <property>MD5} of an embedded document
 *       or {@code contentMD5}/{@code contentSize} of a repository file no longer matches the
 *       content (research D-4; the rebuild recomputes them).
 * </ul>
 */
public final class ArtifactCheckService {

    private static final Pattern DEMUX_KEY = Pattern.compile("^(.+)\\((\\d+)\\)@@@.*$");
    private static final Pattern DEFAULT_OUTPUT = Pattern.compile("^(.+)\\((\\d+)\\)$");
    private static final Pattern REPOSITORY_REFERENCE =
        Pattern.compile("inubitrepository:/+([^\"'<>&\\s]+)");
    private static final String SYSTEM_VARIABLE_PREFIX = "IS";
    private static final Set<String> SKIPPED = Set.of(".git", ".meta", ".tests", ".reports");

    private final Path root;
    private final ArtifactInspectorPort inspector;

    /**
     * @param root      the workspace root
     * @param inspector reads the workspace files
     */
    public ArtifactCheckService(Path root, ArtifactInspectorPort inspector) {
        this.root = Objects.requireNonNull(root, "root");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    /**
     * The structure findings of every artifact file below {@code paths} (workspace-relative
     * files or directories), in path order.
     */
    public List<CheckFinding> checkPaths(List<String> paths) {
        List<CheckFinding> findings = new ArrayList<>();
        for (String file : files(paths)) {
            WorkspacePath path;
            try {
                path = WorkspacePath.parse(Path.of(file));
            } catch (IllegalArgumentException e) {
                continue; // not an artifact file
            }
            switch (path.kind()) {
                case WORKFLOW -> workflow(file, path, findings);
                case MODULE, EMBEDDED -> {
                    if (path.kind() == WorkspacePath.Kind.MODULE) {
                        derived(file, findings);
                    }
                    repositoryReferences(file, path, findings);
                }
                case REPOSITORY -> derived(file, findings);
                default -> {
                    // the index entry carries no references
                }
            }
        }
        return findings;
    }

    /** The workspace-relative artifact files below {@code paths}, sorted, once each. */
    private Set<String> files(List<String> paths) {
        Set<String> files = new TreeSet<>();
        for (String path : paths) {
            Path start = root.resolve(path).normalize();
            if (Files.isRegularFile(start)) {
                files.add(relative(start));
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(Files::isRegularFile).map(this::relative)
                    .filter(file -> !SKIPPED.contains(file.split("/", 2)[0]))
                    .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private void workflow(String file, WorkspacePath path, List<CheckFinding> findings) {
        WorkflowGraph graph = inspector.workflow(root.resolve(file));
        Map<String, Node> byId = new LinkedHashMap<>();
        graph.nodes().forEach(node -> byId.putIfAbsent(node.moduleId(), node));
        ids(file, graph, findings);
        for (Node node : graph.nodes()) {
            for (Edge edge : node.edges()) {
                if (!byId.containsKey(edge.target())) {
                    findings.add(error(file, node.location() + "/Connection",
                        "EDGE_TARGET_MISSING", "edge to node " + edge.target()
                            + ", which does not exist"));
                }
            }
            demux(file, node, byId, findings);
            for (Reference parent : node.parentReferences()) {
                if (!byId.containsKey(parent.value())) {
                    findings.add(error(file, parent.location(), "PARENT_REF_MISSING",
                        "reference to node " + parent.value() + ", which does not exist"));
                }
            }
        }
        Set<String> reported = new LinkedHashSet<>();
        for (Reference reference : graph.variableReferences()) {
            if (!graph.variables().contains(reference.value())
                && !reference.value().startsWith(SYSTEM_VARIABLE_PREFIX)
                && reported.add(reference.value())) {
                findings.add(new CheckFinding(Severity.WARNING, Check.STRUCTURE, file,
                    Optional.of(reference.location()), "VARIABLE_UNRESOLVED",
                    "variable " + reference.value() + " is not declared in this workflow"));
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        for (Reference reference : graph.repositoryReferences()) {
            if (!repositoryFileExists(path, reference.value())
                && missing.add(reference.value())) {
                findings.add(error(file, reference.location(), "REPOSITORY_REF_MISSING",
                    "inubitrepository:/" + reference.value()
                        + " is not in the repository of the workspace"));
            }
        }
    }

    /** {@code ModuleId} and {@code ConnectionId} share one id space. */
    private static void ids(String file, WorkflowGraph graph, List<CheckFinding> findings) {
        Map<String, List<String>> uses = new LinkedHashMap<>();
        for (Node node : graph.nodes()) {
            uses.computeIfAbsent(node.moduleId(), id -> new ArrayList<>())
                .add(node.location());
            for (Edge edge : node.edges()) {
                edge.connectionId().ifPresent(id -> uses.computeIfAbsent(id,
                    any -> new ArrayList<>()).add(node.location() + "/Connection"));
            }
        }
        uses.forEach((id, locations) -> {
            if (locations.size() > 1) {
                findings.add(error(file, locations.get(1), "ID_COLLISION", "id " + id
                    + " is used " + locations.size() + " times (ModuleId and ConnectionId"
                    + " share one id space): " + String.join(", ", locations)));
            }
        });
    }

    private static void demux(String file, Node node, Map<String, Node> byId,
        List<CheckFinding> findings) {
        Set<List<String>> keys = new LinkedHashSet<>();
        node.properties().forEach((name, value) -> {
            Matcher key = DEMUX_KEY.matcher(name);
            if (key.matches()) {
                keys.add(List.of(key.group(1), key.group(2)));
            }
            Matcher output = DEFAULT_OUTPUT.matcher(value.strip());
            if (name.equals("DefaultOutput") && output.matches()) {
                keys.add(List.of(output.group(1), output.group(2)));
            }
        });
        for (List<String> key : keys) {
            String name = key.get(0);
            String id = key.get(1);
            boolean edge = node.edges().stream().anyMatch(e -> e.target().equals(id));
            Node target = byId.get(id);
            if (!edge || target == null || !target.moduleName().equals(name)) {
                findings.add(error(file, node.location() + "/Properties",
                    "DEMUX_KEY_UNMATCHED", "the condition key " + name + "(" + id + ") is not"
                        + " an outgoing edge of this node to a node of that name and id"));
            }
        }
    }

    private void repositoryReferences(String file, WorkspacePath path,
        List<CheckFinding> findings) {
        String text;
        try {
            text = Files.readString(root.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // binary documents carry no references
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = REPOSITORY_REFERENCE.matcher(text);
        while (matcher.find()) {
            if (!repositoryFileExists(path, matcher.group(1)) && missing.add(matcher.group(1))) {
                findings.add(new CheckFinding(Severity.ERROR, Check.STRUCTURE, file,
                    Optional.empty(), "REPOSITORY_REF_MISSING", "inubitrepository:/"
                        + matcher.group(1) + " is not in the repository of the workspace"));
            }
        }
    }

    /** A repository file of any owner of the group, or withheld as key material. */
    private boolean repositoryFileExists(WorkspacePath artifact, String repositoryPath) {
        Path group = root.resolve(artifact.group().value());
        List<String> segments = List.of(repositoryPath.split("/"));
        if (segments.stream().anyMatch(s -> s.isEmpty() || s.equals("..") || s.equals("."))) {
            return false;
        }
        List<Path> owners;
        try (Stream<Path> list = Files.list(group)) {
            owners = list.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            return false;
        }
        for (Path owner : owners) {
            WorkspacePath path;
            try {
                path = new WorkspacePath(artifact.group(), NameCodec.decode(owner.getFileName()
                    .toString()), WorkspacePath.Kind.REPOSITORY, segments);
            } catch (IllegalArgumentException e) {
                continue; // not an owner directory
            }
            if (Files.isRegularFile(root.resolve(path.toRelativePath()))
                || Files.isRegularFile(root.resolve(path.metaPath()))) {
                return true;
            }
        }
        return false;
    }

    private void derived(String file, List<CheckFinding> findings) {
        List<String> mismatches = inspector.derivedValueMismatches(root, root.resolve(file));
        if (!mismatches.isEmpty()) {
            findings.add(new CheckFinding(Severity.WARNING, Check.STRUCTURE, file,
                Optional.empty(), "DERIVED_VALUE_MISMATCH", String.join(", ", mismatches)
                    + " no longer match the content; a rebuild recomputes them"));
        }
    }

    private static CheckFinding error(String file, String location, String code,
        String message) {
        return new CheckFinding(Severity.ERROR, Check.STRUCTURE, file, Optional.of(location),
            code, message);
    }
}
