package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;

/**
 * The answer of {@code processdelete error} (US4, research D-22, FR-017).
 *
 * <p>Recorded on 8.1.17 (T054, fixture {@code admin-operate-processdelete-error}): one line
 * {@code Process with status [error] has been deleted.}, exit 0. It names no number of deleted
 * processes.
 */
public final class ProcessDeleteAnswerParser {

    static final String COMMAND = "processdelete error";

    private static final String FORM = "Process with status [error] has been deleted.";

    private ProcessDeleteAnswerParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return INUBIT's answer, the recorded line
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is that line
     */
    public static String parse(List<String> block) {
        if (block.size() == 1 && block.getFirst().strip().equals(FORM)) {
            return FORM;
        }
        throw UnknownOutput.of(COMMAND, "the line '" + FORM + "'", block);
    }
}
