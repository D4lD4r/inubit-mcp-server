package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@code sdCommunicationCloud} module that carries {@code PartnerManagementSettings}
 * (data-model.md → CommunicationCloud).
 *
 * @param moduleName            {@code ModuleName}; part of the partner key (FR-013)
 * @param moduleId              {@code ModuleId}; shown only when two clouds of one diagram share
 *                              a name (then reported as ambiguous)
 * @param partnerFields         the configured partner fields ({@code PartnerFields})
 * @param referencableWorkflows names of the {@code ReferencableTWFs} entries
 * @param partners              in export order
 */
public record CommunicationCloud(
    String moduleName,
    String moduleId,
    List<PartnerField> partnerFields,
    List<String> referencableWorkflows,
    List<Partner> partners) {

    /** The partner field that keys a partner when present and not masked (FR-013). */
    public static final String KEY_FIELD = "Id";

    public CommunicationCloud {
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(moduleId, "moduleId");
        partnerFields = List.copyOf(partnerFields);
        referencableWorkflows = List.copyOf(referencableWorkflows);
        partners = List.copyOf(partners);
    }

    /**
     * The key of {@code partner} in this cloud of {@code diagram}, built from its {@code Id} field.
     * Empty when {@code Id} is absent or masked; the fallback to another field present on both
     * sides belongs to {@code PartnerComparison}.
     */
    public Optional<PartnerKey> keyOf(DiagramRef diagram, Partner partner) {
        return partner.fields().stream()
            .filter(f -> f.name().equals(KEY_FIELD))
            .findFirst()
            .map(Setting::value)
            .filter(SettingValue.Shown.class::isInstance)
            .map(v -> ((SettingValue.Shown) v).value())
            .map(id -> new PartnerKey(diagram, moduleName, KEY_FIELD, id));
    }
}
