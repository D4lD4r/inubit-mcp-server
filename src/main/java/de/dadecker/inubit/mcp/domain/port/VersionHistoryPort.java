package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The local version history of one workspace (feature 003, research D-1; a git repository). It
 * has no remote and offers no way to transmit the history (FR-007). Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException}.
 */
public interface VersionHistoryPort {

    /**
     * Creates the history in the workspace root if it does not exist yet, with a
     * {@code .gitignore} for {@code .tests/}, {@code .reports/} and {@code .lock}; idempotent.
     */
    void init();

    /** The uncommitted changes of the workspace (added, modified, deleted files). */
    List<PathChange> status();

    /**
     * Records every change of the workspace as one entry with {@code message}.
     *
     * @return the entry, or empty if nothing changed
     */
    Optional<HistoryEntry> commitAll(String message);

    /**
     * Restores {@code subtree} (relative to the workspace root) to the last entry: changed and
     * deleted files are restored, files added since are removed.
     */
    void restore(Path subtree);
}
