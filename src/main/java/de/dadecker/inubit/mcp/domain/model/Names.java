package de.dadecker.inubit.mcp.domain.model;

/** Helpers for echoing user-supplied identifiers in messages. */
public final class Names {

    private static final int MAX_ECHO_LENGTH = 80;

    private Names() {
    }

    /** Quotes a user-supplied value for an error message, shortened to a bounded length. */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        String shown = value.length() > MAX_ECHO_LENGTH
            ? value.substring(0, MAX_ECHO_LENGTH) + "…"
            : value;
        return "'" + shown + "'";
    }
}
