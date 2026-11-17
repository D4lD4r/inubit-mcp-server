package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code maintenance -s} and of {@code maintenance on|off} (US4, research D-22).
 *
 * <p>ASSUMED FORM (not recorded; fixture {@code admin-operate-maintenance-state-reconstructed},
 * SYNTHETIC): one line {@code Maintenance mode: on|off}, for the read and for both switches. The
 * live check T054 (L-2) records the real answers and replaces this form.
 */
public final class MaintenanceStateParser {

    static final String COMMAND = "maintenance -s|on|off";

    private static final Pattern FORM = Pattern.compile("Maintenance mode: (on|off)");
    private static final String EXPECTED =
        "the assumed form 'Maintenance mode: on|off' (not yet recorded)";

    private MaintenanceStateParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return whether maintenance mode is on
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
