package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.PersonalField;
import de.dadecker.inubit.mcp.domain.model.SecretRules;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Finger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The answer of {@code finger <login>} (US2, roles, e-mail and name of a user).
 *
 * <p>FORM as recorded on 8.1.17 (T028, L-6; fixtures {@code admin-users-session} and
 * {@code admin-users-finger*}): in this order
 *
 * <ul>
 *   <li>{@code Login: } with the login padded to 15 characters, followed on the same line by
 *       {@code Name: <last name>, <first name>} only for a user with both names;
 *   <li>{@code Email: } with the address (the same value as the REST {@code email});
 *   <li>{@code Role: <role>}; one line in every recording. Several {@code Role:} lines are read as
 *       several roles in their order (not recorded).
 * </ul>
 *
 * E.g.
 *
 * <pre>
 * Login: jdoe           Name: Doe, John
 * Email: john.doe@example.test
 * Role: Platform Administrator
 * </pre>
 *
 * <p>The answer shows no account state (L-13 not answered). Any other line makes the block
 * unknown. The name is a personal value (FR-011): it leaves this class only as a
 * {@link PersonalField}, masked by {@link SecretRules} like every user field (FR-010), and the
 * error for an unknown block shows only the labels ({@link UnknownOutput#labelsOnly}).
 */
public final class FingerParser {

    static final String COMMAND = "finger <login>";
    private static final String NAME = "Name";

    private static final Pattern EMAIL = Pattern.compile("Email:(?: (.*))?");
    private static final Pattern ROLE = Pattern.compile("Role: (\\S.*)");
    private static final String EXPECTED = "the recorded form with the lines 'Login: <login>"
        + " [Name: <name>]', 'Email: <address>' and one or more 'Role: <role>'";

    private FingerParser() {
    }

    /**
     * @param login the login the command was sent for
     * @param block the lines of the command's session block
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} unless the block has the form above
     *     and names {@code login}; its excerpt shows no value
     */
    public static Finger parse(String login, List<String> block) {
        List<String> lines = block.stream().map(String::strip).toList();
        if (lines.size() >= 3) {
            Matcher first = Pattern.compile("Login: " + Pattern.quote(login)
                + " *(?:" + NAME + ": (\\S.*))?").matcher(lines.get(0));
            Matcher email = EMAIL.matcher(lines.get(1));
            List<String> roles = roles(lines.subList(2, lines.size()));
            if (first.matches() && email.matches() && !roles.isEmpty()) {
                List<PersonalField> personal = first.group(1) == null ? List.of()
                    : List.of(personalField(NAME, first.group(1)));
                return new Finger(login, roles,
                    Optional.ofNullable(email.group(1)).filter(value -> !value.isEmpty()),
                    personal);
            }
        }
        throw UnknownOutput.labelsOnly(COMMAND, EXPECTED, block);
    }

    /** The roles, or none if a line is not a role line. */
    private static List<String> roles(List<String> lines) {
        List<String> roles = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = ROLE.matcher(line);
            if (!matcher.matches()) {
                return List.of();
            }
            roles.add(matcher.group(1));
        }
        return roles;
    }

    private static PersonalField personalField(String name, String value) {
        return SecretRules.masks(name, Optional.empty(), false, value)
            ? PersonalField.masked(name) : PersonalField.shown(name, value);
    }
}
