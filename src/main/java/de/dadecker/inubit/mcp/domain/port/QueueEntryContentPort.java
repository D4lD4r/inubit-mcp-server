package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import java.util.Set;

/**
 * Reads the content of one Queue Manager entry of one node through an unsupported INUBIT
 * interface (feature 020, D-5). Thread-safe.
 */
public interface QueueEntryContentPort {

    /**
     * Reads the requested {@code parts} of {@code ref}; parts not requested are
     * {@link RawEntryContent.PartResult.NotRequested}. A part that cannot be read is reported in
     * its {@link RawEntryContent.PartResult}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException for a failure of the whole
     *     call (e.g. login, {@code TIMEOUT}); no partial content is returned then
     */
    RawEntryContent read(QueueEntryRef ref, InubitVersion version, Set<EntryPart> parts);
}
