package de.dadecker.inubit.mcp.domain.model;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The profile one server process serves (002 data-model.md → ProfileInfo): its name, optional
 * description, terminology and the effective prefix of its credential environment variables.
 *
 * @param credentialPrefix {@code credentials.envPrefix}, or {@link #defaultCredentialPrefix}
 *     of the name; never part of a tool result
 */
public record ProfileInfo(
    String name,
    Optional<String> description,
    Terminology terminology,
    String credentialPrefix) {

    /** Profile names: the same rule as group and node names. */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");

    /**
     * Names no profile may have (002 US2 review P3b): {@code audit} would put the profile's audit
     * directory ({@code ~/.inubit-mcp/<profile>/audit}) into the audit directory of feature 001.
     */
    public static final Set<String> RESERVED_NAMES = Set.of("audit");

    /** The whole rule of {@link #isValidName}, for messages. */
    public static final String NAME_RULE = NAME_PATTERN.pattern() + ", except "
        + String.join(", ", new TreeSet<>(RESERVED_NAMES));

    /** Shown instead of a profile name that is not valid (it could contain control codes). */
    public static final String INVALID_NAME = "(invalid profile.name)";

    /** Upper bound of {@code profile.description}, in code points. */
    public static final int MAX_DESCRIPTION_LENGTH = 200;

    /** The rule {@link #isValidDescription} checks, as an error message. */
    public static final String DESCRIPTION_RULE = "profile.description must be a single line of"
        + " at most " + MAX_DESCRIPTION_LENGTH + " characters without control characters";

    /** Control characters (tab, line breaks, escape, …); a description has none. */
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

    /** {@code credentials.envPrefix}. */
    public static final Pattern PREFIX_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** Start of the default credential prefix, followed by the normalized profile name. */
    public static final String DEFAULT_PREFIX_START = "INUBIT_";

    public ProfileInfo {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("profile.name is missing");
        }
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid profile.name " + Names.quote(name)
                + ": expected " + NAME_RULE);
        }
        description = description == null ? Optional.empty() : description;
        description.filter(text -> !isValidDescription(text)).ifPresent(text -> {
            throw new IllegalArgumentException(DESCRIPTION_RULE);
        });
        Objects.requireNonNull(terminology, "terminology");
        if (!isValidPrefix(credentialPrefix)) {
            throw new IllegalArgumentException("Invalid credentials.envPrefix "
                + Names.quote(credentialPrefix) + ": expected " + PREFIX_PATTERN.pattern());
        }
    }

    /**
     * Renders {@code template} with the terminology ({@link Terminology#render}) and
     * {@code {profile}} as the profile name.
     *
     * @throws IllegalArgumentException for an unknown placeholder
     */
    public String render(String template) {
        return terminology.render(template, Map.of("profile", name));
    }

    /** Returns whether {@code name} is a valid profile name: the pattern, not reserved. */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches()
            && !RESERVED_NAMES.contains(name);
    }

    /** Returns whether {@code name} is one of the {@link #RESERVED_NAMES}. */
    public static boolean isReservedName(String name) {
        return RESERVED_NAMES.contains(name);
    }

    /**
     * {@code name} if it is a valid profile name, otherwise {@link #INVALID_NAME}: names read from
     * a file are printed only when they cannot carry control codes (002 US2 review P3a).
     */
    public static String displayName(String name) {
        return isValidName(name) ? name : INVALID_NAME;
    }

    /**
     * Returns whether {@code text} has at most 200 code points, no control character
     * ({@code \p{Cc}}) and no Unicode line or paragraph separator (002 review G4).
     */
    public static boolean isValidDescription(String text) {
        return text.codePointCount(0, text.length()) <= MAX_DESCRIPTION_LENGTH
            && !CONTROL.matcher(text).find()
            && text.chars().noneMatch(ProfileInfo::isLineBreak);
    }

    /** Returns whether {@code prefix} is a valid credential variable prefix. */
    public static boolean isValidPrefix(String prefix) {
        return prefix != null && PREFIX_PATTERN.matcher(prefix).matches();
    }

    /**
     * The credential prefix of a profile without {@code credentials.envPrefix}: {@code INUBIT_}
     * plus the profile name in upper case, every character other than {@code A-Z0-9} replaced by
     * {@code _} (e.g. {@code acme-2} → {@code INUBIT_ACME_2}).
     */
    public static String defaultCredentialPrefix(String profileName) {
        return DEFAULT_PREFIX_START
            + profileName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    /** U+2028 / U+2029: line breaks that are not control characters. */
    private static boolean isLineBreak(int c) {
        return c == '\u2028' || c == '\u2029';
    }
}
