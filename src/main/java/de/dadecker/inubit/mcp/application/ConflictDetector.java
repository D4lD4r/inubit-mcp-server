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
 * D-25, FR-008): the scope is exported fresh ({@link ScopeExports}: the diagram group, and each
 * module of the change set that its export lacks on its own) and rendered in memory
 * ({@link PreparedExport#files()}), then compared file by file with each artifact's own base
 * ({@link VersionHistoryPort#show}) by reviewed content ({@link ContentEquivalence}): what an
 * import or a rollback rewrites (check-in comment, last update, UIDs) is no conflict (0.4.2).
 *
 * <ul>
 *   <li>A modified artifact of the change set whose files differ from its base, or that no
 *       longer exists, is a conflict; so is every other artifact of the scope that differs from
 *       its own base (D-25).
 *   <li>An unchanged module of the workspace that a workflow of the change set uses, but that the
 *       export of the diagram group lacks, is exported on its own and compared with its base
 *       (0.4.3): unchanged it is identical (shown in the preview, the workflow is bound to it),
 *       changed on the server it is a conflict.
 *   <li>A new artifact that the server has already (e.g. created by an import that was rolled
 *       back) is no conflict (0.4.2): with the same content it is identical and not sent,
 *       otherwise the import updates it ({@link ChangedArtifact.Kind#EXISTING}), which the
 *       preview shows. Whether a new module exists is decided by the owner's module list
 *       (review I3); a listed name without a module of that plugin type is a conflict.
 *   <li>A workflow of the change set with a {@code CheckoutUser} is in Workbench edit mode: a
 *       conflict naming the user INUBIT reports (a publish would overwrite the import, or the
 *       import the edit).
 *   <li>Any conflict is {@code CONFLICT}: the differences go to
 *       {@code .reports/conflict-<auditId>.diff}, whose path the message names; nothing else is
 *       written.
 *   <li>Otherwise the result hands back the change set as compared, the raw exports (for the
 *       secret values and the backup), the fingerprint of the rendered scope (for the
 *       confirmation, research D-2) and the target's modules of the owner (for the
 *       referenced-module rule, D-25).
 * </ul>
 */
public final class ConflictDetector {

    private static final String META = WorkspacePath.META_DIRECTORY + "/";
    private static final String REPORTS = ".reports";

    /**
     * What the fresh export showed.
     *
     * @param changes       the change set as compared: new artifacts the server has already are
     *                      {@code EXISTING} or, with the same content, identical (not sent)
     * @param rawExports    the raw (unredacted) exports, in memory only
     * @param fingerprint   {@code sha256:<hex>} over the rendered artifact files of the scope
     * @param targetModules the module names of the owner on the target
     * @param rendered      the rendered (redacted) files of the export, {@code .meta/} included
     */
    public record Result(ChangeSet changes, List<byte[]> rawExports, String fingerprint,
        Set<String> targetModules, SortedMap<String, byte[]> rendered) {

        public Result {
            Objects.requireNonNull(changes, "changes");
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
    private final ContentEquivalence equivalence;

    /** Compares byte by byte. */
    public ConflictDetector(Path root, VersionHistoryPort history, ArchiveCodecPort codec,
        Function<NodeId, ArtifactPort> artifacts, Function<NodeId, InventoryPort> inventory) {
        this(root, history, codec, artifacts, inventory, ContentEquivalence.BYTES);
    }

    /**
     * @param root        the workspace root (for {@code .reports/})
     * @param artifacts   the exports of a node
     * @param inventory   the module list of a node
     * @param equivalence what counts as the same content
     */
    public ConflictDetector(Path root, VersionHistoryPort history, ArchiveCodecPort codec,
        Function<NodeId, ArtifactPort> artifacts, Function<NodeId, InventoryPort> inventory,
        ContentEquivalence equivalence) {
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
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
        return detect(node, changes, auditId, true);
    }

    /**
     * As {@link #detect(NodeId, ChangeSet, UUID)}; with {@code wholeScope} false only the
     * change set is compared and the owner's module list is not read ({@code set_active}, research
     * D-15: the conflict check covers that workflow only).
     */
    public Result detect(NodeId node, ChangeSet changes, UUID auditId, boolean wholeScope) {
        ImportScope scope = changes.scope();
        // review I3: the owner's module list tells whether a new module exists already
        Set<String> listed = new LinkedHashSet<>();
        if (wholeScope) {
            inventory.apply(node).listModules(scope.owner())
                .forEach(entry -> listed.add(entry.item().name()));
        }
        ScopeExports.Exported exported = withNode(node, () -> ScopeExports.export(
            artifacts.apply(node), codec, changes, module -> module.onServer()
                || listed.contains(module.name()), wholeScope));
        List<byte[]> raw = exported.raw();
        PreparedExport prepared = exported.prepared();
        SortedMap<String, byte[]> rendered = prepared.files();
        SortedMap<String, byte[]> server = artifactFiles(rendered);

        List<String> changed = new ArrayList<>();
        List<String> editMode = new ArrayList<>();
        List<String> exists = new ArrayList<>();
        List<ChangedArtifact> workflows = new ArrayList<>();
        List<ChangedArtifact> modules = new ArrayList<>();
        List<ChangedArtifact> identical = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        Set<String> covered = new TreeSet<>();
        for (ChangedArtifact artifact : changes.artifacts()) {
            String key = key(artifact.paths().get(0));
            covered.add(key);
            List<String> serverPaths = paths(server, key);
            ChangedArtifact compared = artifact;
            if (artifact.kind() == ChangedArtifact.Kind.NEW) {
                if (!serverPaths.isEmpty()) {
                    // 0.4.2: e.g. left by a rolled-back import — identical, or a new version
                    if (sameAsWorkspace(artifact, serverPaths, server)) {
                        identical.add(artifact);
                        continue;
                    }
                    compared = artifact.existing();
                } else if (artifact.ref().kind() == ArtifactRef.Kind.MODULE
                    && listed.contains(artifact.name())) {
                    exists.add(artifact.name());
                    diff.append("=== ").append(key).append(": the name is used on ")
                        .append(node).append(" by a module of another plugin type\n");
                }
            } else {
                Set<String> all = new TreeSet<>(serverPaths);
                all.addAll(artifact.paths());
                if (compare(artifact.base().orElseThrow(), all, server, diff)) {
                    changed.add(artifact.name());
                }
            }
            (artifact.ref().kind() == ArtifactRef.Kind.WORKFLOW ? workflows : modules)
                .add(compared);
            if (artifact.ref().kind() == ArtifactRef.Kind.WORKFLOW
                && prepared.inEditMode().containsKey(artifact.name())) {
                editMode.add(artifact.name() + " (by " + prepared.inEditMode()
                    .get(artifact.name()) + ")");
            }
        }
        // 0.4.3: an unchanged module a workflow uses from outside the export of the diagram group
        // is shown as identical, so the preview says that the workflow is bound to it
        for (ArtifactRef module : changes.referenced()) {
            String key = ScopeExports.key(scope, module);
            if (!exported.separate().contains(key) || paths(server, key).isEmpty()) {
                continue;
            }
            Optional<String> base = history.lastServerState(scope.group(), key);
            if (base.isEmpty()) {
                continue;
            }
            covered.add(key);
            List<String> files = workspaceFiles(key);
            Set<String> all = new TreeSet<>(paths(server, key));
            all.addAll(files);
            if (compare(base.get(), all, server, diff)) {
                changed.add(module.name());
            } else {
                identical.add(new ChangedArtifact(module, ChangedArtifact.Kind.MODIFIED, files,
                    base));
            }
        }
        // research D-25: the other artifacts of the scope are compared as well
        Set<String> others = new TreeSet<>();
        if (wholeScope) {
            server.keySet().stream().map(ConflictDetector::key).filter(Objects::nonNull)
                .filter(key -> !covered.contains(key)).forEach(others::add);
        }
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
        Set<String> targetModules = new LinkedHashSet<>(listed);
        server.keySet().stream().map(ConflictDetector::parse).flatMap(Optional::stream)
            .filter(path -> path.kind() != WorkspacePath.Kind.WORKFLOW
                && path.kind() != WorkspacePath.Kind.REPOSITORY)
            .forEach(path -> targetModules.add(path.segments().get(1)));
        ChangeSet compared = new ChangeSet(scope, changes.baseCommit(), workflows, modules,
            changes.notImported(), identical, changes.referenced());
        return new Result(compared, raw, fingerprint(server), targetModules, rendered);
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

    /** True (and the difference appended) if a file of {@code paths} differs from its base. */
    private boolean compare(String base, Set<String> paths, SortedMap<String, byte[]> server,
        StringBuilder diff) {
        boolean differs = false;
        for (String path : paths) {
            byte[] before = history.show(base, path).orElse(null);
            byte[] now = server.get(path);
            if (!equivalence.equivalent(path, before, now)) {
                differs = true;
                LineDiff.append(diff, path, "workspace base " + base.substring(0,
                    Math.min(12, base.length())), "server now", before, now);
            }
        }
        return differs;
    }

    /** True if the workspace files of a new artifact have the server's content. */
    private boolean sameAsWorkspace(ChangedArtifact artifact, List<String> serverPaths,
        SortedMap<String, byte[]> server) {
        Set<String> all = new TreeSet<>(serverPaths);
        all.addAll(artifact.paths());
        for (String path : all) {
            if (!equivalence.equivalent(path, workspaceFile(path), server.get(path))) {
                return false;
            }
        }
        return true;
    }

    private byte[] workspaceFile(String path) {
        Path file = root.resolve(path);
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /**
     * A small line difference: the lines between the common head and tail of two texts, and a
     * note ({@code \\ …}) for what the lines do not show (0.4.2): a missing file, a missing
     * trailing newline, other line ends, whitespace at the end of a line, other bytes.
     */
    static final class LineDiff {

        private LineDiff() {
        }

        static void append(StringBuilder out, String path, String before, String after,
            byte[] a, byte[] b) {
            out.append("--- ").append(path).append(" (").append(before).append(")\n");
            out.append("+++ ").append(path).append(" (").append(after).append(")\n");
            List<String> left = lines(a);
            List<String> right = lines(b);
            int head = 0;
            while (head < left.size() && head < right.size()
                && left.get(head).equals(right.get(head))) {
                head++;
            }
            int tail = 0;
            while (tail < left.size() - head && tail < right.size() - head
                && left.get(left.size() - 1 - tail).equals(right.get(right.size() - 1 - tail))) {
                tail++;
            }
            out.append("@@ line ").append(head + 1).append(" @@\n");
            List<String> removed = left.subList(head, left.size() - tail);
            List<String> added = right.subList(head, right.size() - tail);
            removed.forEach(line -> out.append('-').append(line).append('\n'));
            added.forEach(line -> out.append('+').append(line).append('\n'));
            notes(out, before, after, a, b, removed, added);
        }

        private static void notes(StringBuilder out, String before, String after, byte[] a,
            byte[] b, List<String> removed, List<String> added) {
            if (a == null || b == null) {
                out.append("\\ the file is missing (").append(a == null ? before : after)
                    .append(")\n");
                return;
            }
            boolean visible = false;
            if (removed.size() == added.size() && !removed.isEmpty()) {
                boolean whitespace = true;
                for (int i = 0; i < removed.size(); i++) {
                    whitespace &= !removed.get(i).equals(added.get(i))
                        && removed.get(i).stripTrailing().equals(added.get(i).stripTrailing());
                }
                if (whitespace) {
                    out.append("\\ the lines differ in trailing whitespace only\n");
                }
                visible = !whitespace;
            } else {
                visible = !removed.isEmpty() || !added.isEmpty();
            }
            String left = new String(a, StandardCharsets.UTF_8);
            String right = new String(b, StandardCharsets.UTF_8);
            boolean newlineLeft = left.endsWith("\n");
            boolean newlineRight = right.endsWith("\n");
            if (newlineLeft != newlineRight) {
                out.append("\\ missing trailing newline (").append(newlineLeft ? after : before)
                    .append(")\n");
            }
            boolean crlfLeft = left.contains("\r\n");
            boolean crlfRight = right.contains("\r\n");
            if (crlfLeft != crlfRight) {
                out.append("\\ line ends differ: ").append(crlfLeft ? "CRLF" : "LF").append(" (")
                    .append(before).append("), ").append(crlfRight ? "CRLF" : "LF").append(" (")
                    .append(after).append(")\n");
            }
            if (!visible && newlineLeft == newlineRight && crlfLeft == crlfRight
                && removed.isEmpty()) {
                out.append("\\ the files differ in bytes the lines do not show (e.g. encoding,"
                    + " byte order mark or the XML serialization)\n");
            }
        }

        private static List<String> lines(byte[] content) {
            return content == null ? List.of()
                : new String(content, StandardCharsets.UTF_8).lines().toList();
        }
    }
}
