package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ChangeSet;
import de.dadecker.inubit.mcp.domain.model.ChangedArtifact;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort.PreparedExport;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 *       such module of that plugin type: it stays absent.
 *   <li>Module scope: every module of the change set that {@code lookUp} selects; only a new
 *       module may be absent ({@code NOT_FOUND}, review I3).
 * </ul>
 */
final class ScopeExports {

    /** The raw exports (in memory only) and their rendering. */
    record Exported(List<byte[]> raw, PreparedExport prepared) {

        @Override
        public String toString() {
            return "Exported[" + raw.size() + " exports]";
        }
    }

    private ScopeExports() {
    }

    static Exported export(ArtifactPort port, ArchiveCodecPort codec, ChangeSet changes,
        Predicate<ChangedArtifact> lookUp) {
        ImportScope scope = changes.scope();
        List<byte[]> raw = new ArrayList<>();
        if (scope.diagramGroup().isEmpty()) {
            for (ChangedArtifact module : changes.modules()) {
                if (lookUp.test(module)) {
                    exportModule(port, scope, module, module.kind() == ChangedArtifact.Kind.NEW)
                        .ifPresent(raw::add);
                }
            }
            return new Exported(raw, codec.prepare(scope.group(), scope.owner(), raw));
        }
        raw.add(port.exportWorkflowGroup(scope.owner(), scope.diagramGroup().get()));
        PreparedExport prepared = codec.prepare(scope.group(), scope.owner(), raw);
        List<ChangedArtifact> missing = changes.modules().stream().filter(lookUp)
            .filter(module -> !holds(prepared, module)).toList();
        if (missing.isEmpty()) {
            return new Exported(raw, prepared);
        }
        for (ChangedArtifact module : missing) {
            exportModule(port, scope, module, true).ifPresent(raw::add);
        }
        return new Exported(raw, codec.prepare(scope.group(), scope.owner(), raw));
    }

    /** True if the rendering has files of {@code artifact}. */
    static boolean holds(PreparedExport prepared, ChangedArtifact artifact) {
        String key = ConflictDetector.key(artifact.paths().get(0));
        return prepared.files().keySet().stream().anyMatch(path -> path.equals(key)
            || path.startsWith(key + "/"));
    }

    private static Optional<byte[]> exportModule(ArtifactPort port, ImportScope scope,
        ChangedArtifact module, boolean mayBeAbsent) {
        try {
            return Optional.of(port.exportModule(scope.owner(), module.ref().pluginType()
                .orElseThrow(), module.name()));
        } catch (ToolErrorException e) {
            if (mayBeAbsent && e.error().code() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
