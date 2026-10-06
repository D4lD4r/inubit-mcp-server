package de.dadecker.inubit.mcp.domain.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The workspace-relative path of one file of an artifact (research D-2, data-model.md →
 * Workspace), built from INUBIT names with {@link NameCodec}:
 *
 * <ul>
 *   <li>{@link Kind#WORKFLOW}: {@code <group>/<owner>/workflows/<diagram group>/<workflow>.xml};
 *       segments {@code [diagram group, workflow]};
 *   <li>{@link Kind#MODULE} / {@link Kind#MODULE_INDEX}:
 *       {@code <group>/<owner>/modules/<plugin type>/<module>/module.xml} / {@code …/index.xml};
 *       segments {@code [plugin type, module]};
 *   <li>{@link Kind#EMBEDDED}:
 *       {@code <group>/<owner>/modules/<plugin type>/<module>/<property>.<ext>}; segments
 *       {@code [plugin type, module, property, ext]}, {@code ext} lower-case letters and
 *       digits;
 *   <li>{@link Kind#REPOSITORY}: {@code <group>/<owner>/repository/<repository path>};
 *       segments are the elements of the repository path (e.g. {@code Root, OWNERS, xsd,
 *       msg.xsd}).
 * </ul>
 *
 * <p>The volatile values of a file live at {@link #metaPath()}: {@code .meta/<same path>.json}
 * (D-6). {@link #parse} and {@link #parseMeta} invert {@link #toRelativePath} and
 * {@link #metaPath}; every other path is refused. Segments hold the decoded names.
 */
public record WorkspacePath(GroupId group, String owner, Kind kind, List<String> segments) {

    /** The directory of volatile values (D-6). */
    public static final String META_DIRECTORY = ".meta";
    private static final String META_SUFFIX = ".json";
    private static final String XML = ".xml";
    private static final String MODULE_FILE = "module.xml";
    private static final String INDEX_FILE = "index.xml";
    private static final Pattern EXTENSION = Pattern.compile("^[a-z0-9]{1,10}$");

    /** What a file of the workspace is. */
    public enum Kind {
        WORKFLOW,
        MODULE,
        MODULE_INDEX,
        EMBEDDED,
        REPOSITORY
    }

    public WorkspacePath {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(kind, "kind");
        if (Objects.requireNonNull(owner, "owner").isEmpty()) {
            throw new IllegalArgumentException("owner must not be empty");
        }
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        int expected = switch (kind) {
            case WORKFLOW, MODULE, MODULE_INDEX -> 2;
            case EMBEDDED -> 4;
            case REPOSITORY -> -1;
        };
        if (expected > 0 ? segments.size() != expected : segments.isEmpty()) {
            throw new IllegalArgumentException(kind + " needs "
                + (expected > 0 ? expected + " segments" : "at least one segment"));
        }
        if (segments.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("A path segment must not be empty");
        }
        if (kind == Kind.EMBEDDED) {
            if (!EXTENSION.matcher(segments.get(3)).matches()) {
                throw new IllegalArgumentException("The file extension of an embedded document"
                    + " must be 1-10 lower-case letters or digits");
            }
            String file = embeddedFileName(segments.get(2), segments.get(3));
            if (file.equals(MODULE_FILE) || file.equals(INDEX_FILE)) {
                throw new IllegalArgumentException("An embedded document cannot be named "
                    + file + ", the name of a module file");
            }
        }
    }

    /** The workflow {@code workflow} of diagram group {@code diagramGroup}. */
    public static WorkspacePath workflow(GroupId group, String owner, String diagramGroup,
        String workflow) {
        return new WorkspacePath(group, owner, Kind.WORKFLOW, List.of(diagramGroup, workflow));
    }

    /** The configuration ({@code module.xml}) of module {@code module}. */
    public static WorkspacePath module(GroupId group, String owner, String pluginType,
        String module) {
        return new WorkspacePath(group, owner, Kind.MODULE, List.of(pluginType, module));
    }

    /** The module index entry ({@code index.xml}) of module {@code module}. */
    public static WorkspacePath moduleIndex(GroupId group, String owner, String pluginType,
        String module) {
        return new WorkspacePath(group, owner, Kind.MODULE_INDEX, List.of(pluginType, module));
    }

    /** The embedded document of property {@code property}, as {@code <property>.<extension>}. */
    public static WorkspacePath embedded(GroupId group, String owner, String pluginType,
        String module, String property, String extension) {
        return new WorkspacePath(group, owner, Kind.EMBEDDED,
            List.of(pluginType, module, property, extension));
    }

    /**
     * The repository file at {@code repositoryPath} ({@code Root/OWNERS/xsd/msg.xsd}, with or
     * without a leading {@code /}).
     *
     * @throws IllegalArgumentException for an empty path element
     */
    public static WorkspacePath repository(GroupId group, String owner, String repositoryPath) {
        String path = Objects.requireNonNull(repositoryPath, "repositoryPath");
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return new WorkspacePath(group, owner, Kind.REPOSITORY, List.of(path.split("/", -1)));
    }

    /** The path relative to the workspace root. */
    public Path toRelativePath() {
        List<String> names = new ArrayList<>(List.of(group.value(), NameCodec.encode(owner)));
        switch (kind) {
            case WORKFLOW -> {
                names.add("workflows");
                names.add(NameCodec.encode(segments.get(0)));
                names.add(NameCodec.encode(segments.get(1)) + XML);
            }
            case MODULE, MODULE_INDEX, EMBEDDED -> {
                names.add("modules");
                names.add(NameCodec.encode(segments.get(0)));
                names.add(NameCodec.encode(segments.get(1)));
                names.add(kind == Kind.MODULE ? MODULE_FILE : kind == Kind.MODULE_INDEX
                    ? INDEX_FILE : embeddedFileName(segments.get(2), segments.get(3)));
            }
            case REPOSITORY -> {
                names.add("repository");
                segments.forEach(segment -> names.add(NameCodec.encode(segment)));
            }
        }
        return Path.of(names.get(0), names.subList(1, names.size()).toArray(String[]::new));
    }

    /** {@code .meta/<relative path>.json} (D-6). */
    public Path metaPath() {
        Path relative = toRelativePath();
        return Path.of(META_DIRECTORY).resolve(relative)
            .resolveSibling(relative.getFileName() + META_SUFFIX);
    }

    /**
     * The workspace path that {@code relative} is.
     *
     * @throws IllegalArgumentException if {@code relative} is not the path of an artifact file
     */
    public static WorkspacePath parse(Path relative) {
        List<String> names = names(relative);
        if (names.size() < 4) {
            throw notAnArtifactPath();
        }
        GroupId group = new GroupId(names.get(0));
        String owner = NameCodec.decode(names.get(1));
        String last = names.get(names.size() - 1);
        switch (names.get(2)) {
            case "workflows" -> {
                if (names.size() == 5 && last.endsWith(XML) && last.length() > XML.length()) {
                    return workflow(group, owner, NameCodec.decode(names.get(3)),
                        NameCodec.decode(last.substring(0, last.length() - XML.length())));
                }
            }
            case "modules" -> {
                if (names.size() == 6) {
                    String pluginType = NameCodec.decode(names.get(3));
                    String module = NameCodec.decode(names.get(4));
                    if (last.equals(MODULE_FILE)) {
                        return module(group, owner, pluginType, module);
                    }
                    if (last.equals(INDEX_FILE)) {
                        return moduleIndex(group, owner, pluginType, module);
                    }
                    int dot = last.lastIndexOf('.');
                    if (dot > 0) {
                        return embedded(group, owner, pluginType, module,
                            NameCodec.decode(last.substring(0, dot)), last.substring(dot + 1));
                    }
                }
            }
            case "repository" -> {
                List<String> segments = new ArrayList<>();
                names.subList(3, names.size()).forEach(n -> segments.add(NameCodec.decode(n)));
                return new WorkspacePath(group, owner, Kind.REPOSITORY, segments);
            }
            default -> {
                // not an artifact directory
            }
        }
        throw notAnArtifactPath();
    }

    /**
     * The workspace path whose {@link #metaPath()} is {@code relative}.
     *
     * @throws IllegalArgumentException if {@code relative} is not such a path
     */
    public static WorkspacePath parseMeta(Path relative) {
        List<String> names = names(relative);
        String last = names.get(names.size() - 1);
        if (names.size() < 2 || !names.get(0).equals(META_DIRECTORY)
            || !last.endsWith(META_SUFFIX) || last.length() == META_SUFFIX.length()) {
            throw new IllegalArgumentException("Not a path below " + META_DIRECTORY
                + " ending in " + META_SUFFIX);
        }
        Path artifact = Path.of(names.get(1),
            names.subList(2, names.size()).toArray(String[]::new));
        return parse(artifact.resolveSibling(last.substring(0,
            last.length() - META_SUFFIX.length())));
    }

    private static String embeddedFileName(String property, String extension) {
        return NameCodec.encode(property) + "." + extension;
    }

    private static List<String> names(Path relative) {
        Objects.requireNonNull(relative, "relative");
        if (relative.isAbsolute() || relative.getRoot() != null) {
            throw new IllegalArgumentException("A workspace path must be relative");
        }
        List<String> names = new ArrayList<>();
        relative.forEach(name -> names.add(name.toString()));
        return names;
    }

    private static IllegalArgumentException notAnArtifactPath() {
        return new IllegalArgumentException("Not the path of a workflow, module, embedded"
            + " document or repository file of the workspace");
    }
}
