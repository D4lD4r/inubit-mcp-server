package de.dadecker.inubit.mcp.domain.model.management;

import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An external system element that manages the module (data-model.md → ManagingElement).
 *
 * @param systemDiagram         the managing system diagram
 * @param element               the external system element's name
 * @param connectorType         the element's {@code ResourceType}, when shown
 * @param settings              the element's settings, as in {@link ExternalSystem#settings()}
 * @param differing             the settings whose values differ from the module's (FR-007); empty
 *                              if none or if the comparison is unavailable
 * @param comparisonUnavailable why the module's own properties could not be read; present
 *                              exactly when they could not
 */
public record ManagingElement(DiagramRef systemDiagram, String element,
    Optional<String> connectorType, List<ModuleDetail.Property> settings,
    List<Difference> differing, Optional<Reason> comparisonUnavailable) {

    public ManagingElement {
        Objects.requireNonNull(systemDiagram, "systemDiagram");
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(connectorType, "connectorType");
        settings = List.copyOf(settings);
        differing = List.copyOf(differing);
        Objects.requireNonNull(comparisonUnavailable, "comparisonUnavailable");
        if (comparisonUnavailable.isPresent() && !differing.isEmpty()) {
            throw new IllegalArgumentException(
                "an unavailable comparison has no differing settings");
        }
    }
}
