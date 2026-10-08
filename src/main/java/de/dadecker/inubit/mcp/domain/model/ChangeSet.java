package de.dadecker.inubit.mcp.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What an import sends (feature 004, research D-4, D-25, data-model.md → ChangeSet): the changed
 * and new workflows and modules of one scope, each against its own last server state; changes
 * of the owner outside the scope are only listed.
 *
 * @param baseCommit  the last server state of the scope (the base of new artifacts)
 * @param notImported workspace-relative files that changed outside the scope
 * @param identical   artifacts new in the workspace that the server has with the same content
 *                    (0.4.2): not sent, their server state is recorded after a successful import
 */
public record ChangeSet(ImportScope scope, String baseCommit, List<ChangedArtifact> workflows,
    List<ChangedArtifact> modules, List<String> notImported, List<ChangedArtifact> identical) {

    /** A change set before it was compared with the server (nothing identical yet). */
    public ChangeSet(ImportScope scope, String baseCommit, List<ChangedArtifact> workflows,
        List<ChangedArtifact> modules, List<String> notImported) {
        this(scope, baseCommit, workflows, modules, notImported, List.of());
    }

    public ChangeSet {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(baseCommit, "baseCommit");
        workflows = List.copyOf(workflows);
        modules = List.copyOf(modules);
        notImported = List.copyOf(notImported);
        identical = List.copyOf(identical);
    }

    /** True if nothing is to be sent (SC-003). */
    public boolean isEmpty() {
        return workflows.isEmpty() && modules.isEmpty();
    }

    /** Workflows, then modules. */
    public List<ChangedArtifact> artifacts() {
        return Stream.concat(workflows.stream(), modules.stream()).toList();
    }

    /** The names of the artifacts the import creates (workflows first). */
    public List<String> created() {
        return names(ChangedArtifact.Kind.NEW);
    }

    /**
     * The names of the artifacts the import modifies, {@link #existing()} included (workflows
     * first).
     */
    public List<String> modified() {
        return artifacts().stream().filter(ChangedArtifact::onServer)
            .map(ChangedArtifact::name).toList();
    }

    /** The names of the artifacts new in the workspace that the import updates on the server. */
    public List<String> existing() {
        return names(ChangedArtifact.Kind.EXISTING);
    }

    /** The names of {@link #identical()}. */
    public List<String> identicalNames() {
        return identical.stream().map(ChangedArtifact::name).toList();
    }

    /** Every file of the change set, workspace-relative. */
    public List<String> paths() {
        List<String> paths = new ArrayList<>();
        artifacts().forEach(artifact -> paths.addAll(artifact.paths()));
        return List.copyOf(paths);
    }

    private List<String> names(ChangedArtifact.Kind kind) {
        return artifacts().stream().filter(artifact -> artifact.kind() == kind)
            .map(ChangedArtifact::name).toList();
    }
}
