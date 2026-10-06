package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactRef;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort.PreparedExport;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Whether the server still holds what the workspace started from (feature 004, research D-5,
 * D-25, FR-008): the scope is exported fresh and rendered in memory
 * ({@link PreparedExport#files()}), then compared file by file with each artifact's own base
 * ({@link VersionHistoryPort#show}).
 *
 * <ul>
 *   <li>A modified artifact of the change set whose files differ from its base, that no longer
 *       exists, or a new one that exists on the server already, is a conflict; so is every
 *       other artifact of the scope that differs from its own base (D-25).
 *   <li>A workflow of the change set with a {@code CheckoutUser} is in Workbench edit mode: a
 *       conflict naming the user INUBIT reports (a publish would overwrite the import, or the
 *       import the edit).
 *   <li>Any conflict is {@code CONFLICT}: the differences go to
 *       {@code .reports/conflict-<auditId>.diff}, whose path the message names; nothing else is
 *       written.
 *   <li>Otherwise the result hands back the raw exports (for the secret values and the backup),
 *       the fingerprint of the rendered scope (for the confirmation, research D-2) and the
 *       target's modules of the owner (for the referenced-module rule, D-25).
 * </ul>
 */
public final class ConflictDetector {

    private static final String META = WorkspacePath.META_DIRECTORY + "/";
    private static final String REPORTS = ".reports";

    /**
     * What the fresh export showed.
     *
     * @param rawExports    the raw (unredacted) exports, in memory only
     * @param fingerprint   {@code sha256:<hex>} over the rendered artifact files of the scope
     * @param targetModules the module names of the owner on the target
     * @param rendered      the rendered (redacted) files of the export, {@code .meta/} included
     */
    public record Result(List<byte[]> rawExports, String fingerprint, Set<String> targetModules,
        SortedMap<String, byte[]> rendered) {

        public Result {
            rawExports = rawExports.stream().map(byte[]::clone).toList();
            Objects.requireNonNull(fingerprint, "fingerprint");
            targetModules = Set.copyOf(targetModules);
            rendered = new TreeMap<>(rendered);
        }

        @Override
        public String toString() {
            return "Result[" + rawExports.size() + " exports, " + fingerprint + "]";
        }
    }

    private final Path root;
    private final VersionHistoryPort history;
    private final ArchiveCodecPort codec;
    private final Function<NodeId, ArtifactPort> artifacts;
    private final Function<NodeId, InventoryPort> inventory;

    /**
     * @param root      the workspace root (for {@code .reports/})
     * @param artifacts the exports of a node
     * @param inventory the module list of a node
     */
    public ConflictDetector(Path root, VersionHistoryPort history, ArchiveCodecPort codec,
        Function<NodeId, ArtifactPort> artifacts, Function<NodeId, InventoryPort> inventory) {
        this.root = Objects.requireNonNull(root, "root");
        this.history = Objects.requireNonNull(history, "history");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    /**
     * Exports the scope of {@code changes} from {@code node} and compares it.
     *
     * @throws ToolErrorException {@code CONFLICT}, or the errors of the export
     */
    public Result detect(NodeId node, ChangeSet changes, UUID auditId) {
        ImportScope scope = changes.scope();
        List<byte[]> raw = export(node, changes);
        PreparedExport prepared = withNode(node, () -> codec.prepare(scope.group(),
            scope.owner(), raw));
        SortedMap<String, byte[]> rendered = prepared.files();
        SortedMap<String, byte[]> server = artifactFiles(rendered);

        List<String> changed = new ArrayList<>();
        List<String> editMode = new ArrayList<>();
        List<String> exists = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        Set<String> covered = new TreeSet<>();
        for (ChangedArtifact artifact : changes.artifacts()) {
            String key = key(artifact.paths().get(0));
            covered.add(key);
            List<String> serverPaths = paths(server, key);
            if (artifact.kind() == ChangedArtifact.Kind.NEW) {
                if (!serverPaths.isEmpty()) {
                    exists.add(artifact.name());
                    diff.append("=== ").append(key).append(": exists on ").append(node)
                        .append(" although the workspace creates it\n");
                }
            } else {
                Set<String> all = new TreeSet<>(serverPaths);
                all.addAll(artifact.paths());
                if (compare(artifact.base().orElseThrow(), all, server, diff)) {
                    changed.add(artifact.name());
                }
            }
            if (artifact.ref().kind() == ArtifactRef.Kind.WORKFLOW
                && prepared.inEditMode().containsKey(artifact.name())) {
                editMode.add(artifact.name() + " (by " + prepared.inEditMode()
                    .get(artifact.name()) + ")");
            }
        }
        // research D-25: the other artifacts of the scope are compared as well
        Set<String> others = new TreeSet<>();
        server.keySet().stream().map(ConflictDetector::key).filter(Objects::nonNull)
            .filter(key -> !covered.contains(key)).forEach(others::add);
        for (String key : others) {
            Optional<String> base = history.lastServerState(scope.group(), key);
            String name = name(key);
            if (base.isEmpty()) {
                changed.add(name);
                diff.append("=== ").append(key).append(": created on ").append(node)
                    .append(" after the export\n");
                continue;
            }
            Set<String> all = new TreeSet<>(paths(server, key));
            all.addAll(workspaceFiles(key));
            if (compare(base.get(), all, server, diff)) {
                changed.add(name);
            }
        }
        if (!changed.isEmpty() || !editMode.isEmpty() || !exists.isEmpty()) {
            String report = report(auditId, diff.toString());
            List<String> parts = new ArrayList<>();
            if (!changed.isEmpty()) {
                parts.add("changed on " + node + " since the export: " + String.join(", ",
                    changed));
            }
            if (!editMode.isEmpty()) {
                parts.add("in Workbench edit mode: " + String.join(", ", editMode));
            }
            if (!exists.isEmpty()) {
                parts.add("exists on " + node + " already: " + String.join(", ", exists));
            }
            throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                "Nothing was sent: " + String.join("; ", parts) + ". Differences: " + report,
                "A colleague (or the Workbench) changed or opened the artifacts after the export;"
                    + " importing now would overwrite one side without a warning",
                editMode.isEmpty()
                    ? "Export the scope again (export_artifacts), redo your change on top, then"
                        + " import again"
                    : "Publish or discard the edit in the Workbench, export the scope again, then"
                        + " import again").withNode(node));
        }
        Set<String> modules = new LinkedHashSet<>();
        inventory.apply(node).listModules(scope.owner())
            .forEach(entry -> modules.add(entry.item().name()));
        server.keySet().stream().map(ConflictDetector::parse).flatMap(Optional::stream)
            .filter(path -> path.kind() != WorkspacePath.Kind.WORKFLOW
                && path.kind() != WorkspacePath.Kind.REPOSITORY)
            .forEach(path -> modules.add(path.segments().get(1)));
        return new Result(raw, fingerprint(server), modules, rendered);
    }

    /**
     * {@code sha256:<hex>} over the artifact files (not {@code .meta/}) of a rendering, in path
     * order: equal for two exports that render the same reviewed content.
     */
    public static String fingerprint(SortedMap<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                if (file.getKey().startsWith(META)) {
                    continue;
                }
                digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.getValue());
                digest.update((byte) 0);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<byte[]> export(NodeId node, ChangeSet changes) {
        ImportScope scope = changes.scope();
        ArtifactPort port = artifacts.apply(node);
        List<byte[]> raw = new ArrayList<>();
        if (scope.diagramGroup().isPresent()) {
            raw.add(port.exportWorkflowGroup(scope.owner(), scope.diagramGroup().get()));
        } else {
            for (ChangedArtifact module : changes.modules()) {
                raw.add(port.exportModule(scope.owner(), module.ref().pluginType().orElseThrow(),
                    module.name()));
            }
        }
        return raw;
    }

    /** True (and the difference appended) if a file of {@code paths} differs from its base. */
    private boolean compare(String base, Set<String> paths, SortedMap<String, byte[]> server,
        StringBuilder diff) {
        boolean differs = false;
        for (String path : paths) {
            Optional<byte[]> before = history.show(base, path);
            byte[] now = server.get(path);
            boolean equal = before.isEmpty() ? now == null
                : now != null && Arrays.equals(before.get(), now);
            if (!equal) {
                differs = true;
                LineDiff.append(diff, path, base, before.orElse(null), now);
            }
        }
        return differs;
    }

    /** The artifact files of a rendering: no {@code .meta/}, no repository files. */
    private static SortedMap<String, byte[]> artifactFiles(SortedMap<String, byte[]> rendered) {
        SortedMap<String, byte[]> files = new TreeMap<>();
        rendered.forEach((path, content) -> {
            if (key(path) != null) {
                files.put(path, content);
            }
        });
        return files;
    }

    /** A workflow's file, or a module's directory; {@code null} for anything else. */
    static String key(String path) {
        return parse(path).map(parsed -> switch (parsed.kind()) {
            case WORKFLOW -> path;
            case MODULE, MODULE_INDEX, EMBEDDED -> path.substring(0, path.lastIndexOf('/'));
            case REPOSITORY -> null;
        }).orElse(null);
    }

    private static String name(String key) {
        String last = key.substring(key.lastIndexOf('/') + 1);
        return parse(key.endsWith(".xml") ? key : key + "/index.xml")
            .map(path -> path.segments().get(1)).orElse(last);
    }

    private static List<String> paths(SortedMap<String, byte[]> files, String key) {
        return files.keySet().stream().filter(path -> path.equals(key)
            || path.startsWith(key + "/")).toList();
    }

    private List<String> workspaceFiles(String key) {
        Path start = root.resolve(key);
        if (Files.isRegularFile(start)) {
            return List.of(key);
        }
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(start)) {
            return walk.filter(Files::isRegularFile)
                .map(file -> root.relativize(file).toString().replace('\\', '/')).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    private String report(UUID auditId, String diff) {
        String name = "conflict-" + auditId + ".diff";
        Path file = root.resolve(REPORTS).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, diff, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return REPORTS + "/" + name;
    }

    private static <T> T withNode(NodeId node, Supplier<T> action) {
        try {
            return action.get();
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e : new ToolErrorException(e.error()
                .withNode(node));
        }
    }

    /** A small line difference: the lines between the common head and tail of two texts. */
    static final class LineDiff {

        private LineDiff() {
        }

        static void append(StringBuilder out, String path, String base, byte[] before,
            byte[] after) {
            out.append("--- ").append(path).append(" (workspace base ")
                .append(base, 0, Math.min(12, base.length())).append(")\n");
            out.append("+++ ").append(path).append(" (server now)\n");
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
    }
}
