package de.dadecker.inubit.mcp.domain.model.node;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.util.Objects;

/** The answer of a node for one configuration name (FR-031, research D-10). */
public sealed interface NodeConfigValue {

    int MAX_VALUE_CHARS = 2_000;

    /** The name asked for. */
    NodeConfigName name();

    /**
     * The node has the property.
     *
     * @param value masked (D-21), at most {@value NodeConfigValue#MAX_VALUE_CHARS} characters
     * @param cut   whether {@code value} was cut to that limit
     * @param type  the simple name of the value's Java type
     */
    record Present(NodeConfigName name, String value, boolean cut, String type,
        String displayName, String description, String category, boolean editable,
        boolean deprecated) implements NodeConfigValue {

        public Present {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(category, "category");
            if (value.length() > MAX_VALUE_CHARS) {
                throw new IllegalArgumentException(
                    "Invalid configuration value: more than " + MAX_VALUE_CHARS + " characters");
            }
        }
    }

    /** The node does not have the property ("not present on this node"). */
    record Absent(NodeConfigName name) implements NodeConfigValue {
        public Absent {
            Objects.requireNonNull(name, "name");
        }
    }

    /** Reading this one name failed; the other names are still answered. */
    record Failed(NodeConfigName name, ErrorCode code, String message)
        implements NodeConfigValue {

        public Failed {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
