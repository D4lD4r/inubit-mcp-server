package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.UserReadService;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.PersonalField;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Block;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Finger;
import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 022 T035 (US2 AS1–AS5, FR-009, FR-011, FR-013, contracts/mcp-tools-delta.md
 * "read_users"): {@code read_users} over MCP with the real {@link UserReadService} on a fake user
 * directory. The description, annotations and input schema (no message view before L-17), the
 * schema rejecting malformed calls before the service, and the result shape per view, validated
 * against {@code read_users.output.json}: a listing without e-mail, one user shown flat with its
 * personal fields, the logins without a login time, and what could not be read listed under the
 * user's {@code unavailable}. The coverage list is a fixture whose US2 rows are reachable on 8.1.
 */
@Timeout(60)
class ReadUsersToolTest {

    /** The tool description, pinned word for word. */
    private static final String CONTRACT_DESCRIPTION = "Read the users of one INUBIT {node}."
        + " `target` takes the id of exactly one {node} (`<{group}>/<{node}>`). `view` picks what"
        + " to read: `users` lists the users by login, each with its user group and roles, without"
        + " e-mail or other personal fields; `user` shows the one user named in `login` with its"
        + " user group, the parent of that user group, roles, e-mail and the other personal fields"
        + " INUBIT shows (e-mail and personal fields come only for one named user); `logins` lists"
        + " the users logged in with a client (the `Toolset` connections of StartCLI `--who`, this"
        + " call's own connection included), without a login time on INUBIT 8.1. `users` and"
        + " `logins` are sorted by login and paged with `offset` and `limit` (at most 25);"
        + " `nextOffset` gives the next page. The logins come from the INUBIT user list; the user"
        + " group, roles, e-mail and personal fields are read with StartCLI. A part that cannot be"
        + " read is listed under the user's `unavailable` with the reason, the other users and"
        + " parts are returned. An unknown login gives NOT_FOUND: list the users first. The"
        + " messages of the INUBIT message box are not offered yet. Reads only; changes nothing on"
        + " INUBIT.";

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        REST\tGET /user/users\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\tuser\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\tfinger\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\t-who\tREAD\tB\tUS2\treachable:read_users/logins\tplanned\t-
        CLI\t-nwho\tREAD\tB\tUS2\tcovered:read_users\tplanned\t-
        """;

    private static final ToolError SESSION_FAILED = ToolError.of(ErrorCode.CLI_UNAVAILABLE,
        "No StartCLI client is installed for dev/node1", "No client", "Install a client")
        .withNode(DEV1);
    private static final ToolError BLOCK_UNREADABLE = ToolError.of(
        ErrorCode.UNEXPECTED_RESPONSE, "StartCLI: the answer is unknown", "The output changed",
        "Report the excerpt").withNode(DEV1);

    private final FakeDirectory directory = new FakeDirectory();
    private McpTestClient client;

    @BeforeEach
    void setUp() {
        NodeVersions versions = new NodeVersions(Clock.systemUTC(),
            Map.of(DEV1, VersionSetting.V8_1)::get, node -> Duration.ofMinutes(10));
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, new ClientVersion(
            InubitVersion.parse("8.1.12").orElseThrow(), InubitVersion.parse("8.1.0"),
            InubitVersion.parse("8.1.99"))));
        VersionGate gate = new VersionGate(versions, new FakeVersionDetection(versions),
            userDirectorySupported(), (node, version, timeout) -> {
                throw new AssertionError("no client probe expected for " + node);
            }, TestClientSelection.of(DEV1::equals), node -> Duration.ZERO,
            Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(LIST)));
        client = McpTestClient.start(List.of(new ReadUsersTool(new UserReadService(
            new TargetResolver(List.of(DEV1)), gate, node -> directory))), new SecretScrubber());
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- announcement -------------------------------------------------------------------------

    @Test
    void theToolIsReadOnlyWithTheContractDescriptionAndSchema() {
        JsonNode tool = client.listTools().path("tools").get(0);

        assertThat(tool.path("name").asString()).isEqualTo("read_users");
        assertThat(ReadUsersTool.DESCRIPTION).isEqualTo(CONTRACT_DESCRIPTION);
        assertThat(tool.path("description").asString())
            .isEqualTo("[acme] " + Terminology.DEFAULT.render(CONTRACT_DESCRIPTION));
        JsonNode annotations = tool.path("annotations");
        assertThat(annotations.path("title").asString()).isEqualTo("Read INUBIT users");
        assertThat(annotations.path("readOnlyHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean(false)).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean(false)).isTrue();
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required")).extracting(JsonNode::asString)
            .containsExactly("target", "view");
        assertThat(schema.path("properties").propertyNames()).containsExactly("target", "view",
            "login", "offset", "limit");
        assertThat(schema.path("properties").path("view").path("enum"))
            .extracting(JsonNode::asString).as("no message view before L-17")
            .containsExactly("users", "user", "logins");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("target").path("description").asString())
            .isEqualTo("Id of exactly one node (`<group>/<node>`); group ids are rejected");
        assertThat(schema.path("properties").path("limit").path("default").asInt())
            .isEqualTo(UserReadService.DEFAULT_LIMIT);
    }

    @Test
    void theDescriptionStatesTheContractPoints() {
        String description = ReadUsersTool.DESCRIPTION.toLowerCase(Locale.ROOT);

        // FR-011 personal fields only for one user; logins from --who without a time; L-17
        assertThat(description).contains("reads only", "changes nothing",
            "without e-mail or other personal fields", "only for one named user", "`--who`",
            "without a login time", "not_found", "`unavailable`", "messages",
            "not offered yet");
    }

    // --- input schema -------------------------------------------------------------------------

    @Test
    void theSchemaRejectsMalformedCallsBeforeTheService() {
        List<Map<String, Object>> calls = new ArrayList<>(List.of(
            Map.of(),
            Map.of("view", "users"),
            Map.of("target", "dev/node1"),
            Map.of("target", "dev", "view", "users"),
            Map.of("target", "dev/node1/x", "view", "users"),
            Map.of("target", "dev/node1", "view", "messages"),
            Map.of("target", "dev/node1", "view", "message", "messageId", "1"),
            Map.of("target", "dev/node1", "view", "user"),
            Map.of("target", "dev/node1", "view", "user", "login", ""),
            Map.of("target", "dev/node1", "view", "user", "login", "x".repeat(201)),
            Map.of("target", "dev/node1", "view", "user", "login", "a\nb"),
            Map.of("target", "dev/node1", "view", "user", "login", "a\u007Fb"),
            Map.of("target", "dev/node1", "view", "user", "login", "a", "offset", 0),
            Map.of("target", "dev/node1", "view", "user", "login", "a", "limit", 5),
            Map.of("target", "dev/node1", "view", "users", "login", "a"),
            Map.of("target", "dev/node1", "view", "logins", "login", "a"),
            Map.of("target", "dev/node1", "view", "users", "offset", -1),
            Map.of("target", "dev/node1", "view", "users", "offset", 10000),
            Map.of("target", "dev/node1", "view", "users", "limit", 0),
            Map.of("target", "dev/node1", "view", "logins", "limit", 26),
            Map.of("target", "dev/node1", "view", "users", "unknown", true)));

        for (Map<String, Object> arguments : calls) {
            JsonNode response = client.callTool("read_users", arguments);
            assertThat(response.path("isError").asBoolean()).as(arguments.toString()).isTrue();
            assertThat(response.has("structuredContent")).isFalse();
            assertThat(response.path("content").get(0).path("text").asString())
                .as("the schema, not the service's ToolError: %s", arguments)
                .doesNotContain("\"code\"");
        }
        assertThat(directory.listCalls).as("the service was not called").isZero();
        assertThat(directory.loginReads).isZero();
    }

    @Test
    void theLimitsOfTheSchemaAreInclusive() {
        String longest = "ü".repeat(200);
        directory.users = List.of(new ListedUser(longest, "ProcessEngineUser"));
        List<Map<String, Object>> arguments = List.of(
            Map.of("target", "dev/node1", "view", "users", "offset", 0, "limit", 1),
            Map.of("target", "dev/node1", "view", "users", "offset", 9999, "limit", 25),
            Map.of("target", "dev/node1", "view", "logins", "offset", 9999, "limit", 25),
            Map.of("target", "dev/node1", "view", "user", "login", longest));

        for (Map<String, Object> call : arguments) {
            JsonNode response = client.callTool("read_users", call);
            assertMatchesOutputSchema("read_users", response);
        }
    }

    // --- users (AS1, AS5, FR-009, FR-013) -----------------------------------------------------

    @Test
    void aPageOfUsersHasTheContractShapeWithoutAnyPersonalField() {
        directory.users = users(3);

        JsonNode content = call(Map.of("target", "dev/node1", "view", "users", "limit", 2));

        assertThat(content.propertyNames()).containsExactly("node", "view", "sources", "page",
            "warnings");
        assertThat(content).isEqualTo(json("""
            {"node": "dev/node1", "view": "users",
             "sources": {"groups": "CLI", "logins": "REST", "roles": "CLI"},
             "page": {"items": [
                 {"login": "user-000", "group": "Group-user-000", "roles": ["Role-user-000"],
                  "unavailable": []},
                 {"login": "user-001", "group": "Group-user-001", "roles": ["Role-user-001"],
                  "unavailable": []}],
               "offset": 0, "limit": 2, "total": 3, "totalIsLowerBound": false,
               "truncated": false, "nextOffset": 2},
             "warnings": []}"""));
        // AS5: the session read e-mails and names, the listing shows none of them
        assertThat(content.toString()).doesNotContain("@example.test", "Doe", "Super-Group");
    }

    @Test
    void theOffsetAndLimitReachTheService() {
        directory.users = users(30);

        JsonNode page = call(Map.of("target", "dev/node1", "view", "users", "offset", 25))
            .path("page");

        assertThat(page.path("items")).extracting(item -> item.path("login").asString())
            .containsExactly("user-025", "user-026", "user-027", "user-028", "user-029");
        assertThat(page.path("limit").asInt()).as("the default limit")
            .isEqualTo(UserReadService.DEFAULT_LIMIT);
        assertThat(page.has("nextOffset")).isFalse();
        assertThat(directory.sessions).containsExactly(
            List.of("user-025", "user-026", "user-027", "user-028", "user-029"));
    }

    @Test
    void aUserWhoseGroupCouldNotBeReadIsListedWithItsGroupUnavailable() {
        // FR-013: one unreadable answer costs only that part of that user
        directory.users = users(2);
        directory.groupsUnreadable.add("user-001");

        JsonNode items = call(Map.of("target", "dev/node1", "view", "users")).path("page")
            .path("items");

        assertThat(items.get(0).path("group").asString()).isEqualTo("Group-user-000");
        assertThat(items.get(1)).isEqualTo(json("""
            {"login": "user-001", "roles": ["Role-user-001"],
             "unavailable": [{"part": "groups",
                              "reason": "UNEXPECTED_RESPONSE: StartCLI: the answer is unknown",
                              "likelyCause": "The output changed",
                              "nextStep": "Report the excerpt"}]}"""));
    }

    @Test
    void aFailedSessionStillListsEveryUserWithGroupsAndRolesUnavailable() {
        directory.users = users(2);
        directory.sessionFailure = new ToolErrorException(SESSION_FAILED);

        JsonNode content = call(Map.of("target", "dev/node1", "view", "users"));

        assertThat(content.path("sources")).isEqualTo(json("{\"logins\": \"REST\"}"));
        for (JsonNode item : content.path("page").path("items")) {
            assertThat(item.propertyNames()).containsExactly("login", "unavailable");
            assertThat(item.path("unavailable")).extracting(part -> part.path("part").asString())
                .containsExactly("groups", "roles");
            assertThat(item.path("unavailable").get(1).path("reason").asString())
                .isEqualTo("CLI_UNAVAILABLE: No StartCLI client is installed for dev/node1");
        }
    }

    // --- user (AS2, AS3, FR-011) --------------------------------------------------------------

    @Test
    void oneUserIsShownFlatWithItsEMailAndPersonalFields() {
        directory.users = users(2);
        directory.personal = List.of(PersonalField.shown("Name", "Doe, user-001"),
            PersonalField.masked("Token"));

        JsonNode content = call(Map.of("target", "dev/node1", "view", "user",
            "login", "user-001"));

        assertThat(content.propertyNames()).containsExactly("node", "view", "sources", "user",
            "warnings");
        assertThat(content.path("sources")).isEqualTo(json("""
            {"email": "CLI", "groups": "CLI", "logins": "REST", "personal": "CLI",
             "roles": "CLI"}"""));
        assertThat(content.path("user")).isEqualTo(json("""
            {"login": "user-001", "group": "Group-user-001", "superGroup": "Super-Group",
             "roles": ["Role-user-001"], "email": "user-001@example.test",
             "personal": [{"name": "Name", "value": "Doe, user-001"},
                          {"name": "Token", "masked": true}],
             "unavailable": []}"""));
        assertThat(directory.sessions).containsExactly(List.of("user-001"));
    }

    @Test
    void aUserWhoseFingerCouldNotBeReadHasRolesEMailAndPersonalFieldsUnavailable() {
        directory.users = users(1);
        directory.fingerUnreadable.add("user-000");

        JsonNode user = call(Map.of("target", "dev/node1", "view", "user", "login", "user-000"))
            .path("user");

        assertThat(user.propertyNames()).containsExactly("login", "group", "superGroup",
            "unavailable");
        assertThat(user.path("unavailable")).extracting(part -> part.path("part").asString())
            .containsExactly("roles", "email", "personal");
    }

    @Test
    void anUnknownLoginIsNotFoundWithTheListingAsNextStep() {
        // AS3
        directory.users = users(2);

        JsonNode error = toolError(client.callTool("read_users", Map.of("target", "dev/node1",
            "view", "user", "login", "nobody")));

        assertThat(error.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.path("node").asString()).isEqualTo("dev/node1");
        assertThat(error.path("nextStep").asString()).contains("view users");
        assertThat(directory.sessions).as("no session for an unknown login").isEmpty();
    }

    // --- logins (AS4) -------------------------------------------------------------------------

    @Test
    void theLoginsArePagedByLoginWithoutALoginTime() {
        directory.loggedIn = List.of(new LoginEntry("zoe", Optional.empty()),
            new LoginEntry("adam", Optional.empty()), new LoginEntry("mia", Optional.empty()));

        JsonNode content = call(Map.of("target", "dev/node1", "view", "logins", "limit", 2));

        assertThat(content).isEqualTo(json("""
            {"node": "dev/node1", "view": "logins", "sources": {"loggedIn": "CLI"},
             "page": {"items": [{"login": "adam"}, {"login": "mia"}],
               "offset": 0, "limit": 2, "total": 3, "totalIsLowerBound": false,
               "truncated": false, "nextOffset": 2},
             "warnings": []}"""));
        assertThat(directory.listCalls).as("the logins need no user list").isZero();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Calls the tool; the result validates against the output schema. */
    private JsonNode call(Map<String, Object> arguments) {
        JsonNode response = client.callTool("read_users", arguments);
        assertMatchesOutputSchema("read_users", response);
        return response.path("structuredContent");
    }

    private static List<ListedUser> users(int count) {
        return IntStream.range(0, count).map(i -> count - 1 - i)
            .mapToObj(i -> new ListedUser("user-%03d".formatted(i), "ProcessEngineUser"))
            .toList();
    }

    private static CapabilityMatrix userDirectorySupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    /**
     * The user directory of dev/node1: {@code finger} gives one role, an e-mail and the
     * {@link #personal} fields per user (planted, AS5).
     */
    private static final class FakeDirectory implements UserDirectoryPort {

        volatile List<ListedUser> users = List.of();
        volatile List<LoginEntry> loggedIn = List.of();
        volatile List<PersonalField> personal = List.of(PersonalField.shown("Name", "Doe"));
        final Set<String> groupsUnreadable = new HashSet<>();
        final Set<String> fingerUnreadable = new HashSet<>();
        volatile ToolErrorException sessionFailure;
        final List<List<String>> sessions = new CopyOnWriteArrayList<>();
        volatile int listCalls;
        volatile int loginReads;

        @Override
        public List<ListedUser> listUsers() {
            listCalls++;
            return users;
        }

        @Override
        public List<UserFacts> readUsers(List<String> logins) {
            sessions.add(List.copyOf(logins));
            if (sessionFailure != null) {
                throw sessionFailure;
            }
            List<UserFacts> facts = new ArrayList<>();
            for (String login : logins) {
                Block<UserGroups> groups = groupsUnreadable.contains(login)
                    ? new Block.Unavailable<>(BLOCK_UNREADABLE)
                    : new Block.Parsed<>(new UserGroups("Group-" + login, "Super-Group"));
                Block<Finger> finger = fingerUnreadable.contains(login)
                    ? new Block.Unavailable<>(BLOCK_UNREADABLE)
                    : new Block.Parsed<>(new Finger(login, List.of("Role-" + login),
                        Optional.of(login + "@example.test"), personal));
                facts.add(new UserFacts(login, groups, finger));
            }
            return facts;
        }

        @Override
        public List<LoginEntry> loggedIn() {
            loginReads++;
            return loggedIn;
        }
    }
}
