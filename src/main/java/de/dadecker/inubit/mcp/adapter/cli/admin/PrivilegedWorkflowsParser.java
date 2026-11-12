package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.PrivilegedWorkflows;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answers of {@code privilegedWorkflows showThreadsPercentage} and
 * {@code privilegedWorkflows showWorkflowList} (US1, part {@code privilegedWorkflows}).
 *
 * <p>ASSUMED FORMS (the vendor page {@code configuring-workflow-threads} shows no output; fixtures
 * {@code admin-settings-privileged-percentage-reconstructed} and
 * {@code admin-settings-privileged-list-reconstructed}, SYNTHETIC; T025 aligns {@code PERCENTAGE}
 * and {@code LIST_HEADER} with the recording of T014):
 *
 * <ul>
 *   <li>percentage: one line
 *       {@code Percentage of threads reserved for privileged workflows: <n>%}, 0 … 100;
 *   <li>list: the line {@code Privileged workflows:}, then one workflow name per line (none for
 *       an empty list). Without the header line the block is not taken for a list, so an error
 *       text never becomes a workflow name.
 * </ul>
 */
public final class PrivilegedWorkflowsParser {

    static final String PERCENTAGE_COMMAND = "privilegedWorkflows showThreadsPercentage";
    static final String LIST_COMMAND = "privilegedWorkflows showWorkflowList";

    private static final Pattern PERCENTAGE = Pattern.compile(
        "Percentage of threads reserved for privileged workflows: (\\d{1,3})%");
    private static final String PERCENTAGE_EXPECTED = "the assumed form 'Percentage of threads"
        + " reserved for privileged workflows: <n>%' (not yet recorded)";
    private static final String LIST_HEADER = "Privileged workflows:";
    private static final String LIST_EXPECTED = "the assumed form '" + LIST_HEADER
        + "' followed by one workflow name per line (not yet recorded)";

    private PrivilegedWorkflowsParser() {
    }

    /**
     * @param percentageBlock the session block of {@value #PERCENTAGE_COMMAND}
     * @param listBlock       the session block of {@value #LIST_COMMAND}
     * @return the percentage and the names, cut to {@value PrivilegedWorkflows#MAX_WORKFLOWS}
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless both blocks have the assumed
     *     form
     */
    public static PrivilegedWorkflows parse(List<String> percentageBlock,
        List<String> listBlock) {
        int percentage = percentage(percentageBlock);
        if (listBlock.isEmpty() || !listBlock.getFirst().strip().equals(LIST_HEADER)) {
            throw UnknownOutput.of(LIST_COMMAND, LIST_EXPECTED, listBlock);
        }
        List<String> names = listBlock.subList(1, listBlock.size()).stream()
            .map(String::strip)
            .toList();
        return PrivilegedWorkflows.of(percentage, names);
    }

    private static int percentage(List<String> block) {
        if (block.size() == 1) {
            Matcher matcher = PERCENTAGE.matcher(block.getFirst().strip());
            int percentage = matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
            if (percentage >= 0 && percentage <= 100) {
                return percentage;
            }
        }
        throw UnknownOutput.of(PERCENTAGE_COMMAND, PERCENTAGE_EXPECTED, block);
    }
}
