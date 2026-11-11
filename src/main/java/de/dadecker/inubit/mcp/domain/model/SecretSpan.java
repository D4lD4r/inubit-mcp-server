package de.dadecker.inubit.mcp.domain.model;

/** The char range {@code [start, end)} of a secret in a text (feature 019, research D-6). */
public record SecretSpan(int start, int end) {

    public SecretSpan {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("a secret span needs 0 <= start < end");
        }
    }

    public int length() {
        return end - start;
    }
}
