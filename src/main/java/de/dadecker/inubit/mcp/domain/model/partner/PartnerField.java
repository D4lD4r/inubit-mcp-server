package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.Objects;

/**
 * A partner field configured in a communication cloud ({@code PartnerFields}). A field whose
 * {@code fieldType} is not on {@code PLAIN_FIELD_TYPES} is masked in every partner (FR-008).
 *
 * @param name      the field name ({@code Id}, {@code Name}, …)
 * @param fieldType the {@code fieldType} attribute, as found
 */
public record PartnerField(String name, String fieldType) {

    public PartnerField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fieldType, "fieldType");
    }
}
