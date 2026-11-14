package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.PersonalField;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Block;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Finger;
import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T037 (feature 022, US2, research "Live recording T028", "Live recording T028a", L-6): the
 * StartCLI reads of {@code read_users} against the recordings of an 8.1.17 server in
 * {@code fixtures/v8_1/cli/admin-users-*}.
 *
 * <ul>
 *   <li>The recorded stdin session through {@link CliUserRunner} and
 *       {@code CliSessionParser.STANDARD}: {@code user '<login>'} and {@code finger '<login>'} for
 *       {@code jdoe}, {@code batch-user} and {@code qa_robot}. The runner never sends
 *       {@code -who}, {@code -nwho} or {@code msglist} in a session, so their blocks are cut from
 *       the recorded output and their lines from the recorded stdin; the recorded exit code 1
 *       comes with those blocks ({@code Command not found.}), so the cut session ends with 0.
 *   <li>The script-mode answers ({@code admin-users-user*}, {@code admin-users-finger*}) have the
 *       forms of the session blocks.
 *   <li>The logins come from the recorded client run {@code --who}; {@code --nwho} is not run
 *       (owner decision 2026-11-13): it prints the number of all connected systems.
 *   <li>{@code -who} and {@code -nwho} as a session command or an {@code --execCommand} argument
 *       read nothing on 8.1.
 * </ul>
 */
