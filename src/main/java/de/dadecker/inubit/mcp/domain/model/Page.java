package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A bounded slice of a larger result (data-model.md → Page).
 *
 * @param total             empty only if INUBIT cannot report it; then {@code totalIsLowerBound}
 * @param truncated         true if items or text were cut
 * @param nextOffset        offset of the next page, if there is one
 */
public record Page<T>(
    List<T> items,
    int offset,
    int limit,
    OptionalLong total,
    boolean totalIsLowerBound,
    boolean truncated,
    OptionalInt nextOffset) {

    public Page {
        items = List.copyOf(items);
        total = total == null ? OptionalLong.empty() : total;
        nextOffset = nextOffset == null ? OptionalInt.empty() : nextOffset;
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
        if (items.size() > limit) {
            throw new IllegalArgumentException(
                "page holds " + items.size() + " items, more than its limit " + limit);
        }
        if (total.isEmpty() && !totalIsLowerBound) {
            throw new IllegalArgumentException("an unknown total requires totalIsLowerBound=true");
        }
    }

    /** Returns a copy with {@code truncated=true}, e.g. after text inside an item was cut. */
    public Page<T> asTruncated() {
        return new Page<>(items, offset, limit, total, totalIsLowerBound, true, nextOffset);
    }
}
