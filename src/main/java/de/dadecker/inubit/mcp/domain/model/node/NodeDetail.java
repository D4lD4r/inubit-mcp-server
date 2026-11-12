package de.dadecker.inubit.mcp.domain.model.node;

import java.util.Objects;

/**
 * One name/value pair of a cluster member's details (research D-7). The value is already masked
 * (D-21) and, if it was longer, cut to {@value #MAX_VALUE_CHARS} characters.
 *
 * @param cut true if the value was cut, by the helper or after masking
 */
public record NodeDetail(String key, String value, boolean cut) {

    public static final int MAX_KEY_CHARS = 200;
    public static final int MAX_VALUE_CHARS = 2_000;

    public NodeDetail {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException(
                "Invalid detail key: more than " + MAX_KEY_CHARS + " characters");
        }
        if (value.length() > MAX_VALUE_CHARS) {
            throw new IllegalArgumentException(
                "Invalid detail value: more than " + MAX_VALUE_CHARS + " characters");
        }
    }
}
