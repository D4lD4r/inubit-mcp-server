package de.dadecker.inubit.mcp.application.knowledge;

import java.time.Duration;

/** The bounds of the local knowledge (feature 019, data-model.md). */
public final class KnowledgeLimits {

    /** Largest note the server writes or reads in full (256 KiB). */
    public static final int NOTE_BYTES = 262_144;
    /** Largest knowledge directory the server still writes to (100 MiB). */
    public static final long TOTAL_BYTES = 104_857_600L;
    /** Largest file read at all; bigger notes are listed as too large (4 MiB). */
    public static final int READ_BYTES = 4_194_304;
    /** Kept versions of a note. */
    public static final int HISTORY = 10;
    /** Longest wait for the lock of the knowledge directory. */
    public static final Duration LOCK_WAIT = Duration.ofSeconds(10);
    /** Characters of one read page, including the truncation marker. */
    public static final int PAGE_CHARS = 20_000;
    /** Characters of one search excerpt. */
    public static final int EXCERPT_CHARS = 300;
    /** Characters of one line of the note list in the instructions. */
    public static final int LINE_CHARS = 120;
    /** Characters of the note list in the instructions, including the summary line. */
    public static final int LIST_CHARS = 4_000;
    /** Most hits of one search. */
    public static final int SEARCH_LIMIT_MAX = 50;
    /** Deepest directory level of the listing walk. */
    public static final int WALK_DEPTH = 16;
    /** Most entries of the listing walk. */
    public static final int WALK_ENTRIES = 100_000;
    /** Most entries of the size walk before it fails closed. */
    public static final int SIZE_WALK_ENTRIES = 1_000_000;
    /** Wait for the configured credentials when the instructions are built at start. */
    public static final Duration START_PREPARE = Duration.ofSeconds(5);

    private KnowledgeLimits() {
    }
}
