package de.dadecker.inubit.mcp.domain.model.partner;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How a technical workflow uses the Partner Management (story 4, FR-015, data-model.md →
 * PartnerUsage): names only, no partner data.
 *
 * @param variables             the distinct {@code ISPartnerProtocol.*} and
 *                              {@code ISPartnerParameter.*} variables, sorted
 * @param partnerManagementNode whether the workflow has a {@code PartnerManagement} module
 * @param systemDiagrams        the communication clouds of the owner's system diagrams whose
 *                              {@code referencableWorkflows} contain the workflow; absent if no
 *                              system diagram was read
 * @param unavailable           why the system diagrams were not read, or not all of them;
 *                              absent if all were read
 */
public record PartnerUsage(List<String> variables, boolean partnerManagementNode,
    Optional<List<CloudUse>> systemDiagrams, Optional<Unavailable> unavailable) {

    public PartnerUsage {
        variables = List.copyOf(variables);
        systemDiagrams = systemDiagrams == null ? Optional.empty()
            : systemDiagrams.map(List::copyOf);
        unavailable = unavailable == null ? Optional.empty() : unavailable;
    }

    /** Whether the system diagrams have to be read: the workflow uses partner data. */
    public boolean usesPartners() {
        return !variables.isEmpty() || partnerManagementNode;
    }

    /** A communication cloud of a system diagram whose {@code ReferencableTWFs} name the flow. */
    public record CloudUse(DiagramRef systemDiagram, String cloud) {

        public CloudUse {
            Objects.requireNonNull(systemDiagram, "systemDiagram");
            Objects.requireNonNull(cloud, "cloud");
        }
    }

    /** Why system diagrams were not read: the error kind, what happened and what to do. */
    public record Unavailable(ErrorCode code, String message, String nextStep) {

        public Unavailable {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(nextStep, "nextStep");
        }
    }
}
