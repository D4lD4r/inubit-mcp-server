package de.dadecker.inubit.mcp.domain.model;

/** The bounds of downloading a server log and building its excerpt (feature 021). */
public final class ServerLogBounds {

    /** The end of the log scanned for the excerpt (50 MiB). */
    public static final long WINDOW_BYTES = 52_428_800;
    /** Lines returned when the caller sets no {@code tailLines}. */
    public static final int DEFAULT_TAIL_LINES = 200;
    /** The largest {@code tailLines} a caller may set. */
    public static final int MAX_TAIL_LINES = 2_000;
    /** Characters of line text returned when the caller sets no {@code maxChars}. */
    public static final int DEFAULT_MAX_CHARS = 20_000;
    /** The largest {@code maxChars} a caller may set. */
    public static final int MAX_MAX_CHARS = 100_000;
    /** Characters of the {@code contains} search text. */
    public static final int MAX_CONTAINS_CHARS = 200;
    /** The largest numeric suffix added to a file name that is already taken. */
    public static final int MAX_NAME_SUFFIX = 999;

    private ServerLogBounds() {
    }
}
