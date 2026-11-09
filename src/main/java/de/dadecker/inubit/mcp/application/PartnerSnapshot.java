package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import java.util.List;
import java.util.Set;

/**
 * The Partner Managements read from one server, as kept in {@link InventoryCache} (research D-6,
 * data-model.md → PartnerSnapshot). It is stored under {@code InventoryKind.DIAGRAM} with one of
 * the scopes below; a call needing the system diagrams S is served by a fresh snapshot whose
 * {@code covered} contains S.
 *
 * @param diagrams the system diagrams read; one without a Partner Management has no clouds
 * @param covered  the system diagrams this snapshot answers for
 */
public record PartnerSnapshot(List<SystemDiagramPartners> diagrams, Set<DiagramRef> covered) {

    /** Scope prefix of an owner's system diagram list: {@code systemdiagrams:<owner>}. */
    public static final String SYSTEM_DIAGRAMS_SCOPE = "systemdiagrams:";

    /**
     * Scope prefix of an owner's Partner Managements: {@code partners:<owner>} for all of them,
     * {@code partners:<owner>|<group>/<name>} for one system diagram.
     */
    public static final String PARTNERS_SCOPE = "partners:";

    public PartnerSnapshot {
        diagrams = List.copyOf(diagrams);
        covered = Set.copyOf(covered);
    }

    /** {@code systemdiagrams:<owner>} */
    public static String systemDiagramsScope(String owner) {
        return SYSTEM_DIAGRAMS_SCOPE + owner;
    }

    /** {@code partners:<owner>} */
    public static String partnersScope(String owner) {
        return PARTNERS_SCOPE + owner;
    }

    /** {@code partners:<owner>|<group>/<name>} */
    public static String partnersScope(String owner, DiagramRef diagram) {
        return partnersScope(owner) + "|" + diagram.diagramGroup() + "/" + diagram.name();
    }
}
