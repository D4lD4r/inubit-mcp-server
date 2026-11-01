package de.dadecker.inubit.mcp.adapter.cli.v61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand.Listing;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T024/T031 (feature 011, research D-5): a StartCLI 6.1 session split into one block per
 * command, here a process session with two listings.
 */
class V61SessionParserTest {

    private static final List<CliSessionCommand> WAITING_QUEUED = List.of(
        CliSessionCommand.psList(Listing.WAITING, CliSessionCommand.MAX_ROWS),
        CliSessionCommand.psList(Listing.QUEUED, CliSessionCommand.MAX_ROWS));

    private static final String STDOUT =
        FakeProcessLauncher.fixtureText("v6_1", "ps_session_waiting_queued.stdout");

    private static void assertUnexpected(String stdout) {
        assertThatThrownBy(() -> V61SessionParser.blocks(stdout, WAITING_QUEUED))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    @Test
    void eachCommandGetsTheLinesUpToTheNextPromptWithoutThePrompt() {
        List<List<String>> blocks = V61SessionParser.blocks(STDOUT, WAITING_QUEUED);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).hasSize(4);
        assertThat(blocks.get(0).getFirst()).startsWith("NODE ");
        assertThat(blocks.get(0).getLast()).isEqualTo("Total: 2");
        assertThat(blocks.get(1)).hasSize(3);
        assertThat(blocks.get(1).getFirst()).startsWith("NODE ");
        assertThat(blocks.get(1).getLast()).isEqualTo("Total: 1");
    }

    @Test
    void blankLinesAndCarriageReturnsAreDropped() {
        assertThat(V61SessionParser.blocks(STDOUT.replace("\n", "\r\n\r\n"), WAITING_QUEUED))
            .isEqualTo(V61SessionParser.blocks(STDOUT, WAITING_QUEUED));
    }

    @Test
    void aMissingListingIsUnexpected() {
        assertUnexpected(STDOUT.substring(STDOUT.indexOf("Total: 2\n") + "Total: 2\n".length()));
    }

    @Test
    void outputAfterExitIsUnexpected() {
        assertUnexpected(STDOUT + "Bye\n");
    }
}
