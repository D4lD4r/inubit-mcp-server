package de.dadecker.inubit.mcp.domain.model;

/**
 * Size bounds of the items in a tool result (data-model.md → LogEntry, {@code Page<T>}), shared
 * by the mappers that build items and the result limiter that pages them.
 */
public final class ItemBounds {

    /** Upper bound of one item's serialized size; room for a full log message plus fields. */
    public static final int MAX_ITEM_CHARS = 4_000;
    /** Upper bound of a log message. */
    public static final int MAX_MESSAGE_CHARS = 2_000;
    /** Upper bound of a short text value (a log row's extra column, a name, an id). */
    public static final int MAX_FIELD_CHARS = 200;
    /** Ends every text that was cut. */
    public static final String TRUNCATION_MARKER = "…[truncated]";

    private ItemBounds() {
    }
}
