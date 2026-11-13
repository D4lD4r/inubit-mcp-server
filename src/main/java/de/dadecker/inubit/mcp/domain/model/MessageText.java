package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * The text of one message (data-model.md → MessageText, US2 AS6), masked by the caller and at
 * most {@link ItemBounds#MAX_ITEM_CHARS} chars long.
 *
 * @param id        the message id
 * @param text      the masked text, ending with {@link ItemBounds#TRUNCATION_MARKER} when cut
 * @param truncated true if the text was cut
 */
public record MessageText(String id, String text, boolean truncated) {

    public MessageText {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        if (text.length() > ItemBounds.MAX_ITEM_CHARS) {
            throw new IllegalArgumentException(
                "a message text is longer than " + ItemBounds.MAX_ITEM_CHARS + " chars");
        }
    }

    /**
     * The masked text, cut to {@link ItemBounds#MAX_ITEM_CHARS} chars with the truncation marker
     * when longer (a surrogate pair is never split).
     */
    public static MessageText of(String id, String maskedText) {
        int max = ItemBounds.MAX_ITEM_CHARS;
        if (maskedText.length() <= max) {
            return new MessageText(id, maskedText, false);
        }
        int end = max - ItemBounds.TRUNCATION_MARKER.length();
        if (Character.isHighSurrogate(maskedText.charAt(end - 1))) {
            end--;
        }
        return new MessageText(id, maskedText.substring(0, end) + ItemBounds.TRUNCATION_MARKER,
            true);
    }

    /** Never the text. */
    @Override
    public String toString() {
        return "MessageText[" + id + ", " + text.length() + " chars, truncated " + truncated + "]";
    }
}
