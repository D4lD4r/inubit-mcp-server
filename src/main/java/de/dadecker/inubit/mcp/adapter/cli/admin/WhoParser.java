package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of the client run {@code startcli.sh … --who <url>} (US2, logins; research D-20).
 * {@code --who} is an option of the client, not a session command, so the parser takes the output
 * lines of its own run without the client's preamble.
 *
 * <p>FORM of {@code --who} as recorded on 8.1.17 (T028a, L-6; fixture {@code admin-users-who}): a
 * table of the systems connected to the server, the header
 * {@code SYSTEM-ID  SYSTEM TYPE  STATE  VERSION  IDLE} with the first four columns 64, 30, 10 and
 * 15 characters wide and {@code IDLE} unpadded, then one row per system. Database, portal and
 * process user server rows are systems, not logins. Only a row of the type {@code Toolset} is a
 * client connection, with the {@code SYSTEM-ID} {@code <client host> (<login>)}; whether the
 * parentheses hold the INUBIT login or the client's operating-system user is not settled (both
 * were the same in the recording). Each such row becomes a {@link LoginEntry} without a login
 * time: the table shows only an idle time. A row that does not fit the columns (e.g. a value as
 * wide as its column) makes the table unknown.
 *
 * <p>{@code --nwho} (fixture {@code admin-users-nwho}) prints one bare number, the number of rows
 * {@code --who} lists, systems included. It is not a number of logged-in users, so
 * {@code read_users} does not run it: the {@code logins} page's {@code total} is the number of
 * logged-in users (owner decision 2026-11-13, coverage row {@code covered:read_users}).
 */
public final class WhoParser {

    static final String WHO_COMMAND = "--who";
    private static final String TOOLSET = "Toolset";

    private static final int[] COLUMN_ENDS = {64, 94, 104, 119};
    private static final String HEADER = String.format("%-64s%-30s%-10s%-15s%s", "SYSTEM-ID",
        "SYSTEM TYPE", "STATE", "VERSION", "IDLE");
    private static final Pattern IDLE = Pattern.compile("\\d{1,9}:\\d{2}:\\d{2}s");
    private static final Pattern CLIENT = Pattern.compile("\\S+ \\(([^()\\s]+)\\)");
    private static final String WHO_EXPECTED = "the recorded table with the header 'SYSTEM-ID"
        + " SYSTEM TYPE STATE VERSION IDLE' (columns 64, 30, 10, 15 characters wide) and"
        + " '<host> (<login>)' in the SYSTEM-ID of a Toolset row";

    private WhoParser() {
    }

    /**
     * @param lines the output lines of the {@code --who} run, without preamble and blank lines
     * @return one entry per {@code Toolset} row, in the table's order, without a login time
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the lines are the table above
     */
    public static List<LoginEntry> parse(List<String> lines) {
        if (lines.isEmpty() || !lines.getFirst().stripTrailing().equals(HEADER)) {
            throw UnknownOutput.of(WHO_COMMAND, WHO_EXPECTED, lines);
        }
        List<LoginEntry> logins = new ArrayList<>();
        for (String row : lines.subList(1, lines.size())) {
            if (!fitsTheColumns(row)) {
                throw UnknownOutput.of(WHO_COMMAND, WHO_EXPECTED, lines);
            }
            if (row.substring(COLUMN_ENDS[0], COLUMN_ENDS[1]).strip().equals(TOOLSET)) {
                Matcher client = CLIENT.matcher(row.substring(0, COLUMN_ENDS[0]).strip());
                if (!client.matches()) {
                    throw UnknownOutput.of(WHO_COMMAND, WHO_EXPECTED, lines);
                }
                logins.add(new LoginEntry(client.group(1), Optional.empty()));
            }
        }
        return logins;
    }

    /** Each padded column ends in a space, and the idle time follows the last one. */
    private static boolean fitsTheColumns(String row) {
        int idleStart = COLUMN_ENDS[COLUMN_ENDS.length - 1];
        if (row.length() <= idleStart) {
            return false;
        }
        for (int end : COLUMN_ENDS) {
            if (row.charAt(end - 1) != ' ') {
                return false;
            }
        }
        return IDLE.matcher(row.substring(idleStart).strip()).matches();
    }
}
