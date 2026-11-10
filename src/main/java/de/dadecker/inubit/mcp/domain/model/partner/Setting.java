package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.Objects;

/**
 * A named partner setting (data-model.md → Setting): a partner field, a protocol setting or an
 * additional parameter.
 *
 * @param name  the name as in the export
 * @param value the value, masked by {@code PartnerSecretRules}
 */
public record Setting(String name, SettingValue value) {

    public Setting {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }
}
