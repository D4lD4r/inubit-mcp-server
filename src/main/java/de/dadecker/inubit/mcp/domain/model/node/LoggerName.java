package de.dadecker.inubit.mcp.domain.model.node;

import java.util.regex.Pattern;

/**
 * A logger whose level is raised for a while (FR-041, research D-11): {@code com.inubit.ibis}
 * followed by one or more dot-separated Java identifiers of ASCII letters, digits and
 * {@code _}, at most {@value #MAX_CHARS} characters. No {@code $}, quote or space can reach the
 * StartCLI command line.
 */
public record LoggerName(String value) {

    public static final int MAX_CHARS = 200;

    private static final Pattern PATTERN =
        Pattern.compile("com\\.inubit\\.ibis(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    public LoggerName {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid logger name: expected com.inubit.ibis"
                + " followed by dot-separated Java identifiers, at most " + MAX_CHARS
                + " characters");
        }
    }

    /** True if {@code value} follows the rule above. */
    public static boolean isValid(String value) {
        return value != null && value.length() <= MAX_CHARS && PATTERN.matcher(value).matches();
    }
}
