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
 *       password, and a command's output starts right after its prompt;
 *   <li>so the output has one prompt per command plus one for {@code exit}, which
 *       {@code runSession} appends and which prints nothing;
 *   <li>everything before the first prompt (the password prompt) is ignored; lines end in
 *       {@code \n} or {@code \r\n}; blank lines are dropped.
 * </ul>
 *
 * <p>{@link #STANDARD} reads the prompt {@code CLI|<user>@<host>>} of the vendor documentation
 * (format to be confirmed by recording L-1); the 6.1 parser passes its own prompt.
 */
public final class CliSessionParser {

    /** The documented prompt, e.g. {@code CLI|root@localhost> }, with one following space. */
    public static final Pattern PROMPT = Pattern.compile("CLI\\|[^@\\s]+@\\S+> ?");

    /** The parser for the documented prompt. */
    public static final CliSessionParser STANDARD =
        new CliSessionParser(PROMPT, "the documented format with the prompt CLI|<user>@<host>>");

    private final Pattern prompt;
    private final String format;

    /**
     * @param prompt matched at the start of a line; the match is removed from the line
     * @param format names the expected format in the {@code UNEXPECTED_RESPONSE} cause
     */
    public CliSessionParser(Pattern prompt, String format) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
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
                line = line.substring(matcher.end());
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
