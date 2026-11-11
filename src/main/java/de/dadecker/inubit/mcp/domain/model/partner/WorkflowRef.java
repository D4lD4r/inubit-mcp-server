package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.Optional;

/**
 * The technical workflow a protocol entry refers to (data-model.md → WorkflowRef). The export
 * names it without diagram group (research D-2).
 *
 * @param name the {@code ReferencableTWFs} entry name that the entry's {@code referencableTWFId}
 *             points to; empty if the id does not resolve (shown as unresolved, with a warning)
 */
public record WorkflowRef(Optional<String> name) {

    public WorkflowRef {
        name = name == null ? Optional.empty() : name;
        if (name.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
