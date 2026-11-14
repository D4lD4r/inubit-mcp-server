package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One module of a diagram as {@code GET /model/nodeByModel/{diagram}/{moduleId}} shows it
 * (data-model.md → ModuleDetail, US3, research D-21): the XML {@code ns4:Node}, its outgoing
 * connections and its properties in INUBIT's order, masked by {@link SecretRules} (FR-010).
 *
 * @param diagram     the diagram (workflow) the module belongs to
 * @param moduleId    the module id
 * @param name        the module name
 * @param type        INUBIT's module type, e.g. {@code twDatabaseConnector}
 * @param parentId    the {@code parentId} attribute, if INUBIT sends one
 * @param connections the outgoing connections
 * @param properties  the properties in INUBIT's order
 * @param maskedCount the number of masked properties
 */
public record ModuleDetail(String diagram, String moduleId, String name, String type,
    Optional<String> parentId, List<Connection> connections, List<Property> properties,
    int maskedCount) {

    public ModuleDetail {
        Objects.requireNonNull(diagram, "diagram");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(parentId, "parentId");
        connections = List.copyOf(connections);
        properties = List.copyOf(properties);
        long counted = properties.stream().filter(Property::masked).count();
        if (maskedCount != counted) {
            throw new IllegalArgumentException(
                "maskedCount " + maskedCount + " differs from the " + counted + " masked values");
        }
    }

    /**
     * One outgoing connection ({@code ns4:Connection}).
     *
     * @param id       the connection id
     * @param targetId the id of the module the connection leads to
     */
    public record Connection(String id, String targetId) {

        public Connection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(targetId, "targetId");
        }
    }

    /**
     * One property: {@code {name, type, value}}, or {@code {name, type, masked: true}} when
     * {@code value} is empty.
     *
     * @param name      the property name
     * @param type      INUBIT's property type, e.g. {@code String} or {@code Password}
     * @param value     the value, at most {@link ItemBounds#MAX_ITEM_CHARS} chars and ending with
     *                  {@link ItemBounds#TRUNCATION_MARKER} when cut; empty when masked as a
     *                  whole; a credential inside it is replaced by its {@code ${secret:…}}
     *                  placeholder ({@link SecretTextMasker})
     * @param truncated true if the value was cut
     */
    public record Property(String name, String type, Optional<String> value, boolean truncated) {

        public Property {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            if (value.filter(text -> text.length() > ItemBounds.MAX_ITEM_CHARS).isPresent()) {
                throw new IllegalArgumentException(
                    "a property value is longer than " + ItemBounds.MAX_ITEM_CHARS + " chars");
            }
            if (value.isEmpty() && truncated) {
                throw new IllegalArgumentException("a masked property has no value to cut");
            }
        }

        public static Property shown(String name, String type, String value,
            boolean truncated) {
            return new Property(name, type, Optional.of(value), truncated);
        }

        public static Property masked(String name, String type) {
            return new Property(name, type, Optional.empty(), false);
        }

        public boolean masked() {
            return value.isEmpty();
        }
    }
}
