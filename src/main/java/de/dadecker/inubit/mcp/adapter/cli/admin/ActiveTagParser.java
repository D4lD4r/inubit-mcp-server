package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The active tag of a user from the answer of {@code user <name>} (US4, research D-18).
 *
 * <p>FORM: the rows recorded on 8.1.17 and 9.0.10 ({@link UserGroupsParser}: {@code User name},
 * {@code User group}, {@code Super group}, optionally {@code Additional user group}), then a row
 * {@code Active tag <tag>} for a user with an active tag. That last row is RECONSTRUCTED (fixture
 * {@code admin-tags-user-active-tag-reconstructed}): no recorded user had an active tag, so its
 * label and place are an assumption until the live check T055 (L-4) records the answer after
 * {@code tag --tagSetActive}. Without the row the user has no active tag. Any other row makes the
 * block unknown; its excerpt shows of such a row only the label, as {@link UserGroupsParser}
 * does.
 */
public final class ActiveTagParser {

    static final String COMMAND = "user <name>";

    private static final Pattern FORM = Pattern.compile("User name +(\\S.*)\n"
        + "User group +\\S.*\n"
        + "Super group +\\S.*"
        + "(?:\nAdditional user group +\\S.*)?"
        + "(?:\nActive tag +(\\S.*))?");
    private static final Pattern KNOWN_ROW = Pattern.compile(
        "(?:User name|User group|Super group|Additional user group|Active tag) +\\S.*");
    private static final String EXPECTED = "the recorded rows of 'user <login>' and the"
        + " reconstructed row 'Active tag <tag>'";

    private ActiveTagParser() {
    }

    /**
     * @param user  the user the command was sent for
     * @param block the lines of the command's session block
     * @return the active tag, empty if the block has no {@code Active tag} row
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block has the form above
     *     and names {@code user}
     */
    public static Optional<String> parse(String user, List<String> block) {
        Matcher matcher = FORM.matcher(String.join("\n", block.stream().map(String::strip)
            .toList()));
        if (matcher.matches() && matcher.group(1).equals(user)) {
            return Optional.ofNullable(matcher.group(2));
        }
        throw UnknownOutput.paddedLabelsOnly(COMMAND, EXPECTED, block, KNOWN_ROW);
    }
}
