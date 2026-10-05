package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.ToIntFunction;

/**
 * Keeps tool results bounded (Constitution VI, FR-026, SC-007).
 *
 * <p>Invariants (see data-model.md → Page&lt;T&gt;):
 *
 * <ul>
 *   <li>Every item must be at most {@value #MAX_ITEM_CHARS} chars ({@code sizeOf}); callers bound
 *       their text fields first (e.g. log messages to {@value #MAX_MESSAGE_CHARS} chars with
 *       {@link #truncateMessage}). A larger item is a programming error
 *       ({@link IllegalArgumentException}).
 *   <li>A page holds at most {@code maxItems} items, and their sizes add up to at most
 *       {@code maxChars}. Because {@code maxChars >= MAX_ITEM_CHARS}, a page holds at least one
 *       item whenever one is left, so paging always makes progress.
 *   <li>The limits apply per tool result: a tool that fans out to {@code n} servers pages each
 *       server with {@link #share(int) share(n)}, whose char budget is {@code maxChars / n} (but
 *       never below {@value #MAX_ITEM_CHARS}). The items of the whole result then add up to at
 *       most {@code max(maxChars, n * MAX_ITEM_CHARS)}, i.e. to {@code maxChars} whenever
 *       {@code n <= maxChars / MAX_ITEM_CHARS} (12 servers at the default 50,000).
 *   <li>{@code truncated} is set when the page holds fewer items than the caller asked for
 *       although more were available (cut by {@code maxItems} or the char budget); plain
 *       pagination only sets {@code nextOffset}.
 * </ul>
 */
public final class ResultLimiter {

    public static final int DEFAULT_MAX_ITEMS = 100;
    public static final int DEFAULT_MAX_CHARS = 50_000;
    public static final int MAX_MESSAGE_CHARS = ItemBounds.MAX_MESSAGE_CHARS;
    /** Upper bound of one item's serialized size; room for a full log message plus fields. */
    public static final int MAX_ITEM_CHARS = ItemBounds.MAX_ITEM_CHARS;
    public static final String TRUNCATION_MARKER = ItemBounds.TRUNCATION_MARKER;

    private final int maxItems;
    private final int maxChars;

    /** @param maxChars char budget of one page, at least {@value #MAX_ITEM_CHARS} */
    public ResultLimiter(int maxItems, int maxChars) {
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be >= 1: " + maxItems);
        }
        if (maxChars < MAX_ITEM_CHARS) {
            throw new IllegalArgumentException(
                "maxChars must be >= " + MAX_ITEM_CHARS + ": " + maxChars);
        }
        this.maxItems = maxItems;
        this.maxChars = maxChars;
    }

    public static ResultLimiter withDefaults() {
        return new ResultLimiter(DEFAULT_MAX_ITEMS, DEFAULT_MAX_CHARS);
    }

    public int maxItems() {
        return maxItems;
    }

    public int maxChars() {
        return maxChars;
    }

    /** The limiter for one of {@code servers} pages that together form one tool result. */
    public ResultLimiter share(int servers) {
        if (servers < 1) {
            throw new IllegalArgumentException("nodes must be >= 1: " + servers);
        }
        return new ResultLimiter(maxItems, Math.max(maxChars / servers, MAX_ITEM_CHARS));
    }

    /** Pages a complete list; {@code sizeOf} gives an item's serialized size in chars. */
    public <T> Page<T> page(List<T> all, int offset, int limit, ToIntFunction<T> sizeOf) {
        validate(offset, limit);
        int effectiveLimit = Math.min(limit, maxItems);
        int from = Math.min(offset, all.size());
        List<T> slice = all.subList(from, Math.min(from + effectiveLimit, all.size()));
        List<T> included = withinCharBudget(slice, sizeOf);
        int requested = Math.min(limit, all.size() - from);
        int next = offset + included.size();
        return new Page<>(included, offset, effectiveLimit, OptionalLong.of(all.size()), false,
            included.size() < requested,
            next < all.size() ? OptionalInt.of(next) : OptionalInt.empty());
    }

    /**
     * Bounds a page that was fetched from INUBIT starting at {@code offset}. With an unknown
     * {@code total}, a full page is assumed to have a successor.
     */
    public <T> Page<T> bound(List<T> items, int offset, int limit, OptionalLong total,
        ToIntFunction<T> sizeOf) {
        validate(offset, limit);
        int effectiveLimit = Math.min(limit, maxItems);
        List<T> slice = items.subList(0, Math.min(items.size(), effectiveLimit));
        List<T> included = withinCharBudget(slice, sizeOf);
        boolean truncated = included.size() < Math.min(items.size(), limit);
        int next = offset + included.size();
        boolean hasMore = total.isPresent()
            ? next < total.getAsLong()
            : included.size() < items.size() || items.size() >= effectiveLimit;
        return new Page<>(included, offset, effectiveLimit, total, total.isEmpty(), truncated,
            hasMore ? OptionalInt.of(next) : OptionalInt.empty());
    }

    /** Cuts a log message to {@value #MAX_MESSAGE_CHARS} chars, ending with the marker. */
    public static String truncateMessage(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        int end = MAX_MESSAGE_CHARS - TRUNCATION_MARKER.length();
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
            end--;
        }
        return message.substring(0, end) + TRUNCATION_MARKER;
    }

    private <T> List<T> withinCharBudget(List<T> items, ToIntFunction<T> sizeOf) {
        long chars = 0;
        for (int i = 0; i < items.size(); i++) {
            int size = sizeOf.applyAsInt(items.get(i));
            if (size > MAX_ITEM_CHARS) {
                throw new IllegalArgumentException("an item of " + size + " chars exceeds the"
                    + " per-item bound of " + MAX_ITEM_CHARS + " chars; bound its fields first");
            }
            chars += size;
            if (chars > maxChars) {
                return List.copyOf(items.subList(0, i));
            }
        }
        return List.copyOf(items);
    }

    private static void validate(int offset, int limit) {
        if (offset < 0) {
            throw invalid("offset must be >= 0, got " + offset);
        }
        if (limit < 1) {
            throw invalid("limit must be >= 1, got " + limit);
        }
    }

    private static ToolErrorException invalid(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            "The paging parameters are out of range.",
            "Use offset >= 0 and limit between 1 and the configured maximum."));
    }
}
