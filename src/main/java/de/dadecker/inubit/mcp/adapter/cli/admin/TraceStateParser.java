package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answers of {@code trace on|off} and {@code trace -l <level>} (US4, research D-22).
 *
 * <p>Recorded on 8.1.17 (T054, fixtures {@code admin-operate-trace-on}, {@code -trace-off},
 * {@code -trace-level-debug}, {@code -trace-level-info}): {@code trace on|off} answers no text;
 * {@code trace -l <level>} answers one line {@code Successfully changed level to <LEVEL>} with the
 * level in upper case.
 */
public final class TraceStateParser {

    static final String SWITCH_COMMAND = "trace on|off";
    static final String LEVEL_COMMAND = "trace -l <level>";

    private static final Pattern LEVEL_FORM =
        Pattern.compile("Successfully changed level to ([A-Z]+)");

    private TraceStateParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is empty
     */
    public static void parseSwitch(List<String> block) {
        if (!block.isEmpty()) {
            throw UnknownOutput.of(SWITCH_COMMAND, "no text", block);
        }
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return the trace level INUBIT names
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is the recorded form
     *     with a level of {@link TraceLevel}
     */
    public static TraceLevel parseLevel(List<String> block) {
        if (block.size() == 1) {
            Matcher matcher = LEVEL_FORM.matcher(block.getFirst().strip());
            if (matcher.matches()) {
                for (TraceLevel level : TraceLevel.values()) {
                    if (level.name().equals(matcher.group(1))) {
                        return level;
                    }
                }
            }
        }
        throw UnknownOutput.of(LEVEL_COMMAND,
            "the line 'Successfully changed level to <LEVEL>'", block);
    }
}
