package de.dadecker.inubit.mcp.config;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Parameter Store names of a node's credentials, per kind (feature 010, data-model.md →
 * ParameterNames, research R-5). Names are not secrets, so {@code toString} shows them; errors
 * still name only the kind, because a value that breaks the rule may be a pasted secret.
 */
public record ParameterNames(
    Optional<String> username,
    Optional<String> password,
    Optional<String> trustStorePassword,
    Optional<String> e2eUsername,
    Optional<String> e2ePassword) {

    /**
     * Full hierarchical names; together with {@link #isValidName} no {@code //} and no trailing
     * {@code /}. The character set excludes {@code :}, so version and label selectors and ARNs
     * are refused.
     */
    public static final Pattern NAME_PATTERN = Pattern.compile("^/[A-Za-z0-9_.\\-/]{1,1010}$");

    /** The whole rule of {@link #isValidName}, for messages. */
    public static final String NAME_RULE = NAME_PATTERN.pattern()
        + ", without '//' and without a trailing '/'";

    /** No names. */
    public static final ParameterNames EMPTY = new ParameterNames(Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public ParameterNames {
        username = checked("username", username);
        password = checked("password", password);
        trustStorePassword = checked("trustStorePassword", trustStorePassword);
        e2eUsername = checked("e2eUsername", e2eUsername);
        e2ePassword = checked("e2ePassword", e2ePassword);
    }

    /** Returns whether {@code name} is a valid full parameter name. */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches() && !name.contains("//")
            && !name.endsWith("/");
    }

    private static Optional<String> checked(String kind, Optional<String> name) {
        Optional<String> value = Optionals.orEmpty(name);
        if (value.isPresent() && !isValidName(value.get())) {
            throw new IllegalArgumentException("Invalid parameter name for " + kind
                + ": expected " + NAME_RULE);
        }
        return value;
    }
}
