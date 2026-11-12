package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * One line of a server log excerpt (feature 021): its {@code number}, absolute in the file and
 * counted from 1, the masked {@code text}, and whether the text was {@code cut} at
 * {@code maxChars}.
 */
public record ExcerptLine(long number, String text, boolean cut) {

    public ExcerptLine {
        if (number < 1) {
            throw new IllegalArgumentException("number must be >= 1: " + number);
        }
        Objects.requireNonNull(text, "text");
    }
}
