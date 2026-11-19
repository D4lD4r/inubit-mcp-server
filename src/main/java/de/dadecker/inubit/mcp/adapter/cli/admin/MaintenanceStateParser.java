package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;

/**
 * The answers of {@code maintenance -s} and of {@code maintenance on|off} (US4, research D-22).
 *
 * <p>Recorded on 8.1.17 (T054, fixtures {@code admin-operate-maintenance-state[-on]},
 * {@code admin-operate-maintenance-on-eq-false}, {@code admin-operate-maintenance-off}):
 * {@code maintenance -s} answers one line {@code on} or {@code off}; both switches answer one line
 * {@code Updated Successfully}, without the state.
 */
public final class MaintenanceStateParser {

    static final String STATE_COMMAND = "maintenance -s";
    static final String SWITCH_COMMAND = "maintenance on|off";

    private static final String SWITCH_FORM = "Updated Successfully";

    private MaintenanceStateParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return whether maintenance mode is on
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is one line
     *     {@code on} or {@code off}
     */
    public static boolean parse(List<String> block) {
        if (block.size() == 1) {
            String state = block.getFirst().strip();
            if (state.equals("on") || state.equals("off")) {
                return state.equals("on");
            }
        }
        throw UnknownOutput.of(STATE_COMMAND, "one line 'on' or 'off'", block);
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return INUBIT's answer, {@code Updated Successfully}
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is that line
     */
    public static String parseSwitch(List<String> block) {
        if (block.size() == 1 && block.getFirst().strip().equals(SWITCH_FORM)) {
            return SWITCH_FORM;
        }
        throw UnknownOutput.of(SWITCH_COMMAND, "the line '" + SWITCH_FORM + "'", block);
    }
}
