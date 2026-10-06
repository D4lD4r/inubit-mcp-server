package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one writing call covers (feature 004, clarification 1, data-model.md → ImportScope):
 * either the workflows of one diagram group of one owner (with their changed or new modules), or
 * single modules of one owner (a module import).
 *
 * @param diagramGroup the diagram group, for a workflow import
 * @param modules      the named modules, for a module import (empty otherwise)
 */
public record ImportScope(GroupId group, String owner, Optional<String> diagramGroup,
    List<Module> modules) {

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
        if (diagramGroup.isPresent() == !modules.isEmpty()) {
            throw new IllegalArgumentException("A scope is one diagram group or modules");
        }
    }

    /** The workflows of {@code diagramGroup}. */
    public static ImportScope diagramGroup(GroupId group, String owner, String diagramGroup) {
        return new ImportScope(group, owner, Optional.of(diagramGroup), List.of());
    }

    /** The named modules. */
    public static ImportScope modules(GroupId group, String owner, List<Module> modules) {
        return new ImportScope(group, owner, Optional.empty(), modules);
    }

    /** {@code diagram group <name>} or {@code modules <a>, <b>}, for messages and the audit. */
    public String describe() {
        return diagramGroup.map(name -> "diagram group " + name)
            .orElseGet(() -> "modules " + String.join(", ", modules.stream()
                .map(Module::name).toList()));
    }
}
