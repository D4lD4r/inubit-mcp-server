package de.dadecker.inubit.mcp.application.knowledge;

import java.util.Objects;
import java.util.Optional;

/**
 * A listing or search of the knowledge (research D-9): without {@code query} and {@code topic}
 * every note is listed.
 *
 * @param query  literal, case-insensitive text to find in name, title, topics or body (1–200)
 * @param topic  a topic the note must have, compared after {@code NoteName.fold} (1–60)
 * @param limit  hits per page, 1 to {@link KnowledgeLimits#SEARCH_LIMIT_MAX}
 * @param offset hits to skip
 */
public record Search(Optional<String> query, Optional<String> topic, int limit, int offset) {

    public static final int QUERY_MAX = 200;
    public static final int TOPIC_MAX = 60;
    public static final int DEFAULT_LIMIT = 20;

    public Search {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(topic, "topic");
        query.ifPresent(text -> check("query", text, QUERY_MAX));
        topic.ifPresent(text -> check("topic", text, TOPIC_MAX));
        if (limit < 1 || limit > KnowledgeLimits.SEARCH_LIMIT_MAX) {
            throw new IllegalArgumentException(
                "limit must be 1 to " + KnowledgeLimits.SEARCH_LIMIT_MAX);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    private static void check(String what, String text, int max) {
        if (text.isEmpty() || text.length() > max) {
            throw new IllegalArgumentException(what + " must have 1 to " + max + " characters");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " must not contain control characters");
        }
    }
}
