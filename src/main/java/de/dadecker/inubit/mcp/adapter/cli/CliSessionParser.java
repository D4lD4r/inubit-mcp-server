package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits the output of an interactive StartCLI session ({@link CliRunner#runSession}) into one
 * block per command (feature 012 research D-5; version-neutral since feature 022, research D-7):
 *
 * <ul>
 *   <li>the client prints its prompt at the start of a line before it reads each line after the
 *       password, and a command's output follows its prompt;
 *   <li>so the output has one prompt per command plus one for {@code exit}, which
 *       {@code runSession} appends and after which nothing is printed;
 *   <li>everything before the first prompt (the client's connection lines and the password
 *       prompt) is ignored; lines end in {@code \n} or {@code \r\n}; blank lines and lines of
 *       only {@code \r} are dropped.
 * </ul>
 *
 * <p>{@link #STANDARD} reads the 8.1 form recorded by T014 (research "Live recording T014", L-1;
 * {@code fixtures/v8_1/cli/admin-settings-session}): the prompt {@code CLI|<user>@<host>> }, where
 * {@code <host>} is the server's host name, followed on the same line by the client's echo of the
 * command it read, also of {@code exit}; the answer starts on the next line. The echo is no
 * output and is dropped whatever it reads (an echoed secret is masked by then, so it is not
 * compared with the command). A refused command does not end the session. The 6.1 parser passes
 * its own prompt, after which the output starts on the same line ({@code echoed} false).
 */
public final class CliSessionParser {

    /** The prompt, e.g. {@code CLI|root@localhost> }, with one following space. */
    public static final Pattern PROMPT = Pattern.compile("CLI\\|[^@\\s]+@\\S+> ?");

    /** The parser for the 8.1 prompt with the echoed command. */
    public static final CliSessionParser STANDARD = new CliSessionParser(PROMPT, true,
        "the recorded 8.1 format with the prompt CLI|<user>@<host>> and the echoed command");

    private final Pattern prompt;
    private final boolean echoed;
    private final String format;

    /**
     * @param prompt matched at the start of a line; the match is removed from the line
     * @param echoed whether the rest of a prompt line is the client's echo of the command, which
     *     is dropped; otherwise it is the first line of the command's output
     * @param format names the expected format in the {@code UNEXPECTED_RESPONSE} cause
     */
    public CliSessionParser(Pattern prompt, boolean echoed, String format) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.echoed = echoed;
        this.format = Objects.requireNonNull(format, "format");
    }

    /**
     * @return the lines of each command's block, in the order of {@code commands}
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} if the number of prompts is not
     *     {@code commands.size() + 1} or the {@code exit} block is not empty
     */
    public List<List<String>> blocks(String stdout, List<CliSessionCommand> commands) {
        List<List<String>> blocks = new ArrayList<>();
        for (String line : stdout.lines().map(l -> l.replace("\r", "")).toList()) {
            Matcher matcher = prompt.matcher(line);
            if (matcher.lookingAt()) {
                blocks.add(new ArrayList<>());
                line = echoed ? "" : line.substring(matcher.end());
            }
            if (!blocks.isEmpty() && !line.isBlank()) {
                blocks.getLast().add(line);
            }
        }
        if (blocks.size() != commands.size() + 1) {
            throw unexpected("StartCLI printed " + blocks.size() + " prompts for "
                + commands.size() + " commands and exit");
        }
        if (!blocks.getLast().isEmpty()) {
            throw unexpected("StartCLI printed output after exit");
        }
        return blocks.subList(0, commands.size()).stream().map(List::copyOf).toList();
    }

    /** {@code UNEXPECTED_RESPONSE} naming this parser's format. */
    public ToolErrorException unexpected(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, message,
            "StartCLI's session output differs from " + format,
            "Check the INUBIT client version (cliHome) and the INUBIT system log"));
    }
}
