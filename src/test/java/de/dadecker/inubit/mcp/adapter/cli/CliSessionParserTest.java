package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T012, T025 (feature 022, research D-7, L-1): a StartCLI stdin session split into one block per
 * command by the prompt {@code CLI|<user>@<host>>}, after which the 8.1 client echoes the command
 * (recorded in {@code fixtures/v8_1/cli/admin-settings-session}).
 */
class CliSessionParserTest {

    private static final List<CliSessionCommand> TWO = List.of(CliSessionCommand.UNAME,
        CliSessionCommand.UPTIME);

    private static final String STDOUT = """
        Password:\s
        CLI|jdoe@inubit-dev-1.example.test> uname
        INUBIT 9.0.10
        Build 4711
        CLI|jdoe@inubit-dev-1.example.test> uptime
        Process Engine is alive (up 2 days, 03:14:07)
        CLI|jdoe@inubit-dev-1.example.test> exit
        """;

    private static void assertUnexpected(String stdout) {
        assertThatThrownBy(() -> CliSessionParser.STANDARD.blocks(stdout, TWO))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    @Test
    void eachCommandGetsTheLinesUpToTheNextPromptWithoutThePromptAndItsEcho() {
        assertThat(CliSessionParser.STANDARD.blocks(STDOUT, TWO)).containsExactly(
            List.of("INUBIT 9.0.10", "Build 4711"),
            List.of("Process Engine is alive (up 2 days, 03:14:07)"));
    }

    /**
     * The 8.1 session as recorded (T014, L-1): the client's lines before the first prompt, one
     * echoed prompt per command with CRLF answers, refused commands that do not end the session,
     * and the echoed {@code exit} as the last line.
     */
    @Test
    void theRecorded81SessionIsSplitByTheEchoedPrompts() {
        Map<String, Set<String>> recorded = Map.of(
            "threads", Set.of("showNumberOfWorkflowThreads"),
            "privilegedWorkflows", Set.of("showThreadsPercentage", "showWorkflowList"),
            "dbconnectiontolerance", Set.of("show"), "date", Set.of(),
            "license", Set.of("-td", "-ts"));
        List<CliSessionCommand> commands = List.of(
            CliSessionCommand.command("threads", recorded).flag("showNumberOfWorkflowThreads")
                .build(),
            CliSessionCommand.command("privilegedWorkflows", recorded)
                .flag("showThreadsPercentage").build(),
            CliSessionCommand.command("privilegedWorkflows", recorded).flag("showWorkflowList")
                .build(),
            CliSessionCommand.command("dbconnectiontolerance", recorded).flag("show").build(),
            CliSessionCommand.command("date", recorded).build(),
            CliSessionCommand.command("license", recorded).flag("-td").build(),
            CliSessionCommand.command("license", recorded).flag("-ts").build());
        String stdout = FakeProcessLauncher.fixtureText("admin-settings-session.stdout");
        List<String> refused = List.of("EXECUTION ERROR", "Only root user can use this command.");

        assertThat(String.join("\n", commands.stream().map(CliSessionCommand::text).toList())
            + "\nexit\n").as("the recorded stdin").isEqualTo(FakeProcessLauncher
                .fixtureText("admin-settings-session.stdin").replaceFirst("<password>\n", ""));
        assertThat(CliSessionParser.STANDARD.blocks(stdout, commands)).containsExactly(
            refused, refused, refused,
            List.of("Error level: \t\t0%", "Fault threshold: \t20", "Drop rate: \t\t5%",
                "Drop interval (s): \t1800 seconds"),
            List.of("Fri Nov 13 05:48:53 CET 2026"),
            List.of("7300"),
            List.of("630700000"));
    }

    /** A masked echo of a command with a secret (CliRunnerSessionTest) is dropped all the same. */
    @Test
    void theEchoIsDroppedWhateverItReads() {
        assertThat(CliSessionParser.STANDARD.blocks(STDOUT.replace("> uname", "> uname '****'"),
            TWO)).isEqualTo(CliSessionParser.STANDARD.blocks(STDOUT, TWO));
    }

    @Test
    void aParserWithoutEchoKeepsTheRestOfThePromptLine() {
        CliSessionParser noEcho = new CliSessionParser(Pattern.compile("cli> "), false,
            "a test format");

        assertThat(noEcho.blocks("Password: \ncli> INUBIT 6.1\ncli> up\ncli> \n", TWO))
            .containsExactly(List.of("INUBIT 6.1"), List.of("up"));
    }

    @Test
    void anyUserAndHostMatchThePrompt() {
        String stdout = STDOUT.replace("jdoe@inubit-dev-1.example.test", "root@localhost");

        assertThat(CliSessionParser.STANDARD.blocks(stdout, TWO))
            .isEqualTo(CliSessionParser.STANDARD.blocks(STDOUT, TWO));
    }

    @Test
    void blankLinesAndCarriageReturnsAreDropped() {
        assertThat(CliSessionParser.STANDARD.blocks(STDOUT.replace("\n", "\r\n\r\n"), TWO))
            .isEqualTo(CliSessionParser.STANDARD.blocks(STDOUT, TWO));
    }

    @Test
    void aMissingPromptIsUnexpected() {
        assertUnexpected(STDOUT.substring(STDOUT.indexOf("Build 4711\n")
            + "Build 4711\n".length()));
    }

    @Test
    void outputWithoutAnyPromptIsUnexpected() {
        assertUnexpected("Password: \nThe user does not exist or password does not match.\n");
        assertUnexpected("");
    }

    @Test
    void aPromptInsideALineIsNoPrompt() {
        assertUnexpected(STDOUT.replace("CLI|jdoe@inubit-dev-1.example.test> uptime",
            "x CLI|jdoe@inubit-dev-1.example.test> uptime"));
    }

    @Test
    void the61PromptIsNoPromptOfTheStandardForm() {
        assertUnexpected(STDOUT.replace("CLI|jdoe@inubit-dev-1.example.test> ", "cli> "));
    }

    @Test
    void outputAfterExitIsUnexpected() {
        assertUnexpected(STDOUT + "Bye\n");
    }

    @Test
    void theUnexpectedResponseNamesTheFormat() {
        assertThatThrownBy(() -> CliSessionParser.STANDARD.blocks(STDOUT + "Bye\n", TWO))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().likelyCause()).contains("CLI|<user>@<host>>"));
    }
}
