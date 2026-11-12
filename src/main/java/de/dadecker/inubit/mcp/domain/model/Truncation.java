package de.dadecker.inubit.mcp.domain.model;

/**
 * How a server log excerpt was shortened to fit {@code maxChars} (feature 021): whole lines
 * dropped from the oldest, and at most one line cut.
 */
public record Truncation(int droppedLines, int cutLines) {

    public Truncation {
        if (droppedLines < 0) {
            throw new IllegalArgumentException("droppedLines must be >= 0: " + droppedLines);
        }
        if (cutLines != 0 && cutLines != 1) {
            throw new IllegalArgumentException("cutLines must be 0 or 1: " + cutLines);
        }
    }
}
