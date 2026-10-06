package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns export archives into workspace files (feature 003, research D-2 – D-6): read, redact
 * and render entirely in memory first, so that an archive that cannot be processed leaves the
 * workspace untouched (D-9 step 3, FR-018); only {@link PreparedExport#writeTo} touches files.
 * Failures are thrown as {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException}
 * ({@code UNEXPECTED_RESPONSE} for an unreadable archive, {@code INVALID_INPUT} for a case-only
 * path collision), without a server id.
 */
public interface ArchiveCodecPort {

    /**
     * The workspace files of {@code archives} (the exports of one request) for {@code owner} on
     * {@code group}, with every secret replaced by a placeholder.
     */
    PreparedExport prepare(GroupId group, String owner, List<byte[]> archives);

    /** The redacted, rendered files of one export request, not written yet. */
    interface PreparedExport {

        /** The number of secret values replaced by placeholders. */
        int secretsReplaced();

        /** Warnings for the caller (e.g. a workflow in edit mode). */
        List<String> warnings();

        /**
         * The workspace-relative directories that contain every file {@link #writeTo} may
         * create, change or delete; restoring them undoes a failed write.
         */
        List<String> scope();

        /**
         * Writes the files below {@code root}, replacing the exported sub-trees (artifacts no
         * longer exported disappear, FR-017); unchanged files are not rewritten.
         */
        void writeTo(Path root);
    }
}