@Timeout(30)
class UsersCliV81ContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-users-pw";
    private static final String CUT_FROM = "CLI|jdoe@inubit-dev-1.example.test> -who\n";
    private static final String CUT_TO = "CLI|jdoe@inubit-dev-1.example.test> exit\n";
    private static final List<String> LOGINS = List.of("jdoe", "batch-user", "qa_robot");
    private static final UserGroups OPS = new UserGroups("OPS", "headoffice");
    private static final MajorMinor V8_1 = new MajorMinor(8, 1);

    @TempDir
    Path cliHome;

    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-13T08:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private CliUserRunner runner(FakeProcessLauncher launcher) {
        CliRunner cli = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliUserRunner(TestNodeConfig.node()
            .baseUrl("https://inubit-dev-1.example.test:8443").cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials, guard, cli,
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    /** The recorded session output without the blocks of {@code -who}, {@code -nwho}, msglist. */
    private static String sessionWithoutClientOptionsAndMessages() {
        String stdout = FakeProcessLauncher.fixtureText("admin-users-session.stdout");
        assertThat(stdout).contains(CUT_FROM).endsWith(CUT_TO);
        return stdout.substring(0, stdout.indexOf(CUT_FROM)) + CUT_TO;
    }

    private static CliResult scriptRun(String fixtureCase) {
        return new CliResult(
            Integer.parseInt(FakeProcessLauncher.fixtureText(fixtureCase + ".exit").strip()),
            FakeProcessLauncher.fixtureText(fixtureCase + ".stdout"),
            FakeProcessLauncher.fixtureText(fixtureCase + ".stderr"), Duration.ofSeconds(2), false,
            CliRunner.MAX_OUTPUT_BYTES);
    }

    private static <T> T parsed(Block<T> block) {
        assertThat(block).isInstanceOf(Block.Parsed.class);
        return ((Block.Parsed<T>) block).value();
    }

    // --- user and finger in one session ------------------------------------------------------

    @Test
    void theRecordedSessionGivesTheGroupsRolesAndPersonalFieldsOfEachUser() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            sessionWithoutClientOptionsAndMessages(),
            FakeProcessLauncher.fixtureText("admin-users-session.stderr"), 0);

        List<UserFacts> users = runner(launcher).read(LOGINS);

        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .as("the recorded stdin without -who, -nwho and msglist")
            .isEqualTo(FakeProcessLauncher.fixtureText("admin-users-session.stdin")
                .replace("<password>", PASSWORD).replace("-who\n", "").replace("-nwho\n", "")
                .replace("msglist\n", ""));
        assertThat(users).extracting(UserFacts::login).containsExactlyElementsOf(LOGINS);
        assertThat(users).extracting(user -> parsed(user.groups())).containsOnly(OPS);
        assertThat(users).extracting(user -> parsed(user.finger())).containsExactly(
            new Finger("jdoe", List.of("Platform Administrator"),
                Optional.of("john.doe@example.test"),
                List.of(PersonalField.shown("Name", "Doe, John"))),
            new Finger("batch-user", List.of("Platform Administrator"),
                Optional.of("batch.service@external.example.test"),
                List.of(PersonalField.shown("Name", "Service, Batch"))),
            new Finger("qa_robot", List.of("XYZ_operator"),
                Optional.of("qa.robot+qa_inubit@corp.example.test"), List.of()));
    }

    /** The script-mode answers (T028) carry the same forms as the session blocks. */
    @Test
    void theRecordedScriptAnswersHaveTheSessionForms() {
        List<List<String>> session = AdminCliFixtures.sessionBlocks("admin-users-session", 9);
        List<String> userCases = List.of("admin-users-user", "admin-users-user-hyphen",
            "admin-users-user-underscore");
        List<String> fingerCases = List.of("admin-users-finger", "admin-users-finger-hyphen",
            "admin-users-finger-no-name");

        for (int i = 0; i < LOGINS.size(); i++) {
            String login = LOGINS.get(i);
            assertThat(UserGroupsParser.parse(login, AdminCliFixtures.answer(userCases.get(i))))
                .as(login).isEqualTo(OPS)
                .isEqualTo(UserGroupsParser.parse(login, session.get(2 * i)));
            assertThat(FingerParser.parse(login, AdminCliFixtures.answer(fingerCases.get(i))))
                .as(login).isEqualTo(FingerParser.parse(login, session.get(2 * i + 1)));
        }
    }

    // --- the logins: --who, not --nwho -------------------------------------------------------

    @Test
    void theLoginsAreTheToolsetRowsOfTheRecordedWhoRun() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("admin-users-who");

        List<LoginEntry> logins = runner(launcher).logins();

        assertThat(logins).containsExactly(new LoginEntry("jdoe", Optional.empty()));
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(launcher.last().spec().startCliArguments()).contains("--who")
            .doesNotContain("--nwho", "--execCommand");
    }

    /**
     * The recorded {@code --nwho} prints 6, the rows of the {@code --who} table (databases,
     * portal, process user server and one client), while one user is logged in. So it is not
     * run: the {@code logins} page total is the number of logged-in users (row
     * {@code covered:read_users}).
     */
    @Test
    void theNwhoCountIsNotANumberOfUsersAndIsNoLongerRun() {
        List<String> table = AdminCliFixtures.answer("admin-users-who");

        assertThat(AdminCliFixtures.answer("admin-users-nwho"))
            .containsExactly(String.valueOf(table.size() - 1));
        assertThat(WhoParser.parse(table)).hasSize(1);
        assertThat(CliRunner.ClientOption.values()).containsExactly(CliRunner.ClientOption.WHO);
        assertThat(InterfaceCoverage.production().state(Channel.CLI, "-nwho", V8_1))
            .contains(new CoverageState.Covered("read_users"));
    }

    // --- -who and -nwho are no session commands and no --execCommand arguments ---------------

    @Test
    void inTheRecordedSessionWhoAndNwhoAreUnknownCommands() {
        List<List<String>> session = AdminCliFixtures.sessionBlocks("admin-users-session", 9);

        assertThat(session.get(6)).containsExactly("Command not found.");
        assertThat(session.get(7)).containsExactly("Command not found.");
        for (String command : List.of("-who", "-nwho")) {
            assertThatThrownBy(() -> CliSessionCommand.command(command))
                .as("never sent in a session").isInstanceOfSatisfying(ToolErrorException.class,
                    e -> assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin-users-who-missing-argument",
        "admin-users-nwho-missing-argument"})
    void asAnExecCommandArgumentTheClientTakesThemAsItsOwnOption(String fixtureCase) {
        CliResult result = scriptRun(fixtureCase);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("EXECUTION ERROR",
            "Missing argument for option: execCommand");
        assertThat(CliOutputClassifier.isLoginFailure(result)).isFalse();
        assertThat(CliOutputClassifier.isMissingCliRight(result)).isFalse();
        assertThat(new CliOutputClassifier(new SecretScrubber()).classify(result))
            .isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> assertThat(
                failure.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    // --- coverage --------------------------------------------------------------------------

    /** 9.0: {@code UsersV90ContractTest} (T037a). */
    @Test
    void theCliRowsAreReachableByTheirViewsOn81() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        assertThat(coverage.state(Channel.CLI, "user", V8_1))
            .contains(new CoverageState.Reachable("read_users", "users"));
        assertThat(coverage.state(Channel.CLI, "finger", V8_1))
            .contains(new CoverageState.Reachable("read_users", "users"));
        assertThat(coverage.state(Channel.CLI, "-who", V8_1))
            .contains(new CoverageState.Reachable("read_users", "logins"));
        assertThat(coverage.state(Channel.CLI, "msglist", V8_1)).as("waits for L-17")
            .contains(new CoverageState.Planned());
        assertThat(coverage.state(Channel.CLI, "msgread", V8_1)).as("waits for L-17")
            .contains(new CoverageState.Planned());
    }
}
