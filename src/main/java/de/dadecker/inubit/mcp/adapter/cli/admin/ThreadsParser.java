package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code threads showNumberOfWorkflowThreads} (US1, part {@code threads}).
 *
 * <p>ASSUMED FORM (the vendor page {@code configuring-workflow-threads} shows no output; fixture
 * {@code admin-settings-threads-reconstructed}, SYNTHETIC): one line
 * {@code Number of workflow threads: <n>}.
 *
 * <p>Not recorded: on 8.1.17 the command answers "Only root user can use this command." to any
 * other account (T014, fixture {@code admin-settings-threads-refused}). Its coverage row is
 * {@code excluded:needs the INUBIT root user} on 8.1 and 9.0 (owner decision 2026-11-13), so the
 * version gate reports the part {@code threads} unavailable with that reason and never sends the
 * command. The parser stays with the part: the part and its coverage entry stay declared in
 * {@code ServerSettingsService.SPEC}, which is where the gate's reason comes from, and
 * {@link CliSettingsRunner} reads every declared StartCLI part a coverage list admits. A recording
 * with the root user would confirm the form and lift the exclusion.
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
