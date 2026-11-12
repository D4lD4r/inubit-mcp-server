package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T012 (feature 022, research D-7): a StartCLI stdin session split into one block per command by
 * the documented prompt {@code CLI|<user>@<host>>} (reconstructed until L-1 records it).
 */
class CliSessionParserTest {

    private static final List<CliSessionCommand> TWO = List.of(CliSessionCommand.UNAME,
        CliSessionCommand.UPTIME);

    private static final String STDOUT = """
        Password:\s
        CLI|jdoe@inubit-dev-1.example.test> INUBIT 9.0.10
        Build 4711
        CLI|jdoe@inubit-dev-1.example.test> Process Engine is alive (up 2 days, 03:14:07)
        CLI|jdoe@inubit-dev-1.example.test>\s
        """;

    private static void assertUnexpected(String stdout) {
        assertThatThrownBy(() -> CliSessionParser.STANDARD.blocks(stdout, TWO))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    @Test
    void eachCommandGetsTheLinesUpToTheNextPromptWithoutThePrompt() {
        assertThat(CliSessionParser.STANDARD.blocks(STDOUT, TWO)).containsExactly(
            List.of("INUBIT 9.0.10", "Build 4711"),
            List.of("Process Engine is alive (up 2 days, 03:14:07)"));
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
        assertUnexpected(STDOUT.replace("CLI|jdoe@inubit-dev-1.example.test> Process",
            "x CLI|jdoe@inubit-dev-1.example.test> Process"));
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
