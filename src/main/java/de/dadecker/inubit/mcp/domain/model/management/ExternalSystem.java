package de.dadecker.inubit.mcp.domain.model.management;

import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One {@code sdExtSystem} element of a system diagram, as read with {@code nodeByModel}
 * (data-model.md → ExternalSystem, research D-1).
 *
 * @param systemDiagram the system diagram the element belongs to
 * @param element       the element's module name
 * @param nodeId        the element's node id, 1 to 10 digits
 * @param connectorType the shown value of {@code ResourceType}; empty if absent or masked
 * @param settings      the entries of {@code ResourceSettings} in INUBIT's order; one masked
 *                      property if the container is masked as a whole; empty if absent
 * @param linkedNames   the shown values of {@code ResourcesLinked/Module} in INUBIT's order;
 *                      empty if absent
 */
public record ExternalSystem(DiagramRef systemDiagram, String element, String nodeId,
    Optional<String> connectorType, List<ModuleDetail.Property> settings,
    List<String> linkedNames) {

    /** A node id: 1 to 10 digits. */
    static final Pattern NODE_ID = Pattern.compile("[0-9]{1,10}");

    public ExternalSystem {
        Objects.requireNonNull(systemDiagram, "systemDiagram");
        Objects.requireNonNull(element, "element");
        if (!NODE_ID.matcher(Objects.requireNonNull(nodeId, "nodeId")).matches()) {
            throw new IllegalArgumentException("nodeId must have 1 to 10 digits");
        }
        Objects.requireNonNull(connectorType, "connectorType");
        settings = List.copyOf(settings);
        linkedNames = List.copyOf(linkedNames);
    }
}
