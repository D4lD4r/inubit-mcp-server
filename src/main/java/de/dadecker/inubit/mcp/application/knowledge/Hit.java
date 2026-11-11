package de.dadecker.inubit.mcp.application.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One note of a listing or search (research D-9). Name, title, topics and source are masked, one
 * line and cut ({@link Shown}).
 *
 * @param lastChange   the file's last modification, in seconds
 * @param versionToken absent for a note that cannot be read
 * @param excerpt      at most {@link KnowledgeLimits#EXCERPT_CHARS} of the masked body; absent for
 *                     a note that cannot be read
 * @param masked       the number of secrets masked in the whole note
 * @param writable     whether {@code record_knowledge} can append to or replace the note
 */
public record Hit(String name, String ref, String title, List<String> topics, Instant lastChange,
    String source, Optional<String> versionToken, Optional<String> excerpt, int masked,
    boolean writable, List<String> warnings) {

    public Hit {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(title, "title");
        topics = List.copyOf(topics);
        Objects.requireNonNull(lastChange, "lastChange");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(versionToken, "versionToken");
        Objects.requireNonNull(excerpt, "excerpt");
        warnings = List.copyOf(warnings);
    }
}
