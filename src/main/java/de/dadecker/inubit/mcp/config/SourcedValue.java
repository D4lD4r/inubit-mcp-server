package de.dadecker.inubit.mcp.config;

import java.util.Objects;

/**
 * A credential value together with the name it was read from (a credential store entry or a
 * Parameter Store parameter; feature 011). For secrets, {@code toString()} shows {@code ***}
 * because {@link de.dadecker.inubit.mcp.infra.Secret} masks itself.
 */
public record SourcedValue<T>(T value, String sourceVariable) {

    public SourcedValue {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(sourceVariable, "sourceVariable");
    }
}
