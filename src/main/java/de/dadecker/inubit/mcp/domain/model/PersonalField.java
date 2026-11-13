package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One personal field INUBIT reports for a user (data-model.md → "UserSummary, UserDetail", US2
 * AS2), other than the e-mail: {@code {name, value}}, or {@code {name, masked: true}} when
 * {@link SecretRules} masked it (FR-010). Only the {@code read_users} view {@code user} shows it
 * (FR-011); its string form names no value.
 *
 * @param name  the field name as INUBIT reports it
 * @param value the value; empty when masked
 */
public record PersonalField(String name, Optional<String> value) {

    public PersonalField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }

    public static PersonalField shown(String name, String value) {
        return new PersonalField(name, Optional.of(value));
    }

    public static PersonalField masked(String name) {
        return new PersonalField(name, Optional.empty());
    }

    public boolean masked() {
        return value.isEmpty();
    }

    /** The name only, never the value. */
    @Override
    public String toString() {
        return "PersonalField[" + name + (masked() ? ", masked" : "") + "]";
    }
}
