package de.dadecker.inubit.mcp.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one writing call covers (feature 004, clarification 1, data-model.md → ImportScope):
 * either the workflows of one diagram group of one owner (with their changed or new modules),
 * single modules of one owner (a module import), or repository paths of one owner (feature 008,
 * research D-4).
 *
 * @param diagramGroup    the diagram group, for a workflow import
 * @param modules         the named modules, for a module import (empty otherwise)
 * @param repositoryPaths 1–50 unique paths relative to {@code /Root/<owner>}, each
 *                        {@link RepositoryPaths#relativeValid}, for a repository import (empty
 *                        otherwise)
 */
public record ImportScope(GroupId group, String owner, Optional<String> diagramGroup,
    List<Module> modules, List<String> repositoryPaths) {

    /** The most repository paths one scope holds (FR-005, contracts/mcp-tools-delta.md). */
    static final int MAX_REPOSITORY_PATHS = 50;

    /** A module of a module import; without plugin type it is looked up in the workspace. */
    public record Module(String name, Optional<String> pluginType) {

        public Module {
            Objects.requireNonNull(name, "name");
            pluginType = pluginType == null ? Optional.empty() : pluginType;
        }
    }

    public ImportScope {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(owner, "owner");
        diagramGroup = diagramGroup == null ? Optional.empty() : diagramGroup;
        modules = List.copyOf(modules);
        repositoryPaths = List.copyOf(repositoryPaths);
        int alternatives = (diagramGroup.isPresent() ? 1 : 0) + (modules.isEmpty() ? 0 : 1)
            + (repositoryPaths.isEmpty() ? 0 : 1);
        if (alternatives != 1) {
            throw new IllegalArgumentException(
                "A scope is one diagram group, modules or repository paths");
        }
        if (repositoryPaths.size() > MAX_REPOSITORY_PATHS) {
            throw new IllegalArgumentException("At most " + MAX_REPOSITORY_PATHS
                + " repository paths");
        }
        if (new HashSet<>(repositoryPaths).size() != repositoryPaths.size()) {
            throw new IllegalArgumentException("Repository paths must be unique");
        }
        for (String path : repositoryPaths) {
            if (!RepositoryPaths.relativeValid(path)) {
                throw new IllegalArgumentException(
                    "Not a normalized repository path relative to /Root/" + owner + ": " + path);
            }
        }
    }

    /** The workflows of {@code diagramGroup}. */
    public static ImportScope diagramGroup(GroupId group, String owner, String diagramGroup) {
        return new ImportScope(group, owner, Optional.of(diagramGroup), List.of(), List.of());
    }

    /** The named modules. */
    public static ImportScope modules(GroupId group, String owner, List<Module> modules) {
        return new ImportScope(group, owner, Optional.empty(), modules, List.of());
    }

    /** The repository files at or below {@code repositoryPaths}. */
    public static ImportScope repositoryPaths(GroupId group, String owner,
        List<String> repositoryPaths) {
        return new ImportScope(group, owner, Optional.empty(), List.of(), repositoryPaths);
    }

    /**
     * {@code diagram group <name>}, {@code modules <a>, <b>} or {@code repository paths <a>, <b>},
     * for messages and the audit.
     */
    public String describe() {
        if (diagramGroup.isPresent()) {
            return "diagram group " + diagramGroup.get();
        }
        if (!repositoryPaths.isEmpty()) {
            return "repository paths " + String.join(", ", repositoryPaths);
        }
        return "modules " + String.join(", ", modules.stream().map(Module::name).toList());
    }
}
