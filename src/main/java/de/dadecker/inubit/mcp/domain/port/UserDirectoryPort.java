package de.dadecker.inubit.mcp.domain.port;

import java.util.Set;

/**
 * INUBIT's user administration of one node, read-only (feature 004, research D-21): the login
 * names of its process engine users. User groups are not listed, so absence proves nothing
 * (research D-25). Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the node id.
 */
@FunctionalInterface
public interface UserDirectoryPort {

    /**
     * The ids (login names) of the node's process engine users.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException e.g.
     *     {@code AUTH_FAILED}, {@code UNREACHABLE}, {@code UNEXPECTED_RESPONSE}
     */
    Set<String> users();
}
