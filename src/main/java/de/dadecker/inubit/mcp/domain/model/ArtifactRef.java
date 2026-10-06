package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * An artifact of the workspace (data-model.md → Workspace, feature 003): a workflow, a module or
 * a repository file of one owner on one configured group.
 *
 * <p>Identity is the {@code name} within {@code group}, {@code owner} and {@code kind} (spike §5:
 * INUBIT matches artifacts by name). {@link #equals} and {@link #hashCode} use only these four;
 * the diagram group of a workflow and the plugin type of a module describe the artifact but do
 * not identify it, and UIDs are never part of it (they change on every modifying import).
 *
 * @param owner        the INUBIT user or user group that owns the artifact
 * @param name         the workflow or module name; for a repository file its repository path
 * @param diagramGroup only for {@link Kind#WORKFLOW}
 * @param pluginType   only for {@link Kind#MODULE}, e.g. {@code XSLT Converter}
 */
public record ArtifactRef(GroupId group, String owner, Kind kind, String name,
    Optional<String> diagramGroup, Optional<String> pluginType) {

    /** What an artifact is. */
    public enum Kind {
        WORKFLOW,
        MODULE,
        REPOSITORY_FILE
    }

    public ArtifactRef {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(kind, "kind");
        requireText(owner, "owner");
        requireText(name, "name");
        diagramGroup = diagramGroup == null ? Optional.empty() : diagramGroup;
        pluginType = pluginType == null ? Optional.empty() : pluginType;
        diagramGroup.ifPresent(value -> requireText(value, "diagramGroup"));
        pluginType.ifPresent(value -> requireText(value, "pluginType"));
        if (diagramGroup.isPresent() && kind != Kind.WORKFLOW) {
            throw new IllegalArgumentException("Only a workflow has a diagramGroup");
        }
        if (pluginType.isPresent() && kind != Kind.MODULE) {
            throw new IllegalArgumentException("Only a module has a pluginType");
        }
    }

    /** The workflow {@code name} of diagram group {@code diagramGroup}. */
    public static ArtifactRef workflow(GroupId group, String owner, String diagramGroup,
        String name) {
        return new ArtifactRef(group, owner, Kind.WORKFLOW, name, Optional.of(diagramGroup),
            Optional.empty());
    }

    /** The module {@code name} of plugin type {@code pluginType}. */
    public static ArtifactRef module(GroupId group, String owner, String pluginType,
        String name) {
        return new ArtifactRef(group, owner, Kind.MODULE, name, Optional.empty(),
            Optional.of(pluginType));
    }

    /** The repository file at {@code path}, e.g. {@code Root/OWNERS/xsd/msg.xsd}. */
    public static ArtifactRef repositoryFile(GroupId group, String owner, String path) {
        return new ArtifactRef(group, owner, Kind.REPOSITORY_FILE, path, Optional.empty(),
            Optional.empty());
    }

    /** Equal when group, owner, kind and name are equal (see the class comment). */
    @Override
    public boolean equals(Object other) {
        return other instanceof ArtifactRef that && group.equals(that.group)
            && owner.equals(that.owner) && kind == that.kind && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(group, owner, kind, name);
    }

    private static void requireText(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
