package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import java.util.List;

/**
 * Reads the users of one node for {@code read_users} (feature 022, US2, research D-20): the login
 * list over REST, the groups, roles and personal fields of a page of logins and the logged-in
 * users over StartCLI. Thread-safe.
 */
public interface UserDirectoryPort {

    /**
     * The users of {@code GET /user/users?type=processEngineUser}, login and type only, in the
     * order of the answer (unsorted).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException with the node set, e.g.
     *     {@code FORBIDDEN}, {@code MAINTENANCE_MODE}, or {@code UNEXPECTED_RESPONSE} for an
     *     answer that is no user list
     */
    List<ListedUser> listUsers();

    /**
     * Reads the groups ({@code user <login>}) and the roles, e-mail and personal fields
     * ({@code finger <login>}) of {@code logins} in one StartCLI session; no logins start nothing.
     *
     * @param logins at most 25, a page of {@code read_users}
     * @return one entry per login, in the order given; an answer that could not be read is
     *     {@link UserFacts.Block.Unavailable} with the reason (FR-013)
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException with the node set, for a
     *     failure of the whole session (e.g. {@code CLI_UNAVAILABLE}, {@code AUTH_FAILED},
     *     {@code TIMEOUT})
     * @throws IllegalArgumentException for more than 25 logins; nothing is started
     */
    List<UserFacts> readUsers(List<String> logins);

    /**
     * The logged-in users: the {@code Toolset} rows of {@code --who}, without a login time on
     * 8.1.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException with the node set, for a
     *     failed StartCLI run or an unknown answer
     */
    List<LoginEntry> loggedIn();
}
