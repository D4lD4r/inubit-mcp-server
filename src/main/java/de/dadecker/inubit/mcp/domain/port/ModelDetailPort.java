package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;

/**
 * Reads the model details of one node for {@code read_model_details} (feature 022, US3, research
 * D-21): one module over REST, the uploaded libraries over StartCLI. Thread-safe.
 */
public interface ModelDetailPort {

    /**
     * The module {@code moduleId} of {@code diagram} from
     * {@code GET /model/nodeByModel/{diagram}/{moduleId}}, read as XML, its secrets masked.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException with the node set:
     *     {@code NOT_FOUND} for a module id the diagram does not have; {@code FORBIDDEN},
     *     {@code MAINTENANCE_MODE} or {@code UNEXPECTED_RESPONSE} for other answers
     */
    ModuleDetail module(String diagram, String moduleId);

    /**
     * The libraries of one type from one StartCLI run of {@code library --list --type <type>};
     * each line of the answer is one file name until a listed library is recorded (L-21).
     *
     * @param type {@code driver} or {@code plugin}
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} for
     *     another type (nothing started); with the node set, for a failed StartCLI run (e.g.
     *     {@code CLI_UNAVAILABLE}, {@code AUTH_FAILED}, {@code TIMEOUT}) or an answer that is no
     *     library list ({@code UNEXPECTED_RESPONSE})
     */
    LibraryEntry.Listing libraries(String type);
}
