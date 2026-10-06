package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The local version history of one workspace (feature 003, research D-1; a git repository). It
 * has no remote and offers no way to transmit the history (FR-007). Only {@link #init},
 * {@link #commitAll} and {@link #restore} write; the methods of feature 004 read (research D-3).
 * Failures are thrown as {@link ToolErrorException}.
 */
public interface VersionHistoryPort {

    /** The git trailer of an entry that records a verified server state (research D-3). */
    String SERVER_STATE = "Server-State";

    /**
     * Creates the history in the workspace root if it does not exist yet, with a
     * {@code .gitignore} for {@code .tests/}, {@code .reports/} and {@code .lock}; idempotent.
     */
    void init();

    /** The uncommitted changes of the workspace (added, modified, deleted files). */
    List<PathChange> status();

    /**
     * Records every change of the workspace as one entry with {@code message}, without
     * trailers.
     *
     * @return the entry, or empty if nothing changed
     */
    default Optional<HistoryEntry> commitAll(String message) {
        return commitAll(message, Map.of());
    }

    /**
     * Records every change of the workspace as one entry with {@code message} and the given git
     * trailers (feature 004, research D-3), e.g. {@link #SERVER_STATE}{@code : <group>} for an
     * entry that records a verified server state.
     *
     * @return the entry (its message without the trailers), or empty if nothing changed
     */
    Optional<HistoryEntry> commitAll(String message, Map<String, String> trailers);

    /**
     * Restores {@code subtree} (relative to the workspace root) to the last entry: changed and
     * deleted files are restored, files added since are removed.
     */
    void restore(Path subtree);

    /**
     * The newest entry that recorded a verified server state of {@code group} and touched
     * {@code path} (a file or directory relative to the workspace root): an entry with the
     * trailer {@code Server-State: <group>}, or, in histories of feature 003 without that trailer,
     * an entry without any {@code Server-State} trailer whose subject starts with
     * {@code export <group>/} (research D-3, D-25). Read-only.
     *
     * @return the full commit id, or empty if there is none
     */
    Optional<String> lastServerState(GroupId group, String path);

    /**
     * {@link #lastServerState}, or a refusal: without a base export nothing can be compared or
     * imported.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} "export the scope first"
     */
    default String serverStateOf(GroupId group, String path) {
        return lastServerState(group, path).orElseThrow(() -> new ToolErrorException(
            ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The workspace history has no exported server state of " + path + " for "
                    + group,
                "The scope was never exported into this workspace (or only edited locally)",
                "export the scope first (export_artifacts), then repeat the call")));
    }

    /**
     * The content of {@code path} at {@code commit} (a commit id of this history). Read-only.
     *
     * @return the bytes, or empty if the file does not exist at that commit
     */
    Optional<byte[]> show(String commit, String path);

    /**
     * The files below {@code subtree} that differ between {@code fromCommit} and the newest entry
     * ({@code HEAD}): added, modified and deleted; a renamed file is a deletion plus an addition.
     * Read-only.
     */
    List<PathChange> changedPaths(String fromCommit, String subtree);
}
