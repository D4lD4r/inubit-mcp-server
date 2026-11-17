package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code processdelete error} (US4, research D-22, FR-017): the number of deleted
 * processes INUBIT reports.
 *
 * <p>ASSUMED FORM (not recorded; fixture {@code admin-operate-processdelete-error-reconstructed},
 * SYNTHETIC): one line {@code Deleted processes: <n>}. The live check T054 records the real answer
 * with one prepared test process in error and replaces this form.
 */
public final class ProcessDeleteAnswerParser {

    static final String COMMAND = "processdelete error";

    private static final Pattern FORM = Pattern.compile("Deleted processes: (\\d{1,9})");
    private static final String EXPECTED =
        "the assumed form 'Deleted processes: <n>' (not yet recorded)";

    private ProcessDeleteAnswerParser() {
    }

    /**
     * @param block the answer's lines after the client's preamble
     * @return the number of processes INUBIT reports as deleted
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block is the assumed form
     */
    public static long parse(List<String> block) {
        if (block.size() == 1) {
            Matcher matcher = FORM.matcher(block.getFirst().strip());
            if (matcher.matches()) {
                return Long.parseLong(matcher.group(1));
            }
        }
        throw UnknownOutput.of(COMMAND, EXPECTED, block);
    }
}
