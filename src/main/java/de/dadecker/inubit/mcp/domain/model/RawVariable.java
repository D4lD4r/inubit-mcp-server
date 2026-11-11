package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One workflow variable of a Queue Manager entry as read from INUBIT, unmasked and unbounded
 * (feature 020, D-13); variables keep the file order.
 *
 * @param type the type INUBIT names, if any
 */
public record RawVariable(String name, Optional<String> type, String value) {

    public RawVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        type = type == null ? Optional.empty() : type;
    }
}
