package de.dadecker.inubit.mcp.domain.model.management;

import java.util.Objects;
import java.util.Optional;

/**
 * A module name an external system element links (data-model.md → LinkedModule).
 *
 * @param name  the linked module name
 * @param found whether the owner has a module of that name; empty exactly when the owner's module
 *              list is unavailable ({@link SystemDiagramManagement#moduleListUnavailable()})
 */
public record LinkedModule(String name, Optional<Boolean> found) {

    public LinkedModule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(found, "found");
    }
}
