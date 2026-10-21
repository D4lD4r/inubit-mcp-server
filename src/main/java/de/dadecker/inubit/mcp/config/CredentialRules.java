package de.dadecker.inubit.mcp.config;

import java.util.Optional;

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

    /** What a fetched value is used as; decides which value rules apply. */
    public enum ValueRole {
        /** {@code username} or {@code e2eUsername}. */
        USERNAME,
        /** {@code password} or {@code e2ePassword}. */
        PASSWORD,
        TRUST_STORE_PASSWORD
    }

    /**
     * A fetched value refused by {@link #checkValue}.
     *
     * @param rule the broken rule, for messages; never the value
     */
    public record Refusal(FailureKind kind, String rule) {
    }

    private CredentialRules() {
    }

    /**
     * The checks of a value read from a secret store, the same at run time and in
     * {@code --check-config}: a {@code StringList} and a value breaking the rule of its role are
     * {@code INVALID_VALUE}, an empty value is {@code NOT_FOUND}. A {@code String} is accepted
     * here; its warning is the caller's ({@link #checkType}).
     */
    public static Optional<Refusal> checkValue(ValueRole role, ParameterType type, String value) {
        if (checkType(type) == TypeVerdict.REFUSED) {
            return Optional.of(new Refusal(FailureKind.INVALID_VALUE,
                "a StringList is not accepted; store it as SecureString"));
        }
        if (!isSet(value)) {
            return Optional.of(new Refusal(FailureKind.NOT_FOUND, "the value is empty"));
        }
        if (role == ValueRole.USERNAME && !isSafeUsername(value)) {
            return Optional.of(new Refusal(FailureKind.INVALID_VALUE,
                "the username " + SAFE_USERNAME_RULE));
        }
        if (role == ValueRole.PASSWORD && !isScrubbable(value)) {
            return Optional.of(new Refusal(FailureKind.INVALID_VALUE,
                "the password " + SHORT_PASSWORD_RULE));
        }
        return Optional.empty();
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
