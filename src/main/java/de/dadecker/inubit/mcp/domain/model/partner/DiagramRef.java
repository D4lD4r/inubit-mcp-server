package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.Objects;

/**
 * A system diagram (data-model.md → DiagramRef). The same name in two diagram groups denotes two
 * different system diagrams, so two refs are equal only if both fields are equal.
 *
 * @param name         the system diagram name
 * @param diagramGroup the diagram group the system diagram belongs to
 */
public record DiagramRef(String name, String diagramGroup) {

    public DiagramRef {
        requireText(name, "name");
        requireText(diagramGroup, "diagramGroup");
    }

    private static void requireText(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
