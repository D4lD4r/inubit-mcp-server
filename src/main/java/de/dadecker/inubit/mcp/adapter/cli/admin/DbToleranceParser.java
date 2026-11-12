package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code dbconnectiontolerance show} (US1, part {@code dbConnectionTolerance}).
 *
 * <p>FORM of the vendor example ({@code diagnosis-commands}, "Displaying and Configuring the
 * Database Connection Tolerance"; fixture {@code admin-settings-db-tolerance-reconstructed}, not
 * yet recorded; T025 aligns {@code FORM} with the recording of T014): exactly these four lines in
 * this order, e.g.
 *
 * <pre>
 * Error level: 0%
 * Fault threshold: 25
 * Drop rate: 1%
 * Drop interval (s): 180 seconds
 * </pre>
 *
 * <p>The error level is the current state, not a setting, and is not returned.
 */
public final class DbToleranceParser {

    static final String COMMAND = "dbconnectiontolerance show";

    private static final Pattern FORM = Pattern.compile("Error level: \\d{1,3}%\n"
        + "Fault threshold: (\\d{1,9})\n"
        + "Drop rate: (\\d{1,3})%\n"
        + "Drop interval \\(s\\): (\\d{1,18}) seconds");
    private static final String EXPECTED = "the vendor example with the lines 'Error level: <n>%',"
        + " 'Fault threshold: <n>', 'Drop rate: <n>%' and 'Drop interval (s): <n> seconds'"
        + " (not yet recorded)";

    private DbToleranceParser() {
    }

    /**
     * @param block the lines of the command's session block
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block has the form above
     *     with a drop rate of 0 … 100 %
     */
    public static DbConnectionTolerance parse(List<String> block) {
        Matcher matcher = FORM.matcher(String.join("\n", block.stream().map(String::strip)
            .toList()));
        if (matcher.matches()) {
            int dropRate = Integer.parseInt(matcher.group(2));
            if (dropRate <= 100) {
                return new DbConnectionTolerance(Integer.parseInt(matcher.group(1)), dropRate,
                    Long.parseLong(matcher.group(3)));
            }
        }
        throw UnknownOutput.of(COMMAND, EXPECTED, block);
    }
}
