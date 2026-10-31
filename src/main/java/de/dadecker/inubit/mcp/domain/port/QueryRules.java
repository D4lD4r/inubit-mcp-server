package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;

/**
 * The server-independent input checks of the diagnosis queries (feature 011, review T013-T014
 * r1): the same checks as {@link LogPort#validate} and {@link ProcessQueryPort#validate}, but
 * available without the node's credentials or client, so that a refused request reads no
 * credential store entry (and opens no access dialog).
 */
public interface QueryRules {

    /**
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} as
     *     {@link LogPort#validate}
     */
    void validate(LogQuery query);

    /**
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} as
     *     {@link ProcessQueryPort#validate}
     */
    void validate(ProcessQuery query);
}
