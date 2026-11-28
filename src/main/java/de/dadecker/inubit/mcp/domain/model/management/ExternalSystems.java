package de.dadecker.inubit.mcp.domain.model.management;

import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds an {@link ExternalSystem} from the module detail of an {@code sdExtSystem} element
 * (data-model.md → ExternalSystem). Only the top-level properties {@code ResourceType},
 * {@code ResourceSettings} and {@code ResourcesLinked} are read, by exact name; all others are
 * ignored. The settings are the already masked {@link Property} values of the detail (research
 * D-9), so no raw value is kept.
 */
public final class ExternalSystems {

    private ExternalSystems() {
    }

    /**
     * The external system and the warnings found while building it.
     *
     * @param system   the external system
     * @param warnings one per {@code ResourcesLinked} entry that was skipped (masked or not a
     *                 {@code String}) and one if {@code ResourcesLinked} is masked as a whole; they
     *                 name the system diagram, the element and its node id, never a value
     */
    public record Result(ExternalSystem system, List<String> warnings) {

        public Result {
            Objects.requireNonNull(system, "system");
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * @param systemDiagram the system diagram the element belongs to
     * @param element       the element as listed in the system diagram (name and node id)
     * @param detail        the element's module detail, masked by the module-detail parser
     */
    public static Result from(DiagramRef systemDiagram, ModuleRef element, ModuleDetail detail) {
        Optional<String> connectorType = property(detail, "ResourceType")
            .flatMap(Property::value);
        List<Property> settings = property(detail, "ResourceSettings")
            .map(container -> container.masked() ? List.of(container) : container.entries())
            .orElse(List.of());

        List<String> linkedNames = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String where = "system diagram '" + systemDiagram.name() + "' (diagram group '"
            + systemDiagram.diagramGroup() + "'), element '" + element.name() + "' (node "
            + element.nodeId() + ")";
        Optional<Property> linked = property(detail, "ResourcesLinked");
        if (linked.filter(Property::masked).isPresent()) {
            warnings.add(where + ": ResourcesLinked is masked as a whole; no linked module is read");
        }
        for (Property entry : linked.map(Property::entries).orElse(List.of())) {
            if (!entry.name().equals("Module")) {
                continue;
            }
            if (entry.type().equals("String") && entry.value().isPresent()) {
                linkedNames.add(entry.value().get());
            } else {
                warnings.add(where + ": a ResourcesLinked Module entry of type '" + entry.type()
                    + "' is masked or not a String and was skipped");
            }
        }

        return new Result(new ExternalSystem(systemDiagram, element.name(), element.nodeId(),
            connectorType, settings, linkedNames), warnings);
    }

    private static Optional<Property> property(ModuleDetail detail, String name) {
        return detail.properties().stream().filter(p -> p.name().equals(name)).findFirst();
    }
}
