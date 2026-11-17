package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answers of {@code trace on|off} and {@code trace -l <level>} (US4, research D-22).
 *
 * <p>ASSUMED FORMS (not recorded; fixtures {@code admin-operate-trace-on-reconstructed} and
 * {@code admin-operate-trace-level-reconstructed}, SYNTHETIC): one line {@code Trace: on|off},
 * the state after the switch, and one line {@code Trace level: <level>} with a level of the
 * reconstructed {@link TraceLevel} list in lower case, as {@code trace -l} takes it. The live check
 * T054 (L-3) records the real answers and the level list and replaces these forms.
 */
public final class TraceStateParser {

    static final String SWITCH_COMMAND = "trace on|off";
    static final String LEVEL_COMMAND = "trace -l <level>";

    private static final Pattern SWITCH_FORM = Pattern.compile("Trace: (on|off)");
    private static final Pattern LEVEL_FORM = Pattern.compile("Trace level: ([a-z]+)");
    private static final String SWITCH_EXPECTED =
        "the assumed form 'Trace: on|off' (not yet recorded)";
    private static final String LEVEL_EXPECTED =
        "the assumed form 'Trace level: <level>' (not yet recorded)";

    private TraceStateParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return whether the trace is on
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is the assumed form
     */
    public static boolean parseSwitch(List<String> block) {
        if (block.size() == 1) {
            Matcher matcher = SWITCH_FORM.matcher(block.getFirst().strip());
            if (matcher.matches()) {
                return matcher.group(1).equals("on");
            }
        }
        throw UnknownOutput.of(SWITCH_COMMAND, SWITCH_EXPECTED, block);
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return the trace level INUBIT names
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is the assumed form
     *     with a level of {@link TraceLevel}
     */
    public static TraceLevel parseLevel(List<String> block) {
        if (block.size() == 1) {
            Matcher matcher = LEVEL_FORM.matcher(block.getFirst().strip());
            if (matcher.matches()) {
                for (TraceLevel level : TraceLevel.values()) {
                    if (level.name().toLowerCase(Locale.ROOT).equals(matcher.group(1))) {
                        return level;
                    }
                }
            }
        }
        throw UnknownOutput.of(LEVEL_COMMAND, LEVEL_EXPECTED, block);
    }
}
