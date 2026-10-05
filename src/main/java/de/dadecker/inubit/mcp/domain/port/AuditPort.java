package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.AuditRecord;

/**
 * The append-only audit log of the write tools (FR-023, research R-14).
 */
public interface AuditPort {

    /**
     * Appends {@code record} durably: when this method returns, the record is on disk.
     *
     * @throws RuntimeException if the record could not be written; callers fail closed (a
     *     state-changing action whose audit record is not written is not executed)
     */
    void append(AuditRecord record);
}
