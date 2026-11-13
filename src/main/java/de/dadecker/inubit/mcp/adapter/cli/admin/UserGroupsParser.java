package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code user <login>} (US2, groups of a user).
 *
 * <p>FORM as recorded on 8.1.17 (T028, L-6; fixtures {@code admin-users-session} and
 * {@code admin-users-user*}): exactly these three rows in this order, labels padded to 13
 * characters, values padded with trailing spaces; the block's empty last line is dropped by the
 * session parser. E.g.
 *
 * <pre>
 * User name    jdoe
 * User group   OPS
 * Super group  headoffice
 * </pre>
 *
 * <p>The super group is the parent of the user group, not an additional group. A row for
 * additional groups was not recorded (none of the recorded users has one), so any other row makes
 * the block unknown. The answer shows no roles and no account state.
 *
 * <p>The error for an unknown block shows the recorded rows in full and of any other row only its
 * label ({@link UnknownOutput#paddedLabelsOnly}): such a row might hold a personal value, and the
 * block is read for every user of a listing (FR-011).
 */
public final class UserGroupsParser {

    static final String COMMAND = "user <login>";

    private static final Pattern FORM = Pattern.compile("User name +(\\S.*)\n"
        + "User group +(\\S.*)\n"
        + "Super group +(\\S.*)");
    private static final Pattern KNOWN_ROW = Pattern.compile(
        "(?:User name|User group|Super group) +\\S.*");
    private static final String EXPECTED = "the recorded form with the rows 'User name <login>',"
        + " 'User group <group>' and 'Super group <group>'";

    private UserGroupsParser() {
    }

    /**
     * @param login the login the command was sent for
     * @param block the lines of the command's session block
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block has the form above
     *     and names {@code login}; its excerpt shows only the values of the recorded rows
     */
    public static UserGroups parse(String login, List<String> block) {
        Matcher matcher = FORM.matcher(String.join("\n", block.stream().map(String::strip)
            .toList()));
        if (matcher.matches() && matcher.group(1).equals(login)) {
            return new UserGroups(matcher.group(2), matcher.group(3));
        }
        throw UnknownOutput.paddedLabelsOnly(COMMAND, EXPECTED, block, KNOWN_ROW);
    }
}
