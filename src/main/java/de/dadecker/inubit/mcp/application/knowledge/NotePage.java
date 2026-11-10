package de.dadecker.inubit.mcp.application.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One page of a note (research D-9). Name, title, topics, source and last writer are masked, one
 * line and cut ({@link Shown}); {@code content} is a part of the masked body.
 *
 * @param masked     the number of secrets masked in the whole note
 * @param length     the length of the masked body
 * @param offset     where {@code content} starts in the masked body
 * @param content    at most {@link KnowledgeLimits#PAGE_CHARS} characters, ending with the
 *                   truncation marker when more follows
 * @param nextOffset present when more content follows
 */
public record NotePage(String name, String title, List<String> topics, String source,
    Optional<String> lastWriter, Instant lastChange, String versionToken, int masked, int length,
    int offset, String content, OptionalInt nextOffset, List<String> warnings) {

    public NotePage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
        topics = List.copyOf(topics);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lastWriter, "lastWriter");
        Objects.requireNonNull(lastChange, "lastChange");
        Objects.requireNonNull(versionToken, "versionToken");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(nextOffset, "nextOffset");
        warnings = List.copyOf(warnings);
    }
}
