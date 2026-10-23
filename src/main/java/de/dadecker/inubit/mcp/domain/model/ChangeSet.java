package de.dadecker.inubit.mcp.domain.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What an import sends (feature 004, research D-4, D-25, data-model.md → ChangeSet): the changed
 * and new workflows and modules of one scope, each against its own last server state; changes
 * of the owner outside the scope are only listed.
 *
 * <p>Feature 008 (research D-4, data-model.md) adds {@code repository}: the changed and new
 * repository files of the scope.
 *
 * @param baseCommit  the last server state of the scope (the base of new artifacts)
 * @param repository  repository files ({@link ArtifactRef.Kind#REPOSITORY_FILE}); {@code ref.name}
 *                    is the full repository path {@code /Root/<owner>/…}, {@code paths} holds
 *                    exactly the workspace path of that repository file
 * @param notImported workspace-relative files that changed outside the scope
 * @param identical   artifacts the server has with the workspace's content (0.4.2, 0.4.3): new
 *                    in the workspace, or {@code referenced} modules outside the export of the
 *                    diagram group; not sent, their server state is recorded after a successful
 *                    import
 * @param referenced  diagram-group scope (0.4.3): the unchanged modules of the workspace that the
 *                    workflows of the change set use; not sent, but compared with the server if
 *                    the export of the diagram group lacks them
 */
public record ChangeSet(ImportScope scope, String baseCommit, List<ChangedArtifact> workflows,
    List<ChangedArtifact> modules, List<ChangedArtifact> repository, List<String> notImported,
    List<ChangedArtifact> identical, List<ArtifactRef> referenced) {

    /** A change set without repository files. */
    public ChangeSet(ImportScope scope, String baseCommit, List<ChangedArtifact> workflows,
        List<ChangedArtifact> modules, List<String> notImported, List<ChangedArtifact> identical,
        List<ArtifactRef> referenced) {
        this(scope, baseCommit, workflows, modules, List.of(), notImported, identical,
            referenced);
    }

    /**
     * A change set without repository files and unchanged referenced modules, not compared with
     * the server yet.
     */
    public ChangeSet(ImportScope scope, String baseCommit, List<ChangedArtifact> workflows,
        List<ChangedArtifact> modules, List<String> notImported) {
        this(scope, baseCommit, workflows, modules, notImported, List.of(), List.of());
    }

    public ChangeSet {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(baseCommit, "baseCommit");
        workflows = List.copyOf(workflows);
        modules = List.copyOf(modules);
        repository = List.copyOf(repository);
        repository.forEach(ChangeSet::requireRepositoryFile);
        notImported = List.copyOf(notImported);
        identical = List.copyOf(identical);
        referenced = List.copyOf(referenced);
    }

    /** True if nothing is to be sent (SC-003). */
    public boolean isEmpty() {
        return workflows.isEmpty() && modules.isEmpty() && repository.isEmpty();
    }

    /** Workflows, then modules, then repository files. */
    public List<ChangedArtifact> artifacts() {
        return Stream.of(workflows, modules, repository).flatMap(List::stream).toList();
    }

    /** The names of the artifacts the import creates (workflows, modules, repository files). */
    public List<String> created() {
        return names(ChangedArtifact.Kind.NEW);
    }

    /**
     * The names of the artifacts the import modifies, {@link #existing()} included (workflows,
     * modules, repository files).
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

    private static void requireRepositoryFile(ChangedArtifact file) {
        ArtifactRef ref = file.ref();
        if (ref.kind() != ArtifactRef.Kind.REPOSITORY_FILE) {
            throw new IllegalArgumentException("Not a repository file: " + ref.name());
        }
        if (!ref.name().startsWith("/Root/" + ref.owner() + "/")) {
            throw new IllegalArgumentException("Not a full repository path of owner "
                + ref.owner() + ": " + ref.name());
        }
        if (file.paths().size() != 1 || !WorkspacePath.parse(Path.of(file.paths().get(0)))
            .equals(WorkspacePath.repository(ref.group(), ref.owner(), ref.name()))) {
            throw new IllegalArgumentException("A repository file has exactly its own workspace"
                + " path: " + ref.name());
        }
    }

    private List<String> names(ChangedArtifact.Kind kind) {
        return artifacts().stream().filter(artifact -> artifact.kind() == kind)
            .map(ChangedArtifact::name).toList();
    }
}
