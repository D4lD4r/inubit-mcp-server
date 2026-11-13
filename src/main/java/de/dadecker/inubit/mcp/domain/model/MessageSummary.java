package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * One message of the login user's message list (data-model.md → MessageSummary, US2 AS6).
 *
 * @param id      the message id
 * @param sender  the sender
 * @param time    the time as INUBIT shows it
 * @param subject the subject
 */
public record MessageSummary(String id, String sender, String time, String subject) {

    public MessageSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(subject, "subject");
    }

    /** Never the subject: it is free text and may hold personal data. */
    @Override
    public String toString() {
        return "MessageSummary[" + id + ", " + sender + ", " + time + "]";
    }
}
