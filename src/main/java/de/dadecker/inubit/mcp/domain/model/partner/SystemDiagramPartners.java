package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.List;
import java.util.Objects;

/**
 * The Partner Management of one system diagram (data-model.md → SystemDiagramPartners).
 *
 * @param diagram the system diagram
 * @param clouds  the communication clouds that carry {@code PartnerManagementSettings}, in export
 *                order; empty if the diagram has no Partner Management (FR-007)
 */
public record SystemDiagramPartners(DiagramRef diagram, List<CommunicationCloud> clouds) {

    public SystemDiagramPartners {
        Objects.requireNonNull(diagram, "diagram");
        clouds = List.copyOf(clouds);
    }
}
