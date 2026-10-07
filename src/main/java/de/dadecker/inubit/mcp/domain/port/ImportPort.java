package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;

/**
 * Imports into one INUBIT server (feature 004, research D-8; 8.1: StartCLI {@code import
 * --returnProtocol}). The archive holds secret values: an adapter keeps it only in a private
 * temporary directory that is deleted on every path. Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id.
 */
public interface ImportPort {

    /** How the archive is imported. */
    enum Mode {
        /** {@code --importWorkflow}: the workflows (and the modules in the archive). */
        WORKFLOW,
        /** {@code --importWorkflow --importWorkflowActive}. */
        WORKFLOW_ACTIVE,
        /** {@code --importWorkflow --importWorkflowInactive}. */
        WORKFLOW_INACTIVE,
        /** {@code --importModule}: a module-only archive. */
        MODULE
    }

    /**
     * Checks without launching anything that an import can run (CLI, credentials).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    void checkAvailable();

    /**
     * Imports {@code archive} for {@code owner} ({@code --importUser} for a user,
     * {@code --importUserGroup} for a user group) and returns INUBIT's protocol.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT}
     *     before anything is launched; {@code IMPORT_FAILED} if StartCLI reports a failure or an
     *     unreadable protocol; {@code TIMEOUT} (the import may have happened)
     */
    ImportProtocol importArchive(byte[] archive, Mode mode, String owner, OwnerKind kind);
}
