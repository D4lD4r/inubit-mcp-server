package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The answer of {@code date} (US1, part {@code serverTime}), kept as text as the server shows it.
 *
 * <p>FORM as recorded on 8.1.17 (T014, L-5; fixtures {@code admin-settings-session} and
 * {@code admin-settings-date}): one line in the form of Java's {@code Date.toString()},
 * {@code EEE MMM dd HH:mm:ss zzz yyyy}, in the server's time zone, e.g.
 * {@code Fri Nov 13 05:48:53 CET 2026}.
 */
public final class ServerDateParser {

    static final String COMMAND = "date";

    private static final Pattern FORM = Pattern.compile(
        "[A-Z][a-z]{2} [A-Z][a-z]{2} \\d{2} \\d{2}:\\d{2}:\\d{2} \\S+ \\d{4}");
    private static final String EXPECTED =
        "the recorded form 'EEE MMM dd HH:mm:ss zzz yyyy'";

    private ServerDateParser() {
    }

    /**
     * @param block the lines of the command's session block
     * @return the server time as the server shows it, without surrounding spaces
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is the recorded form
     */
    public static String parse(List<String> block) {
        if (block.size() == 1) {
            String line = block.getFirst().strip();
            if (FORM.matcher(line).matches()) {
                return line;
            }
        }
        throw UnknownOutput.of(COMMAND, EXPECTED, block);
    }
}
