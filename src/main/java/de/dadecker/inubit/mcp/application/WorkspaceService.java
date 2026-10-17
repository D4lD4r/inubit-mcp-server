package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.RepositoryPaths;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort.PreparedExport;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The workspace of one profile: exports into readable files with a local history (feature 003).
 *
 * <p>{@link #export} is one transaction (research D-9):
 *
 * <ol>
 *   <li>lock the workspace — a second export or check is refused at once (FR-020);
 *   <li>commit uncommitted changes as {@code local changes: <n> files} (clarification 1, FR-019);
 *   <li>run the StartCLI export(s) and read, redact and render them in memory — a failure leaves
 *       files and history untouched (FR-018); a repository export (feature 008, research D-10)
 *       runs one export per path, fails as a whole if any path is missing and is refused above
 *       500 files or 100 MiB, all before anything is written;
 *   <li>replace the affected sub-trees (FR-017) — a failure while writing restores the owner's
 *       directories from the last history entry (safe because step 2 committed everything); a
 *       repository export overwrites the files of its paths and deletes none;
 *   <li>commit {@code export <group>/<node>: <what> (<n> files)} if anything changed, with the
 *       trailer {@code Server-State: <group>}: the entry records a server state, the base of
 *       later imports (feature 004, research D-3, D-25);
 *   <li>release the lock.
 * </ol>
 */
public final class WorkspaceService {

    /** The file the history creates itself; never a local change of the person. */
    private static final String GITIGNORE = ".gitignore";
    /** The directory of full result lists (ignored by the history). */
    private static final String REPORTS = ".reports";
    /** The most files one repository export writes (FR-009). */
    private static final int MAX_REPOSITORY_FILES = 500;
    /** The most bytes one repository export writes (FR-009): 100 MiB. */
    private static final long MAX_REPOSITORY_BYTES = 100L << 20;

    /** A module to export; without a plugin type it is looked up in the module list. */
    public record ModuleRef(String name, Optional<String> pluginType) {

        public ModuleRef {
            Objects.requireNonNull(name, "name");
            pluginType = pluginType == null ? Optional.empty() : pluginType;
        }
    }

    /**
     * What to export from {@code node} for {@code owner}: exactly one of {@code diagramGroups}
     * (technical workflows only), {@code modules} and {@code repositoryPaths} is non-empty.
     * Repository paths (feature 008, FR-009) are 1–{@value #MAX_REPOSITORY_PATHS} distinct
     * normalized paths relative to {@code /Root/<owner>} ({@link RepositoryPaths#relativeValid})
     * of which no two differ only in letter case, for an owner that is a repository segment
     * ({@link RepositoryPaths#ownerValid}).
     */
    public record ExportRequest(NodeId node, String owner, List<String> diagramGroups,
        List<ModuleRef> modules, List<String> repositoryPaths) {

        /** The most repository paths of one export (FR-009). */
        public static final int MAX_REPOSITORY_PATHS = 20;

        public ExportRequest {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(owner, "owner");
            diagramGroups = List.copyOf(diagramGroups);
            modules = List.copyOf(modules);
            repositoryPaths = List.copyOf(repositoryPaths);
            int scopes = (diagramGroups.isEmpty() ? 0 : 1) + (modules.isEmpty() ? 0 : 1)
                + (repositoryPaths.isEmpty() ? 0 : 1);
            if (scopes != 1) {
                throw invalidInput("Give exactly one of diagramGroups, modules or repositoryPaths",
                    "One export covers diagram groups, single modules or repository paths",
                    "Call export_artifacts once per kind");
            }
            if (!repositoryPaths.isEmpty()) {
                checkRepositoryPaths(owner, repositoryPaths);
            }
        }

        private static void checkRepositoryPaths(String owner, List<String> paths) {
            if (!RepositoryPaths.ownerValid(owner)) {
                throw invalidInput("The owner " + owner + " is no repository root StartCLI can"
                    + " take; nothing was exported",
                    "Repository paths are exported below /Root/<owner>; the owner comes from the"
                        + " call or from inventory.owner",
                    "Give an owner of letters, digits, '_', '.', '-' and spaces that is not . or"
                        + " ..");
            }
            if (paths.size() > MAX_REPOSITORY_PATHS) {
                throw invalidInput("At most " + MAX_REPOSITORY_PATHS + " repositoryPaths per"
                    + " export, not " + paths.size(), "The export covers too many paths",
                    "Export a common parent folder or split the paths over several calls");
            }
            Map<String, String> byLowerCase = new HashMap<>();
            for (String path : paths) {
                if (!RepositoryPaths.relativeValid(path)) {
                    throw invalidInput("The repository path '" + path + "' is not a path"
                        + " relative to /Root/" + owner,
                        "A repository path has no leading /, no empty, . or .. segment, no %"
                            + " and is not the owner's root itself",
                        "Give the path below /Root/" + owner + ", e.g. stylesheets/common");
                }
                String other = byLowerCase.putIfAbsent(path.toLowerCase(Locale.ROOT), path);
                if (path.equals(other)) {
                    throw invalidInput("The repository path " + path + " is given twice",
                        "A path is listed more than once", "Give each path once");
                }
                if (other != null) {
                    throw invalidInput("The repository paths " + other + " and " + path
                        + " differ only in letter case",
                        "They would overwrite each other on a case-insensitive file system",
                        "Export only one of them");
                }
            }
        }

        private static ToolErrorException invalidInput(String message, String likelyCause,
            String nextStep) {
            return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
                likelyCause, nextStep));
        }
    }

    /**
     * The outcome of an export: the local-changes entry (if one was needed), the export entry
     * (empty if the export changed nothing, SC-001), the number of replaced secrets and warnings.
     */
    public record ExportResult(NodeId node, String owner, Path workspace,
        Optional<HistoryEntry> localChanges, Optional<HistoryEntry> export, int secretsReplaced,
        List<String> warnings) {

        public ExportResult {
            warnings = List.copyOf(warnings);
        }

        /** {@code true} if the export changed no file. */
        public boolean unchanged() {
            return export.isEmpty();
        }
    }

    private final Path root;
    private final VersionHistoryPort history;
    private final ArchiveCodecPort codec;
    private final Function<NodeId, ArtifactPort> artifacts;
    private final Function<NodeId, InventoryPort> inventory;

    /**
     * @param root the workspace root
     * @param history the history of {@code root}
     * @param codec turns export archives into files
     * @param artifacts the artifact port of a server
     * @param inventory the inventory port of a server (module list for plugin types)
     */
    public WorkspaceService(Path root, VersionHistoryPort history, ArchiveCodecPort codec,
        Function<NodeId, ArtifactPort> artifacts, Function<NodeId, InventoryPort> inventory) {
        this.root = Objects.requireNonNull(root, "root");
        this.history = Objects.requireNonNull(history, "history");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    /** The workspace root. */
    public Path root() {
        return root;
    }

    /**
     * Exports {@code request} into the workspace (see the class description).
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} (workspace busy or not usable, or a
     *     write failed and was undone), the errors of the artifact port, {@code NOT_FOUND} for a
     *     module missing from the module list or for missing repository paths (all named),
     *     {@code UNEXPECTED_RESPONSE} for an archive that cannot be processed,
     *     {@code INVALID_INPUT} for a case-only path collision or a repository export over its
     *     limits
     */
    public ExportResult export(ExportRequest request) {
        NodeId node = request.node();
        try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
            history.init();
            Optional<HistoryEntry> localChanges = commitLocalChanges();
            ArtifactPort port = artifacts.apply(node);
            List<byte[]> archives = new ArrayList<>();
            boolean repository = !request.repositoryPaths().isEmpty();
            String what;
            if (repository) {
                archives.addAll(exportRepository(port, request));
                what = "repository " + String.join(", ", request.repositoryPaths());
            } else if (!request.diagramGroups().isEmpty()) {
                for (String diagramGroup : request.diagramGroups()) {
                    archives.add(port.exportWorkflowGroup(request.owner(), diagramGroup));
                }
                what = describe("diagram group", request.diagramGroups());
            } else {
                Map<String, String> pluginTypes = pluginTypes(request);
                List<String> names = new ArrayList<>();
                for (ModuleRef module : request.modules()) {
                    archives.add(port.exportModule(request.owner(),
                        pluginTypes.get(module.name()), module.name()));
                    names.add(module.name());
                }
                what = describe("module", names);
            }
            PreparedExport prepared = withNode(node, () -> repository
                ? codec.prepareRepository(node.group(), request.owner(), archives)
                : codec.prepare(node.group(), request.owner(), archives));
            if (repository) {
                checkRepositoryLimits(node, prepared);
            }
            Optional<HistoryEntry> export;
            try {
                prepared.writeTo(root);
                List<PathChange> changes = history.status();
                export = changes.isEmpty() ? Optional.empty()
                    : history.commitAll("export " + node.value() + ": " + what + " ("
                        + changes.size() + " files)",
                        Map.of(VersionHistoryPort.SERVER_STATE, node.group().value()));
            } catch (RuntimeException e) {
                throw undo(prepared, e);
            }
            return new ExportResult(node, request.owner(), root, localChanges, export,
                prepared.secretsReplaced(), prepared.warnings());
        }
    }

    /**
     * Writes {@code lines} to {@code .reports/<fileName>} (ignored by the history, research D-10)
     * and returns its workspace-relative path.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if it cannot be written
     */
    public String writeReport(String fileName, List<String> lines) {
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
            || fileName.startsWith(".")) {
            throw new IllegalArgumentException("A report name is a plain file name");
        }
        Path file = root.resolve(REPORTS).resolve(fileName);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The report " + file + " cannot be written (" + e.getClass().getSimpleName()
                    + ")",
                "The workspace is not writable", "Check the workspace directory's permissions"));
        }
        return REPORTS + "/" + fileName;
    }

    /**
     * Step 2: everything uncommitted becomes one entry, so that nothing of the person is lost
     * and a failed write can be undone. The {@code .gitignore} the history has just created is
     * not a change of the person: alone it goes into the export entry; together with changes of
     * the person it is part of this entry and counted (review M4).
     */
    private Optional<HistoryEntry> commitLocalChanges() {
        List<PathChange> changes = history.status();
        boolean onlyCreatedByTheHistory = changes.stream().allMatch(change -> change.path()
            .equals(GITIGNORE) && change.kind() == PathChange.Kind.ADDED);
        if (onlyCreatedByTheHistory) {
            return Optional.empty();
        }
        return history.commitAll("local changes: " + changes.size() + " files");
    }

    /**
     * One repository export per requested path (research D-10). Every path is tried, so that a
     * {@code NOT_FOUND} names all missing paths; nothing has been written at this point.
     */
    private static List<byte[]> exportRepository(ArtifactPort port, ExportRequest request) {
        List<byte[]> archives = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String path : request.repositoryPaths()) {
            String full = RepositoryPaths.full(request.owner(), path);
            try {
                archives.add(port.exportRepository(full));
            } catch (ToolErrorException e) {
                if (e.error().code() != ErrorCode.NOT_FOUND) {
                    throw e;
                }
                missing.add(full);
            }
        }
        if (!missing.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "The repository path(s) " + String.join(", ", missing) + " do not exist on "
                    + request.node() + "; nothing was exported",
                "The repository file or folder does not exist (paths are case-sensitive)",
                "Check the paths below /Root/" + request.owner()
                    + " in the Workbench repository, then retry")
                .withNode(request.node()));
        }
        return archives;
    }

    /**
     * The limits of one repository export (FR-009), checked before anything is written: the
     * rendered files without their {@code .meta} records, by count and size.
     */
    private static void checkRepositoryLimits(NodeId node, PreparedExport prepared) {
        int files = 0;
        long bytes = 0;
        for (Map.Entry<String, byte[]> file : prepared.files().entrySet()) {
            if (!file.getKey().startsWith(WorkspacePath.META_DIRECTORY + "/")) {
                files++;
                bytes += file.getValue().length;
            }
        }
        if (files > MAX_REPOSITORY_FILES || bytes > MAX_REPOSITORY_BYTES) {
            String mebibytes = mebibytes(bytes);
            // in bytes where one decimal would hide the size (0.0) or the excess (100.0)
            boolean inBytes = mebibytes.equals(mebibytes(0))
                || mebibytes.equals(mebibytes(MAX_REPOSITORY_BYTES));
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The repository export holds " + files + " files and "
                    + (inBytes ? bytes + " bytes" : mebibytes + " MiB")
                    + "; one export writes at most " + MAX_REPOSITORY_FILES + " files and "
                    + (MAX_REPOSITORY_BYTES >> 20) + " MiB"
                    + (inBytes ? " (" + MAX_REPOSITORY_BYTES + " bytes)" : ""),
                "The repository paths cover too many or too large files",
                "Export narrower repository paths (sub-folders or single files)")
                .withNode(node));
        }
    }

    /** {@code bytes} in MiB with one decimal. */
    private static String mebibytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (double) (1 << 20));
    }

    /** The plugin type of each requested module, looked up once if any is missing. */
    private Map<String, String> pluginTypes(ExportRequest request) {
        Map<String, String> types = new HashMap<>();
        request.modules().forEach(module -> module.pluginType()
            .ifPresent(type -> types.put(module.name(), type)));
        if (types.size() == request.modules().size()) {
            return types;
        }
        Map<String, String> listed = new HashMap<>();
        for (ModuleEntry entry : inventory.apply(request.node()).listModules(request.owner())) {
            listed.putIfAbsent(entry.item().name(), entry.item().type());
        }
        for (ModuleRef module : request.modules()) {
            if (types.containsKey(module.name())) {
                continue;
            }
            String type = listed.get(module.name());
            if (type == null) {
                throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                    "The module " + module.name() + " of " + request.owner()
                        + " is not in the module list of " + request.node(),
                    "The module does not exist for this owner (names are case-sensitive)",
                    "Check the name with list_inventory (kind module) or give the pluginType")
                    .withNode(request.node()));
            }
            types.put(module.name(), type);
        }
        return types;
    }

    /**
     * Steps 4 and 5 failed (review M1): the owner's directories are restored from the last
     * entry, so that neither a half-written export nor an unrecorded one remains (the next
     * export would otherwise record the server state as local changes).
     */
    private ToolErrorException undo(PreparedExport prepared, RuntimeException failure) {
        try {
            prepared.scope().forEach(directory -> history.restore(Path.of(directory)));
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
            return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The export into " + root + " failed (" + failure.getClass().getSimpleName()
                    + ") and the workspace could not be restored",
                "The file system or git reported an error",
                "Check the workspace with git status and restore it with git restore"));
        }
        if (failure instanceof ToolErrorException tool) {
            ToolError error = tool.error();
            return new ToolErrorException(new ToolError(error.code(), error.message()
                + "; the affected directories were restored from the history",
                error.likelyCause(), error.nextStep(), error.node(), error.excerpt()));
        }
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "The workspace " + root + " could not be written (" + failure.getClass()
                .getSimpleName() + "); the affected directories were restored from the"
                + " history",
            "The file system reported an error (e.g. no space left, no permission)",
            "Fix the file system problem and export again"));
    }

    private static <T> T withNode(NodeId node, Supplier<T> action) {
        try {
            return action.get();
        } catch (ToolErrorException e) {
            if (e.error().node().isPresent()) {
                throw e;
            }
            throw new ToolErrorException(e.error().withNode(node));
        }
    }

    private static String describe(String kind, List<String> names) {
        return kind + (names.size() == 1 ? " " : "s ") + String.join(", ", names);
    }
}
