package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan;
import java.util.List;

/**
 * Slices the export of a diagram group into Workbench archives of one technical workflow each
 * (feature 015, research R-3). The application never reads the archive format itself. The
 * archives hold the secrets as INUBIT exported them: their bytes are never logged. Failures are
 * thrown as {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException}, without a server id.
 */
public interface WorkflowArchivePort {

    /**
     * The export {@code diagramGroupExport} of {@code diagramGroup}, read and analysed: every
     * plan is computed here, so that an export that cannot be analysed fails before the first
     * archive is written (FR-017).
     *
     * @param group the stage group of the node it was exported from
     * @param owner the owner whose repository files the archives may hold
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code UNEXPECTED_RESPONSE}
     *     for an export that cannot be read
     */
    SlicedExport slice(byte[] diagramGroupExport, GroupId group, String owner,
        String diagramGroup);

    /** One analysed export; the archive of a workflow is built only when asked for. */
    interface SlicedExport {

        /** The technical workflows of the diagram group, in export order. */
        List<String> workflows();

        /**
         * What the archive of {@code workflow} holds.
         *
         * @throws IllegalArgumentException if {@code workflow} is none of {@link #workflows()}
         */
        WorkflowArchivePlan plan(String workflow);

        /**
         * The archive of {@code workflow} (a ZIP the Workbench imports); never logged.
         *
         * @throws IllegalArgumentException if {@code workflow} is none of {@link #workflows()}
         */
        byte[] archive(String workflow);
    }
}
