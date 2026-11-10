package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.application.knowledge.Record.Mode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The result of a written note (feature 019, research D-10 step 8).
 *
 * @param name            the note's name, masked, one line, cut
 * @param lastChange      the modification time of the written file, in seconds
 * @param versionToken    the token of the written note, for a later {@code replace}
 * @param sizeBefore      bytes of the note before the write; 0 for {@code create}
 * @param sizeAfter       bytes of the note after the write
 * @param historyVersions versions kept for this note; more than ten only if pruning failed
 * @param auditId         the id of the call's audit records
 * @param warnings        what failed after the note was written (pruning, the final audit record)
 */
public record Recorded(String name, Mode mode, Instant lastChange, String versionToken,
    long sizeBefore, long sizeAfter, int historyVersions, String auditId, List<String> warnings) {

    public Recorded {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(lastChange, "lastChange");
        Objects.requireNonNull(versionToken, "versionToken");
        Objects.requireNonNull(auditId, "auditId");
        warnings = List.copyOf(warnings);
    }
}
