package de.dadecker.inubit.mcp.domain.port;

/**
 * Read-only exports of artifacts from one INUBIT server (feature 003, research D-8; 8.1:
 * StartCLI {@code export}). Only technical workflows are exported (clarification 2). The archive
 * is returned in memory; the raw export file lives only in a private temporary directory that
 * is deleted on every exit path (FR-026). Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id.
 */
public interface ArtifactPort {

    /**
     * The export archive of the technical workflows of diagram group {@code diagramGroup} owned
     * by {@code owner}, with the modules they use and the owner's repository.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} for a
     *     blank diagram group (StartCLI treats an empty group as "all") or a name StartCLI quoting
     *     cannot carry, {@code NOT_FOUND}, {@code CLI_UNAVAILABLE}, {@code AUTH_FAILED},
     *     {@code TIMEOUT}, {@code UNEXPECTED_RESPONSE}
     */
    byte[] exportWorkflowGroup(String owner, String diagramGroup);

    /**
     * The module-only export archive of module {@code name} of plugin type {@code pluginType}
     * owned by {@code owner}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException as
     *     {@link #exportWorkflowGroup}
     */
    byte[] exportModule(String owner, String pluginType, String name);
}
