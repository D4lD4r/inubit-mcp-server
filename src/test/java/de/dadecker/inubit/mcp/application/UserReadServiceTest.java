package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.UserReadService.UserEntry;
import de.dadecker.inubit.mcp.application.UserReadService.ViewResult;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
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
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.StringReader;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T034 (feature 022, US2 AS1–AS6, research D-20, "Live recording T028"/"T028a", FR-009, FR-011,
 * FR-013): the views {@code users}, {@code user} and {@code logins} of {@code read_users}. The
 * capability matrix supports {@code USER_DIRECTORY} and the coverage list is a fixture whose US2
 * rows are reachable on 8.1 and {@code planned} on 9.0; the production list keeps them
 * {@code planned} until T037. The message views wait for an L-17 recording (owner decision
 * 2026-11-13).
 */
@Timeout(30)
class UserReadServiceTest {

    /** INUBIT 8.1 with a StartCLI client. */
    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    /** INUBIT 9.0, whose US2 rows the fixture keeps {@code planned}. */
    private static final NodeId TEST1 = NodeId.parse("test/node1");

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        REST\tGET /user/users\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\tuser\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\tfinger\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\t-who\tREAD\tB\tUS2\treachable:read_users/logins\tplanned\t-
        CLI\t-nwho\tREAD\tB\tUS2\tplanned\tplanned\t-
        """;

    private static final ToolError SESSION_FAILED = ToolError.of(ErrorCode.CLI_UNAVAILABLE,
        "No StartCLI client is installed for dev/node1", "No client", "Install a client")
        .withNode(DEV1);
    private static final ToolError BLOCK_UNREADABLE = ToolError.of(
        ErrorCode.UNEXPECTED_RESPONSE, "StartCLI: the answer of user is unknown",
        "The output changed", "Report the excerpt").withNode(DEV1);

    private final NodeVersions versions = new NodeVersions(Clock.systemUTC(),
        Map.of(DEV1, VersionSetting.V8_1, TEST1, VersionSetting.V9_0)::get,
        node -> Duration.ofMinutes(10));
    private final Set<NodeId> withClient = new HashSet<>(Set.of(DEV1, TEST1));

    private final VersionGate gate = new VersionGate(versions,
        new FakeVersionDetection(versions), userDirectorySupported(),
        (node, version, timeout) -> {
            throw new AssertionError("no client probe expected for " + node);
        }, TestClientSelection.of(withClient::contains), node -> Duration.ZERO,
        Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(LIST)));

    private final FakeDirectory directory = new FakeDirectory();

    private final UserReadService service = new UserReadService(
        new TargetResolver(List.of(DEV1, TEST1)), gate, node -> directory);

    {
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, client("8.1")));
        versions.clientProbed(TEST1, new ProbedClient(ClientKey.NONE, client("9.0")));
    }

    // --- AS1: a page of users ------------------------------------------------------------------

    @Test
    void aListOf500UsersIsPagedBySortedLoginWithANextOffset() {
        directory.users = shuffledUsers(500);

        ViewResult<Page<UserEntry>> first = service.users("dev/node1", 0, OptionalInt.empty());

        Page<UserEntry> page = first.value();
        assertThat(first.node()).isEqualTo(DEV1);
        assertThat(page.items()).extracting(UserEntry::login)
            .containsExactlyElementsOf(logins(0, 25));
        assertThat(page.offset()).isZero();
        assertThat(page.limit()).isEqualTo(UserReadService.DEFAULT_LIMIT);
        assertThat(page.total()).isEqualTo(OptionalLong.of(500));
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.nextOffset()).isEqualTo(OptionalInt.of(25));
        // one session for the page's logins, in page order
        assertThat(directory.sessions).containsExactly(logins(0, 25));
        assertThat(page.items().get(0).group()).isEqualTo(new Block.Parsed<>("Group-user-000"));
        assertThat(page.items().get(0).roles())
            .isEqualTo(new Block.Parsed<>(List.of("Role-user-000")));

        Page<UserEntry> last = service.users("dev/node1", 475, OptionalInt.empty()).value();
        assertThat(last.items()).extracting(UserEntry::login)
            .containsExactlyElementsOf(logins(475, 500));
        assertThat(last.nextOffset()).isEmpty();

        Page<UserEntry> small = service.users("dev/node1", 490, OptionalInt.of(5)).value();
        assertThat(small.items()).extracting(UserEntry::login)
            .containsExactlyElementsOf(logins(490, 495));
        assertThat(small.nextOffset()).isEqualTo(OptionalInt.of(495));
    }

    @Test
    void anOffsetBehindTheLastUserGivesAnEmptyPage() {
        directory.users = shuffledUsers(3);

        Page<UserEntry> page = service.users("dev/node1", 3, OptionalInt.empty()).value();

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(page.nextOffset()).isEmpty();
    }

    // --- AS5, FR-011, FR-009: a listing carries no personal field ------------------------------

    @Test
    void aListingCarriesNoEmailOrOtherPersonalFieldAndNamesItsSources() {
        directory.users = shuffledUsers(3);

        ViewResult<Page<UserEntry>> result = service.users("dev/node1", 0, OptionalInt.empty());

        assertThat(Arrays.stream(UserEntry.class.getRecordComponents())
            .map(RecordComponent::getName)).containsExactly("login", "group", "roles");
        assertThat(result.toString()).doesNotContain("@", "example.test", "Doe");
        assertThat(result.sources()).isEqualTo(Map.of("logins", Channel.REST,
            "groups", Channel.CLI, "roles", Channel.CLI));
    }

    // --- AS2: one user -------------------------------------------------------------------------

    @Test
    void oneUserCarriesGroupRolesAndThePersonalFieldsOfFinger() {
        directory.users = shuffledUsers(3);

        ViewResult<UserFacts> result = service.user("dev/node1", "user-001");

        UserFacts user = result.value();
        assertThat(result.node()).isEqualTo(DEV1);
        assertThat(user.login()).isEqualTo("user-001");
        assertThat(user.groups())
            .isEqualTo(new Block.Parsed<>(new UserGroups("Group-user-001", "Super-Group")));
        Finger finger = ((Block.Parsed<Finger>) user.finger()).value();
        assertThat(finger.roles()).containsExactly("Role-user-001");
        assertThat(finger.email()).contains("user-001@example.test");
        assertThat(finger.personal()).containsExactly(PersonalField.shown("Name", "Doe, user-001"));
        // the login is checked against the list, then only this login is read
        assertThat(directory.listCalls).isEqualTo(1);
        assertThat(directory.sessions).containsExactly(List.of("user-001"));
        assertThat(result.sources()).isEqualTo(Map.of("logins", Channel.REST,
            "groups", Channel.CLI, "roles", Channel.CLI, "email", Channel.CLI,
            "personal", Channel.CLI));
    }

    // --- AS3: an unknown login -----------------------------------------------------------------

    @Test
    void anUnknownLoginIsNotFoundWithTheListingAsNextStepAndStartsNoSession() {
        directory.users = shuffledUsers(3);

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service.user("dev/node1", "nobody"));

        assertThat(e).isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.error().node()).contains(DEV1);
        assertThat(e.error().message()).contains("'nobody'", "dev/node1");
        assertThat(e.error().nextStep()).contains("view users");
        assertThat(directory.sessions).isEmpty();
    }

    // --- AS4: the logins -----------------------------------------------------------------------

    @Test
    void theLoginsAreTheToolsetRowsSortedWithoutALoginTime() {
        directory.loggedIn = List.of(new LoginEntry("zoe", Optional.empty()),
            new LoginEntry("adam", Optional.empty()), new LoginEntry("mia", Optional.empty()));

        ViewResult<Page<LoginEntry>> result = service.logins("dev/node1", 0,
            OptionalInt.empty());

        Page<LoginEntry> page = result.value();
        assertThat(page.items()).extracting(LoginEntry::login)
            .containsExactly("adam", "mia", "zoe");
        assertThat(page.items()).allSatisfy(entry -> assertThat(entry.since()).isEmpty());
        // the total is the number of Toolset rows, never the --nwho count of all systems
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(page.nextOffset()).isEmpty();
        assertThat(result.sources()).isEqualTo(Map.of("loggedIn", Channel.CLI));
        assertThat(directory.listCalls).isZero();
        assertThat(directory.sessions).isEmpty();
    }

    @Test
    void theLoginsArePaged() {
        directory.loggedIn = List.of(new LoginEntry("zoe", Optional.empty()),
            new LoginEntry("adam", Optional.empty()), new LoginEntry("mia", Optional.empty()));

        Page<LoginEntry> page = service.logins("dev/node1", 1, OptionalInt.of(1)).value();

        assertThat(page.items()).extracting(LoginEntry::login).containsExactly("mia");
        assertThat(page.nextOffset()).isEqualTo(OptionalInt.of(2));
    }

    // --- AS6: the message views wait for L-17 --------------------------------------------------

    @Test
    void theActionsAreTheThreeViewsAndDeclareTheirEntries() {
        CoverageKey list = new CoverageKey(Channel.REST, "GET /user/users");
        CoverageKey user = new CoverageKey(Channel.CLI, "user");
        CoverageKey finger = new CoverageKey(Channel.CLI, "finger");
        CoverageKey who = new CoverageKey(Channel.CLI, "-who");

        assertThat(UserReadService.SPECS).extracting(ActionSpec::action)
            .containsExactly("users", "user", "logins");
        assertThat(UserReadService.SPECS).allSatisfy(spec -> {
            assertThat(spec.tool()).isEqualTo("read_users");
            assertThat(spec.capability()).isEqualTo(Capability.USER_DIRECTORY);
            assertThat(spec.area()).isEqualTo(ActionSpec.Area.READ);
            assertThat(spec.kind()).isEqualTo(ActionSpec.Kind.READ);
            assertThat(spec.parts()).isEmpty();
            assertThat(spec.entries(Map.of())).isEqualTo(spec.callEntries());
        });
        assertThat(UserReadService.USERS.callEntries()).containsExactlyInAnyOrder(list, user,
            finger);
        assertThat(UserReadService.USER.callEntries()).containsExactlyInAnyOrder(list, user,
            finger);
        assertThat(UserReadService.LOGINS.callEntries()).containsExactly(who);
        new ActionCatalog(UserReadService.SPECS);
    }

    // --- FR-013: what could not be read --------------------------------------------------------

    @Test
    void aUserWhoseGroupsCouldNotBeReadIsStillListedWithTheReason() {
        directory.users = shuffledUsers(3);
        directory.groupsUnreadable.add("user-001");

        List<UserEntry> items = service.users("dev/node1", 0, OptionalInt.empty()).value()
            .items();

        assertThat(items).extracting(UserEntry::login)
            .containsExactly("user-000", "user-001", "user-002");
        assertThat(items.get(1).group()).isEqualTo(new Block.Unavailable<>(BLOCK_UNREADABLE));
        assertThat(items.get(1).roles())
            .isEqualTo(new Block.Parsed<>(List.of("Role-user-001")));
        assertThat(items.get(0).group()).isEqualTo(new Block.Parsed<>("Group-user-000"));
    }

    @Test
    void aFailedSessionKeepsTheLoginsAndReportsEveryGroupAndRoleUnavailable() {
        directory.users = shuffledUsers(3);
        directory.sessionFailure = new ToolErrorException(SESSION_FAILED);

        ViewResult<Page<UserEntry>> result = service.users("dev/node1", 0, OptionalInt.empty());

        assertThat(result.value().items()).extracting(UserEntry::login)
            .containsExactly("user-000", "user-001", "user-002");
        assertThat(result.value().items()).allSatisfy(entry -> {
            assertThat(entry.group()).isEqualTo(new Block.Unavailable<>(SESSION_FAILED));
            assertThat(entry.roles()).isEqualTo(new Block.Unavailable<>(SESSION_FAILED));
        });
        assertThat(result.sources()).isEqualTo(Map.of("logins", Channel.REST));
    }

    @Test
    void aFailedSessionOnOneUserKeepsTheLoginFromTheList() {
        directory.users = shuffledUsers(3);
        directory.sessionFailure = new ToolErrorException(SESSION_FAILED);

        ViewResult<UserFacts> result = service.user("dev/node1", "user-002");

        assertThat(result.value()).isEqualTo(new UserFacts("user-002",
            new Block.Unavailable<>(SESSION_FAILED), new Block.Unavailable<>(SESSION_FAILED)));
        assertThat(result.sources()).isEqualTo(Map.of("logins", Channel.REST));
    }

    // --- inputs and the version gate -----------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"-1, 25", "10000, 25", "0, 0", "0, 26"})
    void anOffsetOrLimitOutsideItsRangeIsInvalidInputBeforeAnyRead(int offset, int limit) {
        assertInvalid(() -> service.users("dev/node1", offset, OptionalInt.of(limit)));
        assertInvalid(() -> service.logins("dev/node1", offset, OptionalInt.of(limit)));
        assertThat(directory.listCalls).isZero();
    }

    @Test
    void theLargestOffsetIsAccepted() {
        directory.users = shuffledUsers(3);

        assertThat(service.users("dev/node1", UserReadService.MAX_OFFSET, OptionalInt.of(25))
            .value().items()).isEmpty();
    }

    @Test
    void aStageTargetIsInvalidInput() {
        assertInvalid(() -> service.users("dev", 0, OptionalInt.empty()));
    }

    @Test
    void eachViewIsRefusedBeforeAnyReadWhileItsRowsAreNotVerified() {
        List<ThrowingCallable> calls = List.of(
            () -> service.users("test/node1", 0, OptionalInt.empty()),
            () -> service.user("test/node1", "user-000"),
            () -> service.logins("test/node1", 0, OptionalInt.empty()));
        for (ThrowingCallable call : calls) {
            ToolErrorException e = catchThrowableOfType(ToolErrorException.class, call);
            assertThat(e).isNotNull();
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(e.error().message()).contains("not yet verified");
        }
        assertThat(directory.listCalls).isZero();
        assertThat(directory.sessions).isEmpty();
        assertThat(directory.loginReads).isZero();
    }

    // --- helpers -------------------------------------------------------------------------------

    private void assertInvalid(ThrowingCallable call) {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class, call);
        assertThat(e).isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /** {@code user-000} … in an order that is not sorted (7 is coprime with every count used). */
    private static List<ListedUser> shuffledUsers(int count) {
        return IntStream.range(0, count).map(i -> (i * 7 + 1) % count)
            .mapToObj(i -> new ListedUser(login(i), "ProcessEngineUser")).toList();
    }

    private static List<String> logins(int from, int to) {
        return IntStream.range(from, to).mapToObj(UserReadServiceTest::login).toList();
    }

    private static String login(int i) {
        return "user-%03d".formatted(i);
    }

    /** A client for INUBIT {@code line} (e.g. "8.1"), version {@code line}.12. */
    private static ClientVersion client(String line) {
        return new ClientVersion(InubitVersion.parse(line + ".12").orElseThrow(),
            InubitVersion.parse(line + ".0"), InubitVersion.parse(line + ".99"));
    }

    private static CapabilityMatrix userDirectorySupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    /**
     * The user directory of one node: {@code finger} gives one role, an e-mail and a name per
     * user (planted, AS5); {@code --nwho} is never asked for.
     */
    private static final class FakeDirectory implements UserDirectoryPort {

        List<ListedUser> users = List.of();
        List<LoginEntry> loggedIn = List.of();
        final Set<String> groupsUnreadable = new HashSet<>();
        ToolErrorException sessionFailure;
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
                facts.add(new UserFacts(login, groups, new Block.Parsed<>(new Finger(login,
                    List.of("Role-" + login), Optional.of(login + "@example.test"),
                    List.of(PersonalField.shown("Name", "Doe, " + login))))));
            }
            return facts;
        }

        @Override
        public List<LoginEntry> loggedIn() {
            loginReads++;
            return loggedIn;
        }

        @Override
        public int connectedSystems() {
            throw new AssertionError("the --nwho count is not a user count and is not read");
        }
    }
}
