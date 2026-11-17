package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;

/**
 * The answer of {@code triggerMidnightTask} (US4, research D-22, AS11: the result names INUBIT's
 * answer).
 *
 * <p>ASSUMED FORM (not recorded; fixture {@code admin-operate-midnight-task-reconstructed},
 * SYNTHETIC): one line {@code Midnight task triggered.} The live check T054 records the real
 * answer (only if the owner approves running the midnight task) and replaces this form.
 */
public final class MidnightTaskAnswerParser {

    static final String COMMAND = "triggerMidnightTask";

    private static final String FORM = "Midnight task triggered.";
    private static final String EXPECTED = "the assumed form '" + FORM + "' (not yet recorded)";

    private MidnightTaskAnswerParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return INUBIT's answer, the line without surrounding spaces
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is the assumed form
     */
    public static String parse(List<String> block) {
        if (block.size() == 1 && block.getFirst().strip().equals(FORM)) {
            return FORM;
        }
        throw UnknownOutput.of(COMMAND, EXPECTED, block);
    }
}
