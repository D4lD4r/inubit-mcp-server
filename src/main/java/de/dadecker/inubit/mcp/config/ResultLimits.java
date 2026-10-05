package de.dadecker.inubit.mcp.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Size limits of a single tool result (FR-026). Validity is checked by {@link ConfigValidator}. */
public record ResultLimits(int maxItems, int maxChars) {

    public static final int DEFAULT_MAX_ITEMS = 100;
    public static final int DEFAULT_MAX_CHARS = 50_000;
    /** Smallest accepted {@code maxChars}; below it a page could not hold a few bounded items. */
    public static final int MIN_MAX_CHARS = 10_000;
    public static final ResultLimits DEFAULT =
        new ResultLimits(DEFAULT_MAX_ITEMS, DEFAULT_MAX_CHARS);

    @JsonCreator
    static ResultLimits fromYaml(
        @JsonProperty("maxItems") Integer maxItems,
        @JsonProperty("maxChars") Integer maxChars) {
        return new ResultLimits(
            maxItems == null ? DEFAULT_MAX_ITEMS : maxItems,
            maxChars == null ? DEFAULT_MAX_CHARS : maxChars);
    }
}
