package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads the value shapes of INUBIT log rows (spike S-5): plain values, {@code {level, content}}
 * objects ({@code success}, queueLog {@code status}, keyManagerLog {@code validity}), numbers or
 * UUID strings ({@code globalPId}), and {@code ""} for missing times ({@code nextStartTime}).
 */
final class RowValues {

    private static final Pattern DIGITS = Pattern.compile("^-?[0-9]{1,19}$");

    private RowValues() {
    }

    /** The value of {@code field} as text; empty for a missing, null or blank value. */
    static Optional<String> text(JsonNode row, String field) {
        return render(row.get(field));
    }

    /** A value as text: {@code {level, content}} gives its content, objects compact JSON. */
    static Optional<String> render(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return Optional.empty();
        }
        if (value.isObject() && value.has("content")) {
            return render(value.get("content"));
        }
        String text = value.isValueNode() ? value.asString() : value.toString();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /** An epoch-milliseconds value (number, digits, or {@code {level, content}}) as instant. */
    static Optional<Instant> instant(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value != null && value.isObject() && value.has("content")) {
            value = value.get("content");
        }
        if (value == null) {
            return Optional.empty();
        }
        if (value.isIntegralNumber()) {
            return Optional.of(Instant.ofEpochMilli(value.asLong()));
        }
        if (value.isString() && DIGITS.matcher(value.asString().strip()).matches()) {
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(value.asString().strip())));
        }
        return Optional.empty();
    }

    /**
     * {@code text} cut to about half its length with the truncation marker; unchanged when it
     * is not longer than the marker (nothing left to cut).
     */
    static String halve(String text) {
        int marker = ItemBounds.TRUNCATION_MARKER.length();
        if (text.length() <= marker) {
            return text;
        }
        return cut(text, Math.max(text.length() / 2, marker));
    }

    /**
     * {@code text} cut to at most {@code max} chars, ending with the truncation marker when cut
     * (a surrogate pair is never split).
     */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Math.max(max - ItemBounds.TRUNCATION_MARKER.length(), 0);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ItemBounds.TRUNCATION_MARKER;
    }
}
