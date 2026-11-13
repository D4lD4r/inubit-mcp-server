package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code dbconnectiontolerance show} (US1, part {@code dbConnectionTolerance}).
 *
 * <p>FORM as recorded on 8.1.17 (T014, L-5; fixtures {@code admin-settings-session} and
 * {@code admin-settings-db-tolerance}): exactly these four lines in this order, each label
 * separated from its value by one or two tabs ({@code \t}); the vendor example uses a space, which
 * is accepted too. The blank line after the error level and the trailing {@code \r} line of the
 * session are dropped by the session parser. Recorded in English, which the server forces for
 * every StartCLI run ({@code StartCliEnvironment.JAVA_TOOL_OPTIONS}); another locale was not
 * recorded. E.g.
 *
 * <pre>
 * Error level:          0%
 * Fault threshold:      20
 * Drop rate:            5%
 * Drop interval (s):    1800 seconds
 * </pre>
 *
 * <p>The error level is the current state, not a setting, and is not returned.
 */
public final class DbToleranceParser {

    static final String COMMAND = "dbconnectiontolerance show";

    private static final Pattern FORM = Pattern.compile("Error level:[ \t]+\\d{1,3}%\n"
        + "Fault threshold:[ \t]+(\\d{1,9})\n"
        + "Drop rate:[ \t]+(\\d{1,3})%\n"
        + "Drop interval \\(s\\):[ \t]+(\\d{1,18}) seconds");
    private static final String EXPECTED = "the recorded form with the lines 'Error level: <n>%',"
        + " 'Fault threshold: <n>', 'Drop rate: <n>%' and 'Drop interval (s): <n> seconds'";

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
