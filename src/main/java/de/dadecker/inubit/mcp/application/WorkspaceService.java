package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort.PreparedExport;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 *       files and history untouched (FR-018);
 *   <li>replace the affected sub-trees (FR-017) — a failure while writing restores the owner's
 *       directories from the last history entry (safe because step 2 committed everything);
 *   <li>commit {@code export <group>/<node>: <what> (<n> files)} if anything changed;
 *   <li>release the lock.
 * </ol>
 */
public final class WorkspaceService {

    /** The file the history creates itself; never a local change of the person. */
    private static final String GITIGNORE = ".gitignore";

    /** A module to export; without a plugin type it is looked up in the module list. */
    public record ModuleRef(String name, Optional<String> pluginType) {

        public ModuleRef {
            Objects.requireNonNull(name, "name");
            pluginType = pluginType == null ? Optional.empty() : pluginType;
        }
    }

    /**
     * What to export from {@code node} for {@code owner}: exactly one of {@code diagramGroups}
     * (technical workflows only) and {@code modules} is non-empty.
     */
    public record ExportRequest(NodeId node, String owner, List<String> diagramGroups,
        List<ModuleRef> modules) {

        public ExportRequest {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(owner, "owner");
            diagramGroups = List.copyOf(diagramGroups);
            modules = List.copyOf(modules);
            if (diagramGroups.isEmpty() == modules.isEmpty()) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "Give either diagramGroups or modules, not both and not neither",
                    "One export covers diagram groups or single modules",
                    "Call export_artifacts once per kind"));
            }
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
     *     module missing from the module list, {@code UNEXPECTED_RESPONSE} for an archive that
     *     cannot be processed, {@code INVALID_INPUT} for a case-only path collision
     */
    public ExportResult export(ExportRequest request) {
        NodeId node = request.node();
        try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
            history.init();
            Optional<HistoryEntry> localChanges = commitLocalChanges();
            ArtifactPort port = artifacts.apply(node);
            List<byte[]> archives = new ArrayList<>();
            String what;
            if (!request.diagramGroups().isEmpty()) {
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
            PreparedExport prepared = withNode(node, () -> codec.prepare(node.group(),
                request.owner(), archives));
            write(prepared);
            List<PathChange> changes = history.status();
            Optional<HistoryEntry> export = changes.isEmpty() ? Optional.empty()
                : history.commitAll("export " + node.value() + ": " + what + " ("
                    + changes.size() + " files)");
            return new ExportResult(node, request.owner(), root, localChanges, export,
                prepared.secretsReplaced(), prepared.warnings());
        }
    }

    /**
     * Step 2: everything uncommitted becomes one entry, so that nothing of the person is lost
     * and a failed write can be undone. The {@code .gitignore} the history has just created is
     * not a change of the person; it goes into the export entry.
     */
    private Optional<HistoryEntry> commitLocalChanges() {
        List<PathChange> changes = history.status().stream()
            .filter(change -> !(change.path().equals(GITIGNORE)
                && change.kind() == PathChange.Kind.ADDED))
            .toList();
        if (changes.isEmpty()) {
            return Optional.empty();
        }
        return history.commitAll("local changes: " + changes.size() + " files");
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

    /** Step 4; a failure restores the owner's directories from the last entry. */
    private void write(PreparedExport prepared) {
        try {
            prepared.writeTo(root);
        } catch (RuntimeException e) {
            try {
                prepared.scope().forEach(directory -> history.restore(Path.of(directory)));
            } catch (RuntimeException restoreFailure) {
                e.addSuppressed(restoreFailure);
                throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                    "The workspace " + root + " could not be written ("
                        + e.getClass().getSimpleName() + ") and not be restored",
                    "The file system reported an error",
                    "Check the workspace with git status and restore it with git restore"));
            }
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The workspace " + root + " could not be written (" + e.getClass()
                    .getSimpleName() + "); the affected directories were restored from the"
                    + " history",
                "The file system reported an error (e.g. no space left, no permission)",
                "Fix the file system problem and export again"));
        }
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
