package de.dadecker.inubit.mcp.domain.model;

/** The bounds of reading a Queue Manager entry (feature 020, FR-009, FR-010, FR-012, D-12). */
public final class QueueEntryBounds {

    /** A data stream or variable set larger than this is not processed (10 MiB). */
    public static final long MAX_DATA_BYTES = 10L * 1024 * 1024;
    /** Characters of data-stream text returned when the caller sets no {@code maxChars}. */
    public static final int DEFAULT_MAX_CHARS = 20_000;
    /** The largest {@code maxChars} a caller may set. */
    public static final int MAX_MAX_CHARS = 100_000;
    /** Variables returned, system variables included. */
    public static final int MAX_VARIABLES = 200;
    /** Characters of one variable name. */
    public static final int MAX_NAME_CHARS = 200;
    /** Characters of one variable value. */
    public static final int MAX_VALUE_CHARS = 2_000;
    /** Characters of all variable values together (names not counted). */
    public static final int MAX_VALUES_CHARS = 40_000;
    /** Candidates listed when several ERROR entries match. */
    public static final int MAX_LISTED_CANDIDATES = 20;

    private QueueEntryBounds() {
    }
}
