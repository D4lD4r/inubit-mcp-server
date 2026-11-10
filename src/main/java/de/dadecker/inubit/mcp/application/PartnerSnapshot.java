package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Partner Managements read from one server, as kept in {@link InventoryCache} (research D-6,
 * data-model.md → PartnerSnapshot). It is stored under {@code InventoryKind.DIAGRAM} with one of
 * the scopes below; a call of owner O needing the system diagrams S is served by a fresh snapshot
 * that {@link #answers answers} O and S. The scope alone does not decide it: two scopes can read
 * the same ({@code partners:a|g/n} is owner {@code a|g/n}, or owner {@code a} with diagram
 * {@code g/n}).
 *
 * @param owner              the owner the system diagrams were read for
 * @param diagrams           the system diagrams read; one without a Partner Management has no
 *                           clouds
 * @param covered            the system diagrams this snapshot answers for, the unreadable ones
 *                           included
 * @param unreadableDiagrams the requested diagrams that gave no data (T018: a cache hit reports
 *                           them like the read did)
 * @param warnings           the reader's warnings per diagram read (a diagram without warnings
 *                           may be absent)
 */
public record PartnerSnapshot(String owner, List<SystemDiagramPartners> diagrams,
    Set<DiagramRef> covered, List<UnreadableDiagram> unreadableDiagrams,
    Map<DiagramRef, List<String>> warnings) {

    /** Scope prefix of an owner's system diagram list: {@code systemdiagrams:<owner>}. */
    public static final String SYSTEM_DIAGRAMS_SCOPE = "systemdiagrams:";

    /**
     * Scope prefix of an owner's Partner Managements: {@code partners:<owner>} for all of them,
     * {@code partners:<owner>|<group>/<name>} for one system diagram.
     */
    public static final String PARTNERS_SCOPE = "partners:";

    public PartnerSnapshot {
        Objects.requireNonNull(owner, "owner");
        diagrams = List.copyOf(diagrams);
        covered = Set.copyOf(covered);
        unreadableDiagrams = List.copyOf(unreadableDiagrams);
        warnings = warnings.entrySet().stream().collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    /** Whether this snapshot was read for {@code owner} and covers every one of {@code needed}. */
    public boolean answers(String owner, Collection<DiagramRef> needed) {
        return this.owner.equals(owner) && covered.containsAll(needed);
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
