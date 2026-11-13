package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.ResourceKind;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The 8.1 {@link UserDirectoryPort} (feature 022, US2, research D-20); 9.0 uses it too.
 *
 * <ul>
 *   <li>{@code GET /ibis/rest/user/users?type=processEngineUser} with credentials
 *       ({@link UserListXmlParser}: login and type only);
 *   <li>the StartCLI reads are passed to {@code readUsers}, {@code loggedIn} and
 *       {@code connectedSystems} (in production the methods of {@code CliUserRunner}), so this
 *       REST package does not reach the StartCLI adapter.
 * </ul>
 *
 * <p>Every HTTP status keeps the mapping of {@link InubitHttpClient}, e.g. 403 {@code FORBIDDEN},
 * 503 during maintenance {@code MAINTENANCE_MODE}; a 404 is a missing endpoint
 * ({@code UNEXPECTED_RESPONSE}).
 */
public final class V81UserAdapter implements UserDirectoryPort {

    private static final Map<String, String> PROCESS_ENGINE_USERS =
        Map.of("type", "processEngineUser");

    private final NodeId server;
    private final InubitHttpClient client;
    private final Function<List<String>, List<UserFacts>> readUsers;
    private final Supplier<List<LoginEntry>> loggedIn;
    private final IntSupplier connectedSystems;

    public V81UserAdapter(NodeId server, InubitHttpClient client,
        Function<List<String>, List<UserFacts>> readUsers, Supplier<List<LoginEntry>> loggedIn,
        IntSupplier connectedSystems) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
        this.readUsers = Objects.requireNonNull(readUsers, "readUsers");
        this.loggedIn = Objects.requireNonNull(loggedIn, "loggedIn");
        this.connectedSystems = Objects.requireNonNull(connectedSystems, "connectedSystems");
    }

    @Override
    public List<ListedUser> listUsers() {
        try {
            return UserListXmlParser.parse(server, client.get(RestEndpoint.USERS, List.of(),
                PROCESS_ENGINE_USERS, MediaRange.XML, ResourceKind.ENDPOINT).body());
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(server));
        }
    }

    @Override
    public List<UserFacts> readUsers(List<String> logins) {
        return readUsers.apply(logins);
    }

    @Override
    public List<LoginEntry> loggedIn() {
        return loggedIn.get();
    }

    @Override
    public int connectedSystems() {
        return connectedSystems.getAsInt();
    }

    @Override
    public String toString() {
        return "V81UserAdapter[" + server + "]";
    }
}
