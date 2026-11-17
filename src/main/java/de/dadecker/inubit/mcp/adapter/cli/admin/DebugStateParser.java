package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code debug on|off} (US4, research D-22).
 *
 * <p>ASSUMED FORM (not recorded; fixture {@code admin-operate-debug-on-reconstructed},
 * SYNTHETIC): one line {@code Debug mode: on|off}, the state after the switch. The live check
 * T054 (L-3) records the real answers and replaces this form.
 */
public final class DebugStateParser {

    static final String COMMAND = "debug on|off";

    private static final Pattern FORM = Pattern.compile("Debug mode: (on|off)");
    private static final String EXPECTED =
        "the assumed form 'Debug mode: on|off' (not yet recorded)";

    private DebugStateParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return whether debug mode is on
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is the assumed form
     */
    public static boolean parse(List<String> block) {
        if (block.size() == 1) {
            Matcher matcher = FORM.matcher(block.getFirst().strip());
            if (matcher.matches()) {
                return matcher.group(1).equals("on");
            }
        }
        throw UnknownOutput.of(COMMAND, EXPECTED, block);
    }
}
