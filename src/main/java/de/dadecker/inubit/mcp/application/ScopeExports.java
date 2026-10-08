package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArtifactRef;
import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort.PreparedExport;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * What the server holds of a change set's scope (0.4.2): the raw exports and their rendering.
 *
 * <ul>
 *   <li>Diagram-group scope: the export of the diagram group. It holds only the modules that its
 *       workflows use, so a module of the change set that it lacks (e.g. one that a rolled-back
 *       import created, or that was exported on its own) is exported on its own when
 *       {@code lookUp} selects it; the workspace's base of such a module is its own export, so
 *       both sides have the same extent. StartCLI's {@code NOT_FOUND} means the server has no
 *       such module of that plugin type: it stays absent. With {@code referenced}, the unchanged
 *       modules the workflows of the change set use ({@link ChangeSet#referenced()}) are looked
 *       up the same way (0.4.3), so the conflict check sees the module a workflow is bound to.
 *   <li>Module scope: every module of the change set that {@code lookUp} selects; only a new
 *       module may be absent ({@code NOT_FOUND}, review I3).
 * </ul>
 */
final class ScopeExports {

    /**
     * The raw exports (in memory only) and their rendering.
     *
     * @param separate the workspace keys (module directories) of the referenced modules that were
     *                 looked up on their own, found or not
     */
    record Exported(List<byte[]> raw, PreparedExport prepared, Set<String> separate) {

        Exported {
            separate = Set.copyOf(separate);
        }

        @Override
        public String toString() {
            return "Exported[" + raw.size() + " exports]";
        }
    }

    private ScopeExports() {
    }

    static Exported export(ArtifactPort port, ArchiveCodecPort codec, ChangeSet changes,
        Predicate<ChangedArtifact> lookUp) {
        return export(port, codec, changes, lookUp, false);
    }

    static Exported export(ArtifactPort port, ArchiveCodecPort codec, ChangeSet changes,
        Predicate<ChangedArtifact> lookUp, boolean referenced) {
        ImportScope scope = changes.scope();
        List<byte[]> raw = new ArrayList<>();
        if (scope.diagramGroup().isEmpty()) {
            for (ChangedArtifact module : changes.modules()) {
                if (lookUp.test(module)) {
                    exportModule(port, scope, module.ref(),
                        module.kind() == ChangedArtifact.Kind.NEW)
                        .ifPresent(raw::add);
                }
            }
            return new Exported(raw, codec.prepare(scope.group(), scope.owner(), raw), Set.of());
        }
        raw.add(port.exportWorkflowGroup(scope.owner(), scope.diagramGroup().get()));
        PreparedExport prepared = codec.prepare(scope.group(), scope.owner(), raw);
        List<ArtifactRef> missing = new ArrayList<>(changes.modules().stream().filter(lookUp)
            .filter(module -> !holds(prepared, ConflictDetector.key(module.paths().get(0))))
            .map(ChangedArtifact::ref).toList());
        Set<String> separate = new TreeSet<>();
        if (referenced) {
            for (ArtifactRef module : changes.referenced()) {
                String key = key(scope, module);
                if (!holds(prepared, key)) {
                    missing.add(module);
                    separate.add(key);
                }
            }
        }
        if (missing.isEmpty()) {
            return new Exported(raw, prepared, separate);
        }
        for (ArtifactRef module : missing) {
            exportModule(port, scope, module, true).ifPresent(raw::add);
        }
        return new Exported(raw, codec.prepare(scope.group(), scope.owner(), raw), separate);
    }

    /** The workspace key (module directory) of a module of the scope. */
    static String key(ImportScope scope, ArtifactRef module) {
        return WorkspacePath.moduleIndex(scope.group(), scope.owner(), module.pluginType()
            .orElseThrow(), module.name()).toRelativePath().getParent().toString()
            .replace('\\', '/');
    }

    /** True if the rendering has files below {@code key}. */
    private static boolean holds(PreparedExport prepared, String key) {
        return prepared.files().keySet().stream().anyMatch(path -> path.equals(key)
            || path.startsWith(key + "/"));
    }

    private static Optional<byte[]> exportModule(ArtifactPort port, ImportScope scope,
        ArtifactRef module, boolean mayBeAbsent) {
        try {
            return Optional.of(port.exportModule(scope.owner(), module.pluginType()
                .orElseThrow(), module.name()));
        } catch (ToolErrorException e) {
            if (mayBeAbsent && e.error().code() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
