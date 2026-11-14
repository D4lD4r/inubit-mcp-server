package de.dadecker.inubit.mcp.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliUserRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.FingerParser;
import de.dadecker.inubit.mcp.adapter.cli.admin.UserGroupsParser;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81UserAdapter;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageEntry;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.PersonalField;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Block;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Finger;
import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.V90Cases;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T037a (feature 022, US2, research "Live recording T024/T028b"): {@code read_users} against the
 * recordings of a 9.0.10 server in {@code fixtures/v9_0/{rest,cli}/admin-users*}, opened only
 * through {@link V90Cases}. 9.0 uses the 8.1 adapters ({@link V81UserAdapter},
 * {@link CliUserRunner}); these tests pin the recorded 9.0 differences they meet.
 *
 * <ul>
 *   <li>REST: {@code GET /user/users} answers with an XML declaration, the root
 *       {@code ns2:UserList} and one {@code ns2:User} per line; the parser matches local names.
 *       The stub answers like 9.0: a request without the adapter's {@code Accept} gets HTTP 406.
 *   <li>The stdin session: the 9.0 preamble (Netty {@code ERROR} line, {@code Connecting as …
 *       ?isCli}), LF line ends in the session blocks, exit code 0, and for {@code jdoe} a fourth
 *       {@code user} row {@code Additional user group}. The runner never sends {@code msglist}, so
 *       its block is cut from the recorded output and its line from the recorded stdin.
 *   <li>Script mode: the same preamble without {@code Connecting as}; the answers have the forms
 *       of the session blocks.
 *   <li>{@code --who} lists no {@code Toolset} row, not even the run's own connection, so the
 *       logins cannot be read on 9.0: the row {@code -who} is excluded there and the view
 *       {@code logins} is refused with that reason (owner decision 2026-11-14;
 *       {@code UserReadServiceTest}). {@code --nwho} again counts the rows of the table.
 * </ul>
 */
