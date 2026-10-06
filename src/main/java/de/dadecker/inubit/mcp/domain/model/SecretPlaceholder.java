package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text that stands in the workspace where an exported secret was (research D-7, FR-024):
 * {@code ${secret:<property path>}}, e.g. {@code ${secret:Mime.Sign.Password}} or
 * {@code ${secret:xslt.sourceVariables/ISCurrentTime}}. Together with the file it is in, the
 * property path identifies the secret, so that a later feature can restore the value from the
 * target system.
 *
 * <p>A placeholder holds no value-derived data (no hash, length or prefix of the value). The
 * property path must not contain braces or control characters and must not start or end with
 * whitespace, so that rendering and {@link #parse} are inverse.
 */
public record SecretPlaceholder(String propertyPath) {

    private static final String PREFIX = "${secret:";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{secret:([^{}]+)}$");
    private static final Pattern INVALID = Pattern.compile("[{}\\p{Cntrl}]");

    public SecretPlaceholder {
        Objects.requireNonNull(propertyPath, "propertyPath");
        // the path is never echoed: a mistaken caller could have passed a value
        if (propertyPath.isBlank() || !propertyPath.strip().equals(propertyPath)
            || INVALID.matcher(propertyPath).find()) {
            throw new IllegalArgumentException("Invalid property path for a secret placeholder:"
                + " it must be non-blank, without surrounding whitespace, braces or control"
                + " characters");
        }
    }

    /** {@code ${secret:<property path>}}. */
    public String render() {
        return PREFIX + propertyPath + "}";
    }

    /** True if {@code text} is exactly one placeholder. */
    public static boolean isPlaceholder(String text) {
        return parse(text).isPresent();
    }

    /** The placeholder {@code text} is, or empty if it is none (or {@code null}). */
    public static Optional<SecretPlaceholder> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SecretPlaceholder(matcher.group(1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return render();
    }
}
