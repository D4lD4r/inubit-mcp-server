package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserDetail.PersonalField;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T032 (feature 022, US2, research D-7, D-20): the groups and roles of a page of users come from
 * one stdin session with {@code user '<login>'} and {@code finger '<login>'} per login; a block
 * that cannot be read costs only that user's groups or roles. The logins come from client runs of
 * their own, {@code --who} and {@code --nwho} (research "Live recording T028a"), with only the
 * password on stdin. The session is scripted in the recorded form (prompt with the echoed
 * command, {@code admin-users-session}); the client runs replay {@code admin-users-who} and
 * {@code admin-users-nwho}.
 */
@Timeout(30)
class CliUserRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-users-pw";
    private static final String PROMPT = "CLI|jdoe@inubit-dev-1.example.test> ";
    private static final String CLI_URL =
        "https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet";
    /** A full page: 25 logins with hyphen and underscore, as in the recorded session. */
    private static final List<String> PAGE = IntStream.rangeClosed(1, 25)
        .mapToObj(i -> String.format(i % 2 == 0 ? "tester-%02d" : "qa_tester_%02d", i)).toList();

    @TempDir
    Path cliHome;

    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-13T08:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private TestNodeConfig node() {
        return TestNodeConfig.node().baseUrl("https://inubit-dev-1.example.test:8443")
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"));
    }

    private CliUserRunner runner(FakeProcessLauncher launcher) {
        return runner(node().build(), launcher);
    }

    private CliUserRunner runner(EffectiveNodeConfig server, FakeProcessLauncher launcher) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliUserRunner(server, credentials, guard, cliRunner(launcher),
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false));
    }

    private static CliRunner cliRunner(FakeProcessLauncher launcher) {
        return TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
    }

    // --- the scripted session -----------------------------------------------------------------

    /** The answer of {@code user '<login>'} in the recorded form (labels padded to 13). */
    private static List<String> userBlock(String login) {
        return List.of(String.format("%-13s%s ", "User name", login),
            String.format("%-13s%s ", "User group", "OPS"),
            String.format("%-13s%s ", "Super group", "headoffice"), "");
    }

    /** The answer of {@code finger '<login>'} in the recorded form, without a name. */
    private static List<String> fingerBlock(String login) {
        return List.of(String.format("Login: %-15s", login), "Email: " + login + "@example.test",
            "Role: Operator");
    }

    /** The usual answer of each command: {@link #userBlock} or {@link #fingerBlock}. */
    private static List<String> recordedForm(String command) {
        String login = command.substring(command.indexOf('\'') + 1, command.length() - 1);
        return command.startsWith("user ") ? userBlock(login) : fingerBlock(login);
    }

    /**
     * A session that answers what was sent on stdin: per command line the prompt with the echoed
     * command and {@code answers}' block in CRLF lines, then the echoed exit; exit code 0.
     */
    private static FakeProcessLauncher scripted(Function<String, List<String>> answers) {
        return scripted(answers, 0);
    }

    private static FakeProcessLauncher scripted(Function<String, List<String>> answers,
        int exitCode) {
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            List<String> lines = new String(stdin, StandardCharsets.UTF_8).lines().toList();
            StringBuilder out = new StringBuilder("JAVA_HOME is set\nPassword: \n");
            for (String command : lines.subList(1, lines.size())) {
                out.append(PROMPT).append(command).append('\n');
                if (!command.equals("exit")) {
                    answers.apply(command).forEach(line -> out.append(line).append("\r\n"));
                }
            }
            return new Answer(out.toString(), exitCode);
        });
    }

    private static String stdin(FakeProcessLauncher launcher) {
        return new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8);
    }

    private static ToolError errorOf(ThrowingCallable call) {
        ToolError[] error = new ToolError[1];
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolErrorException.class,
            e -> error[0] = e.error());
        return error[0];
    }

    private static <T> T parsed(Block<T> block) {
        assertThat(block).isInstanceOf(Block.Parsed.class);
        return ((Block.Parsed<T>) block).value();
    }

    private static ToolError unavailable(Block<?> block) {
        assertThat(block).isInstanceOf(Block.Unavailable.class);
        return ((Block.Unavailable<?>) block).error();
    }

    // --- one session per page -----------------------------------------------------------------

    @Test
    void aPageOf25UsersIsReadInOneSessionWithQuotedLogins() {
        FakeProcessLauncher launcher = scripted(CliUserRunnerTest::recordedForm);

        List<UserFacts> users = runner(launcher).read(PAGE);

        assertThat(launcher.launchCount()).isEqualTo(1);
        StringBuilder expected = new StringBuilder(PASSWORD + "\n");
        PAGE.forEach(login -> expected.append("user '").append(login).append("'\n")
            .append("finger '").append(login).append("'\n"));
        assertThat(stdin(launcher)).isEqualTo(expected.append("exit\n").toString());
        assertThat(users).extracting(UserFacts::login).containsExactlyElementsOf(PAGE);
        assertThat(users).allSatisfy(user -> {
            assertThat(parsed(user.groups())).isEqualTo(new UserGroups("OPS", "headoffice"));
            Finger finger = parsed(user.finger());
            assertThat(finger.login()).isEqualTo(user.login());
            assertThat(finger.roles()).containsExactly("Operator");
        });
        assertThat(launcher.last().spec().startCliArguments())
            .containsExactly("-u", USER, CLI_URL)
            .noneMatch(argument -> argument.contains(PASSWORD));
    }

    @Test
    void theRecordedAnswersAreRead() {
        List<List<String>> recorded = AdminCliFixtures.sessionBlocks("admin-users-session", 9);
        List<String> logins = List.of("jdoe", "batch-user", "qa_robot");
        FakeProcessLauncher launcher = scripted(command -> recorded.get(
            2 * logins.indexOf(command.substring(command.indexOf('\'') + 1,
                command.length() - 1)) + (command.startsWith("user ") ? 0 : 1)));

        List<UserFacts> users = runner(launcher).read(logins);

        assertThat(users).extracting(user -> parsed(user.groups()))
            .containsOnly(new UserGroups("OPS", "headoffice"));
        assertThat(users).extracting(user -> parsed(user.finger()).roles())
            .containsExactly(List.of("Platform Administrator"), List.of("Platform Administrator"),
                List.of("XYZ_operator"));
        Finger jdoe = parsed(users.getFirst().finger());
        assertThat(jdoe.email()).contains("john.doe@example.test");
        assertThat(jdoe.personal()).containsExactly(PersonalField.shown("Name", "Doe, John"));
        assertThat(parsed(users.get(2).finger()).personal()).isEmpty();
    }

    @Test
    void noLoginStartsNothingAndMoreThan25AreRefused() {
        FakeProcessLauncher launcher = scripted(CliUserRunnerTest::recordedForm);

        assertThat(runner(launcher).read(List.of())).isEmpty();
        List<String> tooMany = IntStream.rangeClosed(1, CliUserRunner.MAX_LOGINS + 1)
            .mapToObj(i -> "tester-" + i).toList();
        assertThatThrownBy(() -> runner(launcher).read(tooMany))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(launcher.launchCount()).isZero();
    }

    // --- a block that cannot be read costs only its user ----------------------------------------

    /** FR-013: an empty {@code user} block costs only that user's groups. */
    @Test
    void aMissingBlockMakesOnlyThatUsersGroupsUnavailable() {
        String missing = PAGE.get(6);
        FakeProcessLauncher launcher = scripted(command -> command.equals("user '" + missing + "'")
            ? List.of() : recordedForm(command));

        List<UserFacts> users = runner(launcher).read(PAGE);

        UserFacts user = users.get(6);
        ToolError error = unavailable(user.groups());
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("user <login>");
        assertThat(parsed(user.finger()).roles()).containsExactly("Operator");
        assertThat(users).filteredOn(other -> other != user).allSatisfy(other -> {
            parsed(other.groups());
            parsed(other.finger());
        });
    }

    /** FR-013: an unknown {@code finger} answer costs only that user's roles. */
    @Test
    void anUnknownFingerAnswerMakesOnlyThatUsersRolesUnavailable() {
        String unknown = PAGE.get(24);
        FakeProcessLauncher launcher = scripted(command ->
            command.equals("finger '" + unknown + "'")
                ? List.of("Login: " + unknown, "Phone: 555-0100") : recordedForm(command));

        List<UserFacts> users = runner(launcher).read(PAGE);

        ToolError error = unavailable(users.get(24).finger());
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.excerpt()).hasValueSatisfying(excerpt ->
            assertThat(excerpt).doesNotContain("555-0100").doesNotContain(unknown));
        parsed(users.get(24).groups());
        assertThat(users.subList(0, 24)).allSatisfy(other -> parsed(other.finger()));
    }

    /**
     * L-1 (recorded, T014): a refused command answers {@code EXECUTION ERROR}, the session goes on
     * and exits with 1; only that user's block is unavailable.
     */
    @Test
    void aRefusedCommandCostsOnlyThatUser() {
        String refused = PAGE.get(3);
        FakeProcessLauncher launcher = scripted(command -> command.equals("user '" + refused + "'")
            ? List.of("EXECUTION ERROR", "Something was refused.") : recordedForm(command), 1);

        List<UserFacts> users = runner(launcher).read(PAGE);

        assertThat(unavailable(users.get(3).groups()).code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(users).filteredOn(user -> !user.login().equals(refused))
            .allSatisfy(user -> parsed(user.groups()));
    }

    /** An exit code other than 0 without a refused block fails the whole session. */
    @Test
    void aFailedExitWithoutARefusedBlockFailsTheSession() {
        FakeProcessLauncher launcher = scripted(CliUserRunnerTest::recordedForm, 1);

        ToolError error = errorOf(() -> runner(launcher).read(PAGE));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
        assertNoPersonalValue(error);
    }

    /** Cut output fails the whole session, without the page's blocks in the error (FR-011). */
    @Test
    void cutOutputFailsTheSessionWithoutThePagesValues() {
        String filler = "x".repeat(1_000) + "\r\n";
        FakeProcessLauncher launcher = scripted(command -> {
            if (!command.equals("finger '" + PAGE.get(1) + "'")) {
                return recordedForm(command);
            }
            List<String> lines = new ArrayList<>(recordedForm(command));
            lines.add(filler.repeat(CliRunner.MAX_OUTPUT_BYTES / filler.length() + 1));
            return lines;
        });

        ToolError error = errorOf(() -> runner(launcher).read(PAGE));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("was cut");
        assertThat(error.node()).contains(DEV);
        assertNoPersonalValue(error);
    }

    /** No e-mail and no login of {@link #PAGE} in any text of {@code error}. */
    private static void assertNoPersonalValue(ToolError error) {
        String texts = String.join("\n", error.message(), error.likelyCause(), error.nextStep(),
            error.excerpt().orElse(""));
        assertThat(texts).doesNotContain("@example.test");
        assertThat(PAGE).allSatisfy(login -> assertThat(texts).doesNotContain(login));
    }

    /** Without one prompt per command the blocks cannot be told apart: the session fails. */
    @Test
    void aMissingPromptFailsTheSession() {
        String stdout = "Password: \n" + PROMPT + "user 'jdoe'\n"
            + String.join("\n", userBlock("jdoe")) + String.join("\n", fingerBlock("jdoe"))
            + "\n" + PROMPT + "exit\n";
        FakeProcessLauncher launcher = FakeProcessLauncher.of(stdout, "", 0);

        ToolError error = errorOf(() -> runner(launcher).read(List.of("jdoe")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
    }

    /** A login outside the value rule never reaches StartCLI; only that user is unavailable. */
    @Test
    void aLoginOutsideThePatternNeverReachesStartCli() {
        FakeProcessLauncher launcher = scripted(CliUserRunnerTest::recordedForm);

        List<UserFacts> users = runner(launcher).read(List.of("jdoe", "x'; passwd", "-who"));

        assertThat(stdin(launcher)).isEqualTo(PASSWORD + "\nuser 'jdoe'\nfinger 'jdoe'\nexit\n");
        parsed(users.getFirst().groups());
        assertThat(users.subList(1, 3)).allSatisfy(user -> {
            assertThat(unavailable(user.groups()).code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(unavailable(user.finger()).code()).isEqualTo(ErrorCode.INVALID_INPUT);
        });

        FakeProcessLauncher none = scripted(CliUserRunnerTest::recordedForm);
        assertThat(runner(none).read(List.of("a;b"))).singleElement()
            .satisfies(user -> unavailable(user.groups()));
        assertThat(none.launchCount()).isZero();
    }

    /** The recorded 8.1 answer to wrong credentials pauses the node: nothing else starts. */
    @Test
    void wrongCredentialsFailTheSessionAndPauseTheNode() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("login_failed");

        ToolError error = errorOf(() -> runner(launcher).read(PAGE));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(DEV);
        FakeProcessLauncher next = FakeProcessLauncher.replaying("admin-users-who");
        assertThat(errorOf(() -> runner(next).logins()).code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(next.launchCount()).isZero();
    }

    @Test
    void theSessionIsBoundedByCliTimeout() {
        FakeProcessLauncher launcher = scripted(CliUserRunnerTest::recordedForm).hanging();

        ToolError error = errorOf(() -> runner(node().cliTimeout(Duration.ofMillis(300)).build(),
            launcher).read(List.of("jdoe")));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliTimeout");
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void missingCredentialsLaunchNothing() {
        FakeProcessLauncher launcher = scripted(CliUserRunnerTest::recordedForm);
        CliUserRunner runner = new CliUserRunner(node().build(),
            new NodeCredentials(DEV, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()),
            guard, cliRunner(launcher), new CliOutputClassifier(new SecretScrubber()));

        assertThat(errorOf(() -> runner.read(List.of("jdoe"))).code())
            .isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(errorOf(runner::logins).code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).isZero();
    }

    // --- the logins: client runs of their own --------------------------------------------------

    /** T028a: {@code --who} is a client option with only the password on stdin. */
    @Test
    void theLoginsComeFromAWhoRunOfTheirOwn() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("admin-users-who");
        Path trustStore = Path.of("/Users/jdoe/.config/inubit-mcp/dev-truststore.p12");

        List<LoginEntry> logins = runner(node().trustStore(trustStore)
            .disableHostnameVerification("AB".repeat(32)).build(), launcher).logins();

        assertThat(logins).containsExactly(new LoginEntry("jdoe", Optional.empty()));
        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(stdin(launcher)).isEqualTo(PASSWORD + "\n");
        assertThat(launcher.last().spec().startCliArguments()).containsExactly("-u", USER,
            "--trustStoreFilePath", trustStore.toString(), "--disableHostNameVerification",
            "--who", CLI_URL);
    }

    /** T028a: {@code --nwho} counts the rows of {@code --who}, systems included. */
    @Test
    void theRowCountComesFromAnNwhoRunOfItsOwn() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("admin-users-nwho");

        assertThat(runner(launcher).connectedSystems()).isEqualTo(6);
        assertThat(stdin(launcher)).isEqualTo(PASSWORD + "\n");
        assertThat(launcher.last().spec().startCliArguments())
            .containsExactly("-u", USER, "--nwho", CLI_URL)
            .doesNotContain("--execCommand");
    }

    @Test
    void anUnknownWhoTableFailsWithTheNode() {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            "JAVA_HOME is set\nPassword: \nSomething else\n", "", 0);

        ToolError error = errorOf(() -> runner(launcher).logins());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
    }

    /** As recorded for {@code --execCommand -who} (T028): a failed client run fails the read. */
    @Test
    void aFailedClientRunFailsWithTheNode() {
        FakeProcessLauncher launcher =
            FakeProcessLauncher.replaying("admin-users-who-missing-argument");

        ToolError error = errorOf(() -> runner(launcher).connectedSystems());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void theClientRunsAreBoundedByCliTimeout() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("admin-users-who").hanging();

        ToolError error = errorOf(() -> runner(node().cliTimeout(Duration.ofMillis(300)).build(),
            launcher).logins());

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.node()).contains(DEV);
    }
}
