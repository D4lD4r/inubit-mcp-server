package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

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

    /**
     * The workspace files of repository exports ({@code archives}, one per exported repository
     * path, feature 008 research D-10) for {@code owner} on {@code group}: every file of each
     * export below {@code /Root/<owner>/}, folders recursively, with its {@code .meta} record.
     * Key material is withheld as in every export (its {@code .meta} record holds a placeholder,
     * a warning counts it); certificates are written. {@link PreparedExport#scope()} is the
     * exported paths and their {@code .meta} mirrors only, and {@link PreparedExport#writeTo}
     * overwrites files there but deletes none. The limits of a repository export are the
     * caller's.
     */
    PreparedExport prepareRepository(GroupId group, String owner, List<byte[]> archives);

    /** The redacted, rendered files of one export request, not written yet. */
    interface PreparedExport {

        /** The number of secret values replaced by placeholders. */
        int secretsReplaced();

        /** Warnings for the caller (e.g. a workflow in edit mode). */
        List<String> warnings();

        /**
         * The workspace-relative directories (for a repository export of a single file: that
         * file) that contain every file {@link #writeTo} may create, change or delete;
         * restoring them undoes a failed write.
         */
        List<String> scope();

        /**
         * The rendered (redacted) files, read-only: workspace-relative path → content, the
         * {@code .meta/} records included (feature 004, research D-5). Nothing is written.
         */
        SortedMap<String, byte[]> files();

        /** The workflows in Workbench edit mode ({@code CheckoutUser}): name → user. */
        Map<String, String> inEditMode();

        /**
         * Writes the files below {@code root}, replacing the exported sub-trees (artifacts no
         * longer exported disappear, FR-017); unchanged files are not rewritten.
         */
        void writeTo(Path root);
    }
}
