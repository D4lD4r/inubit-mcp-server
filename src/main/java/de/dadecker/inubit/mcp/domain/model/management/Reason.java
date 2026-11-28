package de.dadecker.inubit.mcp.domain.model.management;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import java.util.Objects;

/**
 * Why something could not be answered (data-model.md → Reason).
 *
 * @param code     the error code
 * @param message  at most {@value ItemBounds#MAX_MESSAGE_CHARS} characters, no excerpt of an
 *                 INUBIT answer
 * @param nextStep what the caller can do, at most {@value ItemBounds#MAX_MESSAGE_CHARS} characters
 */
public record Reason(ErrorCode code, String message, String nextStep) {

    public Reason {
        Objects.requireNonNull(code, "code");
        requireMessage(message, "message");
        requireMessage(nextStep, "nextStep");
    }

    /** Fails if {@code text} is null or longer than {@link ItemBounds#MAX_MESSAGE_CHARS}. */
    static void requireMessage(String text, String field) {
        if (Objects.requireNonNull(text, field).length() > ItemBounds.MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException(
                field + " is longer than " + ItemBounds.MAX_MESSAGE_CHARS + " chars");
        }
    }
}
