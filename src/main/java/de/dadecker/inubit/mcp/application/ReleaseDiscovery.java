package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Finds the release of a deployment (feature 005, FR-009, research D-4).
 *
 * <ol>
 *   <li>Per source node, in configuration order, one release export (the owner-wide export by
 *       tag, {@link ArtifactPort#exportRelease}), brought into export shape
 *       ({@link ReleaseArchivePort#normalize}) and rendered in memory by the workspace codec for
 *       the <em>target</em> group (secrets become placeholders, volatile values go to
 *       {@code .meta}), so that its paths are those of the target's renderings.
 *   <li>The nodes must agree: equal canonical rendered files ({@link
 *       ReleaseArchivePort#canonical}: check-in comments and UIDs differ between nodes).
 *       Otherwise {@code SOURCE_INCONSISTENT}, with the differing paths in
 *       {@code .reports/deploy-<auditId>/source.diff}; a node on which no diagram group carries
 *       the tag while another has one is inconsistent too. A tag on no diagram group of any node
 *       is {@code NOT_FOUND}.
 *   <li>Per diagram group of the release, one head export on the first source node: a tagged
 *       workflow or module version older than head is noted (the tagged one is deployed).
 * </ol>
 *
 * <p>The release holds the source's secrets only in its raw export, in memory; its rendered
 * files hold placeholders.
 */
public final class ReleaseDiscovery {

    private static final String REPORTS = ".reports";
    private static final int MAX_NAMED = 5;

    /**
     * The release as the source holds it.
     *
     * @param files        rendered files for the target group (with {@code .meta})
     * @param fingerprint  {@code ConflictDetector.fingerprint} of the canonical {@code files}
     * @param olderThanHead notes on tagged versions older than head
     * @param export       the normalized release export of the first source node (with the
     *                     source's secret values: memory only)
     */
    public record Release(String owner, String tag, SortedSet<String> diagramGroups,
        SortedMap<String, byte[]> files, String fingerprint, List<String> olderThanHead,
        Map<String, Integer> taggedVersions, byte[] export) {
        public Release {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(tag, "tag");
            diagramGroups = new TreeSet<>(diagramGroups);
            files = new TreeMap<>(files);
            Objects.requireNonNull(fingerprint, "fingerprint");
            olderThanHead = List.copyOf(olderThanHead);
            taggedVersions = Map.copyOf(taggedVersions);
            export = Objects.requireNonNull(export, "export").clone();
        }

        @Override
        public byte[] export() {
            return export.clone();
        }

        /** Names and counts only; never content. */
        @Override
        public String toString() {
            return "Release[" + tag + ", " + diagramGroups + ", " + files.size() + " files, "
                + fingerprint + "]";
        }
    }

    private final Function<NodeId, ArtifactPort> artifacts;
    private final ArchiveCodecPort codec;
    private final ReleaseArchivePort releases;
    private final Path root;

    /**
     * @param root the workspace root (reports go to {@code .reports/deploy-<auditId>/})
     */
    public ReleaseDiscovery(Function<NodeId, ArtifactPort> artifacts, ArchiveCodecPort codec,
        ReleaseArchivePort releases, Path root) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * The release of {@code admitted}; see the class description.
     *
     * @throws ToolErrorException {@code NOT_FOUND}, {@code SOURCE_INCONSISTENT}, or the export
     *     failures of a source node ({@code CLI_UNAVAILABLE}, {@code AUTH_FAILED},
     *     {@code TIMEOUT}, …)
     */
    public Release discover(DeployGuard.Admitted admitted, UUID auditId) {
        Map<NodeId, SortedMap<String, byte[]>> rendered = new LinkedHashMap<>();
        Map<NodeId, SortedMap<String, byte[]>> canonical = new LinkedHashMap<>();
        List<NodeId> untagged = new ArrayList<>();
        ReleaseArchivePort.ReleaseExport first = null;
        for (NodeId node : admitted.sourceNodes()) {
            byte[] raw;
            try {
                raw = artifacts.apply(node).exportRelease(admitted.owner(), admitted.tag());
            } catch (ToolErrorException e) {
                if (e.error().code() != ErrorCode.NOT_FOUND) {
                    throw e;
                }
                untagged.add(node);
                continue;
            }
            ReleaseArchivePort.ReleaseExport export = releases.normalize(raw);
            first = first == null ? export : first;
            rendered.put(node, codec.prepare(admitted.target(), admitted.owner(),
                List.of(export.export())).files());
            canonical.put(node, canonical(releases, rendered.get(node)));
        }
        if (rendered.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "No diagram group of " + admitted.owner() + " carries the tag "
                    + admitted.tag() + " on " + admitted.source() + "; nothing was sent",
                "The release was not tagged on the source, the tag is spelled differently (tags"
                    + " are case-sensitive), or it belongs to another owner",
                "Tag the release's diagram groups on " + admitted.source() + " (tag_artifacts),"
                    + " then call deploy_release again"));
        }
        if (!untagged.isEmpty()) {
            throw inconsistent(admitted, "on " + String.join(", ", untagged.stream()
                .map(NodeId::value).toList()) + " no diagram group carries the tag", null);
        }
        Map<String, String> differences = differences(canonical);
        if (!differences.isEmpty()) {
            Path report = report(auditId, differences);
            throw inconsistent(admitted, differences.size() + " file(s) differ, e.g. "
                + String.join(", ", differences.keySet().stream().limit(MAX_NAMED).toList()),
                root.relativize(report).toString().replace('\\', '/'));
        }
        SortedMap<String, byte[]> files = rendered.values().iterator().next();
        return new Release(admitted.owner(), admitted.tag(), first.diagramGroups(), files,
            ConflictDetector.fingerprint(canonical.values().iterator().next()),
            olderThanHead(admitted, first),
            first.taggedVersions(), first.export());
    }

    /**
     * The canonical form ({@link ReleaseArchivePort#canonical}) of the artifact files, without
     * {@code .meta}: what fingerprints and comparisons of renderings use.
     */
    static SortedMap<String, byte[]> canonical(ReleaseArchivePort releases,
        SortedMap<String, byte[]> files) {
        SortedMap<String, byte[]> canonical = new TreeMap<>();
        files.forEach((path, content) -> {
            if (!path.startsWith(".meta/")) {
                canonical.put(path, releases.canonical(path, content));
            }
        });
        return canonical;
    }

    /** Path → how it differs, across the source nodes (in path order). */
    private static Map<String, String> differences(Map<NodeId, SortedMap<String, byte[]>> nodes) {
        Map<String, String> differences = new TreeMap<>();
        List<NodeId> ids = new ArrayList<>(nodes.keySet());
        SortedMap<String, byte[]> reference = nodes.get(ids.get(0));
        for (NodeId other : ids.subList(1, ids.size())) {
            SortedMap<String, byte[]> files = nodes.get(other);
            TreeSet<String> paths = new TreeSet<>(reference.keySet());
            paths.addAll(files.keySet());
            for (String path : paths) {
                if (path.startsWith(".meta/")) {
                    continue;
                }
                byte[] a = reference.get(path);
                byte[] b = files.get(path);
                if (a == null || b == null) {
                    differences.putIfAbsent(path, "only on " + (a == null ? other : ids.get(0)));
                } else if (!Arrays.equals(a, b)) {
                    differences.putIfAbsent(path, "differs between " + ids.get(0) + " and "
                        + other);
                }
            }
        }
        return differences;
    }

    private Path report(UUID auditId, Map<String, String> differences) {
        Path file = root.resolve(REPORTS).resolve("deploy-" + auditId).resolve("source.diff");
        StringBuilder text = new StringBuilder();
        differences.forEach((path, how) -> text.append(path).append(": ").append(how)
            .append('\n'));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The report " + file + " cannot be written (" + e.getClass().getSimpleName()
                    + "); nothing was sent",
                "The workspace is not writable", "Check the workspace directory"));
        }
        return file;
    }

    private static ToolErrorException inconsistent(DeployGuard.Admitted admitted, String detail,
        String report) {
        return new ToolErrorException(ToolError.of(ErrorCode.SOURCE_INCONSISTENT,
            "The nodes of " + admitted.source() + " do not hold the same release "
                + admitted.tag() + ": " + detail + (report == null ? "" : "; see " + report)
                + "; nothing was sent",
            "The nodes of the source group were changed or tagged separately",
            "Bring the source nodes to the same state (deploy or tag them alike), then call"
                + " deploy_release again"));
    }

    /** {@code <name> (<group>): tagged version <n>, head <m>} for each older tagged version. */
    private List<String> olderThanHead(DeployGuard.Admitted admitted,
        ReleaseArchivePort.ReleaseExport release) {
        List<String> notes = new ArrayList<>();
        NodeId node = admitted.sourceNodes().get(0);
        for (String group : release.diagramGroups()) {
            Map<String, Integer> head = new TreeMap<>(releases.versions(artifacts.apply(node)
                .exportWorkflowGroup(admitted.owner(), group)));
            head.forEach((name, version) -> {
                Integer tagged = release.taggedVersions().get(name);
                if (tagged != null && tagged < version) {
                    notes.add(name + " (" + group + "): tagged version " + tagged + ", head "
                        + version);
                }
            });
        }
        return List.copyOf(notes);
    }
}
