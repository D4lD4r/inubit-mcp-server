package de.dadecker.inubit.mcp.config;

/**
 * The value checks of credentials, the same for every source (feature 010, research R-17).
 */
public final class CredentialRules {

    /** Passwords shorter than this are a configuration error (Phase 3 review m4). */
    public static final int MIN_SCRUBBED_LENGTH = 4;

    /** What {@link #isSafeUsername} requires, for messages. */
    public static final String SAFE_USERNAME_RULE = "must not contain ':' or control characters"
        + " and must not start with '-' (it is used in the Basic-auth header and as StartCLI"
        + " argument)";

    /** Why {@link #isScrubbable} refuses a password, for messages. */
    public static final String SHORT_PASSWORD_RULE = "is shorter than " + MIN_SCRUBBED_LENGTH
        + " characters; such a short value cannot be scrubbed reliably from logs and results"
        + " without masking unrelated text";

    /** How a value of a given {@link ParameterType} is treated. */
    public enum TypeVerdict {
        ACCEPTED,
        /** Accepted with a warning: the value is stored unencrypted. */
        UNENCRYPTED,
        REFUSED
    }

    private CredentialRules() {
    }

    /** No ':' (Basic auth), no control characters, no leading '-' (StartCLI option). */
    public static boolean isSafeUsername(String username) {
        return !username.startsWith("-") && username.indexOf(':') < 0
            && username.chars().noneMatch(Character::isISOControl);
    }

    /** Returns whether {@code password} is long enough to be scrubbed reliably. */
    public static boolean isScrubbable(String password) {
        return password.length() >= MIN_SCRUBBED_LENGTH;
    }

    /** Returns whether {@code value} counts as set (present and not empty). */
    public static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }

    /** {@code SecureString} accepted, {@code String} warned, {@code StringList} refused. */
    public static TypeVerdict checkType(ParameterType type) {
        return switch (type) {
            case SECURE_STRING -> TypeVerdict.ACCEPTED;
            case STRING -> TypeVerdict.UNENCRYPTED;
            case STRING_LIST -> TypeVerdict.REFUSED;
        };
    }
}
