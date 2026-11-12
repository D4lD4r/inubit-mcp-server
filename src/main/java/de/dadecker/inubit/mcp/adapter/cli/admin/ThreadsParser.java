package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code threads showNumberOfWorkflowThreads} (US1, part {@code threads}).
 *
 * <p>ASSUMED FORM (the vendor page {@code configuring-workflow-threads} shows no output; fixture
 * {@code admin-settings-threads-reconstructed}, SYNTHETIC; T025 aligns {@code FORM} with the
 * recording of T014): one line {@code Number of workflow threads: <n>}.
 */
public final class ThreadsParser {

    static final String COMMAND = "threads showNumberOfWorkflowThreads";

    private static final Pattern FORM = Pattern.compile("Number of workflow threads: (\\d{1,9})");
    private static final String EXPECTED =
        "the assumed form 'Number of workflow threads: <n>' (not yet recorded)";

    private ThreadsParser() {
    }

    /**
     * @param block the lines of the command's session block
     * @return the configured number of workflow threads
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
