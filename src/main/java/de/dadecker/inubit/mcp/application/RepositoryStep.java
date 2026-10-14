package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference;
import de.dadecker.inubit.mcp.domain.model.RepositoryUpload;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The repository part of a development import (feature 008, research D-5, D-7): all logic that
 * is specific to repository files, so that {@link ImportService} only delegates.
 *
 * <p>Node state ({@link #nodeState}), nothing is written to INUBIT:
 *
 * <ol>
 *   <li><b>Read</b>: one {@link ArtifactPort#exportRepository} per distinct parent folder of the
 *       change set's repository files, in path order; a folder export holds every file below it
 *       (spike section 11), only the change set's files are classified. {@code NOT_FOUND} on a
 *       folder means every file below it is absent. A folder export over the read limits (the
 *       CLI's archive bound, or 64 MiB per entry and 128 MiB in total of
 *       {@code RepositoryArchive}; both {@code UNEXPECTED_RESPONSE}) falls back to one export per
 *       file. A file directly below the owner's root is exported on its own: the folder would be
 *       the whole owner root (research D-5, alternatives).
 *   <li><b>Classify</b> byte for byte ({@link ArtifactClassifier#repository}): NEW and absent on
 *       the node stays {@code NEW} ({@code create}); NEW and identical goes to
 *       {@link ChangeSet#identical()} and is not sent; NEW with other content becomes
 *       {@code EXISTING} ({@code modify} and {@code existing}); MODIFIED stays {@code MODIFIED}
 *       ({@code modify}) if the node holds its base, otherwise it is a conflict.
 *   <li><b>Refuse</b> before anything is sent: any conflict with {@code CONFLICT} and the
 *       differences in {@code .reports/conflict-<auditId>.diff} (key material only named);
 *       more than 50 files to send ({@code create} and {@code modify}, FR-017) and a
 *       {@code create} file whose extension has no known content type
 *       ({@link ReleaseArchivePort#repositoryArchiveFromFiles}) with {@code INVALID_INPUT}; a
 *       MODIFIED file whose {@code .meta} metadata is no longer readable with
 *       {@code PRECONDITION_FAILED}.
 *   <li><b>Switch</b> (FR-004, D-5 step 5): on a node whose {@code development.repository} is
 *       {@code READ_ONLY}, a change set with a repository file to send is refused with
 *       {@code REPOSITORY_WRITE_DENIED} ({@link #requireWritable}); before the node read already
 *       if a file is MODIFIED in the workspace ({@link #requireWritableBeforeRead}).
 *   <li><b>Uploads</b> ({@link RepositoryUpload}) of the files to send: MODIFIED with the
 *       {@code .meta} metadata, EXISTING with the node's metadata from the export (it keeps the
 *       node's content type and description), {@code create} without (synthesized on build).
 * </ol>
 *
 * <p>Preview of the files to send ({@link #preview}, FR-012, research D-5 step 4) and the
 * warnings of a change set with repository files ({@link #warnings}).
 *
 * <p>Execute (research D-8): {@link #send} imports the uploads with one
 * {@link ImportPort#importRepository}; {@link #reread} exports their folders again like the node
 * read; {@link #verify} compares every sent file byte for byte with its workspace content (never
 * the metadata, which every import changes, and never the reason, which INUBIT does not keep on
 * repository files, FR-015a); {@link #writeBack} rewrites only the sent files and their
 * {@code .meta} records from the re-export, never the other files of the folders.
 */
final class RepositoryStep {

    private static final int MAX_SENT_FILES = 50;
    private static final int MAX_NAMED = 20;

    /**
     * What the node holds of a change set's repository files.
     *
     * @param changes     the change set with its repository files classified (see the class
     *                    description); the identical ones are in {@link ChangeSet#identical()}
     * @param files       repository path → the node's content, for the change set's files the
     *                    node holds
     * @param rawExports  the repository exports read, in memory only
     * @param fingerprint the sorted {@code repository:<path>=sha256:<hex>} entries of
     *                    {@code files}, one per line (empty if the node holds none)
     * @param uploads     the files to send, in change-set order
     */
    record NodeState(ChangeSet changes, SortedMap<String, byte[]> files, List<byte[]> rawExports,
        String fingerprint, List<RepositoryUpload> uploads) {

        NodeState {
            Objects.requireNonNull(changes, "changes");
            files = new TreeMap<>(files);
            rawExports = rawExports.stream().map(byte[]::clone).toList();
            Objects.requireNonNull(fingerprint, "fingerprint");
            uploads = List.copyOf(uploads);
        }

        /** Counts only; never the content. */
        @Override
        public String toString() {
            return "NodeState[" + files.size() + " files, " + rawExports.size() + " exports, "
                + uploads.size() + " uploads]";
        }
    }

    /**
     * The repository part of a preview.
     *
     * @param references per file to send that workspace artifacts outside the scope use: at most
     *                   20 of them and whether there are more
     * @param diff       {@code .reports/repository-<auditId>.diff}, if a file is to be modified
     * @param sharedUse  the warning on the shared use, with the path of
     *                   {@code .reports/repository-references-<auditId>.txt}, which lists every
     *                   artifact; if any file is used outside the scope
     */
    record Preview(List<ImportPreview.RepositoryReferences> references, Optional<String> diff,
        Optional<String> sharedUse) {

        Preview {
            references = List.copyOf(references);
            Objects.requireNonNull(diff, "diff");
            Objects.requireNonNull(sharedUse, "sharedUse");
        }
    }

    /**
     * What a re-export after the import shows of the files sent ({@link #reread}).
     *
     * @param files   repository path → the node's content, for the sent files the node holds
     * @param records repository path → the {@code .meta} record of the re-export, for the same
     *                files
     * @param failure why the re-export failed, if it did (then both maps are empty)
     */
    record Reread(SortedMap<String, byte[]> files, Map<String, byte[]> records,
        Optional<String> failure) {

        Reread {
            files = new TreeMap<>(files);
            records = Map.copyOf(records);
            Objects.requireNonNull(failure, "failure");
        }

        /** Counts only; never the content. */
        @Override
        public String toString() {
            return "Reread[" + files.size() + " files" + failure.map(f -> ", failed").orElse("")
                + "]";
        }
    }

    /** The folder exports of a set of paths: what they hold of the paths, and the raw exports. */
    private record Read(SortedMap<String, byte[]> held, Map<String, byte[]> exportOf,
        List<byte[]> raw) {
    }

    private final Path root;
    private final VersionHistoryPort history;
    private final ArtifactInspectorPort inspector;
    private final ReleaseArchivePort releases;
    private final Function<NodeId, ArtifactPort> artifacts;
    private final ArtifactClassifier classifier;

    /**
     * @param root      the workspace root (workspace content, reports)
     * @param history   the workspace history (the base of a MODIFIED file)
     * @param inspector the {@code .meta} metadata of a MODIFIED file
     * @param releases  reads the repository exports and checks the uploads
     * @param artifacts the artifact port of each node
     */
    RepositoryStep(Path root, VersionHistoryPort history, ArtifactInspectorPort inspector,
        ReleaseArchivePort releases, Function<NodeId, ArtifactPort> artifacts) {
        this.root = Objects.requireNonNull(root, "root");
        this.history = Objects.requireNonNull(history, "history");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.classifier = new ArtifactClassifier(releases);
    }

    /**
     * Reads and classifies the repository files of {@code changes} on {@code node}; see the class
     * description.
     *
     * @throws ToolErrorException {@code CONFLICT}, {@code INVALID_INPUT},
     *     {@code PRECONDITION_FAILED}, or the errors of the exports (naming the node)
     */
    NodeState nodeState(NodeId node, ChangeSet changes, UUID auditId) {
        String owner = changes.scope().owner();
        Read read = read(node, owner, changes.repository().stream().map(ChangedArtifact::name)
            .toList());
        SortedMap<String, byte[]> held = read.held();
        Map<String, byte[]> exportOf = read.exportOf();

        List<ChangedArtifact> repository = new ArrayList<>();
        List<ChangedArtifact> identical = new ArrayList<>(changes.identical());
        List<String> conflicts = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        for (ChangedArtifact file : changes.repository()) {
            String path = file.name();
            byte[] now = held.get(path);
            if (file.kind() == ChangedArtifact.Kind.MODIFIED) {
                String base = file.base().orElseThrow();
                byte[] before = history.show(base, file.paths().get(0)).orElse(null);
                if (classifier.repository(before, now) != ArtifactClass.UNCHANGED) {
                    conflicts.add(path);
                    appendDiff(diff, path, base, before, now);
                } else {
                    repository.add(file);
                }
                continue;
            }
            switch (classifier.repository(workspace(file), now)) {
                case NEW -> repository.add(file);
                case UNCHANGED -> identical.add(file);
                default -> repository.add(file.existing());
            }
        }
        if (!conflicts.isEmpty()) {
            String report = ConflictDetector.report(root, auditId, diff.toString());
            throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                "Nothing was sent: repository files changed on " + node + " since the export: "
                    + String.join(", ", conflicts) + ". Differences: " + report,
                "A colleague (or the Workbench) changed the repository files after the export;"
                    + " importing now would overwrite their change without a warning",
                "Export the paths again (export_artifacts with repositoryPaths), redo your"
                    + " change on top, then import again").withNode(node));
        }
        if (repository.size() > MAX_SENT_FILES) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                repository.size() + " repository files would be sent, at most "
                    + MAX_SENT_FILES + " are imported in one call (identical files do not"
                    + " count); nothing was sent",
                "The limit keeps the preview, the verification and the rollback bounded",
                "Import narrower repository paths in several calls").withNode(node));
        }

        List<RepositoryUpload> uploads = new ArrayList<>();
        List<RepositoryUpload> created = new ArrayList<>();
        Map<byte[], Map<String, String>> metadataOf = new IdentityHashMap<>();
        for (ChangedArtifact file : repository) {
            String path = file.name();
            Optional<String> metadata = switch (file.kind()) {
                case NEW -> Optional.empty();
                case EXISTING -> Optional.of(metadataOf.computeIfAbsent(exportOf.get(path),
                    zip -> withNode(node, () -> releases.repositoryMetadata(zip))).get(path));
                case MODIFIED -> Optional.of(modifiedMetadata(file));
            };
            RepositoryUpload upload = new RepositoryUpload(path, workspace(file), metadata);
            uploads.add(upload);
            if (file.kind() == ChangedArtifact.Kind.NEW) {
                created.add(upload);
            }
        }
        if (!created.isEmpty()) {
            // INVALID_INPUT naming the supported extensions for a file without a content type
            releases.repositoryArchiveFromFiles(owner, created);
        }

        ChangeSet classified = new ChangeSet(changes.scope(), changes.baseCommit(),
            changes.workflows(), changes.modules(), repository, changes.notImported(), identical,
            changes.referenced());
        return new NodeState(classified, held, read.raw(), fingerprint(held), uploads);
    }

    /**
     * Sends the uploads of {@code state} to the node with one repository import into
     * {@code /Root/<owner>} (research D-8 step 2).
     *
     * @throws ToolErrorException the errors of the import
     */
    void send(ImportPort port, String owner, NodeState state) {
        port.importRepository(releases.repositoryArchiveFromFiles(owner, state.uploads()), owner);
    }

    /**
     * Exports the folders of the sent files of {@code state} again, like {@link #nodeState}, and
     * keeps the content and the {@code .meta} record of each sent file the node holds; a failing
     * export is the {@link Reread#failure()}.
     */
    Reread reread(NodeId node, NodeState state) {
        String owner = state.changes().scope().owner();
        try {
            Read read = read(node, owner, state.uploads().stream().map(RepositoryUpload::path)
                .toList());
            Map<byte[], Map<String, byte[]>> recordsOf = new IdentityHashMap<>();
            Map<String, byte[]> records = new HashMap<>();
            read.held().keySet().forEach(path -> records.put(path, recordsOf.computeIfAbsent(
                read.exportOf().get(path), zip -> withNode(node,
                    () -> releases.repositoryMetaRecords(zip))).get(path)));
            return new Reread(read.held(), records, Optional.empty());
        } catch (ToolErrorException e) {
            return new Reread(new TreeMap<>(), Map.of(), Optional.of(e.error().code() + ": "
                + e.error().message()));
        }
    }

    /**
     * The differences of the sent files between their workspace content and the re-export,
     * byte for byte, as {@code === <path>} blocks with a line diff (empty if all are identical).
     */
    List<String> verify(NodeId node, NodeState state, Reread after) {
        List<String> differences = new ArrayList<>();
        for (RepositoryUpload upload : state.uploads()) {
            byte[] sent = upload.content();
            byte[] now = after.files().get(upload.path());
            if (Arrays.equals(sent, now)) {
                continue;
            }
            differences.add("=== " + upload.path());
            differences.add("--- sent: " + sent.length + " bytes");
            differences.add("+++ on " + node + ": " + (now == null ? "(missing)"
                : now.length + " bytes"));
            StringBuilder diff = new StringBuilder();
            ConflictDetector.LineDiff.append(diff, upload.path(), "sent", "on " + node, sent,
                now);
            differences.addAll(diff.toString().lines().toList());
        }
        return differences;
    }

    /**
     * Rewrites the workspace file and the {@code .meta} record of every sent file from the
     * verified re-export (research D-8 step 5); other files of the exported folders are not
     * written.
     */
    void writeBack(NodeState state, Reread after) {
        try {
            for (ChangedArtifact file : state.changes().repository()) {
                Path content = root.resolve(file.paths().get(0));
                Path meta = root.resolve(WorkspacePath.parse(Path.of(file.paths().get(0)))
                    .metaPath());
                Files.createDirectories(content.getParent());
                Files.write(content, after.files().get(file.name()));
                Files.createDirectories(meta.getParent());
                Files.write(meta, after.records().get(file.name()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The sent files the node held before the call whose content differs now, all of them if
     * the re-export failed: what a failure after sending leaves changed on the node.
     */
    List<String> modifiedOnNode(NodeState state, Reread after) {
        return state.uploads().stream().map(RepositoryUpload::path)
            .filter(path -> state.files().containsKey(path))
            .filter(path -> after.failure().isPresent()
                || !Arrays.equals(state.files().get(path), after.files().get(path)))
            .toList();
    }

    /**
     * The sent files the node did not hold before the call and holds now, all of them if the
     * re-export failed: created, and never removed (an import cannot delete).
     */
    List<String> createdOnNode(NodeState state, Reread after) {
        return state.uploads().stream().map(RepositoryUpload::path)
            .filter(path -> !state.files().containsKey(path))
            .filter(path -> after.failure().isPresent() || after.files().containsKey(path))
            .toList();
    }

    /**
     * The repository part of the preview of the files to send in {@code state} (FR-012, research
     * D-5 step 4, edge case "Shared use"); writes the reports below {@code .reports/}:
     *
     * <ul>
     *   <li>{@code repository-<auditId>.diff}: per file to modify (MODIFIED or EXISTING) the line
     *       differences between the node and the workspace; key material is only named, never
     *       shown (the workspace never holds any, ChangeSetBuilder refuses it);
     *   <li>{@code repository-references-<auditId>.txt}: per file every workspace artifact
     *       outside the scope, of any owner of the group, that uses it ({@code use}), only if
     *       there is any.
     * </ul>
     */
    Preview preview(NodeId node, NodeState state, ChangeSetBuilder.RepositoryUse use,
        UUID auditId) {
        StringBuilder diff = new StringBuilder();
        List<ImportPreview.RepositoryReferences> references = new ArrayList<>();
        StringBuilder used = new StringBuilder();
        List<String> counts = new ArrayList<>();
        for (ChangedArtifact file : state.changes().repository()) {
            String path = file.name();
            if (file.onServer()) {
                byte[] now = state.files().get(path);
                if (releases.keyMaterial(path, now)) {
                    diff.append("=== ").append(path).append(": differs on ").append(node)
                        .append(" from the workspace (key material, not shown)\n");
                } else {
                    ConflictDetector.LineDiff.append(diff, path, "on " + node, "workspace", now,
                        workspace(file));
                }
            }
            SortedSet<String> names = use.referrers().get(path);
            if (names != null) {
                references.add(new ImportPreview.RepositoryReferences(path, names.stream()
                    .limit(MAX_NAMED).toList(), names.size() > MAX_NAMED));
                counts.add(path + " (" + names.size() + ")");
                used.append(path).append(" (").append(names.size()).append("):\n");
                names.forEach(name -> used.append("  ").append(name).append('\n'));
            }
        }
        Optional<String> diffReport = diff.isEmpty() ? Optional.empty() : Optional.of(
            ConflictDetector.report(root, "repository-" + auditId + ".diff", diff.toString()));
        Optional<String> sharedUse = Optional.empty();
        if (!references.isEmpty()) {
            String report = ConflictDetector.report(root, "repository-references-" + auditId
                + ".txt", "Workspace artifacts (of every owner of the group) outside "
                + state.changes().scope().describe() + " that use the repository files to send"
                + " to " + node + ":\n\n" + used);
            sharedUse = Optional.of("Workspace artifacts outside the scope (of every owner of"
                + " the group in the workspace) use repository files of this import, which"
                + " change for them too: " + String.join(", ", counts) + "; every artifact: "
                + report);
        }
        return new Preview(references, diffReport, sharedUse);
    }

    /**
     * The warnings of a call whose change set had repository files ({@code changes} after the
     * node read, also if they all turned out identical), for its preview and its result: every
     * reference the closure did not follow because it leaves the owner's repository root
     * (FR-011; at most 20, then how many more), and, if a file is to be modified, that
     * references on the node which the workspace does not hold cannot be listed (edge case
     * "Shared use").
     */
    static List<String> warnings(NodeId node, ChangeSet changes,
        ChangeSetBuilder.RepositoryUse use) {
        String root = "/Root/" + changes.scope().owner() + "/";
        List<String> warnings = new ArrayList<>();
        List<RepositoryReference> outside = use.outsideOwner();
        outside.stream().limit(MAX_NAMED).forEach(reference -> warnings.add(reference.from()
            .toRelativePath().toString().replace('\\', '/') + " references " + reference.to()
            + ", outside the owner's repository root " + root + ": not followed and not"
            + " imported"));
        if (outside.size() > MAX_NAMED) {
            warnings.add((outside.size() - MAX_NAMED) + " more references outside the owner's"
                + " repository root " + root + " were not followed");
        }
        List<String> modified = changes.repository().stream().filter(ChangedArtifact::onServer)
            .map(ChangedArtifact::name).toList();
        if (!modified.isEmpty()) {
            warnings.add("Artifacts on " + node + " that the workspace does not hold (of any"
                + " owner) may use "
                + String.join(", ", modified) + " as well; such references cannot be listed."
                + " Export the users of these files, or check them in the Workbench, before"
                + " approving");
        }
        return warnings;
    }

    /**
     * The cheap pre-check of the switch right after the change set is built, before anything is
     * read (research D-5 step 5): a repository file MODIFIED in the workspace differs from its
     * base and is always sent, so on a read-only node the call is refused at once.
     *
     * @throws ToolErrorException {@code REPOSITORY_WRITE_DENIED} naming the change set's
     *     repository files
     */
    static void requireWritableBeforeRead(DevelopmentPolicy policy, ChangeSet changes) {
        if (changes.repository().stream()
            .anyMatch(file -> file.kind() == ChangedArtifact.Kind.MODIFIED)) {
            requireWritable(policy, changes);
        }
    }

    /**
     * The switch check after the node read ({@link NodeState#changes()}), before anything is
     * sent: every repository file still in the change set is created or modified, the identical
     * ones are not; reading is always allowed (FR-004).
     *
     * @throws ToolErrorException {@code REPOSITORY_WRITE_DENIED} naming the repository files to
     *     send, if the node is read-only and there is one
     */
    static void requireWritable(DevelopmentPolicy policy, ChangeSet changes) {
        if (policy.repositoryWrite() || changes.repository().isEmpty()) {
            return;
        }
        NodeId node = policy.node();
        throw new ToolErrorException(ToolError.of(ErrorCode.REPOSITORY_WRITE_DENIED,
            "development.repository is READ_ONLY on " + node + "; the change set holds the"
                + " repository files " + String.join(", ", changes.repository().stream()
                    .map(ChangedArtifact::name).toList()) + " to send, so the whole call is"
                + " refused and nothing was sent",
            "Repository files may only be written on a development node whose"
                + " development.repository is WRITE; reading them is always allowed",
            "Revert the repository files in the workspace (git restore), or ask the"
                + " administrator to set development.repository: WRITE on " + node
                + "; then import again").withNode(node));
    }

    /**
     * One export per distinct parent folder of {@code paths} (repository paths of
     * {@code owner}), in path order; a file directly below the owner's root, or below a folder
     * over the read limits ({@code UNEXPECTED_RESPONSE}), is exported on its own.
     */
    private Read read(NodeId node, String owner, List<String> paths) {
        ArtifactPort port = artifacts.apply(node);
        SortedMap<String, List<String>> folders = new TreeMap<>();
        for (String path : paths) {
            folders.computeIfAbsent(path.substring(0, path.lastIndexOf('/')),
                folder -> new ArrayList<>()).add(path);
        }
        SortedMap<String, byte[]> held = new TreeMap<>();
        Map<String, byte[]> exportOf = new HashMap<>();
        List<byte[]> raw = new ArrayList<>();
        for (Map.Entry<String, List<String>> folder : folders.entrySet()) {
            List<String> below = folder.getValue();
            if (!folder.getKey().equals("/Root/" + owner)) {
                try {
                    read(node, port, folder.getKey(), below, held, exportOf, raw);
                    continue;
                } catch (ToolErrorException e) {
                    if (e.error().code() != ErrorCode.UNEXPECTED_RESPONSE) {
                        throw e;
                    }
                    // over the read limits: one export per file below
                }
            }
            for (String path : below) {
                read(node, port, path, List.of(path), held, exportOf, raw);
            }
        }
        return new Read(held, exportOf, raw);
    }

    /**
     * Exports {@code path} (a folder or a file) and records the node's content of
     * {@code paths} it holds; nothing for {@code NOT_FOUND}.
     */
    private void read(NodeId node, ArtifactPort port, String path, List<String> paths,
        Map<String, byte[]> held, Map<String, byte[]> exportOf, List<byte[]> raw) {
        Optional<byte[]> zip = exported(node, () -> port.exportRepository(path));
        if (zip.isEmpty()) {
            return;
        }
        Map<String, byte[]> files = withNode(node, () -> releases.repositoryFiles(zip.get()));
        raw.add(zip.get());
        for (String file : paths) {
            byte[] content = files.get(file);
            if (content != null) {
                held.put(file, content);
                exportOf.put(file, zip.get());
            }
        }
    }

    /** The {@code .meta} metadata of a MODIFIED file; ChangeSetBuilder checked it before. */
    private String modifiedMetadata(ChangedArtifact file) {
        WorkspacePath path = WorkspacePath.parse(Path.of(file.paths().get(0)));
        return inspector.repositoryMetadata(root, path).orElseThrow(() ->
            new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The .meta record of " + file.name() + " (" + path.metaPath().toString()
                    .replace('\\', '/') + ") is missing or its metadata is not readable;"
                    + " nothing was sent",
                "Without the exported metadata the repository file cannot be rebuilt faithfully",
                "export the path first (export_artifacts with repositoryPaths), reapply the edit"
                    + " from the workspace history, then repeat the call")));
    }

    /** The line difference of a conflict; key material is only named, never shown. */
    private void appendDiff(StringBuilder diff, String path, String base, byte[] before,
        byte[] now) {
        if ((before != null && releases.keyMaterial(path, before))
            || (now != null && releases.keyMaterial(path, now))) {
            diff.append("=== ").append(path).append(": differs from the workspace base (key"
                + " material, not shown)\n");
            return;
        }
        ConflictDetector.LineDiff.append(diff, path, "workspace base " + base.substring(0,
            Math.min(12, base.length())), "server now", before, now);
    }

    private byte[] workspace(ChangedArtifact file) {
        try {
            return Files.readAllBytes(root.resolve(file.paths().get(0)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fingerprint(SortedMap<String, byte[]> files) {
        List<String> entries = new ArrayList<>();
        try {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                entries.add("repository:" + file.getKey() + "=sha256:" + HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(file.getValue())));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return String.join("\n", entries);
    }

    /** {@code export}, empty for {@code NOT_FOUND}; other failures name the node. */
    private static Optional<byte[]> exported(NodeId node, Supplier<byte[]> export) {
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

    private static <T> T withNode(NodeId node, Supplier<T> action) {
        try {
            return action.get();
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(node));
        }
    }
}
