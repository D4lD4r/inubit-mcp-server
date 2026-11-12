package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The answer of {@code date} (US1, part {@code serverTime}), kept as text as the server shows it.
 *
 * <p>ASSUMED FORM (the vendor page {@code diagnosis-commands} only says "Returns the Process
 * Engine's date and time"; fixture {@code admin-settings-date-reconstructed}, SYNTHETIC; T025
 * aligns {@code FORM} with the recording of T014): one line in the form of Java's
 * {@code Date.toString()}, {@code EEE MMM dd HH:mm:ss zzz yyyy}, e.g.
 * {@code Thu Nov 12 14:03:27 CET 2026}.
 */
public final class ServerDateParser {

    static final String COMMAND = "date";

    private static final Pattern FORM = Pattern.compile(
        "[A-Z][a-z]{2} [A-Z][a-z]{2} \\d{2} \\d{2}:\\d{2}:\\d{2} \\S+ \\d{4}");
    private static final String EXPECTED =
        "the assumed form 'EEE MMM dd HH:mm:ss zzz yyyy' (not yet recorded)";

    private ServerDateParser() {
    }

    /**
     * @param block the lines of the command's session block
     * @return the server time as the server shows it, without surrounding spaces
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is the assumed form
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