@Timeout(30)
class UsersV90ContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-users-v90-pw";
    private static final String PROMPT = "CLI|jdoe@inubit-dev-2.example.test> ";
    private static final String CUT_FROM = PROMPT + "msglist\n";
    private static final String CUT_TO = PROMPT + "exit\n";
    private static final String WARNING = "SECURITY WARNING: Hostname verification is disabled."
        + " Your connection might not be secured!";
    private static final List<String> LOGINS = List.of("jdoe", "Ops_viewer", "qa_robot");
    private static final UserGroups OPS = new UserGroups("OPS", "headoffice");
    private static final UserGroups OPS_WITH_PORTAL = new UserGroups("OPS", "headoffice",
        List.of("ProcessPortal"));
    private static final String NOT_ACCEPTABLE = "{\"statusCode\":406,\"message\":\"Not"
        + " Acceptable\",\"path\":\"/ibis/rest\",\"timestamp\":\"2026-11-13T14:00:00.000+00:00\"}";

    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;

    private final List<InubitHttpClient> clients = new ArrayList<>();
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-13T13:00:00Z")));

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void serveTheRecordings() throws IOException {
        wireMock.resetAll();
        // INUBIT 9.0 answers a request without the matching Accept with 406 (feature 017, D-11)
        wireMock.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(406)
            .withHeader("Content-Type", "application/json").withBody(NOT_ACCEPTABLE)));
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/user/users")).atPriority(1)
            .withHeader("Accept", equalTo(MediaRange.XML.header()))
            .willReturn(V90Cases.response("admin-users")));
        FakeStartCli.install(cliHome);
    }

    @AfterEach
    void closeClients() {
        clients.forEach(InubitHttpClient::close);
    }

    private List<ListedUser> listUsers() {
        SecretScrubber scrubber = new SecretScrubber();
        InubitHttpClient client = new InubitHttpClient(TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(5))
            .build(),
            Optional.of(new InubitHttpClient.Credentials(USER,
                scrubber.register("users-v90-contract-pw"))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        clients.add(client);
        return new V81UserAdapter(DEV, client, logins -> {
            throw new AssertionError("no StartCLI session expected");
        }, () -> {
            throw new AssertionError("no StartCLI run expected");
        }).listUsers();
    }

    private CliUserRunner runner(FakeProcessLauncher launcher) {
        CliRunner cli = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliUserRunner(TestNodeConfig.node()
            .baseUrl("https://inubit-dev-2.example.test:8443").cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-21")).build(), credentials, guard, cli,
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    /** The recorded session output without the block of {@code msglist}. */
    private static String sessionWithoutMessages() {
        String stdout = V90Cases.text("admin-users-session", "stdout");
        assertThat(stdout).contains(CUT_FROM).endsWith(CUT_TO);
        return stdout.substring(0, stdout.indexOf(CUT_FROM)) + CUT_TO;
    }

    /**
     * The answer of a recorded script-mode or client-option run (without blank lines), after the
     * recorded 9.0 preamble: {@code JAVA_HOME is set}, the warning, the client's Netty
     * {@code ERROR} line, the warning, {@code Password: }, the warning.
     */
    private static List<String> answer(String fixtureCase) {
        List<String> lines = V90Cases.text(fixtureCase, "stdout").lines()
            .filter(line -> !line.isBlank()).toList();
        assertThat(lines.subList(0, 6)).as("the preamble of " + fixtureCase).satisfiesExactly(
            line -> assertThat(line).isEqualTo("JAVA_HOME is set"),
            line -> assertThat(line).isEqualTo(WARNING),
            line -> assertThat(line).startsWith("ERROR ")
                .contains("DnsServerAddressStreamProviders"),
            line -> assertThat(line).isEqualTo(WARNING),
            line -> assertThat(line).isEqualTo("Password: "),
            line -> assertThat(line).isEqualTo(WARNING));
        return lines.subList(6, lines.size());
    }

    private static <T> T parsed(Block<T> block) {
        assertThat(block).isInstanceOf(Block.Parsed.class);
        return ((Block.Parsed<T>) block).value();
    }

    // --- GET /user/users -----------------------------------------------------------------------

    @Test
    void theRecordedListGivesEveryLoginWithItsTypeInTheOrderOfTheAnswer() {
        assertThat(V90Cases.text("admin-users", "http")).contains("application/xml");
        assertThat(V90Cases.text("admin-users", "xml")).startsWith("<?xml ")
            .contains("<ns2:UserList ", "<ns2:User id=");

        List<ListedUser> users = listUsers();

        assertThat(users).hasSize(55).allSatisfy(user ->
            assertThat(user.type()).isEqualTo("ProcessEngineUser"));
        assertThat(users).extracting(ListedUser::login)
            .startsWith("User01", "User02", "User03", "Ops_viewer")
            .endsWith("qa_robot", "User57", "User58").contains("jdoe", "user42")
            .doesNotHaveDuplicates();
        assertThat(users.get(16)).isEqualTo(new ListedUser("jdoe", "ProcessEngineUser"));
    }

    /** FR-011: the e-mails and names of the answer are never read into the list. */
    @Test
    void noEmailOrNameOfTheRecordingIsInTheList() {
        List<ListedUser> users = listUsers();

        for (String text : List.of(users.toString(),
            ResultJson.mapper().writeValueAsString(users))) {
            assertThat(text).doesNotContain("@", "example.test", "First", "Last", "Färst",
                "Läst", "John", "Doe");
        }
    }

    // --- user and finger in one session --------------------------------------------------------

    @Test
    void theRecordedSessionGivesTheGroupsRolesAndPersonalFieldsOfEachUser() {
        CliResult recorded = V90Cases.cliResult("admin-users-session");
        assertThat(recorded.exitCode()).isZero();
        FakeProcessLauncher launcher = FakeProcessLauncher.of(sessionWithoutMessages(),
            recorded.stderr(), recorded.exitCode());

        List<UserFacts> users = runner(launcher).read(LOGINS);

        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .as("the recorded stdin without msglist")
            .isEqualTo(V90Cases.text("admin-users-session", "stdin")
                .replace("<password>", PASSWORD).replace("msglist\n", ""));
        assertThat(users).extracting(UserFacts::login).containsExactlyElementsOf(LOGINS);
        assertThat(users).extracting(user -> parsed(user.groups()))
            .containsExactly(OPS_WITH_PORTAL, OPS, OPS);
        assertThat(users).extracting(user -> parsed(user.finger())).containsExactly(
            new Finger("jdoe", List.of("Platform Administrator"),
                Optional.of("john.doe@example.test"),
                List.of(PersonalField.shown("Name", "Doe, John"))),
            new Finger("Ops_viewer", List.of("XYZ_maintainer"),
                Optional.of("ops.viewer@corp.example.test"), List.of()),
            new Finger("qa_robot", List.of("XYZ_operator"),
                Optional.of("qa.robot+qa_inubit@corp.example.test"), List.of()));
    }

    // --- script mode ---------------------------------------------------------------------------

    /** The script-mode answers carry the same forms as the session blocks. */
    @Test
    void theRecordedScriptAnswersHaveTheSessionForms() {
        assertThat(UserGroupsParser.parse("jdoe", answer("admin-users-user")))
            .isEqualTo(OPS_WITH_PORTAL);
        assertThat(UserGroupsParser.parse("Ops_viewer", answer("admin-users-user-capitalized")))
            .isEqualTo(OPS);
        assertThat(UserGroupsParser.parse("qa_robot", answer("admin-users-user-underscore")))
            .isEqualTo(OPS);
        assertThat(FingerParser.parse("jdoe", answer("admin-users-finger")))
            .isEqualTo(new Finger("jdoe", List.of("Platform Administrator"),
                Optional.of("john.doe@example.test"),
                List.of(PersonalField.shown("Name", "Doe, John"))));
        assertThat(FingerParser.parse("Ops_viewer", answer("admin-users-finger-capitalized"))
            .roles()).containsExactly("XYZ_maintainer");
        assertThat(FingerParser.parse("qa_robot", answer("admin-users-finger-no-name"))
            .personal()).isEmpty();
    }

    // --- the logins: --who and --nwho ----------------------------------------------------------

    /**
     * The recorded {@code --who} table has the five system rows only; no row is of the type
     * {@code Toolset}, so no login can be read from it on 9.0.
     */
    @Test
    void theRecordedWhoTableHasNoClientConnectionSoItsRowIsExcludedOn90() {
        assertThat(V90Cases.cliResult("admin-users-who").exitCode()).isZero();
        List<String> table = answer("admin-users-who");

        assertThat(table.get(0)).startsWith("SYSTEM-ID ")
            .contains("SYSTEM TYPE", "STATE", "VERSION", "IDLE");
        assertThat(table.subList(1, table.size())).hasSize(5)
            .noneMatch(row -> row.contains("Toolset"));
        assertThat(InterfaceCoverage.production().state(Channel.CLI, "-who", V9_0))
            .hasValueSatisfying(state -> assertThat(state).isInstanceOfSatisfying(
                CoverageState.Excluded.class, excluded -> assertThat(excluded.reason())
                    .contains("no Toolset row")));
    }

    /** As on 8.1, {@code --nwho} prints the number of rows {@code --who} lists. */
    @Test
    void theRecordedNwhoCountsTheRowsOfTheWhoTableAndIsCoveredOn90() {
        assertThat(V90Cases.cliResult("admin-users-nwho").exitCode()).isZero();

        assertThat(answer("admin-users-nwho"))
            .containsExactly(String.valueOf(answer("admin-users-who").size() - 1));
        assertThat(InterfaceCoverage.production().state(Channel.CLI, "-nwho", V9_0))
            .contains(new CoverageState.Covered("read_users"));
    }

    // --- coverage list -------------------------------------------------------------------------

    @Test
    void theRecordedUserReadsAreReachableByTheUsersViewsOn90() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        assertThat(coverage.state(Channel.REST, "GET /user/users", V9_0))
            .contains(new CoverageState.Reachable("read_users", "users"));
        for (String entry : List.of("user", "finger")) {
            assertThat(coverage.state(Channel.CLI, entry, V9_0)).as(entry)
                .contains(new CoverageState.Reachable("read_users", "users"));
        }
    }

    /** L-17: no message was recorded on 9.0 either, so the message rows wait for it alone. */
    @ParameterizedTest
    @ValueSource(strings = {"msglist", "msgread"})
    void theMessageRowsStayPlannedAndWaitOnlyForL17(String entry) {
        CoverageEntry row = InterfaceCoverage.production().entries().stream()
            .filter(candidate -> candidate.channel() == Channel.CLI
                && candidate.entry().equals(entry))
            .findFirst().orElseThrow();

        assertThat(row.states()).containsOnly(
            Map.entry(new MajorMinor(8, 1), new CoverageState.Planned()),
            Map.entry(V9_0, new CoverageState.Planned()));
        assertThat(row.waits()).containsExactly("L-17");
    }
}
