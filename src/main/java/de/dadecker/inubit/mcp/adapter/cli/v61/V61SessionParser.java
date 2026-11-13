package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionParser;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits the output of an interactive StartCLI 6.1 session ({@code CliRunner.runSession},
 * research D-5) into one block per command, as reconstructed in
 * {@code fixtures/v6_1/cli/README.md} until recorded; the splitting is {@link CliSessionParser}'s
 * (feature 022, research D-7) with the 6.1 prompt {@link #PROMPT}:
 *
 * <ul>
 *   <li>stdin is not echoed; the client prints {@link #PROMPT} at the start of a line before it
 *       reads each line after the password, and a command's output starts right after its
 *       prompt;
 *   <li>so the output has one prompt per command plus one for {@code exit}, which
 *       {@code runSession} appends and which prints nothing;
 *   <li>everything before the first prompt (the password prompt) is ignored; lines end in
 *       {@code \n} or {@code \r\n}; blank lines are dropped.
 * </ul>
 *
 * <p>Login failures end before the first prompt and are classified by
 * {@code CliOutputClassifier} before this parser runs.
 */
public final class V61SessionParser {

    /** The prompt of the interactive mode (reconstructed; the recordings confirm it, T037+). */
    public static final String PROMPT = "cli> ";

    private static final CliSessionParser PARSER = new CliSessionParser(
        Pattern.compile(Pattern.quote(PROMPT)), false, "the expected INUBIT 6.1 format");

    private V61SessionParser() {
    }

    /**
     * @return the lines of each command's block, in the order of {@code commands}
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} if the number of prompts is not
     *     {@code commands.size() + 1} or the {@code exit} block is not empty
     */
    public static List<List<String>> blocks(String stdout, List<CliSessionCommand> commands) {
        return PARSER.blocks(stdout, commands);
    }

    static ToolErrorException unexpected(String message) {
        return PARSER.unexpected(message);
    }
}
