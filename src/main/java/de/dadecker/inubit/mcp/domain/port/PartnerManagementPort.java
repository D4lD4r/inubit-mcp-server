package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reads the Partner Managements of system diagrams of one server (feature 018, research D-4). Only
 * masked models leave the port: a secret exists only as a masked setting value.
 */
public interface PartnerManagementPort {

    /**
     * Reads every system diagram of {@code systemDiagrams} that belongs to {@code owner}.
     *
     * <p>A diagram whose export belongs to another diagram group or owner (the export selects by
     * name only) gives no data; it is reported in {@link Result#unreadableDiagrams()} and the
     * other diagrams are still read, so this is not thrown.
     *
     * @param owner          the user or user group the system diagrams belong to
     * @param systemDiagrams the system diagrams to read
     * @throws ToolErrorException with the server id: the error of a failed request, or
     *     {@code UNEXPECTED_RESPONSE} naming the part of an export that could not be read (never
     *     its content)
     */
    Result read(String owner, List<DiagramRef> systemDiagrams);

    /**
     * What {@link #read} found.
     *
     * @param diagrams           the masked Partner Managements of the diagrams that were read, in
     *                           request order (a diagram without Partner Management has no clouds)
     * @param unreadableDiagrams one per requested diagram that gave no data, in request order
     * @param warnings           per diagram that was read and gave warnings, its warnings; they
     *                           name partner key, cloud and setting, never a value
     */
    record Result(List<SystemDiagramPartners> diagrams, List<UnreadableDiagram> unreadableDiagrams,
        Map<DiagramRef, List<String>> warnings) {

        public Result {
            diagrams = List.copyOf(diagrams);
            unreadableDiagrams = List.copyOf(unreadableDiagrams);
            warnings = warnings.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        }
    }

    /**
     * A requested system diagram that gave no data while the others were read (contract
     * {@code find_partners}: {@code servers[].unreadableDiagrams}).
     *
     * @param systemDiagram the requested diagram
     * @param code          why, e.g. {@code UNEXPECTED_RESPONSE} for a name collision
     * @param message       what happened, without export content
     */
    record UnreadableDiagram(DiagramRef systemDiagram, ErrorCode code, String message) {

        public UnreadableDiagram {
            Objects.requireNonNull(systemDiagram, "systemDiagram");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
