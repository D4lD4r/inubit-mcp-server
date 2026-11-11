package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.Objects;

/**
 * Matches one partner across servers (FR-013): system diagram, communication cloud and the value
 * of a non-masked key field.
 *
 * @param diagram         the system diagram
 * @param cloudModuleName {@code ModuleName} of the communication cloud
 * @param field           the key field, {@code Id} unless {@code PartnerComparison} falls back
 * @param value           the uncut value of the key field
 */
public record PartnerKey(DiagramRef diagram, String cloudModuleName, String field, String value) {

    public PartnerKey {
        Objects.requireNonNull(diagram, "diagram");
        Objects.requireNonNull(cloudModuleName, "cloudModuleName");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
    }
}
