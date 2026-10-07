package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tags on one INUBIT server (feature 004, research D-16; 8.1: StartCLI {@code tag} and
 * history exports). The smallest unit StartCLI can tag is a diagram group of one owner; a call
 * without a group would tag everything of the owner, so the adapter refuses a blank group
 * before anything is launched. Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id.
 */
public interface TagPort {

    /** A diagram of the history: its diagram group, its type and its versions (newest first). */
    record Diagram(String diagramGroup, String type, List<VersionEntry> versions) {
        public Diagram {
            Objects.requireNonNull(diagramGroup, "diagramGroup");
            Objects.requireNonNull(type, "type");
            versions = List.copyOf(versions);
        }
    }

    /**
     * The version history of an owner's diagrams and modules with the current tag of each
     * version.
     *
     * @param diagrams by diagram name
     * @param modules  versions by module name, newest first
     */
    record History(Map<String, Diagram> diagrams, Map<String, List<VersionEntry>> modules) {
        public History {
            diagrams = Map.copyOf(diagrams);
            modules = Map.copyOf(modules);
        }
    }

    /**
     * Checks without launching anything that tags can be set (CLI, credentials).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    void checkAvailable();

    /** The history of every diagram (all types, all diagram groups) of {@code owner}. */
    History history(String owner);

    /** The history of the technical workflows of {@code diagramGroup} and their modules. */
    History history(String owner, String diagramGroup);

    /**
     * Sets {@code tag} on the head versions of the technical workflows of {@code diagramGroup}
     * of {@code owner} and their modules ({@code tag --tagMove}).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} for a
     *     blank group or a value StartCLI quoting cannot carry (nothing launched);
     *     {@code IMPORT_FAILED} or {@code TIMEOUT} if StartCLI ran
     */
    void tag(String tag, String diagramGroup, String owner);

    /** Removes {@code tag} from every version of {@code owner} ({@code tag --tagDelete}). */
    void deleteTag(String tag, String owner);
}
