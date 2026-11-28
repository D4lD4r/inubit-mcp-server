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
 * @param maskedCount the number of masked values, entries of containers included
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
        long counted = properties.stream().mapToLong(Property::maskedCount).sum();
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
     * One property: {@code {name, type, value}}, {@code {name, type, masked: true}} when masked as
     * a whole, or {@code {name, type, entries}} for a container ({@code Map} or {@code List})
     * whose {@code Property} children are its entries, each masked on its own.
     *
     * @param name      the property name
     * @param type      INUBIT's property type, e.g. {@code String}, {@code Password} or
     *                  {@code Map}
     * @param value     the value, at most {@link ItemBounds#MAX_ITEM_CHARS} chars and ending with
     *                  {@link ItemBounds#TRUNCATION_MARKER} when cut; empty when masked as a
     *                  whole or for a container; a credential inside it is replaced by its
     *                  {@code ${secret:…}} placeholder ({@link SecretTextMasker})
     * @param truncated true if the value was cut
     * @param entries   the entries of a container in INUBIT's order; empty otherwise
     */
    public record Property(String name, String type, Optional<String> value, boolean truncated,
        List<Property> entries) {

        public Property {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            entries = List.copyOf(entries);
            if (value.filter(text -> text.length() > ItemBounds.MAX_ITEM_CHARS).isPresent()) {
                throw new IllegalArgumentException(
                    "a property value is longer than " + ItemBounds.MAX_ITEM_CHARS + " chars");
            }
            if (value.isEmpty() && truncated) {
                throw new IllegalArgumentException("a masked property has no value to cut");
            }
            if (value.isPresent() && !entries.isEmpty()) {
                throw new IllegalArgumentException("a container has entries, not a value");
            }
        }

        public static Property shown(String name, String type, String value,
            boolean truncated) {
            return new Property(name, type, Optional.of(value), truncated, List.of());
        }

        public static Property masked(String name, String type) {
            return new Property(name, type, Optional.empty(), false, List.of());
        }

        /** A container with at least one entry. */
        public static Property entries(String name, String type, List<Property> entries) {
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("a container without entries has a value");
            }
            return new Property(name, type, Optional.empty(), false, entries);
        }

        /** True if the property is masked as a whole. */
        public boolean masked() {
            return value.isEmpty() && entries.isEmpty();
        }

        /** The masked values of this property: 1 if masked as a whole, else those of its entries. */
        public long maskedCount() {
            return masked() ? 1 : entries.stream().mapToLong(Property::maskedCount).sum();
        }
    }
}
