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
 */
public record ChangeSet(ImportScope scope, String baseCommit, List<ChangedArtifact> workflows,
    List<ChangedArtifact> modules, List<String> notImported) {

    public ChangeSet {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(baseCommit, "baseCommit");
        workflows = List.copyOf(workflows);
        modules = List.copyOf(modules);
        notImported = List.copyOf(notImported);
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

    /** The names of the artifacts the import modifies (workflows first). */
    public List<String> modified() {
        return names(ChangedArtifact.Kind.MODIFIED);
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
