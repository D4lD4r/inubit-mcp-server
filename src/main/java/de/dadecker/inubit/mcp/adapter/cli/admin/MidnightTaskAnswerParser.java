package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;

/**
 * The answer of {@code triggerMidnightTask} (US4, research D-22).
 *
 * <p>Recorded on 8.1.17 (T054, fixture {@code admin-operate-midnight-task}): no text, exit 0.
 */
public final class MidnightTaskAnswerParser {

    static final String COMMAND = "triggerMidnightTask";

    private MidnightTaskAnswerParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is empty
     */
    public static void parse(List<String> block) {
        if (!block.isEmpty()) {
            throw UnknownOutput.of(COMMAND, "no text", block);
        }
    }
}
